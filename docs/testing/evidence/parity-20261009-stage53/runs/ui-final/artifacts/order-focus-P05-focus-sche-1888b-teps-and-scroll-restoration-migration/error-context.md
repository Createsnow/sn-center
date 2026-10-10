# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: order-focus.spec.ts >> P05 focus: schedule closing, unit text, keyboard steps and scroll restoration
- Location: e2e\parity\order-focus.spec.ts:11:1

# Error details

```
Error: expect(locator).toHaveValue(expected) failed

Locator:  getByRole('spinbutton')
Expected: "11"
Received: "2"
Timeout:  15000ms

Call log:
  - Expect "toHaveValue" getByRole('spinbutton') with timeout 15000ms
  - waiting for getByRole('spinbutton')
    33 × locator resolved to <input step="1" value="2" id="amount" role="spinbutton" aria-valuemin="1" aria-valuenow="2" aria-label="同步间隔" autocomplete="off" aria-valuemax="10080" class="ant-input-number-input"/>
       - unexpected value "2"

```

# Test source

```ts
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
  265 |     await amount.fill('10');
  266 |     await amount.press('ArrowUp');
> 267 |     await expect(amount).toHaveValue('11');
      |                          ^ Error: expect(locator).toHaveValue(expected) failed
  268 |     await capture('keyboard-up');
  269 |     await amount.press('ArrowDown');
  270 |     await expect(amount).toHaveValue('10');
  271 |     await capture('keyboard-down');
  272 |     await page
  273 |       .getByRole('dialog')
  274 |       .locator(prototype ? '.el-input-number__increase' : '.ant-input-number-action-up')
  275 |       .click();
  276 |     await expect(amount).toHaveValue('11');
  277 |     await capture('mouse-step-up');
  278 |     await page
  279 |       .getByRole('dialog')
  280 |       .locator(prototype ? '.el-input-number__decrease' : '.ant-input-number-action-down')
  281 |       .click();
  282 |     await expect(amount).toHaveValue('10');
  283 |     await capture('mouse-step-down');
  284 |     await amount.fill('1');
  285 |     await amount.press('ArrowDown');
  286 |     await expect(amount).toHaveValue('1');
  287 |     await capture('step-minimum');
  288 |     await amount.fill('10080');
  289 |     await amount.press('ArrowUp');
  290 |     await expect(amount).toHaveValue('10080');
  291 |     await capture('step-maximum');
  292 |     await close();
  293 |     await page.mouse.move(500, 700);
  294 |     await page.mouse.wheel(0, 1000);
  295 |     await expect.poll(() => page.locator('main').evaluate((el) => el.scrollTop)).toBeGreaterThan(0);
  296 |     await capture('wheel-bottom');
  297 |     await open();
  298 |     await capture('opened-after-scroll');
  299 |     await page.keyboard.press('Escape');
  300 |     await expect(page.getByRole('dialog')).toBeHidden();
  301 |     await capture('escape-after-scroll');
  302 |     await save(120);
  303 |     await save(1440);
  304 |     expect(errors).toEqual([]);
  305 |   } finally {
  306 |     writeFileSync(
  307 |       info.outputPath('observations.json'),
  308 |       JSON.stringify({ states, errors }, null, 2),
  309 |     );
  310 |   }
  311 | });
  312 | 
```