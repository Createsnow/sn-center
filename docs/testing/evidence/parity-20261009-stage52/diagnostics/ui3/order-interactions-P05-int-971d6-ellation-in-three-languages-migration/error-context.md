# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: order-interactions.spec.ts >> P05 interactions: overflow, line details, schedule limits and cancellation in three languages
- Location: e2e\parity\order-interactions.spec.ts:12:1

# Error details

```
Error: Command failed: D:\AdtekProjects\mf-sn-uniq-guard\sn-center-python\.venv\Scripts\python.exe -X utf8 D:\AdtekProjects\mf-sn-uniq-guard\adtek-platform-sn\tools\testing\parity-order-audit-fixture.py java prepare 20261009520003
Traceback (most recent call last):
  File "D:\AdtekProjects\mf-sn-uniq-guard\adtek-platform-sn\tools\testing\parity-order-audit-fixture.py", line 30, in <module>
    assert c.fetchone()["n"] == 0, "Use existing prepared fixture; never overwrite an account"
           ^^^^^^^^^^^^^^^^^^^^^^
AssertionError: Use existing prepared fixture; never overwrite an account

```

# Test source

```ts
  1   | import { test, expect, type Locator } from '@playwright/test';
  2   | import { execFileSync } from 'node:child_process';
  3   | import { readFileSync, writeFileSync, renameSync } from 'node:fs';
  4   | import { resolve } from 'node:path';
  5   | import { protectTestSecrets } from '../privacy';
  6   | import { settleOrderPage } from './settleOrderPage';
  7   | import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
  8   | import en from '../../src/i18n/prototype/en.json' with { type: 'json' };
  9   | import vi from '../../src/i18n/prototype/vi.json' with { type: 'json' };
  10  | 
  11  | // 只操作专用比较服务；长字段经真实合成K3同步，禁止伪造浏览器业务响应。
  12  | test('P05 interactions: overflow, line details, schedule limits and cancellation in three languages', async ({
  13  |   page,
  14  | }, info) => {
  15  |   test.setTimeout(360_000);
  16  |   const prototype = info.project.name === 'prototype';
  17  |   const kind = prototype ? 'python' : 'java';
  18  |   const run = process.env.ADTEK_ORDER_INTERACTIONS_RUN!;
  19  |   expect(run).toMatch(/^[0-9]{8,14}$/);
  20  |   const username = 'audit' + run;
  21  |   const helper = (action: string) =>
  22  |     JSON.parse(
> 23  |       execFileSync(
      |       ^ Error: Command failed: D:\AdtekProjects\mf-sn-uniq-guard\sn-center-python\.venv\Scripts\python.exe -X utf8 D:\AdtekProjects\mf-sn-uniq-guard\adtek-platform-sn\tools\testing\parity-order-audit-fixture.py java prepare 20261009520003
  24  |         resolve('../../sn-center-python/.venv/Scripts/python.exe'),
  25  |         [
  26  |           '-X',
  27  |           'utf8',
  28  |           resolve('../tools/testing/parity-order-audit-fixture.py'),
  29  |           kind,
  30  |           action,
  31  |           run,
  32  |         ],
  33  |         { encoding: 'utf8', windowsHide: true },
  34  |       ),
  35  |     );
  36  |   helper('prepare');
  37  |   const password = readFileSync(
  38  |     resolve('../deploy/.secrets/comparison-20261008/' + kind + '-admin-password'),
  39  |     'utf8',
  40  |   ).trim();
  41  |   protectTestSecrets(password);
  42  |   const extraFile = resolve('../tools/.runtime/comparison-20261008/order-audits/extra-orders.json');
  43  |   const original = readFileSync(extraFile, 'utf8');
  44  |   const writeExtras = (data: string) => {
  45  |     writeFileSync(extraFile + '.next', data);
  46  |     renameSync(extraFile + '.next', extraFile);
  47  |   };
  48  |   const billNo = 'CMP-TOOLTIP-LONG-ORDER-00000051';
  49  |   const extras = [1, 2].map((status) => ({
  50  |     'F_ora_kh.FNumber': 'CMP-CUSTOMER-WITH-LONG-CODE-000051',
  51  |     FBillNo: billNo,
  52  |     F_HX_PO: 'PO-TOOLTIP-51',
  53  |     F_HX_PI: 'PI-TOOLTIP-00000000000000000051',
  54  |     'FMaterialId.FNumber': 'MAT-LONG-51-' + status,
  55  |     'FMaterialId.FName': '用于核对完整悬浮提示的生产物料名称 第五十一阶段',
  56  |     FQty: '1',
  57  |     FStatus: String(status),
  58  |     FSaleOrderNo: 'SD-TOOLTIP-00000000000000000051',
  59  |     'FPrdOrgId.FName': '比较测试未建档长名称工厂 第五十一阶段',
  60  |     FID: '9007199254741051',
  61  |     FTreeEntity_FEntryId: String(510 + status),
  62  |     FPrdOrgId: '151',
  63  |     'FPrdOrgId.FNumber': 'CMP-UNMAPPED-51',
  64  |   }));
  65  |   const states: unknown[] = [],
  66  |     writes: unknown[] = [],
  67  |     errors: string[] = [];
  68  |   page.on('pageerror', (error) => errors.push(error.message));
  69  |   page.on('response', async (response) => {
  70  |     if (
  71  |       response.request().method() === 'PUT' &&
  72  |       new URL(response.url()).pathname.endsWith('/orders/sync-schedule')
  73  |     ) {
  74  |       writes.push({
  75  |         status: response.status(),
  76  |         request: response.request().postDataJSON(),
  77  |         response: await response.json(),
  78  |       });
  79  |     }
  80  |   });
  81  |   const messages = page.locator(prototype ? '.el-message' : '.ant-message-notice');
  82  |   const tooltip = page.locator('[role="tooltip"]').filter({ visible: true });
  83  |   const capture = async (label: string) => {
  84  |     // 等待实际进入/离开动画结束，再读取几何；截图的动画快进不能代替测量稳定状态。
  85  |     await settleOrderPage(page);
  86  |     const amount = page.getByRole('dialog').getByRole('spinbutton');
  87  |     const amountValue = (await amount.count()) ? await amount.inputValue() : null;
  88  |     const geometry = await page.evaluate((source) => {
  89  |       const selectors = source
  90  |         ? {
  91  |             dialog: '.el-dialog',
  92  |             title: '.el-dialog__title',
  93  |             labels: '.el-dialog .el-form-item__label',
  94  |             number: '.el-dialog .el-input-number',
  95  |             unit: '.el-dialog .interval .el-select',
  96  |             hint: '.el-dialog .hint',
  97  |             alert: '.el-dialog .el-alert',
  98  |             buttons: '.el-dialog__footer .el-button',
  99  |             switch: '.el-dialog .el-switch__core',
  100 |             message: '.el-message',
  101 |             tooltip: '.el-popper[role=tooltip]',
  102 |             numberInput: '.el-dialog .el-input__inner',
  103 |             unitText: '.el-dialog .el-select__selected-item:not(.el-select__input-wrapper)',
  104 |             alertText: '.el-dialog .el-alert__title',
  105 |             messageText: '.el-message__content',
  106 |             detailTitle: '.el-col:last-child .surface__title',
  107 |             detailButton: '.el-col:last-child .surface__title .el-button',
  108 |             details: '.el-descriptions__cell',
  109 |             lineCells: '.el-col:last-child .el-table__row td',
  110 |           }
  111 |         : {
  112 |             dialog: '.sn-schedule-modal .ant-modal-container',
  113 |             title: '.sn-schedule-modal .ant-modal-title',
  114 |             labels: '.sn-schedule-modal .ant-form-item-label',
  115 |             number: '.sn-schedule-modal .ant-input-number',
  116 |             unit: '.sn-schedule-modal .sn-schedule-interval .ant-select',
  117 |             hint: '.sn-schedule-modal .sn-schedule-interval + .sn-muted',
  118 |             alert: '.sn-schedule-modal .ant-alert',
  119 |             buttons: '.sn-schedule-modal .ant-modal-footer .ant-btn',
  120 |             switch: '.sn-schedule-modal .ant-switch',
  121 |             message: '.ant-message-notice',
  122 |             tooltip: '.ant-tooltip[role=tooltip],.ant-tooltip-container[role=tooltip]',
  123 |             numberInput: '.sn-schedule-modal .ant-input-number-input',
```