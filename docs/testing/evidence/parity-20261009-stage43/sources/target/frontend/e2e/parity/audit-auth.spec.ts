import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import { protectTestSecrets } from '../privacy';
import { paceComparisonLogin } from './login-budget';
import { kind, source, login } from './query-support';

const tag = process.env.ADTEK_AUTH_RUN;
const rowsFor = (info: TestInfo) => (source(info) ? '.el-table__row' : '.ant-table-row');
const privateRoot = resolve('../deploy/.secrets/comparison-20261008');
const python = resolve('../../sn-center-python/.venv/Scripts/python.exe');

async function attempt(
  page: Page,
  info: TestInfo,
  username: string,
  password: string,
  status: number,
) {
  protectTestSecrets(password);
  await page.goto('/login');
  await paceComparisonLogin(page);
  await page.locator('input[autocomplete="username"]').fill(username);
  await page.locator('input[autocomplete="current-password"]').fill(password);
  const response = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' &&
      r.url().endsWith(source(info) ? '/auth/login' : '/system/auth/login'),
  );
  await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
  expect((await response).status()).toBe(status);
}

async function captureMessage(page: Page, info: TestInfo, name: string, text: string) {
  const toast = page.getByText(text, { exact: true }).last();
  const paint = () =>
    toast.evaluate((element) => {
      let opacity = 1;
      for (let node: Element | null = element; node; node = node.parentElement)
        opacity *= Number(getComputedStyle(node).opacity);
      const rect = element.getBoundingClientRect();
      return { opacity, text: element.textContent, top: rect.top, bottom: rect.bottom };
    });
  await expect.poll(async () => (await paint()).opacity).toBe(1);
  await expect.poll(async () => (await paint()).top).toBeGreaterThanOrEqual(0);
  const before = await paint();
  await page.screenshot({ path: info.outputPath(name + '.png'), animations: 'disabled' });
  const after = await paint();
  expect(after.opacity).toBe(1);
  writeFileSync(info.outputPath(name + '-paint.json'), JSON.stringify({ before, after }, null, 2));
}

async function inspect(
  page: Page,
  info: TestInfo,
  username: string,
  expected: {
    action: string;
    result: string;
    detail: string;
    error: string | null;
    factory: string | null;
  }[],
) {
  await login(
    page,
    'admin',
    readFileSync(resolve(privateRoot, `${kind(info)}-admin-password`), 'utf8').trim(),
  );
  await page.getByRole('menuitem', { name: zh.nav.audit, exact: true }).click();
  await page.getByPlaceholder(zh.audit.operator, { exact: true }).fill(username);
  const ready = page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      new URL(r.url()).pathname === (source(info) ? '/api/audits' : '/api/v1/sn/audits/page') &&
      new URL(r.url()).searchParams.get('operator') === username,
  );
  await page.getByRole('button', { name: zh.common.search, exact: true }).click();
  const response = await ready;
  expect(response.ok()).toBe(true);
  const body = (await response.json()) as {
    items: Record<string, unknown>[];
    data?: { items: Record<string, unknown>[] };
  };
  const items = source(info) ? body.items : body.data!.items;
  writeFileSync(info.outputPath(username + '-records.json'), JSON.stringify(items, null, 2));
  await page.screenshot({ path: info.outputPath(username + '-rows.png'), animations: 'disabled' });
  expect(
    items.map((r) => ({
      action: r.action === 'auth:PASSWORD_CHANGE' ? 'PASSWORD_CHANGE' : r.action,
      result: r.result === 'SUCCESS' ? 'OK' : r.result === 'FAILED' ? 'FAIL' : r.result,
      detail: r.detail,
      error: r[source(info) ? 'error_msg' : 'errorMessage'],
      factory: r[source(info) ? 'factory_code' : 'factoryCode'],
    })),
  ).toEqual(expected);
  const rows = page.locator(rowsFor(info));
  await expect(rows).toHaveCount(expected.length);
  writeFileSync(
    info.outputPath(username + '-table.json'),
    JSON.stringify(
      await rows.evaluateAll((elements) =>
        elements.map((el) =>
          [...el.querySelectorAll('td')].map((cell) => cell.textContent?.trim()),
        ),
      ),
      null,
      2,
    ),
  );
  const actors = await rows.evaluateAll((elements) =>
    elements.map((row) => {
      const cell = row.querySelectorAll('td')[2]!;
      const container = cell.querySelector('.cell') ?? cell;
      const rect = cell.getBoundingClientRect();
      const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT);
      const text: { text: string; x: number; y: number; width: number; height: number }[] = [];
      while (walker.nextNode()) {
        const node = walker.currentNode;
        for (let i = 0; i < (node.textContent?.length ?? 0); i++) {
          const range = document.createRange();
          range.setStart(node, i);
          range.setEnd(node, i + 1);
          const box = range.getBoundingClientRect();
          text.push({
            text: node.textContent!.slice(i, i + 1),
            x: box.x - rect.x,
            y: box.y - rect.y,
            width: box.width,
            height: box.height,
          });
        }
      }
      return { text, width: rect.width, height: rect.height };
    }),
  );
  writeFileSync(info.outputPath(username + '-actors.json'), JSON.stringify(actors, null, 2));
  for (let index = 0; index < expected.length; index++) {
    expect(items[index]![source(info) ? 'qty' : 'quantity']).toBeNull();
    expect(items[index]!.source).toBe('PAGE');
    expect(items[index]!.operator).toBe(username);
    await rows
      .nth(index)
      .locator(source(info) ? '.el-table__expand-icon' : '.sn-audit-expand')
      .click();
    await expect(
      page.getByText(expected[index]!.detail, { exact: true }).filter({ visible: true }),
    ).toBeVisible();
    await page.screenshot({
      path: info.outputPath(username + `-detail-${index}.png`),
      animations: 'disabled',
    });
    await rows
      .nth(index)
      .locator(source(info) ? '.el-table__expand-icon' : '.sn-audit-expand')
      .click();
  }
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: zh.query.exportXlsx, exact: true }).click();
  const file = await download;
  expect(file.suggestedFilename()).toBe('audit_export.xlsx');
  const path = info.outputPath(username + '.xlsx');
  await file.saveAs(path);
  const parsed = JSON.parse(
    execFileSync(python, ['-X', 'utf8', resolve('../tools/testing/parity-query-file.py'), path], {
      windowsHide: true,
      encoding: 'utf8',
    }),
  ) as { raw: unknown[][]; types: string[][] };
  writeFileSync(info.outputPath(username + '-file.json'), JSON.stringify(parsed, null, 2));
  expect(
    parsed.raw
      .slice(1)
      .map((r) => ({ action: r[5], result: r[6], detail: r[23], error: r[21], factory: r[7] })),
  ).toEqual(expected);
}

test('P12 real anonymous login failure appears in audit and downloaded file', async ({
  page,
}, info) => {
  test.setTimeout(90000);
  expect(tag).toMatch(/^[0-9]{8,14}$/);
  const username = `cmp_auth_${tag}U`;
  await attempt(page, info, username, 'Invalid9!comparison', 401);
  await expect(page.getByText('工号或密码错误', { exact: true })).toBeVisible();
  await captureMessage(page, info, 'rejected-login', '工号或密码错误');
  await inspect(page, info, username, [
    {
      action: 'LOGIN',
      result: 'FAIL',
      detail: 'client=127.0.0.1',
      error: '工号或密码错误',
      factory: null,
    },
  ]);
});

test('P12 real known login password rejection change and disabled account facts', async ({
  page,
  browser,
}, info) => {
  test.setTimeout(180000);
  expect(tag).toMatch(/^[0-9]{8,14}$/);
  const fixture = (action: string) =>
    execFileSync(
      python,
      ['-X', 'utf8', resolve('../tools/testing/parity-auth-fixture.py'), kind(info), action, tag!],
      { windowsHide: true, encoding: 'utf8' },
    );
  fixture('prepare');
  const entries = (
    JSON.parse(
      readFileSync(resolve(privateRoot, `auth-fixture-${kind(info)}-${tag}.json`), 'utf8'),
    ) as {
      entries: Record<string, { username: string; initial: string; changed: string }>;
    }
  ).entries;
  const active = entries.A!,
    disabled = entries.D!;
  for (const account of Object.values(entries))
    protectTestSecrets(account.initial, account.changed);
  const extras: Page[] = [];
  const newPage = async () => {
    const result = await browser.newPage({
      baseURL: source(info) ? 'http://127.0.0.1:8000' : 'http://127.0.0.1:15173',
      viewport: { width: 1440, height: 960 },
      locale: 'zh-CN',
    });
    extras.push(result);
    return result;
  };
  try {
    await attempt(page, info, active.username, 'Wrong9!comparison', 401);
    await expect(page.getByText('工号或密码错误', { exact: true }).last()).toBeVisible();
    await attempt(page, info, active.username, active.initial, 200);
    await expect(page.getByRole('heading', { name: zh.password.title, exact: true })).toBeVisible();
    const old = page.locator('input[autocomplete="current-password"]');
    const next = page.locator('input[autocomplete="new-password"]').nth(0);
    const confirm = page.locator('input[autocomplete="new-password"]').nth(1);
    for (const input of [
      { old: 'Wrong9!comparison', next: active.changed, status: 400, error: '原密码不正确' },
      { old: active.initial, next: active.initial, status: 400, error: '新密码不能与原密码相同' },
      { old: active.initial, next: active.changed, status: 200, error: null },
    ]) {
      await old.fill(input.old);
      await next.fill(input.next);
      await confirm.fill(input.next);
      const ready = page.waitForResponse(
        (r) => r.request().method() === 'POST' && r.url().endsWith('/auth/password'),
      );
      await page.getByRole('button', { name: zh.common.save, exact: true }).click();
      expect((await ready).status()).toBe(input.status);
      if (input.error) {
        await expect(page.getByText(input.error, { exact: true }).last()).toBeVisible();
        await page.screenshot({
          path: info.outputPath(input.error + '.png'),
          animations: 'disabled',
        });
      } else {
        await expect(page.getByRole('menuitem', { name: zh.nav.query, exact: true })).toBeVisible();
        await page.screenshot({
          path: info.outputPath('password-success.png'),
          animations: 'disabled',
        });
      }
    }
    const audit = await newPage();
    await inspect(audit, info, active.username, [
      {
        action: 'PASSWORD_CHANGE',
        result: 'OK',
        detail: active.username,
        error: null,
        factory: null,
      },
      {
        action: 'PASSWORD_CHANGE',
        result: 'FAIL',
        detail: 'POST /api/auth/password',
        error: '新密码不能与原密码相同',
        factory: null,
      },
      {
        action: 'PASSWORD_CHANGE',
        result: 'FAIL',
        detail: 'POST /api/auth/password',
        error: '原密码不正确',
        factory: null,
      },
      { action: 'LOGIN', result: 'OK', detail: 'client=127.0.0.1', error: null, factory: 'CMP-A' },
      {
        action: 'LOGIN',
        result: 'FAIL',
        detail: 'client=127.0.0.1',
        error: '工号或密码错误',
        factory: 'CMP-A',
      },
    ]);
    const denied = await newPage();
    await attempt(denied, info, disabled.username, 'Wrong9!comparison', 401);
    await attempt(denied, info, disabled.username, disabled.initial, 401);
    await expect(denied.getByText('账户已停用', { exact: true }).last()).toBeVisible();
    await denied.screenshot({
      path: info.outputPath('disabled-login.png'),
      animations: 'disabled',
    });
    await inspect(await newPage(), info, disabled.username, [
      {
        action: 'LOGIN',
        result: 'FAIL',
        detail: 'client=127.0.0.1',
        error: '账户已停用',
        factory: 'CMP-A',
      },
      {
        action: 'LOGIN',
        result: 'FAIL',
        detail: 'client=127.0.0.1',
        error: '工号或密码错误',
        factory: 'CMP-A',
      },
    ]);
  } finally {
    for (const extra of extras) await extra.close();
    expect.soft(() => fixture('clean'), 'Owned fixture cleanup must succeed').not.toThrow();
  }
});

test('P12 real fifth failed login and subsequent lock retain exact audit facts', async ({
  page,
}, info) => {
  test.setTimeout(150000);
  expect(tag).toMatch(/^[0-9]{8,14}$/);
  const username = `cmp_auth_${tag}L`;
  for (let index = 0; index < 5; index++)
    await attempt(page, info, username, 'Wrong9!comparison', 401);
  await attempt(page, info, username, 'Wrong9!comparison', 429);
  await expect(
    page.getByText('登录失败次数过多，请 15 分钟后再试', { exact: true }).last(),
  ).toBeVisible();
  await captureMessage(page, info, 'locked-login', '登录失败次数过多，请 15 分钟后再试');
  await inspect(page, info, username, [
    {
      action: 'LOGIN',
      result: 'FAIL',
      detail: 'client=127.0.0.1 locked',
      error: '登录失败次数过多，请 15 分钟后再试',
      factory: null,
    },
    {
      action: 'LOGIN',
      result: 'FAIL',
      detail: 'client=127.0.0.1 locked=15m',
      error: '工号或密码错误',
      factory: null,
    },
    ...Array.from({ length: 4 }, () => ({
      action: 'LOGIN',
      result: 'FAIL',
      detail: 'client=127.0.0.1',
      error: '工号或密码错误',
      factory: null,
    })),
  ]);
});
