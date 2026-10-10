# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: order-focus.spec.ts >> P05 focus: schedule closing, unit text, keyboard steps and scroll restoration
- Location: e2e\parity\order-focus.spec.ts:10:1

# Error details

```
Error: expect(locator).toBeFocused() failed

Locator:  getByRole('dialog')
Expected: focused
Received: inactive
Timeout:  15000ms

Call log:
  - Expect "toBeFocused" getByRole('dialog') with timeout 15000ms
  - waiting for getByRole('dialog')
    33 × locator resolved to <div role="dialog" aria-modal="true" aria-label="定时全量同步设置" class="el-overlay-dialog" aria-describedby="el-id-8900-22">…</div>
       - unexpected value "inactive"

```

# Test source

```ts
  13  |   test.setTimeout(150_000);
  14  |   const prototype = info.project.name === 'prototype';
  15  |   const kind = prototype ? 'python' : 'java';
  16  |   const run = process.env.ADTEK_ORDER_FOCUS_RUN!;
  17  |   expect(run).toMatch(/^[0-9]{8,14}$/);
  18  |   const fixture = (action: string) =>
  19  |     JSON.parse(
  20  |       execFileSync(
  21  |         resolve('../../sn-center-python/.venv/Scripts/python.exe'),
  22  |         [
  23  |           '-X',
  24  |           'utf8',
  25  |           resolve('../tools/testing/parity-order-audit-fixture.py'),
  26  |           kind,
  27  |           action,
  28  |           run,
  29  |         ],
  30  |         { encoding: 'utf8', windowsHide: true },
  31  |       ),
  32  |     );
  33  |   fixture('prepare');
  34  |   const password = readFileSync(
  35  |     resolve('../deploy/.secrets/comparison-20261008/' + kind + '-admin-password'),
  36  |     'utf8',
  37  |   ).trim();
  38  |   protectTestSecrets(password);
  39  |   const states: unknown[] = [];
  40  |   const errors: string[] = [];
  41  |   page.on('pageerror', (e) => errors.push(e.message));
  42  |   const capture = async (label: string) => {
  43  |     await settleOrderPage(page);
  44  |     const state = await page.evaluate((source) => {
  45  |       const read = (el: Element) => {
  46  |         const s = getComputedStyle(el),
  47  |           r = el.getBoundingClientRect();
  48  |         return {
  49  |           tag: el.tagName,
  50  |           cls: el.getAttribute('class'),
  51  |           text: (el as HTMLElement).innerText,
  52  |           rect: [r.x, r.y, r.width, r.height],
  53  |           display: s.display,
  54  |           font: [s.fontFamily, s.fontSize, s.fontWeight, s.lineHeight],
  55  |           color: s.color,
  56  |           padding: s.padding,
  57  |         };
  58  |       };
  59  |       const main = document.querySelector('main')!;
  60  |       const select = document.querySelector(
  61  |         source ? '.el-dialog .el-select' : '.sn-schedule-modal .ant-select',
  62  |       );
  63  |       const selectors = source
  64  |         ? [
  65  |             '.el-dialog',
  66  |             '.el-dialog__headerbtn',
  67  |             '.el-dialog .el-switch__core',
  68  |             '.el-input-number__increase',
  69  |             '.el-input-number__decrease',
  70  |             '.el-input-number input',
  71  |             '.el-message',
  72  |             '.el-table__row:first-child td',
  73  |             '.el-descriptions__cell',
  74  |           ]
  75  |         : [
  76  |             '.sn-schedule-modal .ant-modal-container',
  77  |             '.sn-schedule-modal .ant-modal-close',
  78  |             '.sn-schedule-modal .ant-switch',
  79  |             '.sn-schedule-modal .ant-input-number-action-up',
  80  |             '.sn-schedule-modal .ant-input-number-action-down',
  81  |             '.sn-schedule-modal .ant-input-number-input',
  82  |             '.ant-message-notice',
  83  |             '.sn-order-table .ant-table-row td',
  84  |             '.sn-order-lines .ant-descriptions-item-label,.sn-order-lines .ant-descriptions-item-content',
  85  |           ];
  86  |       return {
  87  |         scroll: main.scrollTop,
  88  |         scrollHeight: main.scrollHeight,
  89  |         height: main.clientHeight,
  90  |         fonts: document.fonts.status,
  91  |         focus: document.activeElement ? read(document.activeElement) : null,
  92  |         groups: selectors.map((s) =>
  93  |           [...document.querySelectorAll(s)]
  94  |             .filter(
  95  |               (el) => el.getClientRects().length && getComputedStyle(el).visibility !== 'hidden',
  96  |             )
  97  |             .map(read),
  98  |         ),
  99  |         select: select
  100 |           ? {
  101 |               html: select.outerHTML,
  102 |               children: [select, ...select.querySelectorAll('*')].map(read),
  103 |             }
  104 |           : null,
  105 |       };
  106 |     }, prototype);
  107 |     states.push({ label, ...state, schedule: fixture('snapshot').schedule });
  108 |     await page.screenshot({ path: info.outputPath(label + '.png'), animations: 'disabled' });
  109 |   };
  110 |   const open = async () => {
  111 |     await page.getByRole('button', { name: zh.orders.sched.settings, exact: true }).click();
  112 |     await expect(page.getByRole('dialog')).toBeVisible();
> 113 |     await expect(page.getByRole('dialog')).toBeFocused();
      |                                            ^ Error: expect(locator).toBeFocused() failed
  114 |   };
  115 |   const close = async () => {
  116 |     await page
  117 |       .getByRole('dialog')
  118 |       .getByRole('button', { name: zh.common.cancel, exact: true })
  119 |       .click();
  120 |     await expect(page.getByRole('dialog')).toBeHidden();
  121 |     await expect(
  122 |       page.getByRole('button', { name: zh.orders.sched.settings, exact: true }),
  123 |     ).toBeFocused();
  124 |   };
  125 |   const unit = async (name: string) => {
  126 |     await page
  127 |       .getByRole('dialog')
  128 |       .locator(prototype ? '.el-select' : '.ant-select')
  129 |       .click();
  130 |     await page.getByRole('option', { name, exact: true }).click();
  131 |   };
  132 |   const messages = page.locator(prototype ? '.el-message' : '.ant-message-notice');
  133 |   const save = async (minutes: number) => {
  134 |     await open();
  135 |     await unit(zh.orders.sched.unit.minute);
  136 |     await page.getByRole('spinbutton').fill(String(minutes));
  137 |     const response = page.waitForResponse(
  138 |       (r) =>
  139 |         r.request().method() === 'PUT' &&
  140 |         new URL(r.url()).pathname.endsWith('/orders/sync-schedule'),
  141 |     );
  142 |     await page
  143 |       .getByRole('dialog')
  144 |       .getByRole('button', { name: zh.common.save, exact: true })
  145 |       .click();
  146 |     expect((await response).status()).toBe(200);
  147 |     await expect(page.getByRole('dialog')).toBeHidden();
  148 |     await expect(messages).toHaveCount(1);
  149 |     await capture('saved-' + minutes);
  150 |     await expect(messages).toHaveCount(0);
  151 |   };
  152 |   try {
  153 |     await page.goto((prototype ? 'http://127.0.0.1:8001' : 'http://127.0.0.1:15174') + '/login');
  154 |     await page.locator('input[autocomplete=username]').fill('audit' + run);
  155 |     await page.locator('input[autocomplete=current-password]').fill(password);
  156 |     await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
  157 |     await page.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
  158 |     await expect(page.getByText('CMP-MO-002', { exact: true })).toBeVisible();
  159 |     await capture('loaded');
  160 |     await page.getByText('CMP-MO-002', { exact: true }).click();
  161 |     await expect(
  162 |       page.getByText(zh.orders.linesOf.replace('{{bill}}', 'CMP-MO-002'), { exact: true }),
  163 |     ).toBeVisible();
  164 |     await capture('selected');
  165 |     await page.keyboard.press('ArrowDown');
  166 |     await capture('table-arrow-down');
  167 |     await page.keyboard.press('ArrowUp');
  168 |     await capture('table-arrow-up');
  169 |     await open();
  170 |     await capture('opened');
  171 |     await page.keyboard.press('Escape');
  172 |     await expect(page.getByRole('dialog')).toBeHidden();
  173 |     await capture('escape');
  174 |     await open();
  175 |     await page
  176 |       .getByRole('dialog')
  177 |       .locator(prototype ? '.el-switch' : '.ant-switch')
  178 |       .click();
  179 |     await capture('switch-edited');
  180 |     await close();
  181 |     await capture('cancel');
  182 |     expect(fixture('snapshot').schedule[0].enabled).toBe(0);
  183 |     await open();
  184 |     await page
  185 |       .getByRole('dialog')
  186 |       .locator(prototype ? '.el-dialog__headerbtn' : '.ant-modal-close')
  187 |       .click();
  188 |     await expect(page.getByRole('dialog')).toBeHidden();
  189 |     await capture('close-button');
  190 |     await open();
  191 |     await page.mouse.click(10, 500);
  192 |     await expect(page.getByRole('dialog')).toBeHidden();
  193 |     await expect(
  194 |       page.getByRole('button', { name: zh.orders.sched.settings, exact: true }),
  195 |     ).toBeFocused();
  196 |     await capture('backdrop');
  197 |     await open();
  198 |     await unit(zh.orders.sched.unit.minute);
  199 |     const amount = page.getByRole('spinbutton');
  200 |     await amount.fill('10');
  201 |     await amount.press('ArrowUp');
  202 |     await expect(amount).toHaveValue('11');
  203 |     await capture('keyboard-up');
  204 |     await amount.press('ArrowDown');
  205 |     await expect(amount).toHaveValue('10');
  206 |     await capture('keyboard-down');
  207 |     await page
  208 |       .getByRole('dialog')
  209 |       .locator(prototype ? '.el-input-number__increase' : '.ant-input-number-action-up')
  210 |       .click();
  211 |     await expect(amount).toHaveValue('11');
  212 |     await capture('mouse-step-up');
  213 |     await page
```