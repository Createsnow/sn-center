# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: business-loop.spec.ts >> P15 complete UI business loop from order sync and rule creation to transferred reprint and audit
- Location: e2e\parity\business-loop.spec.ts:10:1

# Error details

```
Error: expect(received).toEqual(expected) // deep equality

- Expected  - 0
+ Received  + 3

  Array [
+   "L2026100953000900001",
+   "L2026100953000900002",
+   "L2026100953000900003",
    "L2026100953001100001",
    "L2026100953001100002",
    "L2026100953001100003",
  ]
```

# Test source

```ts
  1   | import { test, expect, type Locator } from '@playwright/test';
  2   | import { execFileSync } from 'node:child_process';
  3   | import { readFileSync, writeFileSync, renameSync } from 'node:fs';
  4   | import { resolve } from 'node:path';
  5   | import { protectTestSecrets } from '../privacy';
  6   | import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
  7   | import ui from '../../src/i18n/locales/zh-CN.json' with { type: 'json' };
  8   | 
  9   | // 仅准备隔离账号和合成ERP输入；规则、SN、分配、打印与转厂均由真实页面创建。
  10  | test('P15 complete UI business loop from order sync and rule creation to transferred reprint and audit', async ({
  11  |   page,
  12  | }, info) => {
  13  |   test.setTimeout(240_000);
  14  |   page.setDefaultTimeout(20_000);
  15  |   const source = info.project.name === 'prototype';
  16  |   const kind = source ? 'python' : 'java';
  17  |   const run = process.env.ADTEK_BUSINESS_LOOP_RUN!;
  18  |   expect(run).toMatch(/^[0-9]{8,14}$/);
  19  |   const username = 'audit' + run;
  20  |   execFileSync(
  21  |     resolve('../../sn-center-python/.venv/Scripts/python.exe'),
  22  |     ['-X', 'utf8', resolve('../tools/testing/parity-order-audit-fixture.py'), kind, 'prepare', run],
  23  |     { windowsHide: true },
  24  |   );
  25  |   const password = readFileSync(
  26  |     resolve('../deploy/.secrets/comparison-20261008/' + kind + '-admin-password'),
  27  |     'utf8',
  28  |   ).trim();
  29  |   protectTestSecrets(password);
  30  |   const origin = source ? 'http://127.0.0.1:8001' : 'http://127.0.0.1:15174';
  31  |   const pi = 'CMP-LOOP-' + run,
  32  |     bill = 'CMP-LOOP-MO-' + run,
  33  |     prefix = 'L' + run;
  34  |   const sns = [1, 2, 3].map((i) => prefix + String(i).padStart(5, '0'));
  35  |   const extraPath = resolve('../tools/.runtime/comparison-20261008/order-audits/extra-orders.json');
  36  |   const original = readFileSync(extraPath, 'utf8');
  37  |   expect(JSON.parse(original)).toEqual([]);
  38  |   const writeExtra = (value: string) => {
  39  |     writeFileSync(extraPath + '.next', value);
  40  |     renameSync(extraPath + '.next', extraPath);
  41  |   };
  42  |   const errors: string[] = [],
  43  |     facts: unknown[] = [],
  44  |     files: { name: string; sns: string[] }[] = [];
  45  |   page.on('pageerror', (e) => errors.push(e.message));
  46  |   const rows = () => page.locator(source ? '.el-table__row' : '.ant-table-row');
  47  |   const panel = () => page.getByRole('tabpanel').filter({ visible: true });
  48  |   const item = (root: Locator, label: string) =>
  49  |     root
  50  |       .locator(source ? '.el-form-item' : '.ant-form-item')
  51  |       .filter({ has: page.locator('label', { hasText: new RegExp('^\\s*' + label + '\\s*$') }) });
  52  |   const field = (root: Locator, label: string) =>
  53  |     item(root, label).locator('input,textarea').first();
  54  |   const go = (name: string) => page.getByRole('menuitem', { name, exact: true }).click();
  55  |   const capture = async (name: string) => {
  56  |     await page.screenshot({ path: info.outputPath(name + '.png'), animations: 'disabled' });
  57  |   };
  58  |   const confirm = async () => {
  59  |     const modal = page.locator(source ? '.el-message-box:visible' : '.ant-modal-confirm:visible');
  60  |     await expect(modal).toBeVisible();
  61  |     await modal.getByRole('button', { name: /^(确定|确认)$/ }).click();
  62  |     await expect(modal).toBeHidden();
  63  |   };
  64  |   const sync = async () => {
  65  |     await go(zh.nav.orders);
  66  |     const response = page.waitForResponse(
  67  |       (r) => r.request().method() === 'POST' && new URL(r.url()).pathname.endsWith('/orders/sync'),
  68  |     );
  69  |     await page.getByRole('button', { name: zh.orders.syncAll, exact: true }).click();
  70  |     expect((await response).status()).toBe(200);
  71  |     await expect(page.getByRole('button', { name: zh.orders.syncAll, exact: true })).toBeEnabled();
  72  |   };
  73  |   const stat = async (label: string, expected: number) => {
  74  |     await expect(
  75  |       page
  76  |         .locator(source ? '.stats > div' : '.sn-work-stats > div')
  77  |         .filter({ has: page.getByText(label, { exact: true }) })
  78  |         .locator('b'),
  79  |     ).toHaveText(String(expected));
  80  |   };
  81  |   const query = async (stage: string, status: string, factory: string | null) => {
  82  |     await go(zh.nav.query);
  83  |     await page.getByPlaceholder('PI', { exact: true }).fill(pi);
  84  |     const response = page.waitForResponse(
  85  |       (r) =>
  86  |         r.request().method() === 'GET' &&
  87  |         new URL(r.url()).pathname === (source ? '/api/sn' : '/api/v1/sn/items/page') &&
  88  |         new URL(r.url()).searchParams.get('pi') === pi,
  89  |     );
  90  |     await page.getByRole('button', { name: zh.common.search, exact: true }).click();
  91  |     const raw = await response;
  92  |     expect(raw.status()).toBe(200);
  93  |     const body = await raw.json();
  94  |     const values = (source ? body.items : body.data.items) as Record<string, unknown>[];
  95  |     const observed = values.map((v) => ({
  96  |       sn: v.sn,
  97  |       status: v.status,
  98  |       pi: v.pi_no ?? v.pi,
  99  |       factory: v.factory_code ?? v.factoryCode ?? null,
> 100 |     }));
      |                                              ^ Error: expect(received).toEqual(expected) // deep equality
  101 |     expect(observed.map((v) => v.sn).sort()).toEqual(sns);
  102 |     expect(observed.map((v) => v.status)).toEqual([status, status, status]);
  103 |     if (factory !== null)
  104 |       expect(observed.map((v) => v.factory)).toEqual([factory, factory, factory]);
  105 |     await expect(rows()).toHaveCount(3);
  106 |     facts.push({ stage, observed });
  107 |     await capture(stage);
  108 |   };
  109 |   const takePrint = async (factory: string, name: string) => {
  110 |     await go(zh.nav.acquire);
  111 |     await page.getByPlaceholder('PI', { exact: true }).fill(pi);
  112 |     await page.getByPlaceholder('PI', { exact: true }).press('Enter');
  113 |     const summary = () =>
  114 |       panel()
  115 |         .locator(source ? '.el-table__row' : '.ant-table-row')
  116 |         .filter({ hasText: pi })
  117 |         .filter({ hasText: factory });
  118 |     await page.getByRole('tab', { name: /^待领取/ }).click();
  119 |     await summary().getByRole('button', { name: zh.acquire.take, exact: true }).click();
  120 |     await confirm();
  121 |     await expect(summary()).toHaveCount(0);
  122 |     await page.getByRole('tab', { name: /^待打印/ }).click();
  123 |     await summary().getByRole('button', { name: zh.acquire.print, exact: true }).click();
  124 |     const download = page.waitForEvent('download');
  125 |     await confirm();
  126 |     const file = await download;
  127 |     expect(file.suggestedFilename()).toMatch(/^print_P[0-9]{18}\.xlsx$/);
  128 |     await file.saveAs(info.outputPath(name + '.xlsx'));
  129 |     files.push({ name: name + '.xlsx', sns });
  130 |     await expect(summary()).toHaveCount(0);
  131 |     await page.getByRole('tab', { name: zh.acquire.prints, exact: true }).click();
  132 |     await capture(name);
  133 |   };
  134 |   let loggedIn = false;
  135 |   try {
  136 |     await page.goto(origin + '/login');
  137 |     await page.locator('input[autocomplete=username]').fill(username);
  138 |     await page.locator('input[autocomplete=current-password]').fill(password);
  139 |     await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
  140 |     await expect(page.getByRole('menuitem', { name: zh.nav.orders, exact: true })).toBeVisible();
  141 |     loggedIn = true;
  142 |     writeExtra(
  143 |       JSON.stringify([
  144 |         {
  145 |           'F_ora_kh.FNumber': 'CMP-LOOP-CUSTOMER',
  146 |           FBillNo: bill,
  147 |           F_HX_PO: 'CMP-LOOP-PO',
  148 |           F_HX_PI: pi,
  149 |           'FMaterialId.FNumber': 'CMP-LOOP-MAT',
  150 |           'FMaterialId.FName': '全流程验收物料',
  151 |           FQty: '3',
  152 |           FStatus: '1',
  153 |           FSaleOrderNo: 'CMP-LOOP-SO',
  154 |           'FPrdOrgId.FName': '比较测试甲厂',
  155 |           FID: String(9007199254900000n + BigInt(run.slice(-6))),
  156 |           FTreeEntity_FEntryId: '5401',
  157 |           FPrdOrgId: '101',
  158 |           'FPrdOrgId.FNumber': 'CMP-A',
  159 |         },
  160 |       ]),
  161 |     );
  162 |     await sync();
  163 |     await expect(page.getByText(bill, { exact: true })).toBeVisible();
  164 |     await capture('01-order-synced');
  165 |     await go(zh.nav.rules);
  166 |     await page.getByRole('button', { name: zh.rules.create, exact: true }).click();
  167 |     const editor = page.getByRole('dialog');
  168 |     await field(editor, zh.rules.code).fill('LOOP_' + run);
  169 |     await field(editor, zh.rules.name).fill('完整界面闭环 ' + run);
  170 |     await editor
  171 |       .locator('label')
  172 |       .filter({ has: page.getByRole('radio', { name: zh.rules.scopePI, exact: true }) })
  173 |       .click();
  174 |     await field(editor, 'PI').fill(pi);
  175 |     await field(editor, zh.rules.prefix).fill(prefix);
  176 |     await editor.getByRole('button', { name: zh.common.save, exact: true }).click();
  177 |     await expect(editor).toBeHidden();
  178 |     await expect(rows().filter({ hasText: 'LOOP_' + run })).toHaveCount(1);
  179 |     await capture('02-rule-created');
  180 |     await go(zh.nav.orders);
  181 |     await rows().filter({ hasText: bill }).click();
  182 |     await page.getByRole('button', { name: zh.orders.toGenerate, exact: true }).click();
  183 |     await stat(zh.orders.quota, 3);
  184 |     await field(page.locator('main'), zh.generate.qty).fill('3');
  185 |     await field(page.locator('main'), zh.generate.qty).blur();
  186 |     await page.getByRole('button', { name: zh.generate.preview, exact: true }).click();
  187 |     await expect(
  188 |       page.getByRole('button', { name: zh.generate.generate, exact: true }),
  189 |     ).toBeEnabled();
  190 |     await expect(page.getByText(new RegExp(sns[0] + '\\s*~\\s*' + sns[2])).first()).toBeVisible();
  191 |     await capture('03-preview');
  192 |     await page.getByRole('button', { name: zh.generate.generate, exact: true }).click();
  193 |     await confirm();
  194 |     await stat(zh.orders.generated, 3);
  195 |     await stat(zh.orders.quota, 0);
  196 |     await stat(zh.generate.pendingAlloc, 3);
  197 |     await capture('04-generated');
  198 |     await field(page.locator('main'), zh.generate.allocQty).fill('3');
  199 |     await field(page.locator('main'), zh.generate.allocQty).blur();
  200 |     await page.getByRole('button', { name: zh.generate.allocate, exact: true }).click();
```