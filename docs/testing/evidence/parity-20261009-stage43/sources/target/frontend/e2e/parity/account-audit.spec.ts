import { test, expect, type Page } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import errors from '../../src/i18n/prototype/errors.zh-CN.json' with { type: 'json' };
import { protectTestSecrets } from '../privacy';
import { paceComparisonLogin } from './login-budget';

test('P13 management audit: real account writes, rejected writes, no-change edit and complete export', async ({
  page,
  browser,
}, info) => {
  test.setTimeout(240_000);
  page.setDefaultTimeout(15_000);
  const prototype = info.project.name === 'prototype';
  const kind = prototype ? 'python' : 'java';
  const tag = process.env.ADTEK_ACCOUNT_AUDIT_RUN!;
  expect(tag).toMatch(/^[0-9]{8,14}$/);
  const python = resolve('../../sn-center-python/.venv/Scripts/python.exe');
  const fixture = (action: string) =>
    execFileSync(
      python,
      [
        '-X',
        'utf8',
        resolve('../tools/testing/parity-account-audit-fixture.py'),
        kind,
        action,
        tag,
      ],
      { windowsHide: true, encoding: 'utf8' },
    );
  fixture('prepare');
  const privateRoot = resolve('../deploy/.secrets/comparison-20261008');
  const path = resolve(privateRoot, `account-audit-${kind}-${tag}.json`);
  const state = JSON.parse(readFileSync(path, 'utf8')) as {
    names: [string, string];
    created: string[];
    password: string;
    changed: string;
  };
  const rootPassword = readFileSync(resolve(privateRoot, `${kind}-admin-password`), 'utf8').trim();
  const tooLong = 'Ab9' + 'x'.repeat(62);
  protectTestSecrets(rootPassword, state.password, state.changed, tooLong);
  const context = await browser.newContext({
    baseURL: String(info.project.use.baseURL),
    viewport: { width: 1440, height: 960 },
  });
  const actor = await context.newPage();
  actor.setDefaultTimeout(15_000);
  const writes: { route: string; status: number }[] = [];
  const versions: unknown[] = [];
  const pageErrors: string[] = [];
  for (const tab of [page, actor]) {
    tab.on('pageerror', (e) => pageErrors.push(e.message));
    tab.on('response', async (response) => {
      const route = new URL(response.url()).pathname;
      if (!prototype && /^\/api\/v1\/(sn|system)\/users(?:\/|$)/.test(route)) {
        const value = await response.json();
        const body = response.request().postDataJSON() as { version?: string } | null;
        versions.push({
          route,
          status: response.status(),
          requestVersion: body?.version,
          code: value.code,
          users: value.data?.items?.map((u: { id: string; version: string; enabled: boolean }) => ({
            id: u.id,
            version: u.version,
            enabled: u.enabled,
          })),
          version: value.data?.version,
        });
      }
    });
  }
  const login = async (tab: Page, username: string, password: string) => {
    await tab.goto('/login');
    await paceComparisonLogin(tab);
    await tab.locator('input[autocomplete="username"]').fill(username);
    await tab.locator('input[autocomplete="current-password"]').fill(password);
    await tab.getByRole('button', { name: zh.login.submit, exact: true }).click();
  };
  const rows = (tab: Page) => tab.locator(prototype ? '.el-table__row' : '.ant-table-row');
  const inputs = (tab: Page) =>
    tab
      .getByRole('dialog')
      .locator(
        prototype
          ? '.el-form-item .el-input__inner:not([role="combobox"]):not([type="password"])'
          : '.ant-form-item input.ant-input:not([type="password"])',
      );
  const role = async (tab: Page, value: 'admin' | 'query' | 'factory_operator') => {
    await tab
      .getByRole('dialog')
      .locator(prototype ? '.el-radio-button' : '.ant-radio-button-wrapper')
      .filter({ hasText: zh.roles[value] })
      .click();
  };
  const factory = async (tab: Page, label: string) => {
    await tab
      .getByRole('dialog')
      .locator(prototype ? '.el-select' : '.ant-select')
      .click();
    await tab.getByRole('option', { name: label, exact: true }).click();
  };
  const search = async (tab: Page, username: string) => {
    await tab.getByRole('menuitem', { name: zh.nav.users, exact: true }).click();
    const box = tab.getByPlaceholder(zh.users.search);
    await box.fill(username);
    const ready = tab.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        new URL(r.url()).pathname === (prototype ? '/api/users' : '/api/v1/system/users'),
    );
    await box.press('Enter');
    expect((await ready).status()).toBe(200);
    await expect(rows(tab)).toHaveCount(1);
    await expect(rows(tab).first()).toContainText(username);
  };
  const save = async (tab: Page, status = 200, message?: string) => {
    const pending = tab.waitForResponse(
      (r) =>
        ['POST', 'PUT'].includes(r.request().method()) &&
        /\/(?:sn\/)?users(?:\/[^/]+(?:\/update)?)?$/.test(new URL(r.url()).pathname),
    );
    await tab
      .getByRole('dialog')
      .getByRole('button', { name: zh.common.save, exact: true })
      .click();
    const response = await pending;
    const route = new URL(response.url()).pathname;
    writes.push({ route, status: response.status() });
    if (response.ok() && !/\/users\//.test(route)) {
      const body = await response.json();
      state.created.push(String(prototype ? body.id : body.data.id));
      writeFileSync(path, JSON.stringify(state));
    }
    expect(response.status()).toBe(status);
    if (status === 200) await expect(tab.getByRole('dialog')).toBeHidden();
    if (message) await expect(tab.getByText(message, { exact: true }).last()).toBeVisible();
  };
  const create = async (tab: Page, username: string, value: 'admin' | 'factory_operator') => {
    await tab.getByRole('button', { name: zh.users.create, exact: true }).click();
    await inputs(tab).nth(0).fill(username);
    await inputs(tab)
      .nth(1)
      .fill(value === 'admin' ? '账户痕迹操作员' : '账户痕迹成员');
    await role(tab, value);
    if (value === 'factory_operator') await factory(tab, '比较测试甲厂（CMP-A）');
    await tab.getByRole('dialog').locator('input[type="password"]').fill(state.password);
    await save(tab);
  };
  const capture = async (tab: Page, name: string) => {
    await tab.mouse.move(0, 0);
    await expect(tab.locator('.ant-spin-spinning:visible,.el-loading-mask:visible')).toHaveCount(0);
    await tab.screenshot({ path: info.outputPath(name + '.png'), animations: 'disabled' });
  };
  const reset = async (password: string, status: number) => {
    await rows(actor).first().getByRole('button', { name: zh.users.resetPwd, exact: true }).click();
    const dialog = actor.getByRole('dialog');
    await dialog.locator('input[type="password"]').fill(password);
    const pending = actor.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new URL(r.url()).pathname.endsWith(prototype ? '/reset-password' : '/password'),
    );
    await dialog.getByRole('button', { name: '确定', exact: true }).click();
    const response = await pending;
    writes.push({ route: new URL(response.url()).pathname, status: response.status() });
    if (!prototype) {
      const body = response.request().postDataJSON() as { version?: string };
      const value = await response.json();
      versions.push({ resetRequestVersion: body.version, response: value });
    }
    expect(response.status()).toBe(status);
    await expect(dialog).toBeHidden();
    await expect(
      actor
        .getByText(status === 200 ? zh.users.resetDone : errors.USER_PASSWORD_WEAK, { exact: true })
        .last(),
    ).toBeVisible();
  };
  try {
    await login(page, 'admin', rootPassword);
    await page.getByRole('menuitem', { name: zh.nav.users, exact: true }).click();
    await create(page, state.names[0], 'admin');
    await login(actor, state.names[0], state.password);
    await expect(
      actor.getByRole('heading', { name: zh.password.title, exact: true }),
    ).toBeVisible();
    await actor.locator('input[autocomplete="current-password"]').fill(state.password);
    await actor.locator('input[autocomplete="new-password"]').nth(0).fill(state.changed);
    await actor.locator('input[autocomplete="new-password"]').nth(1).fill(state.changed);
    await actor.getByRole('button', { name: zh.common.save, exact: true }).click();
    await actor.getByRole('menuitem', { name: zh.nav.users, exact: true }).click();
    await actor.getByRole('button', { name: zh.users.create, exact: true }).click();
    await save(actor, 400, errors.USER_EMP_NO_INVALID);
    await actor
      .getByRole('dialog')
      .getByRole('button', { name: zh.common.cancel, exact: true })
      .click();
    await create(actor, state.names[1], 'factory_operator');
    await actor.getByRole('button', { name: zh.users.create, exact: true }).click();
    await inputs(actor).nth(0).fill(state.names[1]);
    await inputs(actor).nth(1).fill('重复账户');
    await role(actor, 'query');
    await factory(actor, '比较测试乙厂（CMP-B）');
    await actor.getByRole('dialog').locator('input[type="password"]').fill(state.password);
    await save(actor, 409, errors.USER_EMP_NO_EXISTS.replace('{{emp_no}}', state.names[1]));
    await actor
      .getByRole('dialog')
      .getByRole('button', { name: zh.common.cancel, exact: true })
      .click();
    await search(actor, state.names[1]);
    await rows(actor).first().getByRole('button', { name: zh.common.edit, exact: true }).click();
    await role(actor, 'query');
    await factory(actor, '比较测试乙厂（CMP-B）');
    await save(actor);
    await rows(actor).first().getByRole('button', { name: zh.common.edit, exact: true }).click();
    await save(actor);
    await rows(actor).first().getByRole('button', { name: zh.users.disable, exact: true }).click();
    await actor.getByRole('dialog').getByRole('button', { name: '确定', exact: true }).click();
    await expect(
      rows(actor).first().locator('td').nth(4).getByText(zh.users.disabled, { exact: true }),
    ).toBeVisible();
    await rows(actor).first().getByRole('button', { name: zh.users.enable, exact: true }).click();
    // 故意直接打开重置，不等待启用后的列表刷新；不能把同名“启用”按钮当状态检查。
    await reset(state.password, 200);
    await expect(
      rows(actor).first().locator('td').nth(4).getByText(zh.users.active, { exact: true }),
    ).toBeVisible();
    await reset(state.password, 200);
    await reset(tooLong, 400);
    await capture(actor, '01-rejected-reset');
    await search(actor, state.names[0]);
    await rows(actor).first().getByRole('button', { name: zh.common.edit, exact: true }).click();
    await inputs(actor).nth(1).fill('修改后的操作员');
    await save(actor);

    // 由原管理员查看/导出，待删除夹具账户不会拥有持久任务；保留全部真实痕迹。
    await page.getByRole('menuitem', { name: zh.nav.audit, exact: true }).click();
    await page.getByPlaceholder(zh.audit.operator, { exact: true }).fill(state.names[0]);
    const ready = page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        new URL(r.url()).pathname === (prototype ? '/api/audits' : '/api/v1/sn/audits/page') &&
        new URL(r.url()).searchParams.get('operator') === state.names[0],
    );
    await page.getByRole('button', { name: zh.common.search, exact: true }).click();
    const response = await ready;
    expect(response.status()).toBe(200);
    const body = await response.json();
    const items: Record<string, unknown>[] = prototype ? body.items : body.data.items;
    writeFileSync(info.outputPath('records.json'), JSON.stringify(items, null, 2));
    const names: Record<string, string> = {
      'system:user:add': 'USER_CREATE',
      'system:user:update': 'USER_UPDATE',
      'system:user:reset': 'USER_RESET_PWD',
      'auth:PASSWORD_CHANGE': 'PASSWORD_CHANGE',
    };
    const expected = [
      [
        'USER_UPDATE',
        'OK',
        `emp_no=${state.names[0]} role=admin->admin factory=null->null`,
        null,
        null,
      ],
      [
        'USER_UPDATE',
        'FAIL',
        `POST /api/users/${state.created[1]}/reset-password`,
        errors.USER_PASSWORD_WEAK,
        null,
      ],
      ['USER_RESET_PWD', 'OK', `emp_no=${state.names[1]}`, null, 'CMP-B'],
      ['USER_RESET_PWD', 'OK', `emp_no=${state.names[1]}`, null, 'CMP-B'],
      ['USER_ENABLE', 'OK', `emp_no=${state.names[1]}`, null, 'CMP-B'],
      ['USER_DISABLE', 'OK', `emp_no=${state.names[1]}`, null, 'CMP-B'],
      [
        'USER_UPDATE',
        'OK',
        `emp_no=${state.names[1]} role=query->query factory=CMP-B->CMP-B`,
        null,
        'CMP-B',
      ],
      [
        'USER_UPDATE',
        'OK',
        `emp_no=${state.names[1]} role=factory_operator->query factory=CMP-A->CMP-B`,
        null,
        'CMP-B',
      ],
      [
        'USER_CREATE',
        'FAIL',
        'POST /api/users',
        errors.USER_EMP_NO_EXISTS.replace('{{emp_no}}', state.names[1]),
        'CMP-B',
      ],
      ['USER_CREATE', 'OK', `emp_no=${state.names[1]} role=factory_operator`, null, 'CMP-A'],
      ['USER_CREATE', 'FAIL', 'POST /api/users', errors.USER_EMP_NO_INVALID, null],
      ['PASSWORD_CHANGE', 'OK', state.names[0], null, null],
      ['LOGIN', 'OK', 'client=127.0.0.1', null, null],
    ];
    expect(
      items.map((r) => [
        names[String(r.action)] ?? r.action,
        r.result === 'SUCCESS' ? 'OK' : r.result === 'FAILED' ? 'FAIL' : r.result,
        r.detail,
        r[prototype ? 'error_msg' : 'errorMessage'],
        r[prototype ? 'factory_code' : 'factoryCode'],
      ]),
    ).toEqual(expected);
    await expect(rows(page)).toHaveCount(expected.length);
    for (const item of items) {
      expect(item.operator).toBe(state.names[0]);
      expect(item[prototype ? 'operator_name' : 'operatorName']).toBe('账户痕迹操作员');
      expect(item[prototype ? 'qty' : 'quantity']).toBeNull();
      expect(item.source).toBe('PAGE');
    }
    await capture(page, '02-audit-table');
    writeFileSync(
      info.outputPath('table.json'),
      JSON.stringify(
        await rows(page).evaluateAll((elements) =>
          elements.map((el) =>
            [...el.querySelectorAll('td')].map((cell) => cell.textContent?.trim()),
          ),
        ),
        null,
        2,
      ),
    );
    for (let i = 0; i < 11; i++) {
      await rows(page)
        .nth(i)
        .locator(prototype ? '.el-table__expand-icon' : '.sn-audit-expand')
        .click();
      await expect(
        page.getByText(String(expected[i]![2]), { exact: true }).filter({ visible: true }),
      ).toBeVisible();
      await capture(page, `detail-${i}`);
      await rows(page)
        .nth(i)
        .locator(prototype ? '.el-table__expand-icon' : '.sn-audit-expand')
        .click();
    }
    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: zh.query.exportXlsx, exact: true }).click();
    const file = await download;
    expect(file.suggestedFilename()).toBe('audit_export.xlsx');
    await file.saveAs(info.outputPath('accounts.xlsx'));
    const parsed = JSON.parse(
      execFileSync(
        python,
        [
          '-X',
          'utf8',
          resolve('../tools/testing/parity-query-file.py'),
          info.outputPath('accounts.xlsx'),
        ],
        { windowsHide: true, encoding: 'utf8' },
      ),
    ) as { raw: unknown[][]; types: string[][] };
    writeFileSync(info.outputPath('file.json'), JSON.stringify(parsed, null, 2));
    expect(parsed.raw.slice(1).map((r) => [r[5], r[6], r[23], r[21], r[7]])).toEqual(expected);
    for (const row of parsed.raw.slice(1)) {
      expect(row[14]).toBeNull();
      expect(row[1]).toBe(state.names[0]);
    }
    writeFileSync(
      info.outputPath('identities.json'),
      JSON.stringify(
        { actor: state.created[0], member: state.created[1], names: state.names },
        null,
        2,
      ),
    );
    expect(pageErrors).toEqual([]);
  } finally {
    writeFileSync(
      info.outputPath('writes.json'),
      JSON.stringify({ writes, pageErrors, versions }, null, 2),
    );
    await context.close();
    fixture('clean');
  }
});
