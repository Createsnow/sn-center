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

Locator:  getByRole('button', { name: '设置', exact: true })
Expected: focused
Received: inactive
Timeout:  15000ms

Call log:
  - Expect "toBeFocused" getByRole('button', { name: '设置', exact: true }) with timeout 15000ms
  - waiting for getByRole('button', { name: '设置', exact: true })
    33 × locator resolved to <button type="button" data-v-ba804486="" aria-disabled="false" class="el-button el-button--primary is-link">…</button>
       - unexpected value "inactive"

```

# Test source

```ts
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
  113 |     await expect(prototype ? page.locator('.el-dialog') : page.getByRole('dialog')).toBeFocused();
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
> 195 |     ).toBeFocused();
      |       ^ Error: expect(locator).toBeFocused() failed
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
  214 |       .getByRole('dialog')
  215 |       .locator(prototype ? '.el-input-number__decrease' : '.ant-input-number-action-down')
  216 |       .click();
  217 |     await expect(amount).toHaveValue('10');
  218 |     await capture('mouse-step-down');
  219 |     await amount.fill('1');
  220 |     await amount.press('ArrowDown');
  221 |     await expect(amount).toHaveValue('1');
  222 |     await capture('step-minimum');
  223 |     await amount.fill('10080');
  224 |     await amount.press('ArrowUp');
  225 |     await expect(amount).toHaveValue('10080');
  226 |     await capture('step-maximum');
  227 |     await close();
  228 |     await page.mouse.move(500, 700);
  229 |     await page.mouse.wheel(0, 1000);
  230 |     await expect.poll(() => page.locator('main').evaluate((el) => el.scrollTop)).toBeGreaterThan(0);
  231 |     await capture('wheel-bottom');
  232 |     await open();
  233 |     await capture('opened-after-scroll');
  234 |     await page.keyboard.press('Escape');
  235 |     await expect(page.getByRole('dialog')).toBeHidden();
  236 |     await capture('escape-after-scroll');
  237 |     await save(120);
  238 |     await save(1440);
  239 |     expect(errors).toEqual([]);
  240 |   } finally {
  241 |     writeFileSync(
  242 |       info.outputPath('observations.json'),
  243 |       JSON.stringify({ states, errors }, null, 2),
  244 |     );
  245 |   }
  246 | });
  247 | 
```