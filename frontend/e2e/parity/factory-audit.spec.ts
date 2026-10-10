import { test, expect, type Page } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import errors from '../../src/i18n/prototype/errors.zh-CN.json' with { type: 'json' };
import { protectTestSecrets } from '../privacy';
import { paceComparisonLogin } from './login-budget';

test('P14 factory audit: real create, validation, sync conflicts, failure recovery and complete export', async ({
  page,
  browser,
}, info) => {
  test.setTimeout(240_000);
  const prototype = info.project.name === 'prototype';
  const kind = prototype ? 'python' : 'java';
  const tag = process.env.ADTEK_ACCOUNT_AUDIT_RUN!;
  expect(tag).toMatch(/^[0-9]{8,14}$/);
  const python = resolve('../../sn-center-python/.venv/Scripts/python.exe');
  const fixture = (name: string, action: string) =>
    execFileSync(
      python,
      [
        '-X',
        'utf8',
        resolve(`../tools/testing/parity-${name}-fixture.py`),
        kind,
        action,
        ...(name === 'account-audit' ? [tag] : []),
      ],
      { windowsHide: true, encoding: 'utf8' },
    );
  fixture('account-audit', 'prepare');
  const privateRoot = resolve('../deploy/.secrets/comparison-20261008');
  const statePath = resolve(privateRoot, `account-audit-${kind}-${tag}.json`);
  const state = JSON.parse(readFileSync(statePath, 'utf8')) as {
    names: string[];
    created: string[];
    password: string;
    changed: string;
  };
  const rootPassword = readFileSync(resolve(privateRoot, `${kind}-admin-password`), 'utf8').trim();
  protectTestSecrets(rootPassword, state.password, state.changed);
  const context = await browser.newContext({
    baseURL: String(info.project.use.baseURL),
    viewport: { width: 1440, height: 960 },
  });
  const actor = await context.newPage();
  const writes: unknown[] = [];
  const pageErrors: string[] = [];
  for (const tab of [page, actor]) {
    tab.setDefaultTimeout(15_000);
    tab.on('pageerror', (e) => pageErrors.push(e.message));
  }
  const login = async (tab: Page, username: string, password: string) => {
    await tab.goto('/login');
    await paceComparisonLogin(tab);
    await tab.locator('input[autocomplete="username"]').fill(username);
    await tab.locator('input[autocomplete="current-password"]').fill(password);
    await tab.getByRole('button', { name: zh.login.submit, exact: true }).click();
  };
  const rows = (tab: Page) => tab.locator(prototype ? '.el-table__row' : '.ant-table-row');
  const capture = async (tab: Page, label: string) => {
    await tab.mouse.move(0, 0);
    await expect(tab.locator('.ant-spin-spinning:visible,.el-loading-mask:visible')).toHaveCount(0);
    await tab.screenshot({ path: info.outputPath(label + '.png'), animations: 'disabled' });
  };
  const control = resolve('../tools/.runtime/comparison-20261008/k3-control.json');
  const setControl = (value: unknown) => writeFileSync(control, JSON.stringify(value));
  const route = prototype ? '/api/factories' : '/api/v1/sn/factories';
  const dialog = actor.getByRole('dialog');
  const create = async (code: string, name: string, status: number) => {
    if (!(await dialog.isVisible()))
      await actor.getByRole('button', { name: zh.factories.create, exact: true }).click();
    await dialog.locator('input').nth(0).fill(code);
    await dialog.locator('input').nth(1).fill(name);
    const ready = actor.waitForResponse(
      (r) => r.request().method() === 'POST' && new URL(r.url()).pathname === route,
    );
    await dialog.getByRole('button', { name: zh.common.save, exact: true }).click();
    const response = await ready;
    writes.push({ status: response.status(), route, body: await response.json() });
    expect(response.status()).toBe(status);
    if (status === 200) await expect(dialog).toBeHidden();
  };
  const sync = async (value: unknown, status = 200) => {
    setControl(value);
    const ready = actor.waitForResponse(
      (r) => r.request().method() === 'POST' && new URL(r.url()).pathname === route + '/sync',
    );
    await actor.getByRole('button', { name: zh.factories.sync, exact: true }).click();
    const response = await ready;
    writes.push({ status: response.status(), route: route + '/sync', body: await response.json() });
    expect(response.status()).toBe(status);
    await expect(
      actor.getByRole('button', { name: zh.factories.sync, exact: true }),
    ).not.toHaveClass(/loading/);
  };
  let factoriesPrepared = false;
  try {
    await login(page, 'admin', rootPassword);
    await page.getByRole('menuitem', { name: zh.nav.users, exact: true }).click();
    await page.getByRole('button', { name: zh.users.create, exact: true }).click();
    const accountDialog = page.getByRole('dialog');
    const fields = accountDialog.locator(
      prototype
        ? '.el-form-item .el-input__inner:not([role="combobox"]):not([type="password"])'
        : '.ant-form-item input.ant-input:not([type="password"])',
    );
    await fields.nth(0).fill(state.names[0]!);
    await fields.nth(1).fill('工厂痕迹操作员');
    await accountDialog
      .locator(prototype ? '.el-radio-button' : '.ant-radio-button-wrapper')
      .filter({ hasText: zh.roles.admin })
      .click();
    await accountDialog.locator('input[type="password"]').fill(state.password);
    const saved = page.waitForResponse(
      (r) => r.request().method() === 'POST' && new URL(r.url()).pathname.endsWith('/users'),
    );
    await accountDialog.getByRole('button', { name: zh.common.save, exact: true }).click();
    const response = await saved;
    expect(response.status()).toBe(200);
    const body = await response.json();
    state.created.push(String(prototype ? body.id : body.data.id));
    writeFileSync(statePath, JSON.stringify(state));
    await login(actor, state.names[0]!, state.password);
    await expect(
      actor.getByRole('heading', { name: zh.password.title, exact: true }),
    ).toBeVisible();
    await actor.locator('input[autocomplete="current-password"]').fill(state.password);
    await actor.locator('input[autocomplete="new-password"]').nth(0).fill(state.changed);
    await actor.locator('input[autocomplete="new-password"]').nth(1).fill(state.changed);
    await actor.getByRole('button', { name: zh.common.save, exact: true }).click();
    fixture('factory', 'prepare');
    factoriesPrepared = true;
    await actor.getByRole('menuitem', { name: zh.nav.factories, exact: true }).click();
    await expect(rows(actor)).toHaveCount(55);
    await create('', '', 400);
    await create('A'.repeat(65), '过长编码', 400);
    await create('CMP_F44_MANUAL', '名'.repeat(129), 400);
    await create('\u00a0CMP_F44_MANUAL\u0085', '\u00a0工厂审计手工\u202f', 200);
    await create('CMP_F44_MANUAL', '重复编码', 409);
    await create('CMP_F44_CONFLICT', '工厂审计手工', 409);
    await dialog.getByRole('button', { name: zh.common.cancel, exact: true }).click();
    const orgs = [
      { FOrgID: '944001', FNumber: 'CMP_F44_MANUAL', FName: '工厂审计改名', FForbidStatus: 'A' },
      { FOrgID: '944002', FNumber: 'CMP_F44_K3', FName: '工厂审计新增', FForbidStatus: 'A' },
      { FOrgID: '944003', FNumber: 'CMP_F44_CONFLICT', FName: '比较工厂44-00', FForbidStatus: 'A' },
    ];
    await sync({ organizations: orgs });
    await sync({ organizations: orgs });
    await sync({ organizations: [{ ...orgs[0], FName: '比较工厂44-00' }] });
    await sync({ organizationFailure: true }, 502);
    await sync({ organizations: [] });
    await capture(actor, '01-factory-recovery');
    // 原管理员导出，临时操作员不拥有持久任务；此处读取真实页面查询与下载结果。
    await page.getByRole('menuitem', { name: zh.nav.audit, exact: true }).click();
    await page.getByPlaceholder(zh.audit.operator, { exact: true }).fill(state.names[0]!);
    const ready = page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        new URL(r.url()).pathname === (prototype ? '/api/audits' : '/api/v1/sn/audits/page') &&
        new URL(r.url()).searchParams.get('operator') === state.names[0],
    );
    await page.getByRole('button', { name: zh.common.search, exact: true }).click();
    const result = await ready;
    expect(result.status()).toBe(200);
    const resultBody = await result.json();
    const items: Record<string, unknown>[] = prototype ? resultBody.items : resultBody.data.items;
    writeFileSync(info.outputPath('records.json'), JSON.stringify(items, null, 2));
    const expected = [
      ['FACTORY_SYNC', 'OK', 'inserted=0 updated=0 conflicts=[]', null, null, 0],
      ['FACTORY_SYNC', 'FAIL', 'POST /api/factories/sync', '金蝶查询失败：HTTP 503', null, null],
      [
        'FACTORY_SYNC',
        'OK',
        'inserted=0 updated=0 conflicts=[CMP_F44_MANUAL/比较工厂44-00]',
        null,
        null,
        1,
      ],
      [
        'FACTORY_SYNC',
        'OK',
        'inserted=0 updated=0 conflicts=[CMP_F44_CONFLICT/比较工厂44-00]',
        null,
        null,
        3,
      ],
      [
        'FACTORY_SYNC',
        'OK',
        'inserted=1 updated=1 conflicts=[CMP_F44_CONFLICT/比较工厂44-00]',
        null,
        null,
        3,
      ],
      [
        'FACTORY_SYNC',
        'FAIL',
        'POST /api/factories',
        '工厂编码或名称已存在：CMP_F44_CONFLICT/工厂审计手工',
        'CMP_F44_CONFLICT',
        null,
      ],
      [
        'FACTORY_SYNC',
        'FAIL',
        'POST /api/factories',
        '工厂编码或名称已存在：CMP_F44_MANUAL/重复编码',
        'CMP_F44_MANUAL',
        null,
      ],
      ['FACTORY_CREATE', 'OK', '工厂审计手工', null, 'CMP_F44_MANUAL', null],
      [
        'FACTORY_SYNC',
        'FAIL',
        'POST /api/factories',
        errors.FACTORY_CODE_INVALID,
        'CMP_F44_MANUAL',
        null,
      ],
      ['FACTORY_SYNC', 'FAIL', 'POST /api/factories', errors.FACTORY_CODE_INVALID, null, null],
      ['PASSWORD_CHANGE', 'OK', state.names[0], null, null, null],
      ['LOGIN', 'OK', 'client=127.0.0.1', null, null, null],
    ];
    const names: Record<string, string> = {
      'sn:factory:create': 'FACTORY_CREATE',
      'sn:factory:sync': 'FACTORY_SYNC',
      'auth:PASSWORD_CHANGE': 'PASSWORD_CHANGE',
    };
    expect(
      items.map((r) => [
        names[String(r.action)] ?? r.action,
        r.result === 'SUCCESS' ? 'OK' : r.result === 'FAILED' ? 'FAIL' : r.result,
        r.detail,
        r[prototype ? 'error_msg' : 'errorMessage'],
        r[prototype ? 'factory_code' : 'factoryCode'],
        r[prototype ? 'qty' : 'quantity'] === null
          ? null
          : Number(r[prototype ? 'qty' : 'quantity']),
      ]),
    ).toEqual(expected);
    await expect(rows(page)).toHaveCount(expected.length);
    expect(await rows(page).locator('td:nth-child(7)').allTextContents()).toEqual(
      expected.map((row) => row[4] ?? ''),
    );
    for (const item of items) {
      expect(item.operator).toBe(state.names[0]);
      expect(item[prototype ? 'operator_name' : 'operatorName']).toBe('工厂痕迹操作员');
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
    for (let i = 0; i < 10; i++) {
      await rows(page)
        .nth(i)
        .locator(prototype ? '.el-table__expand-icon' : '.sn-audit-expand')
        .click();
      await expect(
        page.getByText(String(expected[i]![2]), { exact: true }).filter({ visible: true }).last(),
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
    await file.saveAs(info.outputPath('factories.xlsx'));
    const parsed = JSON.parse(
      execFileSync(
        python,
        [
          '-X',
          'utf8',
          resolve('../tools/testing/parity-query-file.py'),
          info.outputPath('factories.xlsx'),
        ],
        { windowsHide: true, encoding: 'utf8' },
      ),
    ) as { raw: unknown[][]; types: string[][] };
    writeFileSync(info.outputPath('file.json'), JSON.stringify(parsed, null, 2));
    expect(parsed.raw[0]).toHaveLength(24);
    expect(parsed.raw.slice(1).map((r) => [r[5], r[6], r[23], r[21], r[7], r[14]])).toEqual(
      expected,
    );
    // 刷新目录后，同一历史痕迹显示当前名称；详情和文件仍保存当时的事实。
    await page.getByRole('menuitem', { name: zh.nav.factories, exact: true }).click();
    await expect(rows(page)).toHaveCount(57);
    await page.getByRole('menuitem', { name: zh.nav.audit, exact: true }).click();
    await page.getByPlaceholder(zh.audit.operator, { exact: true }).fill(state.names[0]!);
    const refreshed = page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        new URL(r.url()).pathname === (prototype ? '/api/audits' : '/api/v1/sn/audits/page') &&
        new URL(r.url()).searchParams.get('operator') === state.names[0],
    );
    await page.getByRole('button', { name: zh.common.search, exact: true }).click();
    expect((await refreshed).status()).toBe(200);
    await expect(rows(page)).toHaveCount(expected.length);
    expect(await rows(page).locator('td:nth-child(7)').allTextContents()).toEqual(
      expected.map((row) => (row[4] === 'CMP_F44_MANUAL' ? '工厂审计改名' : (row[4] ?? ''))),
    );
    await capture(page, '03-audit-after-directory-refresh');
    writeFileSync(
      info.outputPath('factory-label-glyphs.json'),
      JSON.stringify(
        await rows(page)
          .locator('td:nth-child(7)')
          .evaluateAll((cells) =>
            cells.map((cell) => {
              const origin = cell.getBoundingClientRect();
              const glyphs: unknown[] = [];
              const walker = document.createTreeWalker(cell, NodeFilter.SHOW_TEXT);
              let node: Node | null;
              while ((node = walker.nextNode())) {
                const value = node.textContent ?? '';
                for (let start = 0; start < value.length;) {
                  const text = String.fromCodePoint(value.codePointAt(start)!);
                  const range = document.createRange();
                  range.setStart(node, start);
                  range.setEnd(node, start + text.length);
                  const box = range.getBoundingClientRect();
                  glyphs.push({
                    text,
                    rect: [box.x - origin.x, box.y - origin.y, box.width, box.height].map(
                      (v) => Math.round(v * 1000) / 1000,
                    ),
                  });
                  start += text.length;
                }
              }
              return glyphs;
            }),
          ),
        null,
        2,
      ),
    );
    writeFileSync(
      info.outputPath('refreshed-table.json'),
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
    expect(pageErrors).toEqual([]);
  } finally {
    writeFileSync(info.outputPath('writes.json'), JSON.stringify({ writes, pageErrors }, null, 2));
    await context.close();
    setControl({});
    if (factoriesPrepared) fixture('factory', 'clean');
    fixture('account-audit', 'clean');
  }
});
