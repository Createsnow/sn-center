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

Expected: false
Received: true
```

# Test source

```ts
  52  |     if (route.endsWith('/auth/login') && response.request().method() === 'POST') memberLogins++;
  53  |     const bearer = response.request().headers().authorization;
  54  |     if (bearer) lastBearer = bearer;
  55  |   });
  56  |   const login = async (tab: Page, username: string, password: string) => {
  57  |     await tab.goto('/login');
  58  |     await paceComparisonLogin(tab);
  59  |     await tab.locator('input[autocomplete="username"]').fill(username);
  60  |     await tab.locator('input[autocomplete="current-password"]').fill(password);
  61  |     await tab.getByRole('button', { name: zh.login.submit, exact: true }).click();
  62  |   };
  63  |   const search = async (tab: Page) => {
  64  |     await tab.getByRole('menuitem', { name: zh.nav.users, exact: true }).click();
  65  |     await tab.getByPlaceholder(zh.users.search).fill(state.names[0]!);
  66  |     const refreshed = tab.waitForResponse(
  67  |       (r) =>
  68  |         r.request().method() === 'GET' &&
  69  |         new URL(r.url()).pathname === (prototype ? '/api/users' : '/api/v1/system/users'),
  70  |     );
  71  |     await tab.getByPlaceholder(zh.users.search).press('Enter');
  72  |     expect((await refreshed).status()).toBe(200);
  73  |     const rows = tab.locator(
  74  |       prototype
  75  |         ? '.el-table__body tbody tr'
  76  |         : '.ant-table-tbody > tr:not(.ant-table-measure-row):not(.ant-table-placeholder)',
  77  |     );
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
> 152 |     expect(names.some((n) => n.trim() === zh.nav.acquire)).toBe(value !== 'query');
      |                                                            ^ Error: expect(received).toBe(expected) // Object.is equality
  153 |     if (bound)
  154 |       await expect(
  155 |         member.locator(prototype ? '.header .fac' : '.prototype-header .prototype-factory-tag'),
  156 |       ).toContainText(`比较测试${bound === 'A' ? '甲' : '乙'}厂`);
  157 |     snapshots.push({
  158 |       name,
  159 |       displayName,
  160 |       role: value,
  161 |       bound,
  162 |       menus: names.map((n) => n.trim()),
  163 |       logins: memberLogins,
  164 |     });
  165 |     expect(memberLogins).toBe(1);
  166 |     await member.mouse.move(0, 0);
  167 |     await member.screenshot({
  168 |       path: info.outputPath(name + '.png'),
  169 |       fullPage: true,
  170 |       animations: 'disabled',
  171 |     });
  172 |   };
  173 |   try {
  174 |     await login(page, 'admin', adminPassword);
  175 |     await page.getByRole('menuitem', { name: zh.nav.users, exact: true }).click();
  176 |     await page.getByRole('button', { name: zh.users.create, exact: true }).click();
  177 |     await inputs(page).nth(0).fill(state.names[0]!);
  178 |     await inputs(page).nth(1).fill('会话测试成员');
  179 |     await factory(page, 'A');
  180 |     await page.getByRole('dialog').locator('input[type="password"]').fill(state.password);
  181 |     await save(page, true);
  182 |     await login(member, state.names[0]!, state.password);
  183 |     await expect(
  184 |       member.getByRole('heading', { name: zh.password.title, exact: true }),
  185 |     ).toBeVisible();
  186 |     await member.locator('input[autocomplete="current-password"]').fill(state.password);
  187 |     await member.locator('input[autocomplete="new-password"]').nth(0).fill(state.changed);
  188 |     await member.locator('input[autocomplete="new-password"]').nth(1).fill(state.changed);
  189 |     await member.getByRole('button', { name: zh.common.save, exact: true }).click();
  190 |     await member.getByRole('menuitem', { name: zh.nav.query, exact: true }).click();
  191 |     await capture('01-operator', '会话测试成员', 'factory_operator', 'A');
  192 |     await edit(page, '管理修改姓名', 'factory_operator', 'A');
  193 |     const oldBearer = lastBearer;
  194 |     await member.getByRole('menuitem', { name: zh.nav.dashboard, exact: true }).click();
  195 |     await member.getByRole('menuitem', { name: zh.nav.query, exact: true }).click();
  196 |     const queryResponse = member.waitForResponse(
  197 |       (r) =>
  198 |         r.request().method() === 'GET' &&
  199 |         new URL(r.url()).pathname === (prototype ? '/api/sn' : '/api/v1/sn/items/page'),
  200 |     );
  201 |     await member.getByRole('button', { name: zh.common.search, exact: true }).click();
  202 |     const queried = await queryResponse;
  203 |     expect(queried.status()).toBe(200);
  204 |     expect(queried.request().headers().authorization === oldBearer && oldBearer.length > 0).toBe(
  205 |       true,
  206 |     );
  207 |     snapshots.push({
  208 |       name: 'original-token-query-after-edit',
  209 |       status: queried.status(),
  210 |       sameBearer: true,
  211 |     });
  212 |     await member.reload();
  213 |     await capture('02-renamed', '管理修改姓名', 'factory_operator', 'A');
  214 |     await edit(page, '乙厂只读成员', 'query', 'B');
  215 |     await member.reload();
  216 |     await capture('03-query-b', '乙厂只读成员', 'query', 'B');
  217 |     await expect(member.getByRole('switch')).toHaveCount(0);
  218 |     await edit(page, '会话测试管理员', 'admin');
  219 |     await member.reload();
  220 |     await capture('04-admin', '会话测试管理员', 'admin');
  221 |     await edit(member, '本人修改姓名', 'admin');
  222 |     await member.reload();
  223 |     await capture('05-self-edited', '本人修改姓名', 'admin');
  224 |     await edit(page, '恢复甲厂操作员', 'factory_operator', 'A');
  225 |     await member.goto('/dashboard');
  226 |     await capture('06-operator-again', '恢复甲厂操作员', 'factory_operator', 'A');
  227 |     expect(requests.some((r) => r.status === 401)).toBe(false);
  228 |     expect(failures).toEqual([]);
  229 |   } finally {
  230 |     writeFileSync(
  231 |       info.outputPath('session-evidence.json'),
  232 |       JSON.stringify({ snapshots, requests, memberLogins, failures }, null, 2),
  233 |     );
  234 |     await memberContext.close();
  235 |     fixture('clean');
  236 |   }
  237 | });
  238 | 
```