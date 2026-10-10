# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: order-boundaries.spec.ts >> P05 boundaries: Unicode blank bill, three-language validation and schedule owner handover
- Location: e2e\parity\order-boundaries.spec.ts:14:1

# Error details

```
TimeoutError: page.waitForResponse: Timeout 15000ms exceeded while waiting for event "response"
```

# Test source

```ts
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
  187 |     );
  188 |     await dialog.getByRole('button', { name: zh.common.save, exact: true }).click();
  189 |     const result = await response;
  190 |     expect(result.ok()).toBe(true);
  191 |     const body = await result.json();
  192 |     await expect(dialog).toBeHidden();
  193 |     return prototype ? body : body.data;
  194 |   };
  195 |   try {
  196 |     await login(page, username);
  197 |     const ordersBefore = fixture('snapshot').orders;
  198 |     const erpBefore = (await (await request.get('http://127.0.0.1:19091')).json()).queryRequests;
  199 |     for (const [locale, label, text, errors] of [
  200 |       ['zh-CN', '简体中文', zh, errZh],
  201 |       ['en', 'English', en, errEn],
  202 |       ['vi', 'Tiếng Việt', vi, errVi],
  203 |     ] as const) {
  204 |       await language(label);
  205 |       const input = page.getByPlaceholder(text.orders.billToSync, { exact: true });
  206 |       const button = page.getByRole('button', { name: text.orders.syncOne, exact: true });
  207 |       await input.fill('   ');
  208 |       await expect(button).toBeDisabled();
  209 |       const before = postPaths.length;
  210 |       await input.press('Enter');
  211 |       await page.waitForLoadState('networkidle');
  212 |       expect(postPaths).toHaveLength(before);
  213 |       await capture(locale + '-blank-disabled');
  214 |       for (const [suffix, value] of [
  215 |         ['nel', '\u0085'],
  216 |         ['separator', '\u001c'],
  217 |       ] as const) {
  218 |         await input.fill(value);
  219 |         await expect(button).toBeEnabled();
  220 |         const pending = page.waitForResponse(
  221 |           (r) =>
  222 |             r.request().method() === 'POST' && new URL(r.url()).pathname.endsWith('/orders/sync'),
  223 |         );
  224 |         await button.click();
  225 |         const response = await pending;
  226 |         expect(response.status()).toBe(400);
  227 |         writes.push({ locale, suffix, body: await response.json() });
  228 |         const message = page.locator(
  229 |           prototype ? '.el-message--error' : '.sn-prototype-error-message',
  230 |         );
  231 |         await expect(message).toHaveText(errors.ORDER_BILL_REQUIRED);
  232 |         expect(fixture('snapshot').orders).toEqual(ordersBefore);
  233 |         expect((await (await request.get('http://127.0.0.1:19091')).json()).queryRequests).toBe(
  234 |           erpBefore,
  235 |         );
  236 |         await capture(locale + '-' + suffix);
  237 |         await message.locator(prototype ? '.el-message__closeBtn' : 'button').click();
  238 |         await expect(message).toHaveCount(0);
  239 |       }
  240 |       await page.getByRole('button', { name: text.orders.sched.settings, exact: true }).click();
  241 |       const dialog = page.getByRole('dialog');
  242 |       await dialog.locator(prototype ? '.el-select' : '.ant-select').click();
  243 |       await page.getByRole('option', { name: text.orders.sched.unit.minute, exact: true }).click();
  244 |       await dialog.getByRole('spinbutton').fill('9');
  245 |       const beforeSettings = fixture('snapshot').schedule;
  246 |       await dialog.getByRole('button', { name: text.common.save, exact: true }).click();
  247 |       const intervalMessage = page.locator(
  248 |         prototype ? '.el-message--error' : '.ant-message-notice-error',
  249 |       );
  250 |       await expect(intervalMessage).toHaveText(
  251 |         text.orders.sched.intervalHint.replace('{{min}}', '10'),
  252 |       );
  253 |       await expect(dialog).toBeVisible();
  254 |       expect(fixture('snapshot').schedule).toEqual(beforeSettings);
  255 |       await capture(locale + '-interval-nine');
  256 |       await dialog.getByRole('button', { name: text.common.cancel, exact: true }).click();
  257 |       await expect(intervalMessage).toHaveCount(0);
  258 |       const sizeSuffix = locale === 'zh-CN' ? '条/页' : locale === 'en' ? '/page' : '/trang';
  259 |       for (const size of [100, 20]) {
> 260 |         const changed = page.waitForResponse((r) => {
      |                              ^ TimeoutError: page.waitForResponse: Timeout 15000ms exceeded while waiting for event "response"
  261 |           const address = new URL(r.url());
  262 |           return (
  263 |             r.request().method() === 'GET' &&
  264 |             address.pathname === (prototype ? '/api/orders' : '/api/v1/sn/orders') &&
  265 |             address.searchParams.get(prototype ? 'page_size' : 'size') === String(size)
  266 |           );
  267 |         });
  268 |         await page
  269 |           .locator(prototype ? '.el-pagination .el-select' : '.sn-order-pager .ant-select')
  270 |           .click();
  271 |         await page.getByRole('option', { name: String(size) + sizeSuffix, exact: true }).click();
  272 |         expect((await changed).ok()).toBe(true);
  273 |       }
  274 |       await page.locator(prototype ? '.el-pagination' : '.sn-order-pager').scrollIntoViewIfNeeded();
  275 |       await capture(locale + '-pager');
  276 |       await page.mouse.move(900, 400);
  277 |       await page.mouse.wheel(0, -1500);
  278 |       await expect.poll(() => page.locator('main').evaluate((el) => el.scrollTop)).toBe(0);
  279 |     }
  280 |     await language('简体中文');
  281 |     const initial = await saveSchedule(page, true, 60);
  282 |     await capture('schedule-owner-a');
  283 |     await login(other, otherName);
  284 |     const changed = await saveSchedule(other, true, 60);
  285 |     expect(changed[prototype ? 'next_run_at' : 'nextRunAt']).toBe(
  286 |       initial[prototype ? 'next_run_at' : 'nextRunAt'],
  287 |     );
  288 |     expect(changed[prototype ? 'updated_by' : 'ownerName']).toBe(otherName);
  289 |     await capture('schedule-owner-b', other);
  290 |     await saveSchedule(other, false, 1440);
  291 |     await page.getByRole('menuitem', { name: zh.nav.audit, exact: true }).click();
  292 |     await page.getByPlaceholder(zh.audit.operator, { exact: true }).fill(username);
  293 |     await page
  294 |       .locator(prototype ? '.toolbar .el-select' : '.sn-toolbar .ant-select')
  295 |       .nth(0)
  296 |       .click();
  297 |     await page.getByRole('option', { name: zh.actions.ORDER_SYNC, exact: true }).click();
  298 |     const pending = page.waitForResponse(
  299 |       (r) =>
  300 |         r.request().method() === 'GET' &&
  301 |         new URL(r.url()).pathname === (prototype ? '/api/audits' : '/api/v1/sn/audits/page') &&
  302 |         new URL(r.url()).searchParams.get('operator') === username,
  303 |     );
  304 |     await page.getByRole('button', { name: zh.common.search, exact: true }).click();
  305 |     const body = await (await pending).json();
  306 |     const facts = prototype ? body.items : body.data.items;
  307 |     expect(facts).toHaveLength(6);
  308 |     for (const fact of facts) {
  309 |       expect(fact[prototype ? 'error_msg' : 'errorMessage']).toBe(errZh.ORDER_BILL_REQUIRED);
  310 |       expect(fact.detail).toBe('POST /api/orders/sync');
  311 |       expect(fact[prototype ? 'qty' : 'quantity']).toBeNull();
  312 |     }
  313 |     await capture('audit-six-rejections');
  314 |     const rows = page.locator(prototype ? '.el-table__row' : '.ant-table-row');
  315 |     await expect(rows).toHaveCount(6);
  316 |     await rows
  317 |       .first()
  318 |       .locator(prototype ? '.el-table__expand-icon' : '.sn-audit-expand')
  319 |       .click();
  320 |     await expect(
  321 |       page
  322 |         .locator(prototype ? '.el-descriptions' : '.sn-audit-details')
  323 |         .getByText(errZh.ORDER_BILL_REQUIRED, { exact: true }),
  324 |     ).toBeVisible();
  325 |     await capture('audit-rejection-detail');
  326 |     expect(pageErrors).toEqual([]);
  327 |   } finally {
  328 |     await otherContext.close();
  329 |     writeFileSync(info.outputPath('states.json'), JSON.stringify(states, null, 2));
  330 |     writeFileSync(info.outputPath('writes.json'), JSON.stringify({ writes, pageErrors }, null, 2));
  331 |   }
  332 | });
  333 | 
```