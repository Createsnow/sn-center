# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: order-interactions.spec.ts >> P05 interactions: overflow, line details, schedule limits and cancellation in three languages
- Location: e2e\parity\order-interactions.spec.ts:12:1

# Error details

```
TimeoutError: locator.click: Timeout 15000ms exceeded.
Call log:
  - waiting for getByRole('option', { name: 'phút', exact: true })
    - locator resolved to <div title="phút" role="option" id="unit_list_0" aria-selected="false" aria-disabled="false" class="ant-select-item ant-select-item-option">…</div>
  - attempting click action
    2 × waiting for element to be visible, enabled and stable
      - element is not stable
    - retrying click action
    - waiting 20ms
    2 × waiting for element to be visible, enabled and stable
      - element is not stable
    - retrying click action
      - waiting 100ms
    - waiting for element to be visible, enabled and stable
    - element is not stable
  28 × retrying click action
       - waiting 500ms
       - waiting for element to be visible, enabled and stable
       - element is not visible
  - retrying click action
    - waiting 500ms

```

# Test source

```ts
  106 |             messageText: '.el-message__content',
  107 |             detailTitle: '.el-col:last-child .surface__title',
  108 |             detailButton: '.el-col:last-child .surface__title .el-button',
  109 |             details: '.el-descriptions__cell',
  110 |             lineCells: '.el-col:last-child .el-table__row td',
  111 |           }
  112 |         : {
  113 |             dialog: '.sn-schedule-modal .ant-modal-container',
  114 |             title: '.sn-schedule-modal .ant-modal-title',
  115 |             labels: '.sn-schedule-modal .ant-form-item-label',
  116 |             number: '.sn-schedule-modal .ant-input-number',
  117 |             unit: '.sn-schedule-modal .sn-schedule-interval .ant-select',
  118 |             hint: '.sn-schedule-modal .sn-schedule-interval + .sn-muted',
  119 |             alert: '.sn-schedule-modal .ant-alert',
  120 |             buttons: '.sn-schedule-modal .ant-modal-footer .ant-btn',
  121 |             switch: '.sn-schedule-modal .ant-switch',
  122 |             message: '.ant-message-notice',
  123 |             tooltip: '.ant-tooltip[role=tooltip],.ant-tooltip-container[role=tooltip]',
  124 |             numberInput: '.sn-schedule-modal .ant-input-number-input',
  125 |             unitText: '.sn-schedule-modal .ant-select-content',
  126 |             alertText: '.sn-schedule-modal .ant-alert-title',
  127 |             messageText: '.ant-message-notice-title',
  128 |             detailTitle: '.sn-order-lines .sn-surface-title',
  129 |             detailButton: '.sn-order-lines .sn-surface-title .ant-btn',
  130 |             details:
  131 |               '.sn-order-lines .ant-descriptions-item-label,.sn-order-lines .ant-descriptions-item-content',
  132 |             lineCells: '[data-testid=order-lines] .ant-table-row td',
  133 |           };
  134 |       return Object.fromEntries(
  135 |         Object.entries(selectors).map(([key, selector]) => [
  136 |           key,
  137 |           [...document.querySelectorAll(selector)]
  138 |             .filter(
  139 |               (el) => el.getClientRects().length && getComputedStyle(el).visibility !== 'hidden',
  140 |             )
  141 |             .map((el) => {
  142 |               const r = el.getBoundingClientRect(),
  143 |                 s = getComputedStyle(el);
  144 |               return {
  145 |                 text: (el as HTMLElement).innerText,
  146 |                 box: [r.x, r.y, r.width, r.height],
  147 |                 font: [s.fontFamily, s.fontSize, s.fontWeight, s.lineHeight],
  148 |                 color: s.color,
  149 |                 background: s.backgroundColor,
  150 |                 padding: s.padding,
  151 |                 shadow: s.boxShadow,
  152 |                 filter: s.filter,
  153 |               };
  154 |             }),
  155 |         ]),
  156 |       );
  157 |     }, prototype);
  158 |     states.push({
  159 |       label,
  160 |       geometry,
  161 |       mainScroll: await page.locator('main').evaluate((el) => el.scrollTop),
  162 |       focus: await page.evaluate(() => ({
  163 |         tag: document.activeElement?.tagName,
  164 |         text: document.activeElement?.textContent?.slice(0, 120),
  165 |       })),
  166 |       tooltips: await tooltip.allTextContents(),
  167 |       message: await messages.allTextContents(),
  168 |       amount: amountValue,
  169 |       schedule: helper('snapshot').schedule,
  170 |     });
  171 |     await page.screenshot({ path: info.outputPath(label + '.png'), animations: 'disabled' });
  172 |   };
  173 |   const hover = async (cell: Locator, label: string) => {
  174 |     await cell.hover();
  175 |     await page.waitForTimeout(250); // 原型Tooltip含show-after延迟；测量稳定展开状态。
  176 |     await expect(tooltip).toHaveCount(1);
  177 |     await capture(label);
  178 |     await page.mouse.move(0, 0);
  179 |     await expect(tooltip).toHaveCount(0);
  180 |   };
  181 |   let text = zh;
  182 |   let languageKey = 'zh-CN';
  183 |   const sync = async (label: string) => {
  184 |     await expect(messages).toHaveCount(0);
  185 |     const ready = page.waitForResponse(
  186 |       (r) => r.request().method() === 'POST' && new URL(r.url()).pathname.endsWith('/orders/sync'),
  187 |     );
  188 |     await page.getByRole('button', { name: text.orders.syncAll, exact: true }).click();
  189 |     expect((await ready).status()).toBe(200);
  190 |     await expect(messages).toHaveCount(1);
  191 |     await capture(label);
  192 |     await expect(page.locator('.ant-spin-spinning:visible,.el-loading-mask:visible')).toHaveCount(
  193 |       0,
  194 |     );
  195 |     await expect(messages).toHaveCount(0);
  196 |   };
  197 |   const open = async () => {
  198 |     await page.getByRole('button', { name: text.orders.sched.settings, exact: true }).click();
  199 |     await expect(page.getByRole('dialog')).toBeVisible();
  200 |   };
  201 |   const choose = async (unit: 'minute' | 'hour' | 'day') => {
  202 |     await page
  203 |       .getByRole('dialog')
  204 |       .locator(prototype ? '.el-select' : '.ant-select')
  205 |       .click();
> 206 |     await page.getByRole('option', { name: text.orders.sched.unit[unit], exact: true }).click();
      |                                                                                         ^ TimeoutError: locator.click: Timeout 15000ms exceeded.
  207 |   };
  208 |   const save = async (minutes: number) => {
  209 |     await open();
  210 |     await choose('minute');
  211 |     await page.getByRole('dialog').getByRole('spinbutton').fill(String(minutes));
  212 |     const saved = page.waitForResponse(
  213 |       (r) =>
  214 |         r.request().method() === 'PUT' &&
  215 |         new URL(r.url()).pathname.endsWith('/orders/sync-schedule'),
  216 |     );
  217 |     await page
  218 |       .getByRole('dialog')
  219 |       .getByRole('button', { name: text.common.save, exact: true })
  220 |       .click();
  221 |     expect((await saved).status()).toBe(200);
  222 |     await expect(page.getByRole('dialog')).toBeHidden();
  223 |     await expect(messages).toHaveCount(1);
  224 |     await capture(`${languageKey}-saved-${minutes}`);
  225 |     await expect(messages).toHaveCount(0);
  226 |   };
  227 |   try {
  228 |     writeExtras(JSON.stringify(extras));
  229 |     await page.goto((prototype ? 'http://127.0.0.1:8001' : 'http://127.0.0.1:15174') + '/login');
  230 |     await page.locator('input[autocomplete="username"]').fill(username);
  231 |     await page.locator('input[autocomplete="current-password"]').fill(password);
  232 |     await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
  233 |     await page.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
  234 |     await expect(page.getByText('CMP-MO-002', { exact: true })).toBeVisible();
  235 |     await expect(
  236 |       page.getByRole('button', { name: zh.orders.sched.settings, exact: true }),
  237 |     ).toBeVisible();
  238 |     await expect(page.locator('.ant-spin-spinning:visible,.el-loading-mask:visible')).toHaveCount(
  239 |       0,
  240 |     );
  241 |     await capture('initial-orders');
  242 |     for (const [locale, language, translations] of [
  243 |       ['zh-CN', '简体中文', zh],
  244 |       ['en', 'English', en],
  245 |       ['vi', 'Tiếng Việt', vi],
  246 |     ] as const) {
  247 |       text = translations;
  248 |       languageKey = locale;
  249 |       await page.setViewportSize({ width: 1440, height: 960 });
  250 |       const languageControl = page.locator(
  251 |         prototype ? '.header .lang' : '.prototype-language .ant-select',
  252 |       );
  253 |       if (!(await languageControl.innerText()).includes(language)) {
  254 |         const changed = page.waitForResponse(
  255 |           (r) =>
  256 |             r.request().method() !== 'GET' &&
  257 |             new URL(r.url()).pathname.endsWith(prototype ? '/auth/lang' : '/system/auth/language'),
  258 |         );
  259 |         await languageControl.click();
  260 |         await page.getByRole('option', { name: language, exact: true }).click();
  261 |         await changed;
  262 |       }
  263 |       await sync(locale + '-synced');
  264 |       for (const width of [1440, 768]) {
  265 |         await page.setViewportSize({ width, height: 960 });
  266 |         const rows = page.locator(prototype ? '.el-table__row' : '.sn-order-table .ant-table-row');
  267 |         const row = rows.filter({ has: page.getByText(billNo, { exact: true }) });
  268 |         await expect(row).toHaveCount(1);
  269 |         for (let i = 0; i < 5; i++)
  270 |           await hover(row.locator('td').nth(i), `${locale}-${width}-overflow-${i}`);
  271 |         const short = rows
  272 |           .filter({ has: page.getByText('CMP-MO-002', { exact: true }) })
  273 |           .locator('td')
  274 |           .first();
  275 |         await short.hover();
  276 |         await page.waitForTimeout(250);
  277 |         await expect(tooltip).toHaveCount(0);
  278 |         await capture(`${locale}-${width}-short-no-tooltip`);
  279 |         await page.mouse.move(0, 0);
  280 |         if (width === 768) {
  281 |           const scroller = page.locator(
  282 |             prototype
  283 |               ? '.el-col:first-child .el-table__body-wrapper .el-scrollbar__wrap'
  284 |               : '.sn-order-table .ant-table-body',
  285 |           );
  286 |           await row.locator('td').nth(4).hover();
  287 |           await expect(tooltip).toHaveCount(1);
  288 |           const beforeScroll = await scroller.evaluate((el) => el.scrollLeft);
  289 |           await page.mouse.wheel(120, 0);
  290 |           await expect
  291 |             .poll(() => scroller.evaluate((el) => el.scrollLeft))
  292 |             .toBeGreaterThan(beforeScroll);
  293 |           await expect(tooltip).toHaveCount(0);
  294 |           await capture(`${locale}-${width}-scroll-closes-tooltip`);
  295 |         }
  296 |         await row.locator('td').first().click();
  297 |         await expect(
  298 |           page.getByText(text.orders.linesOf.replace('{{bill}}', billNo), { exact: true }),
  299 |         ).toBeVisible();
  300 |         await expect(
  301 |           page.locator('.ant-spin-spinning:visible,.el-loading-mask:visible'),
  302 |         ).toHaveCount(0);
  303 |         const lines = page.locator(
  304 |           prototype
  305 |             ? '.el-col:last-child .el-table__row'
  306 |             : '[data-testid=order-lines] .ant-table-row',
```