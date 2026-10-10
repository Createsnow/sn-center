# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: factory-identity.spec.ts >> P14 organization changes: snapshot name, manual factory, changed ID/code, rename and directory filters
- Location: e2e\parity\factory-identity.spec.ts:9:1

# Error details

```
Error: expect(locator).toBeVisible() failed

Locator: getByRole('heading', { name: '生产订单', exact: true })
Expected: visible
Timeout: 15000ms
Error: element(s) not found

Call log:
  - Expect "toBeVisible" getByRole('heading', { name: '生产订单', exact: true }) with timeout 15000ms
  - waiting for getByRole('heading', { name: '生产订单', exact: true })

```

# Test source

```ts
  1   | import { test, expect } from '@playwright/test';
  2   | import { execFileSync } from 'node:child_process';
  3   | import { readFileSync, writeFileSync } from 'node:fs';
  4   | import { resolve } from 'node:path';
  5   | import { protectTestSecrets } from '../privacy';
  6   | import { paceComparisonLogin } from './login-budget';
  7   | import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
  8   | 
  9   | test('P14 organization changes: snapshot name, manual factory, changed ID/code, rename and directory filters', async ({ page }, info) => {
  10  |   test.setTimeout(180_000);
  11  |   const prototype = info.project.name === 'prototype';
  12  |   const kind = prototype ? 'python' : 'java';
  13  |   const run = resolve('../tools/.runtime/comparison-20261008');
  14  |   const fixture = (action: string) => execFileSync(resolve('../../sn-center-python/.venv/Scripts/python.exe'),
  15  |     ['-X', 'utf8', resolve('../tools/testing/parity-factory-identity-fixture.py'), kind, action],
  16  |     { windowsHide: true, encoding: 'utf8' });
  17  |   const password = readFileSync(resolve(`../deploy/.secrets/comparison-20261008/${kind}-admin-password`), 'utf8').trim();
  18  |   protectTestSecrets(password);
  19  |   const errors: string[] = [];
  20  |   page.on('pageerror', e => errors.push(e.message));
  21  |   const writes: unknown[] = [], states: unknown[] = [], scrolls: unknown[] = [];
  22  |   const route = prototype ? '/api/factories' : '/api/v1/sn/factories';
  23  |   const org = (id: string, code: string, name: string, status = 'A') => ({ FOrgID: id, FNumber: code, FName: name, FForbidStatus: status });
  24  |   const menu = async (name: string) => {
  25  |     await page.getByRole('menuitem', { name, exact: true }).click();
> 26  |     await expect(page.getByRole('heading', { name, exact: true })).toBeVisible();
      |                                                                    ^ Error: expect(locator).toBeVisible() failed
  27  |     await page.waitForLoadState('networkidle');
  28  |     await expect.poll(() => page.locator(prototype ? 'main > .page' : 'main > .sn-page').evaluate(el => getComputedStyle(el).opacity)).toBe('1');
  29  |   };
  30  |   const capture = async (label: string) => {
  31  |     await expect(page.locator('.ant-spin-spinning:visible,.el-loading-mask:visible')).toHaveCount(0);
  32  |     await page.mouse.move(0,0);
  33  |     states.push({ label, data: JSON.parse(fixture('snapshot')), text: await page.locator('main').innerText() });
  34  |     await page.screenshot({ path: info.outputPath(label+'.png'), animations: 'disabled' });
  35  |   };
  36  |   const sync = async (orgs: unknown[], inserted: number, updated: number, conflicts = 0) => {
  37  |     writeFileSync(resolve(run, 'k3-control.json'), JSON.stringify({ organizations: orgs }));
  38  |     const pending = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === route+'/sync');
  39  |     await page.getByRole('button', { name: zh.factories.sync, exact: true }).click();
  40  |     const response = await pending;
  41  |     expect(response.status()).toBe(200);
  42  |     const body = await response.json();
  43  |     writes.push(body);
  44  |     const result = body.data ?? body;
  45  |     expect(Number(result.inserted)).toBe(inserted);
  46  |     expect(Number(result.updated)).toBe(updated);
  47  |     expect(result.conflicts).toHaveLength(conflicts);
  48  |     await page.waitForLoadState('networkidle');
  49  |   };
  50  |   const create = async (code: string, name: string) => {
  51  |     await page.getByRole('button', { name: zh.factories.create, exact: true }).click();
  52  |     const dialog = page.getByRole('dialog');
  53  |     await dialog.locator('input').nth(0).fill(code);
  54  |     await dialog.locator('input').nth(1).fill(name);
  55  |     const pending = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === route);
  56  |     await dialog.getByRole('button', { name: zh.common.save, exact: true }).click();
  57  |     expect((await pending).status()).toBe(200);
  58  |     await expect(dialog).toBeHidden();
  59  |     await page.waitForLoadState('networkidle');
  60  |   };
  61  |   const order = async (code: string | null, label: string) => {
  62  |     await menu(zh.nav.orders);
  63  |     const row = page.locator(prototype ? '.el-row .el-table__row' : '.sn-order-table .ant-table-row').filter({ hasText: 'CMP-F46-MO' });
  64  |     await expect(row).toHaveCount(1);
  65  |     await row.click();
  66  |     await expect(page.getByRole('cell', { name: `比较46旧名（${code ?? '—'}）`, exact: true })).toBeVisible();
  67  |     await expect(row.getByText(zh.orders.noFactory, { exact: true })).toHaveCount(code ? 0 : 1);
  68  |     const observed = JSON.parse(fixture('snapshot'));
  69  |     expect(observed.orders).toHaveLength(1);
  70  |     expect(observed.orders[0].factory_code).toBe(code);
  71  |     await capture(label);
  72  |     await menu(zh.nav.factories);
  73  |   };
  74  |   const scroll = async (label: string) => {
  75  |     scrolls.push({ label, ...(await page.locator('main').evaluate(el => ({top: el.scrollTop, height:el.scrollHeight, client:el.clientHeight}))) });
  76  |   };
  77  |   fixture('prepare');
  78  |   try {
  79  |     await page.goto('/login');
  80  |     await paceComparisonLogin(page);
  81  |     await page.locator('input[autocomplete="username"]').fill('admin');
  82  |     await page.locator('input[autocomplete="current-password"]').fill(password);
  83  |     await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
  84  |     await menu(zh.nav.factories);
  85  |     await create('CMP_F46_OLD', '比较46旧名');
  86  |     await menu(zh.nav.orders);
  87  |     await page.getByPlaceholder(zh.orders.billToSync, { exact: true }).fill('CMP-F46-MO');
  88  |     await page.getByRole('button', { name: zh.orders.syncOne, exact: true }).click();
  89  |     await expect(page.getByText('比较46旧名', { exact: true }).first()).toBeVisible();
  90  |     await page.waitForLoadState('networkidle');
  91  |     await order('CMP_F46_OLD', '01-manual-name-matches-different-erp-code');
  92  |     await sync([org('946101','CMP_F46_MANUAL','比较46旧名')],0,0,1);
  93  |     await order('CMP_F46_OLD','02-name-conflict-still-resolves-order');
  94  |     await sync([org('946101','CMP_F46_OLD','比较46旧名')],0,0);
  95  |     await sync([org('946102','CMP_F46_OLD','比较46旧名')],0,0);
  96  |     await order('CMP_F46_OLD','03-replaced-id-preserves-factory');
  97  |     await sync([org('946102','CMP_F46_NEW','比较46新编码')],1,0);
  98  |     await order('CMP_F46_OLD','04-new-code-leaves-old-snapshot');
  99  |     await sync([org('946103','CMP_F46_OLD','比较46改名')],0,1);
  100 |     await order(null,'05-rename-leaves-snapshot-unmapped');
  101 |     await create('CMP_F46_MANUAL','比较46旧名');
  102 |     await order('CMP_F46_MANUAL','06-create-matching-name-reprojects');
  103 |     await sync([
  104 |       org('946201','CMP_F46_DUP','比较46去重','a'),
  105 |       org('946202','CMP_F46_DISABLED','比较46去重'),
  106 |       org('946203','CMP_F46_DISABLED','比较46禁用','B'),
  107 |       org('bad','CMP_F46_NOID','比较46无内码'),
  108 |       org('946204','','比较46空编码'),
  109 |     ],2,0);
  110 |     await capture('07-deduplicated-active-directory');
  111 |     // 记录真实导航滚动，供双端数值比较；不注入布局或业务响应。
  112 |     await page.locator('main').evaluate(el => { el.scrollTop=151; });
  113 |     await scroll('factories-before');
  114 |     await menu(zh.nav.audit);
  115 |     await scroll('audit-after-long');
  116 |     await menu(zh.nav.factories);
  117 |     await scroll('factories-return');
  118 |     await menu(zh.nav.orders);
  119 |     await scroll('orders-after-long');
  120 |     await menu(zh.nav.factories);
  121 |     await scroll('factories-after-short');
  122 |     expect(errors).toEqual([]);
  123 |   } finally {
  124 |     writeFileSync(info.outputPath('states.json'), JSON.stringify(states,null,2));
  125 |     writeFileSync(info.outputPath('writes.json'), JSON.stringify(writes,null,2));
  126 |     writeFileSync(info.outputPath('scrolls.json'), JSON.stringify(scrolls,null,2));
```