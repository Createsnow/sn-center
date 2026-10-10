import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, renameSync } from 'node:fs';
import { resolve } from 'node:path';
import { protectTestSecrets } from '../privacy';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };

// 全量替换和时钟仅操作专用a48库；业务同步、设置和导出均从真实页面触发。
test('P05/P12 order audit: manual outcomes, system schedule, filters, details and 24-column files', async ({
  page,
}, info) => {
  test.setTimeout(240_000);
  page.setDefaultTimeout(15_000);
  const prototype = info.project.name === 'prototype';
  const kind = prototype ? 'python' : 'java';
  const tag = process.env.ADTEK_ORDER_AUDIT_RUN!;
  expect(tag).toMatch(/^[0-9]{8,14}$/);
  const username = 'audit' + tag;
  const python = resolve('../../sn-center-python/.venv/Scripts/python.exe');
  const fixture = (action: string) =>
    JSON.parse(
      execFileSync(
        python,
        [
          '-X',
          'utf8',
          resolve('../tools/testing/parity-order-audit-fixture.py'),
          kind,
          action,
          tag,
        ],
        { windowsHide: true, encoding: 'utf8' },
      ),
    );
  const controlFile = resolve('../tools/.runtime/comparison-20261008/order-audits/k3-control.json');
  const control = (value: unknown) => {
    writeFileSync(controlFile + '.next', JSON.stringify(value));
    renameSync(controlFile + '.next', controlFile);
  };
  const password = readFileSync(
    resolve(`../deploy/.secrets/comparison-20261008/${kind}-admin-password`),
    'utf8',
  ).trim();
  protectTestSecrets(password);
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  const writes: unknown[] = [];
  const capture = async (label: string) => {
    await expect(page.locator('.ant-spin-spinning:visible,.el-loading-mask:visible')).toHaveCount(
      0,
    );
    await page.mouse.move(0, 0);
    await page.screenshot({ path: info.outputPath(label + '.png'), animations: 'disabled' });
  };
  const sync = async (label: string, expected: string, status = 200, bill?: string) => {
    if (bill !== undefined)
      await page.getByPlaceholder(zh.orders.billToSync, { exact: true }).fill(bill);
    const ready = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/orders\/sync(?:-all)?$/.test(new URL(r.url()).pathname),
    );
    await page
      .getByRole('button', {
        name: bill === undefined ? zh.orders.syncAll : zh.orders.syncOne,
        exact: true,
      })
      .click();
    const response = await ready;
    writes.push({
      label,
      status: response.status(),
      body: status === 500 ? null : await response.json(),
    });
    expect(response.status()).toBe(status);
    const message = page
      .locator(prototype ? '.el-message' : '.ant-message-notice')
      .filter({ hasText: expected });
    await expect(message).toHaveText(expected);
    await capture(label);
    if (status !== 200)
      await message.locator(prototype ? '.el-message__closeBtn' : 'button').click();
    await expect(page.getByRole('button', { name: zh.orders.syncAll, exact: true })).toBeEnabled();
  };
  const schedule = async (enabled: boolean, minutes: number) => {
    await page.getByRole('button', { name: zh.orders.sched.settings, exact: true }).click();
    const dialog = page.getByRole('dialog');
    await dialog.locator(prototype ? '.el-select' : '.ant-select').click();
    await page.getByRole('option', { name: zh.orders.sched.unit.minute, exact: true }).click();
    await dialog.getByRole('spinbutton').fill(String(minutes));
    if ((await dialog.getByRole('switch').getAttribute('aria-checked')) !== String(enabled))
      await dialog.locator(prototype ? '.el-switch' : '.ant-switch').click();
    const ready = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        new URL(r.url()).pathname.endsWith('/orders/sync-schedule'),
    );
    await dialog.getByRole('button', { name: zh.common.save, exact: true }).click();
    expect((await ready).status()).toBe(200);
    await expect(dialog).toBeHidden();
  };
  const scheduled = async (label: string, success: boolean) => {
    const before = fixture('snapshot').audits.filter(
      (r: Record<string, unknown>) =>
        r.operator === 'system' && ['ORDER_SYNC', 'sn:order:sync'].includes(String(r.action)),
    ).length;
    fixture('due');
    await expect
      .poll(
        () =>
          fixture('snapshot').audits.filter(
            (r: Record<string, unknown>) =>
              r.operator === 'system' && ['ORDER_SYNC', 'sn:order:sync'].includes(String(r.action)),
          ).length,
        { timeout: 45_000, intervals: [1000] },
      )
      .toBe(before + 1);
    const snapshot = fixture('snapshot');
    expect(Boolean(snapshot.schedule[0].last_ok)).toBe(success);
    writeFileSync(info.outputPath(label + '.json'), JSON.stringify(snapshot, null, 2));
    await page.reload();
    await page.getByRole('button', { name: zh.orders.sched.settings, exact: true }).click();
    await capture(label);
    await page
      .getByRole('dialog')
      .getByRole('button', { name: zh.common.cancel, exact: true })
      .click();
  };
  const actionNames: Record<string, string> = {
    ORDER_SYNC: 'sn:order:sync',
    ORDER_SYNC_SCHEDULE: 'sn:order:schedule',
  };
  // expected columns: result, detail, error, bill, quantity; API and actual Excel are checked separately.
  const verify = async (
    label: string,
    actor: string,
    action: 'ORDER_SYNC' | 'ORDER_SYNC_SCHEDULE',
    expected: unknown[][],
  ) => {
    await page.getByPlaceholder(zh.audit.operator, { exact: true }).fill(actor);
    await page
      .locator(prototype ? '.toolbar .el-select' : '.sn-toolbar .ant-select')
      .nth(0)
      .click();
    await page.getByRole('option', { name: zh.actions[action], exact: true }).click();
    const route = prototype ? '/api/audits' : '/api/v1/sn/audits/page';
    const ready = page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        new URL(r.url()).pathname === route &&
        new URL(r.url()).searchParams.get('operator') === actor,
    );
    await page.getByRole('button', { name: zh.common.search, exact: true }).click();
    const body = await (await ready).json();
    const items = prototype ? body.items : body.data.items;
    const actual = items.map((r: Record<string, unknown>) => [
      r.result === 'SUCCESS' ? 'OK' : r.result === 'FAILED' ? 'FAIL' : r.result,
      r.detail,
      r[prototype ? 'error_msg' : 'errorMessage'],
      r[prototype ? 'bill_no' : 'billNo'],
      r[prototype ? 'qty' : 'quantity'] === null ? null : Number(r[prototype ? 'qty' : 'quantity']),
    ]);
    expect(actual).toEqual(expected);
    for (const r of items) {
      expect(r.action).toBe(prototype ? action : actionNames[action]);
      expect(r.operator).toBe(actor);
      expect(r[prototype ? 'operator_name' : 'operatorName']).toBe(
        actor === 'system' ? '系统' : '同步痕迹操作员',
      );
      expect(r.source).toBe(actor === 'system' ? 'SYSTEM' : 'PAGE');
      if (actor === 'system') expect(r[prototype ? 'trace_id' : 'traceId']).toBeNull();
    }
    const rows = page.locator(prototype ? '.el-table__row' : '.ant-table-row');
    await expect(rows).toHaveCount(expected.length);
    await capture(label + '-table');
    const table = await rows.evaluateAll((elements) =>
      elements.map((el) => [...el.querySelectorAll('td')].map((cell) => cell.textContent?.trim())),
    );
    for (let i = 0; i < expected.length; i++) {
      await rows
        .nth(i)
        .locator(prototype ? '.el-table__expand-icon' : '.sn-audit-expand')
        .click();
      await expect(
        page.getByText(String(expected[i]![1]), { exact: true }).filter({ visible: true }).last(),
      ).toBeVisible();
      if (expected[i]![2])
        await expect(
          page.getByText(String(expected[i]![2]), { exact: true }).filter({ visible: true }).last(),
        ).toBeVisible();
      await capture(label + '-detail-' + i);
      await rows
        .nth(i)
        .locator(prototype ? '.el-table__expand-icon' : '.sn-audit-expand')
        .click();
    }
    const pending = page.waitForEvent('download');
    await page.getByRole('button', { name: zh.query.exportXlsx, exact: true }).click();
    const file = await pending;
    expect(file.suggestedFilename()).toBe('audit_export.xlsx');
    await file.saveAs(info.outputPath(label + '.xlsx'));
    const parsed = JSON.parse(
      execFileSync(
        python,
        [
          '-X',
          'utf8',
          resolve('../tools/testing/parity-query-file.py'),
          info.outputPath(label + '.xlsx'),
        ],
        { windowsHide: true, encoding: 'utf8' },
      ),
    );
    expect(parsed.raw[0]).toHaveLength(24);
    expect(parsed.raw.slice(1).map((r: unknown[]) => [r[6], r[23], r[21], r[11], r[14]])).toEqual(
      expected,
    );
    writeFileSync(
      info.outputPath(label + '-evidence.json'),
      JSON.stringify({ items, table, parsed }, null, 2),
    );
  };
  try {
    fixture('prepare');
    fixture('clear');
    control({});
    await page.goto(prototype ? 'http://127.0.0.1:8001/login' : 'http://127.0.0.1:15174/login');
    await page.locator('input[autocomplete="username"]').fill(username);
    await page.locator('input[autocomplete="current-password"]').fill(password);
    await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
    await page.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
    await page.waitForLoadState('networkidle');
    await sync('01-full', '已同步 3 张单据，4 行');
    await sync('02-single', '单据 CMP-MO-001 已同步，2 行', 200, 'CMP-MO-001');
    await sync(
      '03-missing',
      '单据 CMP-MISSING 已不在计划 / 计划确认，已从快照移除',
      200,
      'CMP-MISSING',
    );
    control({ orders: 'empty' });
    await sync('04-empty', '已同步 0 张单据，0 行');
    control({ failure: 'query' });
    await sync('05-query-failed', '金蝶查询失败：比较查询失败', 502);
    control({ failure: 'login' });
    await sync('06-login-failed', '金蝶登录失败：比较登录失败', 502, 'CMP-MO-001');
    control({});
    fixture('fault');
    await sync('07-sql-failed', 'Internal Server Error', 500);
    fixture('clear');
    await sync('08-recovered', '已同步 3 张单据，4 行');
    await schedule(true, 10);
    await schedule(true, 10);
    await scheduled('09-scheduled-success', true);
    control({ failure: 'query' });
    await scheduled('10-scheduled-failure', false);
    control({});
    await scheduled('11-scheduled-recovered', true);
    await schedule(false, 1440);
    await page.getByRole('menuitem', { name: zh.nav.audit, exact: true }).click();
    await verify('manual', username, 'ORDER_SYNC', [
      ['OK', 'mode=all rows=4', null, null, 3],
      ['FAIL', 'POST /api/orders/sync', '金蝶登录失败：比较登录失败', 'CMP-MO-001', null],
      ['FAIL', 'POST /api/orders/sync', '金蝶查询失败：比较查询失败', null, null],
      ['OK', 'mode=all rows=0', null, null, 0],
      ['OK', 'mode=one rows=0', null, 'CMP-MISSING', 0],
      ['OK', 'mode=one rows=2', null, 'CMP-MO-001', 1],
      ['OK', 'mode=all rows=4', null, null, 3],
    ]);
    await verify('settings', username, 'ORDER_SYNC_SCHEDULE', [
      [
        'OK',
        'enabled=False interval_minutes=1440 before=enabled=True interval_minutes=10',
        null,
        null,
        null,
      ],
      [
        'OK',
        'enabled=True interval_minutes=10 before=enabled=True interval_minutes=10',
        null,
        null,
        null,
      ],
      [
        'OK',
        'enabled=True interval_minutes=10 before=enabled=False interval_minutes=1440',
        null,
        null,
        null,
      ],
    ]);
    await verify('system', 'system', 'ORDER_SYNC', [
      ['OK', 'mode=all rows=4', null, null, 3],
      ['FAIL', 'mode=scheduled', '金蝶查询失败：比较查询失败', null, null],
      ['OK', 'mode=all rows=4', null, null, 3],
    ]);
    expect(errors).toEqual([]);
  } finally {
    control({});
    fixture('clear');
    writeFileSync(info.outputPath('writes.json'), JSON.stringify({ writes, errors }, null, 2));
    writeFileSync(
      info.outputPath('final-database.json'),
      JSON.stringify(fixture('snapshot'), null, 2),
    );
  }
});

test('P12 order audit filters: bill, detail, system source and result use the real generated facts', async ({
  page,
}, info) => {
  const prototype = info.project.name === 'prototype';
  const kind = prototype ? 'python' : 'java';
  const tag = process.env.ADTEK_ORDER_AUDIT_RUN!;
  expect(tag).toMatch(/^[0-9]{8,14}$/);
  const username = 'audit' + tag;
  const password = readFileSync(
    resolve(`../deploy/.secrets/comparison-20261008/${kind}-admin-password`),
    'utf8',
  ).trim();
  protectTestSecrets(password);
  await page.goto(prototype ? 'http://127.0.0.1:8001/login' : 'http://127.0.0.1:15174/login');
  await page.locator('input[autocomplete="username"]').fill(username);
  await page.locator('input[autocomplete="current-password"]').fill(password);
  await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
  await page.getByRole('menuitem', { name: zh.nav.audit, exact: true }).click();
  const choose = async (index: number, label: string) => {
    await page
      .locator(prototype ? '.toolbar .el-select' : '.sn-toolbar .ant-select')
      .nth(index)
      .click();
    await page.getByRole('option', { name: label, exact: true }).click();
  };
  const evidence: unknown[] = [];
  const search = async (label: string, count: number, detail?: string) => {
    const pending = page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        new URL(r.url()).pathname === (prototype ? '/api/audits' : '/api/v1/sn/audits/page'),
    );
    await page.getByRole('button', { name: zh.common.search, exact: true }).click();
    const response = await pending;
    const body = await response.json();
    const items = prototype ? body.items : body.data.items;
    expect(items).toHaveLength(count);
    if (detail)
      expect(items.every((r: Record<string, unknown>) => String(r.detail).includes(detail))).toBe(
        true,
      );
    const rows = page.locator(prototype ? '.el-table__row' : '.ant-table-row');
    await expect(rows).toHaveCount(count);
    await page.screenshot({ path: info.outputPath(label + '.png'), animations: 'disabled' });
    evidence.push({
      label,
      items,
      table: await rows.evaluateAll((elements) =>
        elements.map((el) =>
          [...el.querySelectorAll('td')].map((cell) => cell.textContent?.trim()),
        ),
      ),
    });
    return items;
  };
  await choose(0, zh.actions.ORDER_SYNC);
  await page.getByPlaceholder(zh.audit.operator, { exact: true }).fill(username);
  await page.getByPlaceholder(zh.audit.keyword, { exact: true }).fill('CMP-MO-001');
  const bill = await search('01-bill', 2);
  expect(
    bill.every(
      (r: Record<string, unknown>) => r[prototype ? 'bill_no' : 'billNo'] === 'CMP-MO-001',
    ),
  ).toBe(true);
  await page.getByPlaceholder(zh.audit.keyword, { exact: true }).fill('mode=one');
  await search('02-detail', 2, 'mode=one');
  await page.getByPlaceholder(zh.audit.operator, { exact: true }).fill('');
  await page.getByPlaceholder(zh.audit.keyword, { exact: true }).fill('');
  await choose(2, zh.audit.srcSYSTEM);
  const system = await search('03-system', 3);
  expect(
    system.every((r: Record<string, unknown>) => r.operator === 'system' && r.source === 'SYSTEM'),
  ).toBe(true);
  await choose(1, zh.audit.fail);
  await search('04-system-failure', 1, 'mode=scheduled');
  await page.getByPlaceholder(zh.audit.keyword, { exact: true }).fill('mode=all');
  await search('05-no-match', 0);
  writeFileSync(info.outputPath('filters.json'), JSON.stringify(evidence, null, 2));
});
