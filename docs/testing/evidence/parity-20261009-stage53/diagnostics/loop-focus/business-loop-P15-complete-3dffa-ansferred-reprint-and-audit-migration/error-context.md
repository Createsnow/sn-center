# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: business-loop.spec.ts >> P15 complete UI business loop from order sync and rule creation to transferred reprint and audit
- Location: e2e\parity\business-loop.spec.ts:10:1

# Error details

```
Error: expect(received).toBe(expected) // Object.is equality

Expected: true
Received: false
```

# Test source

```ts
  176 |     await expect(rows().filter({ hasText: 'LOOP_' + run })).toHaveCount(1);
  177 |     await capture('02-rule-created');
  178 |     await go(zh.nav.orders);
  179 |     await rows().filter({ hasText: bill }).click();
  180 |     await page.getByRole('button', { name: zh.orders.toGenerate, exact: true }).click();
  181 |     await stat(zh.orders.quota, 3);
  182 |     await field(page.locator('main'), zh.generate.qty).fill('3');
  183 |     await field(page.locator('main'), zh.generate.qty).blur();
  184 |     await page.getByRole('button', { name: zh.generate.preview, exact: true }).click();
  185 |     await expect(
  186 |       page.getByRole('button', { name: zh.generate.generate, exact: true }),
  187 |     ).toBeEnabled();
  188 |     await expect(page.getByText(new RegExp(sns[0] + '\\s*~\\s*' + sns[2])).first()).toBeVisible();
  189 |     await capture('03-preview');
  190 |     await page.getByRole('button', { name: zh.generate.generate, exact: true }).click();
  191 |     await confirm();
  192 |     await stat(zh.orders.generated, 3);
  193 |     await stat(zh.orders.quota, 0);
  194 |     await stat(zh.generate.pendingAlloc, 3);
  195 |     await capture('04-generated');
  196 |     await field(page.locator('main'), zh.generate.allocQty).fill('3');
  197 |     await field(page.locator('main'), zh.generate.allocQty).blur();
  198 |     await page.getByRole('button', { name: zh.generate.allocate, exact: true }).click();
  199 |     await confirm();
  200 |     await stat(zh.generate.allocated, 3);
  201 |     await stat(zh.generate.pendingAlloc, 0);
  202 |     await query('05-allocated', 'TO_ACQUIRE', 'CMP-A');
  203 |     await takePrint('比较测试甲厂', '06-first-print');
  204 |     await query('07-printed', 'PRINTED', 'CMP-A');
  205 |     await go(zh.nav.transfer);
  206 |     await page.getByRole('tab', { name: zh.transfer.directTab, exact: true }).click();
  207 |     if (source) {
  208 |       for (const [label, name] of [
  209 |         [zh.transfer.fromFactory, '比较测试甲厂（CMP-A）'],
  210 |         [zh.transfer.toFactory, '比较测试乙厂（CMP-B）'],
  211 |       ]) {
  212 |         const combo = item(panel(), label!).getByRole('combobox');
  213 |         await combo.click();
  214 |         const id = await combo.getAttribute('aria-controls');
  215 |         await page.locator(`[id="${id}"]`).getByRole('option', { name, exact: true }).click();
  216 |       }
  217 |       await field(panel(), 'PI').fill(pi);
  218 |       await field(panel(), zh.transfer.toPi).fill(pi);
  219 |       await field(panel(), zh.transfer.reason).fill('完整界面闭环 ' + run);
  220 |     } else {
  221 |       await panel().getByRole('combobox', { name: ui.snPages.common.factory, exact: true }).click();
  222 |       await page.getByRole('option', { name: '比较测试甲厂（CMP-A）', exact: true }).click();
  223 |       await page.getByRole('textbox', { name: ui.snTransfer.source + ' PI', exact: true }).fill(pi);
  224 |       const target = page.getByRole('combobox', { name: ui.snTransfer.toFactory, exact: true });
  225 |       await target.click();
  226 |       const id = await target.getAttribute('aria-controls');
  227 |       await page
  228 |         .locator('.ant-select-dropdown')
  229 |         .filter({ has: page.locator(`[id="${id}"]`) })
  230 |         .locator('.ant-select-item-option-content')
  231 |         .getByText('比较测试乙厂（CMP-B）', { exact: true })
  232 |         .click();
  233 |       await page.getByRole('textbox', { name: ui.snTransfer.toPi, exact: true }).fill(pi);
  234 |       await page
  235 |         .getByRole('textbox', { name: ui.snTransfer.reason, exact: true })
  236 |         .fill('完整界面闭环 ' + run);
  237 |     }
  238 |     await expect(panel()).toContainText('可转移 3 枚（范围内共 3 枚）');
  239 |     await page.getByRole('button', { name: zh.transfer.directSubmit, exact: true }).click();
  240 |     await confirm();
  241 |     await expect(page.getByRole('tab', { name: zh.transfer.records, exact: true })).toHaveAttribute(
  242 |       'aria-selected',
  243 |       'true',
  244 |     );
  245 |     await query('08-transferred', 'TO_ACQUIRE', 'CMP-B');
  246 |     await takePrint('比较测试乙厂', '09-second-print');
  247 |     await query('10-reprinted', 'PRINTED', 'CMP-B');
  248 |     await go(zh.nav.audit);
  249 |     await page.getByPlaceholder(zh.audit.keyword, { exact: true }).fill(pi);
  250 |     const response = page.waitForResponse(
  251 |       (r) =>
  252 |         r.request().method() === 'GET' &&
  253 |         new URL(r.url()).pathname === (source ? '/api/audits' : '/api/v1/sn/audits/page') &&
  254 |         new URL(r.url()).searchParams.get('keyword') === pi,
  255 |     );
  256 |     await page.getByRole('button', { name: zh.common.search, exact: true }).click();
  257 |     const raw = await response;
  258 |     expect(raw.status()).toBe(200);
  259 |     const body = await raw.json();
  260 |     const audits = (source ? body.items : body.data.items) as Record<string, unknown>[];
  261 |     facts.push({ stage: 'audit', audits });
  262 |     const actions: Record<string, string> = {
  263 |       'sn:generation:execute': 'SN_GENERATE',
  264 |       'sn:allocation:execute': 'SN_ALLOCATE',
  265 |       'sn:acquire:take': 'SN_ACQUIRE',
  266 |       'sn:print:execute': 'SN_PRINT',
  267 |       'sn:transfer:direct': 'TRANSFER_DIRECT',
  268 |     };
  269 |     for (const action of [
  270 |       'SN_GENERATE',
  271 |       'SN_ALLOCATE',
  272 |       'SN_ACQUIRE',
  273 |       'SN_PRINT',
  274 |       'TRANSFER_DIRECT',
  275 |     ]) {
> 276 |       expect(audits.some((r) => (actions[String(r.action)] ?? r.action) === action)).toBe(true);
      |                                                                                      ^ Error: expect(received).toBe(expected) // Object.is equality
  277 |     }
  278 |     await capture('11-audit');
  279 |     expect(errors).toEqual([]);
  280 |   } finally {
  281 |     writeExtra(original);
  282 |     try {
  283 |       if (loggedIn) {
  284 |         await page.goto(origin + '/orders');
  285 |         await sync();
  286 |       }
  287 |     } finally {
  288 |       writeFileSync(
  289 |         info.outputPath('observations.json'),
  290 |         JSON.stringify({ pi, bill, sns, files, facts, errors }, null, 2),
  291 |       );
  292 |     }
  293 |   }
  294 | });
  295 | 
```