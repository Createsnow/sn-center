# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: order-focus.spec.ts >> P05 focus: schedule closing, unit text, keyboard steps and scroll restoration
- Location: e2e\parity\order-focus.spec.ts:11:1

# Error details

```
Error: expect(locator).toHaveAttribute(expected) failed

Locator:  getByRole('spinbutton')
Expected: "10080"
Received: "7"
Timeout:  15000ms

Call log:
  - Expect "toHaveAttribute" getByRole('spinbutton') with timeout 15000ms
  - waiting for getByRole('spinbutton')
    33 × locator resolved to <input min="1" step="1" max="10080" tabindex="0" type="number" role="spinbutton" aria-valuemax="7" aria-valuemin="1" aria-valuenow="1" autocomplete="off" id="el-id-2284-50" aria-disabled="false" class="el-input__inner"/>
       - unexpected value "7"

```

# Test source

```ts
  166 |   try {
  167 |     await page.goto((prototype ? 'http://127.0.0.1:8001' : 'http://127.0.0.1:15174') + '/login');
  168 |     await page.locator('input[autocomplete=username]').fill('audit' + run);
  169 |     await page.locator('input[autocomplete=current-password]').fill(password);
  170 |     await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
  171 |     await page.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
  172 |     await expect(page.getByText('CMP-MO-002', { exact: true })).toBeVisible();
  173 |     const synced = page.waitForResponse(
  174 |       (r) => r.request().method() === 'POST' && new URL(r.url()).pathname.endsWith('/orders/sync'),
  175 |     );
  176 |     await clickVisibleControl(
  177 |       page,
  178 |       page.getByRole('button', { name: zh.orders.syncAll, exact: true }),
  179 |     );
  180 |     expect((await synced).status()).toBe(200);
  181 |     await expect(messages).toHaveCount(1);
  182 |     await expect(messages).toHaveCount(0);
  183 |     await capture('loaded');
  184 |     await page.getByText('CMP-MO-002', { exact: true }).click();
  185 |     await expect(
  186 |       page.getByText(zh.orders.linesOf.replace('{{bill}}', 'CMP-MO-002'), { exact: true }),
  187 |     ).toBeVisible();
  188 |     await capture('selected');
  189 |     const detailRow = page
  190 |       .locator(
  191 |         prototype
  192 |           ? '.el-col:last-child .el-table__row'
  193 |           : '[data-testid=order-lines] .ant-table-row',
  194 |       )
  195 |       .first();
  196 |     await detailRow.hover();
  197 |     await expect
  198 |       .poll(() =>
  199 |         detailRow
  200 |           .locator('td')
  201 |           .evaluateAll((cells) => cells.map((cell) => getComputedStyle(cell).backgroundColor)),
  202 |       )
  203 |       .toEqual(Array(4).fill('rgb(245, 247, 250)'));
  204 |     await capture('detail-row-hover');
  205 |     await page.mouse.move(0, 0);
  206 |     await page.getByText('CMP-MO-002', { exact: true }).click();
  207 |     await page.keyboard.press('ArrowDown');
  208 |     await capture('table-arrow-down');
  209 |     await page.keyboard.press('ArrowUp');
  210 |     await capture('table-arrow-up');
  211 |     await open();
  212 |     await capture('opened');
  213 |     await page.keyboard.press('Escape');
  214 |     await expect(page.getByRole('dialog')).toBeHidden();
  215 |     await capture('escape');
  216 |     await open();
  217 |     await page
  218 |       .getByRole('dialog')
  219 |       .locator(prototype ? '.el-switch' : '.ant-switch')
  220 |       .click();
  221 |     await capture('switch-edited');
  222 |     await close();
  223 |     await capture('cancel');
  224 |     expect(fixture('snapshot').schedule[0].enabled).toBe(0);
  225 |     await open();
  226 |     await page
  227 |       .getByRole('dialog')
  228 |       .locator(prototype ? '.el-dialog__headerbtn' : '.ant-modal-close')
  229 |       .click();
  230 |     await expect(page.getByRole('dialog')).toBeHidden();
  231 |     await capture('close-button');
  232 |     await open();
  233 |     await page.mouse.click(10, 500);
  234 |     await expect(page.getByRole('dialog')).toBeHidden();
  235 |     await capture('backdrop');
  236 |     expect(await page.evaluate(() => document.activeElement?.tagName)).toBe('BODY');
  237 |     await page.setViewportSize({ width: 768, height: 960 });
  238 |     await open();
  239 |     await capture('narrow-open');
  240 |     await page.keyboard.press('Escape');
  241 |     await expect(page.getByRole('dialog')).toBeHidden();
  242 |     await page.setViewportSize({ width: 1440, height: 960 });
  243 |     await open();
  244 |     await capture('wide-reopened');
  245 |     await close();
  246 |     // 不等待弹窗获得初始焦点：复现打开动画期间立即操作单位下拉。
  247 |     for (let attempt = 0; attempt < 3; attempt++) {
  248 |       await page.getByRole('button', { name: zh.orders.sched.settings, exact: true }).click();
  249 |       await expect(page.getByRole('dialog')).toBeVisible();
  250 |       await page
  251 |         .getByRole('dialog')
  252 |         .locator(prototype ? '.el-select' : '.ant-select')
  253 |         .click();
  254 |       await settleOrderPage(page);
  255 |       await expect(
  256 |         page.getByRole('option', { name: zh.orders.sched.unit.minute, exact: true }),
  257 |       ).toBeVisible();
  258 |       await capture('fast-unit-' + attempt);
  259 |       await page.getByRole('option', { name: zh.orders.sched.unit.minute, exact: true }).click();
  260 |       await close();
  261 |     }
  262 |     await open();
  263 |     await unit(zh.orders.sched.unit.minute);
  264 |     const amount = page.getByRole('spinbutton');
  265 |     // 单位选择落定后再输入，确认测试读取的是分钟控件和已提交的输入值。
> 266 |     await expect(amount).toHaveAttribute('aria-valuemax', '10080');
      |                          ^ Error: expect(locator).toHaveAttribute(expected) failed
  267 |     await amount.fill('10');
  268 |     await expect(amount).toHaveValue('10');
  269 |     await amount.press('ArrowUp');
  270 |     await expect(amount).toHaveValue('11');
  271 |     await capture('keyboard-up');
  272 |     await amount.press('ArrowDown');
  273 |     await expect(amount).toHaveValue('10');
  274 |     await capture('keyboard-down');
  275 |     await page
  276 |       .getByRole('dialog')
  277 |       .locator(prototype ? '.el-input-number__increase' : '.ant-input-number-action-up')
  278 |       .click();
  279 |     await expect(amount).toHaveValue('11');
  280 |     await capture('mouse-step-up');
  281 |     await page
  282 |       .getByRole('dialog')
  283 |       .locator(prototype ? '.el-input-number__decrease' : '.ant-input-number-action-down')
  284 |       .click();
  285 |     await expect(amount).toHaveValue('10');
  286 |     await capture('mouse-step-down');
  287 |     await amount.fill('1');
  288 |     await amount.press('ArrowDown');
  289 |     await expect(amount).toHaveValue('1');
  290 |     await capture('step-minimum');
  291 |     await amount.fill('10080');
  292 |     await amount.press('ArrowUp');
  293 |     await expect(amount).toHaveValue('10080');
  294 |     await capture('step-maximum');
  295 |     await close();
  296 |     await page.mouse.move(500, 700);
  297 |     await page.mouse.wheel(0, 1000);
  298 |     await expect.poll(() => page.locator('main').evaluate((el) => el.scrollTop)).toBeGreaterThan(0);
  299 |     await capture('wheel-bottom');
  300 |     await open();
  301 |     await capture('opened-after-scroll');
  302 |     await page.keyboard.press('Escape');
  303 |     await expect(page.getByRole('dialog')).toBeHidden();
  304 |     await capture('escape-after-scroll');
  305 |     await save(120);
  306 |     await save(1440);
  307 |     expect(errors).toEqual([]);
  308 |   } finally {
  309 |     writeFileSync(
  310 |       info.outputPath('observations.json'),
  311 |       JSON.stringify({ states, errors }, null, 2),
  312 |     );
  313 |   }
  314 | });
  315 | 
```