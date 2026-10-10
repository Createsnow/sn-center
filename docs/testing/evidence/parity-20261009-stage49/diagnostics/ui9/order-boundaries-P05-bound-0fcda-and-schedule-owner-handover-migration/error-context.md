# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: order-boundaries.spec.ts >> P05 boundaries: Unicode blank bill, three-language validation and schedule owner handover
- Location: e2e\parity\order-boundaries.spec.ts:14:1

# Error details

```
Error: expect(locator).toHaveCount(expected) failed

Locator:  locator('.sn-page-actions .ant-btn-loading-icon')
Expected: 0
Received: 1
Timeout:  15000ms

Call log:
  - Expect "toHaveCount" locator('.sn-page-actions .ant-btn-loading-icon') with timeout 15000ms
  - waiting for locator('.sn-page-actions .ant-btn-loading-icon')
    33 × locator resolved to 1 element
       - unexpected value "1"

```

# Test source

```ts
  1   | import { test, expect, type Page } from '@playwright/test';
  2   | import { execFileSync } from 'node:child_process';
  3   | import { readFileSync, writeFileSync } from 'node:fs';
  4   | import { resolve } from 'node:path';
  5   | import { protectTestSecrets } from '../privacy';
  6   | import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
  7   | import en from '../../src/i18n/prototype/en.json' with { type: 'json' };
  8   | import vi from '../../src/i18n/prototype/vi.json' with { type: 'json' };
  9   | import errZh from '../../src/i18n/prototype/errors.zh-CN.json' with { type: 'json' };
  10  | import errEn from '../../src/i18n/prototype/errors.en.json' with { type: 'json' };
  11  | import errVi from '../../src/i18n/prototype/errors.vi.json' with { type: 'json' };
  12  | 
  13  | // 复用专用a48合成库，新增本轮身份；不清除既有痕迹或连接常驻比较库。
  14  | test('P05 boundaries: Unicode blank bill, three-language validation and schedule owner handover', async ({
  15  |   page,
  16  |   browser,
  17  |   request,
  18  | }, info) => {
  19  |   test.setTimeout(180_000);
  20  |   page.setDefaultTimeout(15_000);
  21  |   const prototype = info.project.name === 'prototype';
  22  |   const kind = prototype ? 'python' : 'java';
  23  |   const base = process.env.ADTEK_BOUNDARY_RUN!;
  24  |   expect(base).toMatch(/^[0-9]{8,12}$/);
  25  |   const tag = base + '01',
  26  |     otherTag = base + '02',
  27  |     username = 'audit' + tag,
  28  |     otherName = 'audit' + otherTag;
  29  |   const fixture = (action: string, run = tag) =>
  30  |     JSON.parse(
  31  |       execFileSync(
  32  |         resolve('../../sn-center-python/.venv/Scripts/python.exe'),
  33  |         [
  34  |           '-X',
  35  |           'utf8',
  36  |           resolve('../tools/testing/parity-order-audit-fixture.py'),
  37  |           kind,
  38  |           action,
  39  |           run,
  40  |         ],
  41  |         { windowsHide: true, encoding: 'utf8' },
  42  |       ),
  43  |     );
  44  |   fixture('prepare');
  45  |   fixture('prepare', otherTag);
  46  |   const password = readFileSync(
  47  |     resolve(`../deploy/.secrets/comparison-20261008/${kind}-admin-password`),
  48  |     'utf8',
  49  |   ).trim();
  50  |   protectTestSecrets(password);
  51  |   const url = prototype ? 'http://127.0.0.1:8001' : 'http://127.0.0.1:15174';
  52  |   const otherContext = await browser.newContext({ viewport: { width: 1440, height: 960 } });
  53  |   const other = await otherContext.newPage();
  54  |   const pageErrors: string[] = [];
  55  |   for (const tab of [page, other]) tab.on('pageerror', (e) => pageErrors.push(e.message));
  56  |   const states: unknown[] = [];
  57  |   const writes: unknown[] = [];
  58  |   const postPaths: string[] = [];
  59  |   page.on('request', (r) => {
  60  |     if (r.method() === 'POST' && new URL(r.url()).pathname.endsWith('/orders/sync'))
  61  |       postPaths.push(r.url());
  62  |   });
  63  |   const login = async (tab: Page, name: string) => {
  64  |     await tab.goto(url + '/login');
  65  |     await tab.locator('input[autocomplete="username"]').fill(name);
  66  |     await tab.locator('input[autocomplete="current-password"]').fill(password);
  67  |     await tab.getByRole('button', { name: zh.login.submit, exact: true }).click();
  68  |     await tab.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
  69  |     await tab.waitForLoadState('networkidle');
  70  |   };
  71  |   const language = async (label: string) => {
  72  |     const control = page.locator(prototype ? '.header .lang' : '.prototype-language .ant-select');
  73  |     if ((await control.innerText()).includes(label)) return;
  74  |     const saved = page.waitForResponse(
  75  |       (r) =>
  76  |         r.request().method() === (prototype ? 'PUT' : 'POST') &&
  77  |         new URL(r.url()).pathname.endsWith(prototype ? '/auth/lang' : '/system/auth/language'),
  78  |     );
  79  |     await control.click();
  80  |     await page.getByRole('option', { name: label, exact: true }).click();
  81  |     expect((await saved).ok()).toBe(true);
  82  |   };
  83  |   const capture = async (label: string, tab = page) => {
  84  |     await tab.mouse.move(0, 0);
  85  |     await expect(tab.locator('.ant-spin-spinning:visible,.el-loading-mask:visible')).toHaveCount(0);
> 86  |     await expect(tab.locator('.sn-page-actions .ant-btn-loading-icon')).toHaveCount(0);
      |                                                                         ^ Error: expect(locator).toHaveCount(expected) failed
  87  |     states.push({
  88  |       label,
  89  |       body: await tab.locator('main').innerText(),
  90  |       messages: await tab
  91  |         .locator(prototype ? '.el-message' : '.ant-message-notice')
  92  |         .allTextContents(),
  93  |       database: fixture('snapshot'),
  94  |       geometry: await tab.evaluate((source) => {
  95  |         const groups = source
  96  |           ? {
  97  |               buttons: '.page__actions .el-button',
  98  |               headers: '.el-table__header th',
  99  |               meta: '.surface.meta > span',
  100 |               input: '.page__actions .el-input',
  101 |               summary: '.sched > *',
  102 |               inputText: '.page__actions input',
  103 |               toolbarButtons: '.toolbar .el-button',
  104 |               table: '.el-table',
  105 |               pager: '.el-pagination',
  106 |               pagerTotal: '.el-pagination__total',
  107 |               pagerSize: '.el-pagination__sizes',
  108 |               pagerPrev: '.el-pagination .btn-prev',
  109 |               pagerNext: '.el-pagination .btn-next',
  110 |               pagerNumber: '.el-pagination .el-pager .number',
  111 |             }
  112 |           : {
  113 |               buttons: '.sn-page-actions .ant-btn',
  114 |               headers: '.ant-table-thead th',
  115 |               meta: '.sn-order-meta > span',
  116 |               input: '.sn-page-actions .sn-sync-bill',
  117 |               summary: '.sn-schedule-summary > *',
  118 |               inputText: '.sn-page-actions input',
  119 |               toolbarButtons: '.sn-toolbar > .ant-btn',
  120 |               table: '.sn-order-table .ant-table-container',
  121 |               pager: '.sn-order-table .ant-pagination',
  122 |               pagerTotal: '.sn-order-pager .ant-pagination-total-text',
  123 |               pagerSize: '.sn-order-pager .ant-pagination-options',
  124 |               pagerPrev: '.sn-order-pager .ant-pagination-prev',
  125 |               pagerNext: '.sn-order-pager .ant-pagination-next',
  126 |               pagerNumber: '.sn-order-pager .ant-pagination-item',
  127 |             };
  128 |         return Object.fromEntries(
  129 |           Object.entries(groups).map(([key, selector]) => [
  130 |             key,
  131 |             [...document.querySelectorAll(selector)].map((el) => {
  132 |               const r = el.getBoundingClientRect(),
  133 |                 s = getComputedStyle(el);
  134 |               return {
  135 |                 text: el.textContent,
  136 |                 rect: [r.x, r.y, r.width, r.height],
  137 |                 font: s.font,
  138 |                 padding: s.padding,
  139 |                 gap: s.gap,
  140 |                 color: s.color,
  141 |                 background: s.backgroundColor,
  142 |                 border: s.border,
  143 |                 whiteSpace: s.whiteSpace,
  144 |                 overflow: s.overflow,
  145 |                 fontFamily: s.fontFamily,
  146 |                 textBoxes:
  147 |                   key === 'headers'
  148 |                     ? (() => {
  149 |                         const boxes = [];
  150 |                         const nodes = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
  151 |                         while (nodes.nextNode()) {
  152 |                           if (!nodes.currentNode.textContent?.trim()) continue;
  153 |                           const range = document.createRange();
  154 |                           range.selectNodeContents(nodes.currentNode);
  155 |                           boxes.push(
  156 |                             ...[...range.getClientRects()].map((box) => [
  157 |                               box.x,
  158 |                               box.y,
  159 |                               box.width,
  160 |                               box.height,
  161 |                             ]),
  162 |                           );
  163 |                         }
  164 |                         return boxes;
  165 |                       })()
  166 |                     : undefined,
  167 |               };
  168 |             }),
  169 |           ]),
  170 |         );
  171 |       }, prototype),
  172 |     });
  173 |     await tab.screenshot({ path: info.outputPath(label + '.png'), animations: 'disabled' });
  174 |   };
  175 |   const saveSchedule = async (tab: Page, enabled: boolean, minutes: number) => {
  176 |     await tab.getByRole('button', { name: zh.orders.sched.settings, exact: true }).click();
  177 |     const dialog = tab.getByRole('dialog');
  178 |     await dialog.locator(prototype ? '.el-select' : '.ant-select').click();
  179 |     await tab.getByRole('option', { name: zh.orders.sched.unit.minute, exact: true }).click();
  180 |     await dialog.getByRole('spinbutton').fill(String(minutes));
  181 |     if ((await dialog.getByRole('switch').getAttribute('aria-checked')) !== String(enabled))
  182 |       await dialog.locator(prototype ? '.el-switch' : '.ant-switch').click();
  183 |     const response = tab.waitForResponse(
  184 |       (r) =>
  185 |         r.request().method() === 'PUT' &&
  186 |         new URL(r.url()).pathname.endsWith('/orders/sync-schedule'),
```