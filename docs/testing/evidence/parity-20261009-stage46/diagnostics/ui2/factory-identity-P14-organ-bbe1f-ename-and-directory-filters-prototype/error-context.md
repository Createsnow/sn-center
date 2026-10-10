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

Locator: getByText('比较46旧名（CMP_F46_OLD）', { exact: true })
Expected: visible
Error: strict mode violation: getByText('比较46旧名（CMP_F46_OLD）', { exact: true }) resolved to 2 elements:
    1) <td colspan="1" class="el-descriptions__cell el-descriptions__content is-bordered-content">比较46旧名（CMP_F46_OLD）</td> aka getByRole('cell', { name: '比较46旧名（CMP_F46_OLD）' })
    2) <span>比较46旧名（CMP_F46_OLD）</span> aka locator('#el-id-2749-86').getByText('比较46旧名（CMP_F46_OLD）')

Call log:
  - Expect "toBeVisible" getByText('比较46旧名（CMP_F46_OLD）', { exact: true }) with timeout 15000ms
  - waiting for getByText('比较46旧名（CMP_F46_OLD）', { exact: true })

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
  26  |     await page.waitForLoadState('networkidle');
  27  |   };
  28  |   const capture = async (label: string) => {
  29  |     await expect(page.locator('.ant-spin-spinning:visible,.el-loading-mask:visible')).toHaveCount(0);
  30  |     await page.mouse.move(0,0);
  31  |     states.push({ label, data: JSON.parse(fixture('snapshot')), text: await page.locator('main').innerText() });
  32  |     await page.screenshot({ path: info.outputPath(label+'.png'), animations: 'disabled' });
  33  |   };
  34  |   const sync = async (orgs: unknown[], inserted: number, updated: number, conflicts = 0) => {
  35  |     writeFileSync(resolve(run, 'k3-control.json'), JSON.stringify({ organizations: orgs }));
  36  |     const pending = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === route+'/sync');
  37  |     await page.getByRole('button', { name: zh.factories.sync, exact: true }).click();
  38  |     const response = await pending;
  39  |     expect(response.status()).toBe(200);
  40  |     const body = await response.json();
  41  |     writes.push(body);
  42  |     const result = body.data ?? body;
  43  |     expect(Number(result.inserted)).toBe(inserted);
  44  |     expect(Number(result.updated)).toBe(updated);
  45  |     expect(result.conflicts).toHaveLength(conflicts);
  46  |     await page.waitForLoadState('networkidle');
  47  |   };
  48  |   const create = async (code: string, name: string) => {
  49  |     await page.getByRole('button', { name: zh.factories.create, exact: true }).click();
  50  |     const dialog = page.getByRole('dialog');
  51  |     await dialog.locator('input').nth(0).fill(code);
  52  |     await dialog.locator('input').nth(1).fill(name);
  53  |     const pending = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === route);
  54  |     await dialog.getByRole('button', { name: zh.common.save, exact: true }).click();
  55  |     expect((await pending).status()).toBe(200);
  56  |     await expect(dialog).toBeHidden();
  57  |     await page.waitForLoadState('networkidle');
  58  |   };
  59  |   const order = async (code: string | null, label: string) => {
  60  |     await menu(zh.nav.orders);
  61  |     const row = page.locator(prototype ? '.el-row .el-table__row' : '.sn-order-table .ant-table-row').filter({ hasText: 'CMP-F46-MO' });
  62  |     await expect(row).toHaveCount(1);
  63  |     await row.click();
> 64  |     await expect(page.getByText(`比较46旧名（${code ?? '—'}）`, { exact: true })).toBeVisible();
      |                                                                             ^ Error: expect(locator).toBeVisible() failed
  65  |     await expect(row.getByText(zh.orders.noFactory, { exact: true })).toHaveCount(code ? 0 : 1);
  66  |     const observed = JSON.parse(fixture('snapshot'));
  67  |     expect(observed.orders).toHaveLength(1);
  68  |     expect(observed.orders[0].factory_code).toBe(code);
  69  |     await capture(label);
  70  |     await menu(zh.nav.factories);
  71  |   };
  72  |   const scroll = async (label: string) => {
  73  |     scrolls.push({ label, ...(await page.locator('main').evaluate(el => ({top: el.scrollTop, height:el.scrollHeight, client:el.clientHeight}))) });
  74  |   };
  75  |   fixture('prepare');
  76  |   try {
  77  |     await page.goto('/login');
  78  |     await paceComparisonLogin(page);
  79  |     await page.locator('input[autocomplete="username"]').fill('admin');
  80  |     await page.locator('input[autocomplete="current-password"]').fill(password);
  81  |     await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
  82  |     await menu(zh.nav.factories);
  83  |     await create('CMP_F46_OLD', '比较46旧名');
  84  |     await menu(zh.nav.orders);
  85  |     await page.getByPlaceholder(zh.orders.billToSync, { exact: true }).fill('CMP-F46-MO');
  86  |     await page.getByRole('button', { name: zh.orders.syncOne, exact: true }).click();
  87  |     await expect(page.getByText('比较46旧名', { exact: true }).first()).toBeVisible();
  88  |     await page.waitForLoadState('networkidle');
  89  |     await order('CMP_F46_OLD', '01-manual-name-matches-different-erp-code');
  90  |     await sync([org('946101','CMP_F46_MANUAL','比较46旧名')],0,0,1);
  91  |     await order('CMP_F46_OLD','02-name-conflict-still-resolves-order');
  92  |     await sync([org('946101','CMP_F46_OLD','比较46旧名')],0,0);
  93  |     await sync([org('946102','CMP_F46_OLD','比较46旧名')],0,0);
  94  |     await order('CMP_F46_OLD','03-replaced-id-preserves-factory');
  95  |     await sync([org('946102','CMP_F46_NEW','比较46新编码')],1,0);
  96  |     await order('CMP_F46_OLD','04-new-code-leaves-old-snapshot');
  97  |     await sync([org('946103','CMP_F46_OLD','比较46改名')],0,1);
  98  |     await order(null,'05-rename-leaves-snapshot-unmapped');
  99  |     await create('CMP_F46_MANUAL','比较46旧名');
  100 |     await order('CMP_F46_MANUAL','06-create-matching-name-reprojects');
  101 |     await sync([
  102 |       org('946201','CMP_F46_DUP','比较46去重','a'),
  103 |       org('946202','CMP_F46_DISABLED','比较46去重'),
  104 |       org('946203','CMP_F46_DISABLED','比较46禁用','B'),
  105 |       org('bad','CMP_F46_NOID','比较46无内码'),
  106 |       org('946204','','比较46空编码'),
  107 |     ],2,0);
  108 |     await capture('07-deduplicated-active-directory');
  109 |     // 记录真实导航滚动，供双端数值比较；不注入布局或业务响应。
  110 |     await page.locator('main').evaluate(el => { el.scrollTop=151; });
  111 |     await scroll('factories-before');
  112 |     await menu(zh.nav.audit);
  113 |     await scroll('audit-after-long');
  114 |     await menu(zh.nav.factories);
  115 |     await scroll('factories-return');
  116 |     await menu(zh.nav.orders);
  117 |     await scroll('orders-after-long');
  118 |     await menu(zh.nav.factories);
  119 |     await scroll('factories-after-short');
  120 |     expect(errors).toEqual([]);
  121 |   } finally {
  122 |     writeFileSync(info.outputPath('states.json'), JSON.stringify(states,null,2));
  123 |     writeFileSync(info.outputPath('writes.json'), JSON.stringify(writes,null,2));
  124 |     writeFileSync(info.outputPath('scrolls.json'), JSON.stringify(scrolls,null,2));
  125 |     fixture('clean');
  126 |   }
  127 | });
  128 | 
```