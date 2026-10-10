# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: order-interactions.spec.ts >> P05 interactions: overflow, line details, schedule limits and cancellation in three languages
- Location: e2e\parity\order-interactions.spec.ts:12:1

# Error details

```
Error: locator.click: Target crashed 
Call log:
  - waiting for getByRole('option', { name: '分钟', exact: true })
    - locator resolved to <div title="分钟" role="option" id="unit_list_0" aria-selected="false" aria-disabled="false" class="ant-select-item ant-select-item-option">…</div>
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
  407 × retrying click action
        - waiting 500ms
        - waiting for element to be visible, enabled and stable
        - element is not visible
  - retrying click action
    - waiting 500ms

```

# Test source

```ts
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
  124 |             unitText: '.sn-schedule-modal .ant-select-content',
  125 |             alertText: '.sn-schedule-modal .ant-alert-title',
  126 |             messageText: '.ant-message-notice-title',
  127 |             detailTitle: '.sn-order-lines .sn-surface-title',
  128 |             detailButton: '.sn-order-lines .sn-surface-title .ant-btn',
  129 |             details:
  130 |               '.sn-order-lines .ant-descriptions-item-label,.sn-order-lines .ant-descriptions-item-content',
  131 |             lineCells: '[data-testid=order-lines] .ant-table-row td',
  132 |           };
  133 |       return Object.fromEntries(
  134 |         Object.entries(selectors).map(([key, selector]) => [
  135 |           key,
  136 |           [...document.querySelectorAll(selector)]
  137 |             .filter(
  138 |               (el) => el.getClientRects().length && getComputedStyle(el).visibility !== 'hidden',
  139 |             )
  140 |             .map((el) => {
  141 |               const r = el.getBoundingClientRect(),
  142 |                 s = getComputedStyle(el);
  143 |               return {
  144 |                 text: (el as HTMLElement).innerText,
  145 |                 box: [r.x, r.y, r.width, r.height],
  146 |                 font: [s.fontFamily, s.fontSize, s.fontWeight, s.lineHeight],
  147 |                 color: s.color,
  148 |                 background: s.backgroundColor,
  149 |                 padding: s.padding,
  150 |                 shadow: s.boxShadow,
  151 |                 filter: s.filter,
  152 |               };
  153 |             }),
  154 |         ]),
  155 |       );
  156 |     }, prototype);
  157 |     states.push({
  158 |       label,
  159 |       geometry,
  160 |       mainScroll: await page.locator('main').evaluate((el) => el.scrollTop),
  161 |       focus: await page.evaluate(() => ({
  162 |         tag: document.activeElement?.tagName,
  163 |         text: document.activeElement?.textContent?.slice(0, 120),
  164 |       })),
  165 |       tooltips: await tooltip.allTextContents(),
  166 |       message: await messages.allTextContents(),
  167 |       amount: amountValue,
  168 |       schedule: helper('snapshot').schedule,
  169 |     });
  170 |     await page.screenshot({ path: info.outputPath(label + '.png'), animations: 'disabled' });
  171 |   };
  172 |   const hover = async (cell: Locator, label: string) => {
  173 |     await cell.hover();
  174 |     await page.waitForTimeout(250); // 原型Tooltip含show-after延迟；测量稳定展开状态。
  175 |     await expect(tooltip).toHaveCount(1);
  176 |     await capture(label);
  177 |     await page.mouse.move(0, 0);
  178 |     await expect(tooltip).toHaveCount(0);
  179 |   };
  180 |   let text = zh;
  181 |   let languageKey = 'zh-CN';
  182 |   const sync = async (label: string) => {
  183 |     await expect(messages).toHaveCount(0);
  184 |     const ready = page.waitForResponse(
  185 |       (r) => r.request().method() === 'POST' && new URL(r.url()).pathname.endsWith('/orders/sync'),
  186 |     );
  187 |     await page.getByRole('button', { name: text.orders.syncAll, exact: true }).click();
  188 |     expect((await ready).status()).toBe(200);
  189 |     await expect(messages).toHaveCount(1);
  190 |     await capture(label);
  191 |     await expect(page.locator('.ant-spin-spinning:visible,.el-loading-mask:visible')).toHaveCount(
  192 |       0,
  193 |     );
  194 |     await expect(messages).toHaveCount(0);
  195 |   };
  196 |   const open = async () => {
  197 |     await page.getByRole('button', { name: text.orders.sched.settings, exact: true }).click();
  198 |     await expect(page.getByRole('dialog')).toBeVisible();
  199 |   };
  200 |   const choose = async (unit: 'minute' | 'hour' | 'day') => {
  201 |     await page
  202 |       .getByRole('dialog')
  203 |       .locator(prototype ? '.el-select' : '.ant-select')
  204 |       .click();
> 205 |     await page.getByRole('option', { name: text.orders.sched.unit[unit], exact: true }).click();
      |                                                                                         ^ Error: locator.click: Target crashed 
  206 |   };
  207 |   const save = async (minutes: number) => {
  208 |     await open();
  209 |     await choose('minute');
  210 |     await page.getByRole('dialog').getByRole('spinbutton').fill(String(minutes));
  211 |     const saved = page.waitForResponse(
  212 |       (r) =>
  213 |         r.request().method() === 'PUT' &&
  214 |         new URL(r.url()).pathname.endsWith('/orders/sync-schedule'),
  215 |     );
  216 |     await page
  217 |       .getByRole('dialog')
  218 |       .getByRole('button', { name: text.common.save, exact: true })
  219 |       .click();
  220 |     expect((await saved).status()).toBe(200);
  221 |     await expect(page.getByRole('dialog')).toBeHidden();
  222 |     await expect(messages).toHaveCount(1);
  223 |     await capture(`${languageKey}-saved-${minutes}`);
  224 |     await expect(messages).toHaveCount(0);
  225 |   };
  226 |   try {
  227 |     writeExtras(JSON.stringify(extras));
  228 |     await page.goto((prototype ? 'http://127.0.0.1:8001' : 'http://127.0.0.1:15174') + '/login');
  229 |     await page.locator('input[autocomplete="username"]').fill(username);
  230 |     await page.locator('input[autocomplete="current-password"]').fill(password);
  231 |     await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
  232 |     await page.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
  233 |     for (const [locale, language, translations] of [
  234 |       ['zh-CN', '简体中文', zh],
  235 |       ['en', 'English', en],
  236 |       ['vi', 'Tiếng Việt', vi],
  237 |     ] as const) {
  238 |       text = translations;
  239 |       languageKey = locale;
  240 |       await page.setViewportSize({ width: 1440, height: 960 });
  241 |       const languageControl = page.locator(
  242 |         prototype ? '.header .lang' : '.prototype-language .ant-select',
  243 |       );
  244 |       if (!(await languageControl.innerText()).includes(language)) {
  245 |         const changed = page.waitForResponse(
  246 |           (r) =>
  247 |             r.request().method() !== 'GET' &&
  248 |             new URL(r.url()).pathname.endsWith(prototype ? '/auth/lang' : '/system/auth/language'),
  249 |         );
  250 |         await languageControl.click();
  251 |         await page.getByRole('option', { name: language, exact: true }).click();
  252 |         await changed;
  253 |       }
  254 |       await sync(locale + '-synced');
  255 |       for (const width of [1440, 768]) {
  256 |         await page.setViewportSize({ width, height: 960 });
  257 |         const rows = page.locator(prototype ? '.el-table__row' : '.sn-order-table .ant-table-row');
  258 |         const row = rows.filter({ has: page.getByText(billNo, { exact: true }) });
  259 |         await expect(row).toHaveCount(1);
  260 |         for (let i = 0; i < 5; i++)
  261 |           await hover(row.locator('td').nth(i), `${locale}-${width}-overflow-${i}`);
  262 |         const short = rows
  263 |           .filter({ has: page.getByText('CMP-MO-002', { exact: true }) })
  264 |           .locator('td')
  265 |           .first();
  266 |         await short.hover();
  267 |         await page.waitForTimeout(250);
  268 |         await expect(tooltip).toHaveCount(0);
  269 |         await capture(`${locale}-${width}-short-no-tooltip`);
  270 |         await page.mouse.move(0, 0);
  271 |         if (width === 768) {
  272 |           const scroller = page.locator(
  273 |             prototype
  274 |               ? '.el-col:first-child .el-table__body-wrapper .el-scrollbar__wrap'
  275 |               : '.sn-order-table .ant-table-body',
  276 |           );
  277 |           await row.locator('td').nth(4).hover();
  278 |           await expect(tooltip).toHaveCount(1);
  279 |           const beforeScroll = await scroller.evaluate((el) => el.scrollLeft);
  280 |           await page.mouse.wheel(120, 0);
  281 |           await expect
  282 |             .poll(() => scroller.evaluate((el) => el.scrollLeft))
  283 |             .toBeGreaterThan(beforeScroll);
  284 |           await expect(tooltip).toHaveCount(0);
  285 |           await capture(`${locale}-${width}-scroll-closes-tooltip`);
  286 |         }
  287 |         await row.locator('td').first().click();
  288 |         await expect(
  289 |           page.getByText(text.orders.linesOf.replace('{{bill}}', billNo), { exact: true }),
  290 |         ).toBeVisible();
  291 |         await expect(
  292 |           page.locator('.ant-spin-spinning:visible,.el-loading-mask:visible'),
  293 |         ).toHaveCount(0);
  294 |         const lines = page.locator(
  295 |           prototype
  296 |             ? '.el-col:last-child .el-table__row'
  297 |             : '[data-testid=order-lines] .ant-table-row',
  298 |         );
  299 |         await expect(lines).toHaveCount(2);
  300 |         await hover(lines.first().locator('td').nth(2), `${locale}-${width}-line-overflow`);
  301 |         await open();
  302 |         await page.mouse.move(0, 0);
  303 |         await capture(`${locale}-${width}-dialog`);
  304 |         await page.keyboard.press('Escape');
  305 |         await expect(page.getByRole('dialog')).toBeHidden();
```