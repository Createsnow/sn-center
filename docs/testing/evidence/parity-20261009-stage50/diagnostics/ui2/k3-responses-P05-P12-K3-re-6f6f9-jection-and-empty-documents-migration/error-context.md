# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: k3-responses.spec.ts >> P05/P12 K3 response shapes, messages, later-page rejection and empty documents
- Location: e2e\parity\k3-responses.spec.ts:99:1

# Error details

```
Error: expect(received).toHaveLength(expected)

Expected length: 0
Received length: 1
Received array:  [{"active": 0, "bill_no": "CMP-MO-001", "customer": "CMP-CUSTOMER", "factory_code": "CMP-A", "factory_id": 1, "id": 1, "line_count": 2, "org_code": "CMP-A", "org_id": "101", "org_name": "比较测试甲厂", "pi": "CMP-PI-001", "po": "CMP-PO", "quantity_ready": 1, "snapshot_version": 52, "source_account": "COMPARISON_SYNTHETIC", "source_bill_id": "9007199254740993", "source_system": "K3", "statuses": "1", "synced_at": "2026-10-09 09:52:26.432377", "total_qty": "10.0000"}]
```

```
Error: expect(received).toHaveLength(expected)

Expected length: 0
Received length: 1
Received array:  [{"active": 0, "bill_no": "CMP-MO-001", "customer": "CMP-CUSTOMER", "factory_code": "CMP-A", "factory_id": 1, "id": 1, "line_count": 2, "org_code": "CMP-A", "org_id": "101", "org_name": "比较测试甲厂", "pi": "CMP-PI-001", "po": "CMP-PO", "quantity_ready": 1, "snapshot_version": 55, "source_account": "COMPARISON_SYNTHETIC", "source_bill_id": "9007199254740993", "source_system": "K3", "statuses": "1", "synced_at": "2026-10-09 09:52:34.426834", "total_qty": "10.0000"}]
```

```
Error: expect(received).toHaveLength(expected)

Expected length: 0
Received length: 1
Received array:  [{"active": 0, "bill_no": "CMP-MO-001", "customer": "CMP-CUSTOMER", "factory_code": "CMP-A", "factory_id": 1, "id": 1, "line_count": 2, "org_code": "CMP-A", "org_id": "101", "org_name": "比较测试甲厂", "pi": "CMP-PI-001", "po": "CMP-PO", "quantity_ready": 1, "snapshot_version": 58, "source_account": "COMPARISON_SYNTHETIC", "source_bill_id": "9007199254740993", "source_system": "K3", "statuses": "1", "synced_at": "2026-10-09 09:52:42.567924", "total_qty": "10.0000"}]
```

```
Error: expect(received).toHaveLength(expected)

Expected length: 0
Received length: 1
Received array:  [{"active": 0, "bill_no": "CMP-MO-001", "customer": "CMP-CUSTOMER", "factory_code": "CMP-A", "factory_id": 1, "id": 1, "line_count": 2, "org_code": "CMP-A", "org_id": "101", "org_name": "比较测试甲厂", "pi": "CMP-PI-001", "po": "CMP-PO", "quantity_ready": 1, "snapshot_version": 63, "source_account": "COMPARISON_SYNTHETIC", "source_bill_id": "9007199254740993", "source_system": "K3", "statuses": "1", "synced_at": "2026-10-09 09:53:13.804493", "total_qty": "10.0000"}]
```

```
Error: expect(received).toHaveLength(expected)

Expected length: 0
Received length: 1
Received array:  [{"active": 0, "bill_no": "CMP-MO-001", "customer": "CMP-CUSTOMER", "factory_code": "CMP-A", "factory_id": 1, "id": 1, "line_count": 2, "org_code": "CMP-A", "org_id": "101", "org_name": "比较测试甲厂", "pi": "CMP-PI-001", "po": "CMP-PO", "quantity_ready": 1, "snapshot_version": 66, "source_account": "COMPARISON_SYNTHETIC", "source_bill_id": "9007199254740993", "source_system": "K3", "statuses": "1", "synced_at": "2026-10-09 09:53:21.409762", "total_qty": "10.0000"}]
```

```
Error: expect(received).toHaveLength(expected)

Expected length: 0
Received length: 1
Received array:  [{"active": 0, "bill_no": "CMP-MO-001", "customer": "CMP-CUSTOMER", "factory_code": "CMP-A", "factory_id": 1, "id": 1, "line_count": 2, "org_code": "CMP-A", "org_id": "101", "org_name": "比较测试甲厂", "pi": "CMP-PI-001", "po": "CMP-PO", "quantity_ready": 1, "snapshot_version": 69, "source_account": "COMPARISON_SYNTHETIC", "source_bill_id": "9007199254740993", "source_system": "K3", "statuses": "1", "synced_at": "2026-10-09 09:53:29.078923", "total_qty": "10.0000"}]
```

```
Error: expect(received).toHaveLength(expected)

Expected length: 0
Received length: 1
Received array:  [{"active": 0, "bill_no": "CMP-MO-001", "customer": "CMP-CUSTOMER", "factory_code": "CMP-A", "factory_id": 1, "id": 1, "line_count": 2, "org_code": "CMP-A", "org_id": "101", "org_name": "比较测试甲厂", "pi": "CMP-PI-001", "po": "CMP-PO", "quantity_ready": 1, "snapshot_version": 74, "source_account": "COMPARISON_SYNTHETIC", "source_bill_id": "9007199254740993", "source_system": "K3", "statuses": "1", "synced_at": "2026-10-09 09:53:59.508050", "total_qty": "10.0000"}]
```

```
Error: expect(received).toHaveLength(expected)

Expected length: 0
Received length: 1
Received array:  [{"active": 0, "bill_no": "CMP-MO-001", "customer": "CMP-CUSTOMER", "factory_code": "CMP-A", "factory_id": 1, "id": 1, "line_count": 2, "org_code": "CMP-A", "org_id": "101", "org_name": "比较测试甲厂", "pi": "CMP-PI-001", "po": "CMP-PO", "quantity_ready": 1, "snapshot_version": 77, "source_account": "COMPARISON_SYNTHETIC", "source_bill_id": "9007199254740993", "source_system": "K3", "statuses": "1", "synced_at": "2026-10-09 09:54:07.417525", "total_qty": "10.0000"}]
```

```
Error: expect(received).toHaveLength(expected)

Expected length: 0
Received length: 1
Received array:  [{"active": 0, "bill_no": "CMP-MO-001", "customer": "CMP-CUSTOMER", "factory_code": "CMP-A", "factory_id": 1, "id": 1, "line_count": 2, "org_code": "CMP-A", "org_id": "101", "org_name": "比较测试甲厂", "pi": "CMP-PI-001", "po": "CMP-PO", "quantity_ready": 1, "snapshot_version": 80, "source_account": "COMPARISON_SYNTHETIC", "source_bill_id": "9007199254740993", "source_system": "K3", "statuses": "1", "synced_at": "2026-10-09 09:54:14.838096", "total_qty": "10.0000"}]
```

# Test source

```ts
  165 |       0,
  166 |     );
  167 |     const after = helper('snapshot');
  168 |     if (status === 502) {
  169 |       expect.soft(after.orders, label + ' orders').toEqual(snapshot.orders);
  170 |       expect.soft(after.entries, label + ' entries').toEqual(snapshot.entries);
  171 |     }
  172 |     const traffic = (
  173 |       await (await request.get('http://127.0.0.1:19091')).json()
  174 |     ).wireRequests.filter((r: { sequence: number }) => r.sequence > before);
  175 |     const body = await response.json();
  176 |     observations.push({
  177 |       label,
  178 |       status: response.status(),
  179 |       message: actual,
  180 |       body,
  181 |       traffic,
  182 |       orders: after.orders,
  183 |       entries: after.entries,
  184 |     });
  185 |     await page.mouse.move(0, 0);
  186 |     await page.screenshot({ path: info.outputPath(label + '.png'), animations: 'disabled' });
  187 |     if (response.status() !== 200)
  188 |       await messages.locator(prototype ? '.el-message__closeBtn' : 'button').click();
  189 |     await expect(messages).toHaveCount(0);
  190 |     return { after, traffic };
  191 |   };
  192 |   try {
  193 |     control({});
  194 |     await page.goto((prototype ? 'http://127.0.0.1:8001' : 'http://127.0.0.1:15174') + '/login');
  195 |     await page.locator('input[autocomplete="username"]').fill(username);
  196 |     await page.locator('input[autocomplete="current-password"]').fill(password);
  197 |     await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
  198 |     await page.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
  199 |     await sync(
  200 |       'initial-snapshot',
  201 |       zh,
  202 |       zh.orders.syncedAll.replace('{{bills}}', '3').replace('{{rows}}', '4'),
  203 |       false,
  204 |       200,
  205 |     );
  206 |     for (const [locale, language, text, errors] of [
  207 |       ['zh-CN', '简体中文', zh, errorsZh],
  208 |       ['en', 'English', en, errorsEn],
  209 |       ['vi', 'Tiếng Việt', vi, errorsVi],
  210 |     ] as const) {
  211 |       const languageControl = page.locator(
  212 |         prototype ? '.header .lang' : '.prototype-language .ant-select',
  213 |       );
  214 |       if (!(await languageControl.innerText()).includes(language)) {
  215 |         const changed = page.waitForResponse(
  216 |           (r) =>
  217 |             r.request().method() !== 'GET' &&
  218 |             new URL(r.url()).pathname.endsWith(prototype ? '/auth/lang' : '/system/auth/language'),
  219 |         );
  220 |         await languageControl.click();
  221 |         await page.getByRole('option', { name: language, exact: true }).click();
  222 |         await changed;
  223 |       }
  224 |       for (const example of cases) {
  225 |         control({
  226 |           ...(example.laterPage ? { orderCount: 2000 } : {}),
  227 |           wire: {
  228 |             [example.operation]: {
  229 |               body: example.body,
  230 |               status: example.httpStatus ?? 200,
  231 |               encodeTwice: example.encodeTwice,
  232 |               fromStart: example.laterPage ? 2000 : 0,
  233 |             },
  234 |           },
  235 |         });
  236 |         const rejected = example.error !== undefined;
  237 |         const expected = rejected
  238 |           ? (example.operation === 'login'
  239 |               ? errors.K3_LOGIN_FAILED
  240 |               : errors.K3_QUERY_FAILED
  241 |             ).replace('{{message}}', example.error!)
  242 |           : example.removes
  243 |             ? text.orders.syncedRemoved.replace('{{bill}}', 'CMP-MO-001')
  244 |             : text.orders.syncedOne.replace('{{bill}}', 'CMP-MO-001').replace('{{rows}}', '2');
  245 |         const observed = await sync(
  246 |           locale + '-' + example.label,
  247 |           text,
  248 |           expected,
  249 |           example.operation !== 'orders',
  250 |           rejected ? 502 : 200,
  251 |         );
  252 |         if (example.laterPage)
  253 |           expect
  254 |             .soft(
  255 |               observed.traffic
  256 |                 .filter((r: { operation: string }) => r.operation === 'orders')
  257 |                 .map((r: { start: number }) => r.start),
  258 |             )
  259 |             .toEqual([0, 2000]);
  260 |         if (example.removes) {
  261 |           expect
  262 |             .soft(
  263 |               observed.after.orders.filter((r: { bill_no: string }) => r.bill_no === 'CMP-MO-001'),
  264 |             )
> 265 |             .toHaveLength(0);
      |              ^ Error: expect(received).toHaveLength(expected)
  266 |           control({});
  267 |           await sync(
  268 |             locale + '-' + example.label + '-restore',
  269 |             text,
  270 |             text.orders.syncedAll.replace('{{bills}}', '3').replace('{{rows}}', '4'),
  271 |             false,
  272 |             200,
  273 |           );
  274 |         }
  275 |       }
  276 |     }
  277 |     control({});
  278 |     const facts = helper('snapshot').audits.filter(
  279 |       (r: Record<string, unknown>) =>
  280 |         r.operator === username &&
  281 |         ['ORDER_SYNC', 'sn:order:sync'].includes(String(r.action)) &&
  282 |         ['FAIL', 'FAILED'].includes(String(r.result)),
  283 |     );
  284 |     writeFileSync(info.outputPath('failure-audits.json'), JSON.stringify(facts, null, 2));
  285 |     expect.soft(facts).toHaveLength(39);
  286 |     await page.getByRole('menuitem', { name: vi.nav.audit, exact: true }).click();
  287 |     await page.getByPlaceholder(vi.audit.operator, { exact: true }).fill(username);
  288 |     const filters = page.locator(prototype ? '.toolbar .el-select' : '.sn-toolbar .ant-select');
  289 |     await filters.nth(0).click();
  290 |     await page.getByRole('option', { name: vi.actions.ORDER_SYNC, exact: true }).click();
  291 |     await filters.nth(1).click();
  292 |     await page.getByRole('option', { name: vi.audit.fail, exact: true }).click();
  293 |     const auditReady = () =>
  294 |       page.waitForResponse(
  295 |         (r) =>
  296 |           r.request().method() === 'GET' &&
  297 |           new URL(r.url()).pathname === (prototype ? '/api/audits' : '/api/v1/sn/audits/page') &&
  298 |           new URL(r.url()).searchParams.get('operator') === username,
  299 |       );
  300 |     const searched = auditReady();
  301 |     await page.getByRole('button', { name: vi.common.search, exact: true }).click();
  302 |     await searched;
  303 |     await page
  304 |       .locator(
  305 |         prototype ? '.el-pagination__sizes .el-select' : '.ant-pagination-options .ant-select',
  306 |       )
  307 |       .click();
  308 |     const resized = auditReady();
  309 |     await page.getByRole('option', { name: /^100\s*\// }).click();
  310 |     const auditBody = await (await resized).json();
  311 |     const items = prototype ? auditBody.items : auditBody.data.items;
  312 |     expect(items).toHaveLength(39);
  313 |     const expectedMessages = cases
  314 |       .filter((example) => example.error !== undefined)
  315 |       .map((example) =>
  316 |         (example.operation === 'login'
  317 |           ? errorsZh.K3_LOGIN_FAILED
  318 |           : errorsZh.K3_QUERY_FAILED
  319 |         ).replace('{{message}}', example.error!),
  320 |       );
  321 |     expect(
  322 |       items.map((r: Record<string, unknown>) => r[prototype ? 'error_msg' : 'errorMessage']).sort(),
  323 |     ).toEqual([...expectedMessages, ...expectedMessages, ...expectedMessages].sort());
  324 |     const rows = page.locator(prototype ? '.el-table__row' : '.ant-table-row');
  325 |     await expect(rows).toHaveCount(39);
  326 |     const details: unknown[] = [];
  327 |     for (let i = 0; i < items.length; i++) {
  328 |       const item = items[i];
  329 |       expect(item.operator).toBe(username);
  330 |       expect(item.action).toBe(prototype ? 'ORDER_SYNC' : 'sn:order:sync');
  331 |       expect(item.result).toBe(prototype ? 'FAIL' : 'FAILED');
  332 |       expect(item.source).toBe('PAGE');
  333 |       expect(item.detail).toBe('POST /api/orders/sync');
  334 |       expect(item[prototype ? 'qty' : 'quantity']).toBeNull();
  335 |       const expand = rows.nth(i).locator(prototype ? '.el-table__expand-icon' : '.sn-audit-expand');
  336 |       await expand.click();
  337 |       const detail = page
  338 |         .locator(prototype ? '.el-descriptions' : '.sn-audit-details')
  339 |         .filter({ visible: true });
  340 |       await expect(
  341 |         detail.getByText(item[prototype ? 'error_msg' : 'errorMessage'], { exact: true }),
  342 |       ).toBeVisible();
  343 |       await expect(detail.getByText(item.detail, { exact: true })).toBeVisible();
  344 |       details.push({ item, visible: await detail.innerText() });
  345 |       await page.screenshot({
  346 |         path: info.outputPath('audit-detail-' + i + '.png'),
  347 |         animations: 'disabled',
  348 |       });
  349 |       await expand.click();
  350 |     }
  351 |     writeFileSync(info.outputPath('audit-visible-details.json'), JSON.stringify(details, null, 2));
  352 |     await expect(page.locator('.ant-spin-spinning:visible,.el-loading-mask:visible')).toHaveCount(
  353 |       0,
  354 |     );
  355 |     await page.screenshot({
  356 |       path: info.outputPath('audit-actual-actions.png'),
  357 |       animations: 'disabled',
  358 |     });
  359 |     expect(pageErrors).toEqual([]);
  360 |   } finally {
  361 |     control({});
  362 |     writeFileSync(
  363 |       info.outputPath('observations.json'),
  364 |       JSON.stringify({ observations, pageErrors }, null, 2),
  365 |     );
```