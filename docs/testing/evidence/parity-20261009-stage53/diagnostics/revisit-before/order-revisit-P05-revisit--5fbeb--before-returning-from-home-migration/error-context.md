# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: order-revisit.spec.ts >> P05 revisit: another page updates orders and schedule before returning from home
- Location: e2e\parity\order-revisit.spec.ts:10:1

# Error details

```
Error: expect(received).toEqual(expected) // deep equality

- Expected  - 1
+ Received  + 1

  Array [
+   "3",
    "4",
-   "5",
  ]
```

```
Error: expect(received).toBe(expected) // Object.is equality

Expected: "2"
Received: "1"
```

```
Error: expect(received).toBe(expected) // Object.is equality

Expected: "小时"
Received: "天"
```

```
Error: expect(received).toBe(expected) // Object.is equality

Expected: true
Received: false
```

# Test source

```ts
  78  |     await target
  79  |       .getByRole('dialog')
  80  |       .locator(prototype ? '.el-select' : '.ant-select')
  81  |       .click();
  82  |     await target.getByRole('option', { name: zh.orders.sched.unit.minute, exact: true }).click();
  83  |     await target.getByRole('spinbutton').fill(String(minutes));
  84  |     const response = target.waitForResponse(
  85  |       (r) =>
  86  |         r.request().method() === 'PUT' && new URL(r.url()).pathname === prefix + '/sync-schedule',
  87  |     );
  88  |     await target
  89  |       .getByRole('dialog')
  90  |       .getByRole('button', { name: zh.common.save, exact: true })
  91  |       .click();
  92  |     expect((await response).status()).toBe(200);
  93  |     await expect(target.getByRole('dialog')).toBeHidden();
  94  |     await expect(messages(target)).toHaveCount(1);
  95  |     await expect(messages(target)).toHaveCount(0);
  96  |   };
  97  |   const capture = async (label: string) => {
  98  |     await settleOrderPage(page);
  99  |     const values = await page
  100 |       .locator(prototype ? '.meta > span > b' : '.sn-order-meta > span > b')
  101 |       .allTextContents();
  102 |     const amount = (await page.getByRole('spinbutton').count())
  103 |       ? await page.getByRole('spinbutton').inputValue()
  104 |       : null;
  105 |     const unit = (await page.getByRole('dialog').count())
  106 |       ? await page
  107 |           .getByRole('dialog')
  108 |           .locator(prototype ? '.el-select' : '.ant-select')
  109 |           .innerText()
  110 |       : null;
  111 |     observations.push({
  112 |       label,
  113 |       values,
  114 |       amount,
  115 |       unit,
  116 |       schedule: fixture('snapshot').schedule,
  117 |       at: Date.now(),
  118 |     });
  119 |     await page.screenshot({ path: info.outputPath(label + '.png'), animations: 'disabled' });
  120 |     return { values, amount, unit };
  121 |   };
  122 |   try {
  123 |     await page.goto(origin + '/login');
  124 |     await page.locator('input[autocomplete=username]').fill('audit' + run);
  125 |     await page.locator('input[autocomplete=current-password]').fill(password);
  126 |     await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
  127 |     await page.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
  128 |     await expect(page.getByText('CMP-MO-002', { exact: true })).toBeVisible();
  129 |     await expect(
  130 |       page.getByRole('button', { name: zh.orders.sched.settings, exact: true }),
  131 |     ).toBeVisible();
  132 |     const initial = await capture('initial');
  133 |     expect(initial.values.slice(1)).toEqual(['3', '4']);
  134 |     await page.getByRole('menuitem', { name: zh.nav.dashboard, exact: true }).click();
  135 |     peer = await context.newPage();
  136 |     peer.on('pageerror', (e) => errors.push(e.message));
  137 |     await peer.goto(origin + '/orders');
  138 |     await expect(peer.getByText('CMP-MO-002', { exact: true })).toBeVisible();
  139 |     const bill = 'CMP-REVISIT-' + run;
  140 |     changed = true;
  141 |     writeExtras(
  142 |       JSON.stringify([
  143 |         {
  144 |           'F_ora_kh.FNumber': 'CMP-CUSTOMER',
  145 |           FBillNo: bill,
  146 |           F_HX_PO: 'CMP-PO',
  147 |           F_HX_PI: 'CMP-PI-002',
  148 |           'FMaterialId.FNumber': 'CMP-MAT-REVISIT',
  149 |           'FMaterialId.FName': '返回页面刷新验证',
  150 |           FQty: '1',
  151 |           FStatus: '1',
  152 |           FSaleOrderNo: 'CMP-SO',
  153 |           'FPrdOrgId.FName': '比较测试乙厂',
  154 |           FID: '9007199254741053',
  155 |           FTreeEntity_FEntryId: '5301',
  156 |           FPrdOrgId: '102',
  157 |           'FPrdOrgId.FNumber': 'CMP-B',
  158 |         },
  159 |       ]),
  160 |     );
  161 |     await sync(peer);
  162 |     await expect(peer.getByText(bill, { exact: true })).toBeVisible();
  163 |     await schedule(peer, 120);
  164 |     const returnedAt = Date.now();
  165 |     await page.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
  166 |     await expect(page.getByText(bill, { exact: true })).toBeVisible();
  167 |     const returned = await capture('returned');
  168 |     await page.getByRole('button', { name: zh.orders.sched.settings, exact: true }).click();
  169 |     const editor = await capture('schedule-after-return');
  170 |     expect.soft(returned.values.slice(1)).toEqual(['4', '5']);
  171 |     expect.soft(editor.amount).toBe('2');
  172 |     expect.soft(editor.unit?.trim()).toBe(zh.orders.sched.unit.hour);
  173 |     expect(
  174 |       requests.some((r) => {
  175 |         const value = r as { at: number; path: string };
  176 |         return value.at >= returnedAt && value.path === prefix + '/meta';
  177 |       }),
> 178 |     ).toBe(true);
      |       ^ Error: expect(received).toBe(expected) // Object.is equality
  179 |     await page
  180 |       .getByRole('dialog')
  181 |       .getByRole('button', { name: zh.common.cancel, exact: true })
  182 |       .click();
  183 |     expect(errors).toEqual([]);
  184 |   } finally {
  185 |     writeExtras(original);
  186 |     try {
  187 |       if (peer && changed) {
  188 |         await sync(peer);
  189 |         await schedule(peer, 1440);
  190 |       }
  191 |     } finally {
  192 |       await peer?.close();
  193 |       writeFileSync(
  194 |         info.outputPath('observations.json'),
  195 |         JSON.stringify({ observations, requests, errors }, null, 2),
  196 |       );
  197 |     }
  198 |   }
  199 | });
  200 | 
```