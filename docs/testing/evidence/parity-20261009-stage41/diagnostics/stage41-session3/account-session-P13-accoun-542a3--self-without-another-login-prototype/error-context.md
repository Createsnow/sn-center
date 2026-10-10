# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: account-session.spec.ts >> P13 account sessions: edit name, role, factory and self without another login
- Location: e2e\parity\account-session.spec.ts:9:1

# Error details

```
Error: expect(received).toBe(expected) // Object.is equality

Expected: true
Received: false
```

# Test source

```ts
  78  |     await expect(rows).toHaveCount(1);
  79  |     return rows.first();
  80  |   };
  81  |   const role = async (tab: Page, value: 'admin' | 'query' | 'factory_operator') => {
  82  |     await tab
  83  |       .getByRole('dialog')
  84  |       .locator(prototype ? '.el-radio-button' : '.ant-radio-button-wrapper')
  85  |       .filter({ hasText: zh.roles[value] })
  86  |       .click();
  87  |   };
  88  |   const factory = async (tab: Page, code: 'A' | 'B') => {
  89  |     await tab
  90  |       .getByRole('dialog')
  91  |       .locator(prototype ? '.el-select' : '.ant-select')
  92  |       .click();
  93  |     await tab
  94  |       .getByRole('option', {
  95  |         name: `比较测试${code === 'A' ? '甲' : '乙'}厂（CMP-${code}）`,
  96  |         exact: true,
  97  |       })
  98  |       .click();
  99  |   };
  100 |   const inputs = (tab: Page) =>
  101 |     tab
  102 |       .getByRole('dialog')
  103 |       .locator(
  104 |         prototype
  105 |           ? '.el-form-item .el-input__inner:not([role="combobox"]):not([type="password"])'
  106 |           : '.ant-form-item input.ant-input:not([type="password"])',
  107 |       );
  108 |   const save = async (tab: Page, creating = false) => {
  109 |     const pending = tab.waitForResponse(
  110 |       (r) =>
  111 |         ['POST', 'PUT'].includes(r.request().method()) &&
  112 |         /\/(?:sn\/)?users(?:\/[^/]+(?:\/update)?)?$/.test(new URL(r.url()).pathname),
  113 |     );
  114 |     await tab
  115 |       .getByRole('dialog')
  116 |       .getByRole('button', { name: zh.common.save, exact: true })
  117 |       .click();
  118 |     const response = await pending;
  119 |     expect(response.status()).toBe(200);
  120 |     if (creating) {
  121 |       const body = await response.json();
  122 |       state.created.push(String(prototype ? body.id : body.data.id));
  123 |       writeFileSync(path, JSON.stringify(state));
  124 |     }
  125 |     await expect(tab.getByRole('dialog')).toBeHidden();
  126 |   };
  127 |   const edit = async (
  128 |     tab: Page,
  129 |     name: string,
  130 |     value: 'admin' | 'query' | 'factory_operator',
  131 |     code?: 'A' | 'B',
  132 |   ) => {
  133 |     const row = await search(tab);
  134 |     await row.getByRole('button', { name: zh.common.edit, exact: true }).click();
  135 |     await inputs(tab).nth(1).fill(name);
  136 |     await role(tab, value);
  137 |     if (code) await factory(tab, code);
  138 |     await save(tab);
  139 |   };
  140 |   const capture = async (
  141 |     name: string,
  142 |     displayName: string,
  143 |     value: 'admin' | 'query' | 'factory_operator',
  144 |     bound?: 'A' | 'B',
  145 |   ) => {
  146 |     await expect(member.locator(prototype ? '.who-name' : '.prototype-user-name')).toHaveText(
  147 |       `${displayName} · ${zh.roles[value]}`,
  148 |     );
  149 |     const menu = member.getByRole('menuitem');
  150 |     const names = await menu.allTextContents();
  151 |     expect(names.some((n) => n.trim() === zh.nav.users)).toBe(value === 'admin');
  152 |     expect(names.some((n) => n.trim() === zh.nav.acquire)).toBe(true);
  153 |     expect(names.some((n) => n.trim() === zh.nav.generate)).toBe(value === 'admin');
  154 |     if (bound)
  155 |       await expect(
  156 |         member.locator(prototype ? '.header .fac' : '.prototype-header .prototype-factory-tag'),
  157 |       ).toContainText(`比较测试${bound === 'A' ? '甲' : '乙'}厂`);
  158 |     await expect(member.locator('main').getByRole('heading').first()).toBeVisible();
  159 |     await expect(
  160 |       member.locator('.ant-spin-spinning:visible, .el-loading-mask:visible'),
  161 |     ).toHaveCount(0);
  162 |     let queryRows: string[][] | undefined;
  163 |     if (new URL(member.url()).pathname === '/query') {
  164 |       const rows = member.locator(
  165 |         prototype
  166 |           ? '.el-table__body tbody tr'
  167 |           : '.ant-table-tbody > tr:not(.ant-table-measure-row):not(.ant-table-placeholder)',
  168 |       );
  169 |       await expect.poll(() => rows.count()).toBeGreaterThan(0);
  170 |       queryRows = await rows.evaluateAll((elements) =>
  171 |         elements.map((row) =>
  172 |           [...row.querySelectorAll('td')].slice(0, 8).map((cell) => cell.textContent?.trim() ?? ''),
  173 |         ),
  174 |       );
  175 |       if (bound)
  176 |         expect(
  177 |           queryRows.every((row) => row[6] === `比较测试${bound === 'A' ? '甲' : '乙'}厂`),
> 178 |         ).toBe(true);
      |           ^ Error: expect(received).toBe(expected) // Object.is equality
  179 |     }
  180 |     snapshots.push({
  181 |       queryRows,
  182 |       name,
  183 |       displayName,
  184 |       role: value,
  185 |       bound,
  186 |       menus: names.map((n) => n.trim()),
  187 |       logins: memberLogins,
  188 |     });
  189 |     expect(memberLogins).toBe(1);
  190 |     await member.mouse.move(0, 0);
  191 |     await member.screenshot({
  192 |       path: info.outputPath(name + '.png'),
  193 |       fullPage: true,
  194 |       animations: 'disabled',
  195 |     });
  196 |   };
  197 |   try {
  198 |     await login(page, 'admin', adminPassword);
  199 |     await page.getByRole('menuitem', { name: zh.nav.users, exact: true }).click();
  200 |     await page.getByRole('button', { name: zh.users.create, exact: true }).click();
  201 |     await inputs(page).nth(0).fill(state.names[0]!);
  202 |     await inputs(page).nth(1).fill('会话测试成员');
  203 |     await factory(page, 'A');
  204 |     await page.getByRole('dialog').locator('input[type="password"]').fill(state.password);
  205 |     await save(page, true);
  206 |     await login(member, state.names[0]!, state.password);
  207 |     await expect(
  208 |       member.getByRole('heading', { name: zh.password.title, exact: true }),
  209 |     ).toBeVisible();
  210 |     await member.locator('input[autocomplete="current-password"]').fill(state.password);
  211 |     await member.locator('input[autocomplete="new-password"]').nth(0).fill(state.changed);
  212 |     await member.locator('input[autocomplete="new-password"]').nth(1).fill(state.changed);
  213 |     await member.getByRole('button', { name: zh.common.save, exact: true }).click();
  214 |     await member.getByRole('menuitem', { name: zh.nav.query, exact: true }).click();
  215 |     await capture('01-operator', '会话测试成员', 'factory_operator', 'A');
  216 |     await edit(page, '管理修改姓名', 'factory_operator', 'A');
  217 |     const oldBearer = lastBearer;
  218 |     await member.getByRole('menuitem', { name: zh.nav.dashboard, exact: true }).click();
  219 |     await member.getByRole('menuitem', { name: zh.nav.query, exact: true }).click();
  220 |     const queryResponse = member.waitForResponse(
  221 |       (r) =>
  222 |         r.request().method() === 'GET' &&
  223 |         new URL(r.url()).pathname === (prototype ? '/api/sn' : '/api/v1/sn/items/page'),
  224 |     );
  225 |     await member.getByRole('button', { name: zh.common.search, exact: true }).click();
  226 |     const queried = await queryResponse;
  227 |     expect(queried.status()).toBe(200);
  228 |     expect(queried.request().headers().authorization === oldBearer && oldBearer.length > 0).toBe(
  229 |       true,
  230 |     );
  231 |     snapshots.push({
  232 |       name: 'original-token-query-after-edit',
  233 |       status: queried.status(),
  234 |       sameBearer: true,
  235 |     });
  236 |     await member.reload();
  237 |     await capture('02-renamed', '管理修改姓名', 'factory_operator', 'A');
  238 |     await edit(page, '乙厂只读成员', 'query', 'B');
  239 |     await member.reload();
  240 |     await capture('03-query-b', '乙厂只读成员', 'query', 'B');
  241 |     await expect(member.getByRole('switch')).toHaveCount(0);
  242 |     await edit(page, '会话测试管理员', 'admin');
  243 |     await member.reload();
  244 |     await capture('04-admin', '会话测试管理员', 'admin');
  245 |     await edit(member, '本人修改姓名', 'admin');
  246 |     await member.reload();
  247 |     await capture('05-self-edited', '本人修改姓名', 'admin');
  248 |     await edit(page, '恢复甲厂操作员', 'factory_operator', 'A');
  249 |     await member.goto('/');
  250 |     await capture('06-operator-again', '恢复甲厂操作员', 'factory_operator', 'A');
  251 |     expect(requests.some((r) => r.authenticated && r.status === 401)).toBe(false);
  252 |     expect(failures).toEqual([]);
  253 |   } finally {
  254 |     writeFileSync(
  255 |       info.outputPath('session-evidence.json'),
  256 |       JSON.stringify({ snapshots, requests, memberLogins, failures }, null, 2),
  257 |     );
  258 |     await memberContext.close();
  259 |     fixture('clean');
  260 |   }
  261 | });
  262 | 
```