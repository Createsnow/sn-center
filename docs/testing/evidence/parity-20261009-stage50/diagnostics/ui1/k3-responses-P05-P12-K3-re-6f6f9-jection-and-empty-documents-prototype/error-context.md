# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: k3-responses.spec.ts >> P05/P12 K3 response shapes, messages, later-page rejection and empty documents
- Location: e2e\parity\k3-responses.spec.ts:99:1

# Error details

```
Error: en-query-empty-message

expect(received).toBe(expected) // Object.is equality

Expected: "Kingdee query failed: "
Received: "Kingdee query failed:"
```

```
Error: vi-query-empty-message

expect(received).toBe(expected) // Object.is equality

Expected: "Truy vấn Kingdee thất bại: "
Received: "Truy vấn Kingdee thất bại:"
```

# Test source

```ts
  46  |   { label: 'query-object', operation: 'orders', body: {}, error: 'unexpected response' },
  47  |   { label: 'query-scalar', operation: 'orders', body: 'OK', error: 'unexpected response' },
  48  |   {
  49  |     label: 'query-message-values',
  50  |     operation: 'orders',
  51  |     body: failure([' \u0085组织权限不足\u0085 ', 123, false, null]),
  52  |     error: '组织权限不足; 123; false',
  53  |   },
  54  |   { label: 'query-empty-message', operation: 'orders', body: failure(['   ']), error: '' },
  55  |   {
  56  |     label: 'query-nested-encoded',
  57  |     operation: 'orders',
  58  |     body: [[failure(['查询权限不足'])]],
  59  |     encodeTwice: true,
  60  |     error: '查询权限不足',
  61  |   },
  62  |   { label: 'view-missing-status', operation: 'view', body: {}, error: 'View failed' },
  63  |   { label: 'view-fallback-message', operation: 'view', body: failure(), error: '查询失败' },
  64  |   { label: 'query-http-503', operation: 'orders', body: {}, httpStatus: 503, error: 'HTTP 503' },
  65  |   {
  66  |     label: 'query-later-page',
  67  |     operation: 'orders',
  68  |     body: [[failure(['后续页失败'])]],
  69  |     laterPage: true,
  70  |     error: '后续页失败',
  71  |   },
  72  |   { label: 'login-decimal', operation: 'login', body: { LoginResultType: 1.0 } },
  73  |   {
  74  |     label: 'login-empty-session',
  75  |     operation: 'login',
  76  |     body: { LoginResultType: 1, KDSVCSessionId: '   ' },
  77  |   },
  78  |   {
  79  |     label: 'view-decimal-missing',
  80  |     operation: 'view',
  81  |     body: { Result: { ResponseStatus: { IsSuccess: false, MsgCode: 10.0 } } },
  82  |     removes: true,
  83  |   },
  84  |   {
  85  |     label: 'view-empty-document',
  86  |     operation: 'view',
  87  |     body: { Result: { ResponseStatus: { IsSuccess: true }, Result: {} } },
  88  |     removes: true,
  89  |   },
  90  |   {
  91  |     label: 'view-empty-lines',
  92  |     operation: 'view',
  93  |     body: { Result: { ResponseStatus: { IsSuccess: true }, Result: { TreeEntity: [] } } },
  94  |     removes: true,
  95  |   },
  96  | ];
  97  | 
  98  | // 仅覆盖专用a48环境的合成K3协议，业务请求通过页面完成；失败不修改订单或分录快照。
  99  | test('P05/P12 K3 response shapes, messages, later-page rejection and empty documents', async ({
  100 |   page,
  101 |   request,
  102 | }, info) => {
  103 |   test.setTimeout(360_000);
  104 |   page.setDefaultTimeout(15_000);
  105 |   const prototype = info.project.name === 'prototype';
  106 |   const kind = prototype ? 'python' : 'java';
  107 |   const tag = process.env.ADTEK_K3_SHAPES_RUN!;
  108 |   expect(tag).toMatch(/^[0-9]{8,14}$/);
  109 |   const username = 'audit' + tag;
  110 |   const helper = (action: string) =>
  111 |     JSON.parse(
  112 |       execFileSync(
  113 |         resolve('../../sn-center-python/.venv/Scripts/python.exe'),
  114 |         ['-X', 'utf8', resolve('../tools/testing/parity-order-audit-fixture.py'), kind, action, tag],
  115 |         { windowsHide: true, encoding: 'utf8' },
  116 |       ),
  117 |     );
  118 |   helper('prepare');
  119 |   const password = readFileSync(
  120 |     resolve('../deploy/.secrets/comparison-20261008/' + kind + '-admin-password'),
  121 |     'utf8',
  122 |   ).trim();
  123 |   protectTestSecrets(password);
  124 |   const controlFile = resolve('../tools/.runtime/comparison-20261008/order-audits/k3-control.json');
  125 |   const control = (value: unknown) => {
  126 |     writeFileSync(controlFile + '.next', JSON.stringify(value));
  127 |     renameSync(controlFile + '.next', controlFile);
  128 |   };
  129 |   const observations: unknown[] = [];
  130 |   const pageErrors: string[] = [];
  131 |   page.on('pageerror', (error) => pageErrors.push(error.message));
  132 |   const messages = page.locator(prototype ? '.el-message' : '.ant-message-notice');
  133 |   const sync = async (label: string, text: typeof zh, expected: string, one: boolean, status: number) => {
  134 |     await expect(messages).toHaveCount(0);
  135 |     if (one) await page.getByPlaceholder(text.orders.billToSync, { exact: true }).fill('CMP-MO-001');
  136 |     const snapshot = helper('snapshot');
  137 |     const before = (await (await request.get('http://127.0.0.1:19091')).json()).wireRequests.length;
  138 |     const received = page.waitForResponse(
  139 |       (r) => r.request().method() === 'POST' && new URL(r.url()).pathname.endsWith('/orders/sync'),
  140 |     );
  141 |     await page.getByRole('button', { name: one ? text.orders.syncOne : text.orders.syncAll, exact: true }).click();
  142 |     const response = await received;
  143 |     await expect(messages).toHaveCount(1);
  144 |     const actual = await messages.innerText();
  145 |     expect.soft(response.status(), label).toBe(status);
> 146 |     expect.soft(actual, label).toBe(expected);
      |                                ^ Error: vi-query-empty-message
  147 |     await expect(page.locator('.sn-page-actions [aria-busy="true"]')).toHaveCount(0);
  148 |     await expect(page.locator('.ant-spin-spinning:visible,.el-loading-mask:visible')).toHaveCount(0);
  149 |     const after = helper('snapshot');
  150 |     if (status === 502) {
  151 |       expect.soft(after.orders, label + ' orders').toEqual(snapshot.orders);
  152 |       expect.soft(after.entries, label + ' entries').toEqual(snapshot.entries);
  153 |     }
  154 |     const traffic = (await (await request.get('http://127.0.0.1:19091')).json()).wireRequests.slice(before);
  155 |     const body = await response.json();
  156 |     observations.push({ label, status: response.status(), message: actual, body, traffic, orders: after.orders, entries: after.entries });
  157 |     await page.mouse.move(0, 0);
  158 |     await page.screenshot({ path: info.outputPath(label + '.png'), animations: 'disabled' });
  159 |     if (response.status() !== 200)
  160 |       await messages.locator(prototype ? '.el-message__closeBtn' : 'button').click();
  161 |     await expect(messages).toHaveCount(0);
  162 |     return { after, traffic };
  163 |   };
  164 |   try {
  165 |     control({});
  166 |     await page.goto((prototype ? 'http://127.0.0.1:8001' : 'http://127.0.0.1:15174') + '/login');
  167 |     await page.locator('input[autocomplete="username"]').fill(username);
  168 |     await page.locator('input[autocomplete="current-password"]').fill(password);
  169 |     await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
  170 |     await page.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
  171 |     await sync('initial-snapshot', zh, zh.orders.syncedAll.replace('{{bills}}', '3').replace('{{rows}}', '4'), false, 200);
  172 |     for (const [locale, language, text, errors] of [
  173 |       ['zh-CN', '简体中文', zh, errorsZh],
  174 |       ['en', 'English', en, errorsEn],
  175 |       ['vi', 'Tiếng Việt', vi, errorsVi],
  176 |     ] as const) {
  177 |       const languageControl = page.locator(prototype ? '.header .lang' : '.prototype-language .ant-select');
  178 |       if (!(await languageControl.innerText()).includes(language)) {
  179 |         const changed = page.waitForResponse(
  180 |           (r) => r.request().method() !== 'GET' && new URL(r.url()).pathname.endsWith(prototype ? '/auth/lang' : '/system/auth/language'),
  181 |         );
  182 |         await languageControl.click();
  183 |         await page.getByRole('option', { name: language, exact: true }).click();
  184 |         await changed;
  185 |       }
  186 |       for (const example of cases) {
  187 |         control({
  188 |           ...(example.laterPage ? { orderCount: 2000 } : {}),
  189 |           wire: { [example.operation]: { body: example.body, status: example.httpStatus ?? 200, encodeTwice: example.encodeTwice, fromStart: example.laterPage ? 2000 : 0 } },
  190 |         });
  191 |         const rejected = example.error !== undefined;
  192 |         const expected = rejected
  193 |           ? (example.operation === 'login' ? errors.K3_LOGIN_FAILED : errors.K3_QUERY_FAILED).replace('{{message}}', example.error!)
  194 |           : example.removes
  195 |             ? text.orders.syncedRemoved.replace('{{bill}}', 'CMP-MO-001')
  196 |             : text.orders.syncedOne.replace('{{bill}}', 'CMP-MO-001').replace('{{rows}}', '2');
  197 |         const observed = await sync(locale + '-' + example.label, text, expected, example.operation !== 'orders', rejected ? 502 : 200);
  198 |         if (example.laterPage)
  199 |           expect.soft(observed.traffic.filter((r: { operation: string }) => r.operation === 'orders').map((r: { start: number }) => r.start)).toEqual([0, 2000]);
  200 |         if (example.removes) {
  201 |           expect.soft(observed.after.orders.filter((r: { bill_no: string }) => r.bill_no === 'CMP-MO-001')).toHaveLength(0);
  202 |           control({});
  203 |           await sync(locale + '-' + example.label + '-restore', text, text.orders.syncedAll.replace('{{bills}}', '3').replace('{{rows}}', '4'), false, 200);
  204 |         }
  205 |       }
  206 |     }
  207 |     control({});
  208 |     const facts = helper('snapshot').audits.filter((r: Record<string, unknown>) => r.operator === username && ['ORDER_SYNC', 'sn:order:sync'].includes(String(r.action)) && ['FAIL', 'FAILED'].includes(String(r.result)));
  209 |     writeFileSync(info.outputPath('failure-audits.json'), JSON.stringify(facts, null, 2));
  210 |     expect.soft(facts).toHaveLength(39);
  211 |     await page.getByRole('menuitem', { name: vi.nav.audit, exact: true }).click();
  212 |     await page.getByPlaceholder(vi.audit.operator, { exact: true }).fill(username);
  213 |     await page.getByRole('button', { name: vi.common.search, exact: true }).click();
  214 |     await expect(page.locator('.ant-spin-spinning:visible,.el-loading-mask:visible')).toHaveCount(0);
  215 |     await page.screenshot({ path: info.outputPath('audit-actual-actions.png'), animations: 'disabled' });
  216 |     expect(pageErrors).toEqual([]);
  217 |   } finally {
  218 |     control({});
  219 |     writeFileSync(info.outputPath('observations.json'), JSON.stringify({ observations, pageErrors }, null, 2));
  220 |   }
  221 | });
  222 | 
```