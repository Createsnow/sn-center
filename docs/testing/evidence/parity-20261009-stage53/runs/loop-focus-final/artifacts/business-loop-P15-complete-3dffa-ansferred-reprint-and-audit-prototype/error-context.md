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
  87  |         new URL(r.url()).pathname === (source ? '/api/sn' : '/api/v1/sn/items/page'),
  88  |     );
  89  |     await page.getByRole('button', { name: zh.common.search, exact: true }).click();
  90  |     const raw = await response;
  91  |     expect(raw.status()).toBe(200);
  92  |     const body = await raw.json();
  93  |     const values = (source ? body.items : body.data.items) as Record<string, unknown>[];
  94  |     const observed = values.map((v) => ({
  95  |       sn: v.sn,
  96  |       status: v.status,
  97  |       pi: v.pi_no ?? v.pi,
  98  |       factory: v.factory_code ?? v.factoryCode ?? null,
  99  |     }));
> 100 |     expect(observed.map((v) => v.sn).sort()).toEqual(sns);
      |                                              ^ Error: expect(received).toEqual(expected) // deep equality
  101 |     expect(observed.map((v) => v.status)).toEqual([status, status, status]);
  102 |     if (factory !== null)
  103 |       expect(observed.map((v) => v.factory)).toEqual([factory, factory, factory]);
  104 |     await expect(rows()).toHaveCount(3);
  105 |     facts.push({ stage, observed });
  106 |     await capture(stage);
  107 |   };
  108 |   const takePrint = async (factory: string, name: string) => {
  109 |     await go(zh.nav.acquire);
  110 |     await page.getByPlaceholder('PI', { exact: true }).fill(pi);
  111 |     await page.getByPlaceholder('PI', { exact: true }).press('Enter');
  112 |     const summary = () =>
  113 |       panel()
  114 |         .locator(source ? '.el-table__row' : '.ant-table-row')
  115 |         .filter({ hasText: pi })
  116 |         .filter({ hasText: factory });
  117 |     await page.getByRole('tab', { name: /^待领取/ }).click();
  118 |     await summary().getByRole('button', { name: zh.acquire.take, exact: true }).click();
  119 |     await confirm();
  120 |     await expect(summary()).toHaveCount(0);
  121 |     await page.getByRole('tab', { name: /^待打印/ }).click();
  122 |     await summary().getByRole('button', { name: zh.acquire.print, exact: true }).click();
  123 |     const download = page.waitForEvent('download');
  124 |     await confirm();
  125 |     const file = await download;
  126 |     expect(file.suggestedFilename()).toMatch(/^print_P[0-9]{18}\.xlsx$/);
  127 |     await file.saveAs(info.outputPath(name + '.xlsx'));
  128 |     files.push({ name: name + '.xlsx', sns });
  129 |     await expect(summary()).toHaveCount(0);
  130 |     await page.getByRole('tab', { name: zh.acquire.prints, exact: true }).click();
  131 |     await capture(name);
  132 |   };
  133 |   let loggedIn = false;
  134 |   try {
  135 |     await page.goto(origin + '/login');
  136 |     await page.locator('input[autocomplete=username]').fill(username);
  137 |     await page.locator('input[autocomplete=current-password]').fill(password);
  138 |     await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
  139 |     await expect(page.getByRole('menuitem', { name: zh.nav.orders, exact: true })).toBeVisible();
  140 |     loggedIn = true;
  141 |     writeExtra(
  142 |       JSON.stringify([
  143 |         {
  144 |           'F_ora_kh.FNumber': 'CMP-LOOP-CUSTOMER',
  145 |           FBillNo: bill,
  146 |           F_HX_PO: 'CMP-LOOP-PO',
  147 |           F_HX_PI: pi,
  148 |           'FMaterialId.FNumber': 'CMP-LOOP-MAT',
  149 |           'FMaterialId.FName': '全流程验收物料',
  150 |           FQty: '3',
  151 |           FStatus: '1',
  152 |           FSaleOrderNo: 'CMP-LOOP-SO',
  153 |           'FPrdOrgId.FName': '比较测试甲厂',
  154 |           FID: String(9007199254900000n + BigInt(run.slice(-6))),
  155 |           FTreeEntity_FEntryId: '5401',
  156 |           FPrdOrgId: '101',
  157 |           'FPrdOrgId.FNumber': 'CMP-A',
  158 |         },
  159 |       ]),
  160 |     );
  161 |     await sync();
  162 |     await expect(page.getByText(bill, { exact: true })).toBeVisible();
  163 |     await capture('01-order-synced');
  164 |     await go(zh.nav.rules);
  165 |     await page.getByRole('button', { name: zh.rules.create, exact: true }).click();
  166 |     const editor = page.getByRole('dialog');
  167 |     await field(editor, zh.rules.code).fill('LOOP_' + run);
  168 |     await field(editor, zh.rules.name).fill('完整界面闭环 ' + run);
  169 |     await editor
  170 |       .locator('label')
  171 |       .filter({ has: page.getByRole('radio', { name: zh.rules.scopePI, exact: true }) })
  172 |       .click();
  173 |     await field(editor, 'PI').fill(pi);
  174 |     await field(editor, zh.rules.prefix).fill(prefix);
  175 |     await editor.getByRole('button', { name: zh.common.save, exact: true }).click();
  176 |     await expect(editor).toBeHidden();
  177 |     await expect(rows().filter({ hasText: 'LOOP_' + run })).toHaveCount(1);
  178 |     await capture('02-rule-created');
  179 |     await go(zh.nav.orders);
  180 |     await rows().filter({ hasText: bill }).click();
  181 |     await page.getByRole('button', { name: zh.orders.toGenerate, exact: true }).click();
  182 |     await stat(zh.orders.quota, 3);
  183 |     await field(page.locator('main'), zh.generate.qty).fill('3');
  184 |     await field(page.locator('main'), zh.generate.qty).blur();
  185 |     await page.getByRole('button', { name: zh.generate.preview, exact: true }).click();
  186 |     await expect(
  187 |       page.getByRole('button', { name: zh.generate.generate, exact: true }),
  188 |     ).toBeEnabled();
  189 |     await expect(page.getByText(new RegExp(sns[0] + '\\s*~\\s*' + sns[2])).first()).toBeVisible();
  190 |     await capture('03-preview');
  191 |     await page.getByRole('button', { name: zh.generate.generate, exact: true }).click();
  192 |     await confirm();
  193 |     await stat(zh.orders.generated, 3);
  194 |     await stat(zh.orders.quota, 0);
  195 |     await stat(zh.generate.pendingAlloc, 3);
  196 |     await capture('04-generated');
  197 |     await field(page.locator('main'), zh.generate.allocQty).fill('3');
  198 |     await field(page.locator('main'), zh.generate.allocQty).blur();
  199 |     await page.getByRole('button', { name: zh.generate.allocate, exact: true }).click();
  200 |     await confirm();
```