# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: order-audit.spec.ts >> P05/P12 order audit: manual outcomes, system schedule, filters, details and 24-column files
- Location: e2e\parity\order-audit.spec.ts:9:1

# Error details

```
Error: expect(locator).toHaveText(expected) failed

Locator: locator('.el-message').filter({ hasText: '单据 CMP-MISSING 已同步，0 行' })
Expected: "单据 CMP-MISSING 已同步，0 行"
Timeout: 15000ms
Error: element(s) not found

Call log:
  - Expect "toHaveText" locator('.el-message').filter({ hasText: '单据 CMP-MISSING 已同步，0 行' }) with timeout 15000ms
  - waiting for locator('.el-message').filter({ hasText: '单据 CMP-MISSING 已同步，0 行' })

```

# Test source

```ts
  1   | import { test, expect } from '@playwright/test';
  2   | import { execFileSync } from 'node:child_process';
  3   | import { readFileSync, writeFileSync, renameSync } from 'node:fs';
  4   | import { resolve } from 'node:path';
  5   | import { protectTestSecrets } from '../privacy';
  6   | import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
  7   | 
  8   | // 全量替换和时钟仅操作专用a48库；业务同步、设置和导出均从真实页面触发。
  9   | test('P05/P12 order audit: manual outcomes, system schedule, filters, details and 24-column files', async ({ page }, info) => {
  10  |   test.setTimeout(240_000);
  11  |   const prototype = info.project.name === 'prototype';
  12  |   const kind = prototype ? 'python' : 'java';
  13  |   const python = resolve('../../sn-center-python/.venv/Scripts/python.exe');
  14  |   const fixture = (action: string) => JSON.parse(execFileSync(python, ['-X', 'utf8', resolve('../tools/testing/parity-order-audit-fixture.py'), kind, action], { windowsHide: true, encoding: 'utf8' }));
  15  |   const controlFile = resolve('../tools/.runtime/comparison-20261008/order-audits/k3-control.json');
  16  |   const control = (value: unknown) => {
  17  |     writeFileSync(controlFile + '.next', JSON.stringify(value));
  18  |     renameSync(controlFile + '.next', controlFile);
  19  |   };
  20  |   const password = readFileSync(resolve(`../deploy/.secrets/comparison-20261008/${kind}-admin-password`), 'utf8').trim();
  21  |   protectTestSecrets(password);
  22  |   const errors: string[] = [];
  23  |   page.on('pageerror', e => errors.push(e.message));
  24  |   const writes: unknown[] = [];
  25  |   const capture = async (label: string) => {
  26  |     await expect(page.locator('.ant-spin-spinning:visible,.el-loading-mask:visible')).toHaveCount(0);
  27  |     await page.mouse.move(0, 0);
  28  |     await page.screenshot({ path: info.outputPath(label + '.png'), animations: 'disabled' });
  29  |   };
  30  |   const sync = async (label: string, expected: string, status = 200, bill?: string) => {
  31  |     if (bill !== undefined) await page.getByPlaceholder(zh.orders.billToSync, { exact: true }).fill(bill);
  32  |     const ready = page.waitForResponse(r => r.request().method() === 'POST' && /\/orders\/sync(?:-all)?$/.test(new URL(r.url()).pathname));
  33  |     await page.getByRole('button', { name: bill === undefined ? zh.orders.syncAll : zh.orders.syncOne, exact: true }).click();
  34  |     const response = await ready;
  35  |     writes.push({ label, status: response.status(), body: status === 500 ? null : await response.json() });
  36  |     expect(response.status()).toBe(status);
  37  |     const message = page.locator(prototype ? '.el-message' : '.ant-message-notice').filter({ hasText: expected });
> 38  |     await expect(message).toHaveText(expected);
      |                           ^ Error: expect(locator).toHaveText(expected) failed
  39  |     await capture(label);
  40  |     if (status !== 200) await message.locator(prototype ? '.el-message__closeBtn' : 'button').click();
  41  |     await expect(page.getByRole('button', { name: zh.orders.syncAll, exact: true })).toBeEnabled();
  42  |   };
  43  |   const schedule = async (enabled: boolean, minutes: number) => {
  44  |     await page.getByRole('button', { name: zh.orders.sched.settings, exact: true }).click();
  45  |     const dialog = page.getByRole('dialog');
  46  |     await dialog.locator(prototype ? '.el-select' : '.ant-select').click();
  47  |     await page.getByRole('option', { name: zh.orders.sched.unit.minute, exact: true }).click();
  48  |     await dialog.getByRole('spinbutton').fill(String(minutes));
  49  |     if (await dialog.getByRole('switch').getAttribute('aria-checked') !== String(enabled)) await dialog.getByRole('switch').click();
  50  |     const ready = page.waitForResponse(r => r.request().method() === 'PUT' && new URL(r.url()).pathname.endsWith('/orders/sync-schedule'));
  51  |     await dialog.getByRole('button', { name: zh.common.save, exact: true }).click();
  52  |     expect((await ready).status()).toBe(200);
  53  |     await expect(dialog).toBeHidden();
  54  |   };
  55  |   const scheduled = async (label: string, success: boolean) => {
  56  |     const before = fixture('snapshot').audits.filter((r: Record<string, unknown>) => r.operator === 'system' && ['ORDER_SYNC','sn:order:sync'].includes(String(r.action))).length;
  57  |     fixture('due');
  58  |     await expect.poll(() => fixture('snapshot').audits.filter((r: Record<string, unknown>) => r.operator === 'system' && ['ORDER_SYNC','sn:order:sync'].includes(String(r.action))).length, { timeout: 45_000, intervals: [1000] }).toBe(before + 1);
  59  |     const snapshot = fixture('snapshot');
  60  |     expect(Boolean(snapshot.schedule[0].last_ok)).toBe(success);
  61  |     writeFileSync(info.outputPath(label + '.json'), JSON.stringify(snapshot, null, 2));
  62  |     await page.reload();
  63  |     await page.getByRole('button', { name: zh.orders.sched.settings, exact: true }).click();
  64  |     await capture(label);
  65  |     await page.getByRole('dialog').getByRole('button', { name: zh.common.cancel, exact: true }).click();
  66  |   };
  67  |   const actionNames: Record<string, string> = { ORDER_SYNC: 'sn:order:sync', ORDER_SYNC_SCHEDULE: 'sn:order:schedule' };
  68  |   // expected columns: result, detail, error, bill, quantity; API and actual Excel are checked separately.
  69  |   const verify = async (label: string, actor: string, action: 'ORDER_SYNC'|'ORDER_SYNC_SCHEDULE', expected: unknown[][]) => {
  70  |     await page.getByPlaceholder(zh.audit.operator, { exact: true }).fill(actor);
  71  |     await page.locator(prototype ? '.toolbar .el-select' : '.sn-toolbar .ant-select').nth(0).click();
  72  |     await page.getByRole('option', { name: zh.actions[action], exact: true }).click();
  73  |     const route = prototype ? '/api/audits' : '/api/v1/sn/audits/page';
  74  |     const ready = page.waitForResponse(r => r.request().method() === 'GET' && new URL(r.url()).pathname === route && new URL(r.url()).searchParams.get('operator') === actor);
  75  |     await page.getByRole('button', { name: zh.common.search, exact: true }).click();
  76  |     const body = await (await ready).json();
  77  |     const items = prototype ? body.items : body.data.items;
  78  |     const actual = items.map((r: Record<string, unknown>) => [r.result === 'SUCCESS' ? 'OK' : r.result === 'FAILED' ? 'FAIL' : r.result, r.detail, r[prototype ? 'error_msg' : 'errorMessage'], r[prototype ? 'bill_no' : 'billNo'], r[prototype ? 'qty' : 'quantity'] === null ? null : Number(r[prototype ? 'qty' : 'quantity'])]);
  79  |     expect(actual).toEqual(expected);
  80  |     for (const r of items) {
  81  |       expect(r.action).toBe(prototype ? action : actionNames[action]);
  82  |       expect(r.operator).toBe(actor);
  83  |       expect(r[prototype ? 'operator_name' : 'operatorName']).toBe(actor === 'system' ? '系统' : '同步痕迹操作员');
  84  |       expect(r.source).toBe(actor === 'system' ? 'SYSTEM' : 'PAGE');
  85  |       if (actor === 'system') expect(r[prototype ? 'trace_id' : 'traceId']).toBeNull();
  86  |     }
  87  |     const rows = page.locator(prototype ? '.el-table__row' : '.ant-table-row');
  88  |     await expect(rows).toHaveCount(expected.length);
  89  |     await capture(label + '-table');
  90  |     const table = await rows.evaluateAll(elements => elements.map(el => [...el.querySelectorAll('td')].map(cell => cell.textContent?.trim())));
  91  |     for (let i=0; i<expected.length; i++) {
  92  |       await rows.nth(i).locator(prototype ? '.el-table__expand-icon' : '.sn-audit-expand').click();
  93  |       await expect(page.getByText(String(expected[i]![1]), { exact: true }).filter({ visible:true }).last()).toBeVisible();
  94  |       if (expected[i]![2]) await expect(page.getByText(String(expected[i]![2]), { exact: true }).filter({ visible:true }).last()).toBeVisible();
  95  |       await capture(label + '-detail-' + i);
  96  |       await rows.nth(i).locator(prototype ? '.el-table__expand-icon' : '.sn-audit-expand').click();
  97  |     }
  98  |     const pending = page.waitForEvent('download');
  99  |     await page.getByRole('button', { name: zh.query.exportXlsx, exact: true }).click();
  100 |     const file = await pending;
  101 |     expect(file.suggestedFilename()).toBe('audit_export.xlsx');
  102 |     await file.saveAs(info.outputPath(label + '.xlsx'));
  103 |     const parsed = JSON.parse(execFileSync(python, ['-X','utf8',resolve('../tools/testing/parity-query-file.py'),info.outputPath(label + '.xlsx')], { windowsHide:true, encoding:'utf8' }));
  104 |     expect(parsed.raw[0]).toHaveLength(24);
  105 |     expect(parsed.raw.slice(1).map((r: unknown[]) => [r[6],r[23],r[21],r[11],r[14]])).toEqual(expected);
  106 |     writeFileSync(info.outputPath(label + '-evidence.json'), JSON.stringify({items,table,parsed},null,2));
  107 |   };
  108 |   try {
  109 |     fixture('clear'); control({});
  110 |     await page.goto(prototype ? 'http://127.0.0.1:8001/login' : 'http://127.0.0.1:15174/login');
  111 |     await page.locator('input[autocomplete="username"]').fill('audit48');
  112 |     await page.locator('input[autocomplete="current-password"]').fill(password);
  113 |     await page.getByRole('button', { name: zh.login.submit, exact:true }).click();
  114 |     await page.getByRole('menuitem', { name: zh.nav.orders, exact:true }).click();
  115 |     await page.waitForLoadState('networkidle');
  116 |     await sync('01-full','已同步 3 张单据，4 行');
  117 |     await sync('02-single','单据 CMP-MO-001 已同步，2 行',200,'CMP-MO-001');
  118 |     await sync('03-missing','单据 CMP-MISSING 已同步，0 行',200,'CMP-MISSING');
  119 |     control({ orders:'empty' });
  120 |     await sync('04-empty','已同步 0 张单据，0 行');
  121 |     control({ failure:'query' });
  122 |     await sync('05-query-failed','金蝶查询失败：比较查询失败',502);
  123 |     control({ failure:'login' });
  124 |     await sync('06-login-failed','金蝶登录失败：比较登录失败',502,'CMP-MO-001');
  125 |     control({}); fixture('fault');
  126 |     await sync('07-sql-failed','Internal Server Error',500);
  127 |     fixture('clear');
  128 |     await sync('08-recovered','已同步 3 张单据，4 行');
  129 |     await schedule(true,10);
  130 |     await schedule(true,10);
  131 |     await scheduled('09-scheduled-success',true);
  132 |     control({ failure:'query' });
  133 |     await scheduled('10-scheduled-failure',false);
  134 |     control({});
  135 |     await scheduled('11-scheduled-recovered',true);
  136 |     await schedule(false,1440);
  137 |     await page.getByRole('menuitem', { name: zh.nav.audit, exact:true }).click();
  138 |     await verify('manual','audit48','ORDER_SYNC',[
```