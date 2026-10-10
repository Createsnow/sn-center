# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: account-session.spec.ts >> P13 account sessions: edit name, role, factory and self without another login
- Location: e2e\parity\account-session.spec.ts:9:1

# Error details

```
TimeoutError: page.waitForResponse: Timeout 15000ms exceeded while waiting for event "response"
```

# Test source

```ts
  1   | import { test, expect, type Page } from '@playwright/test';
  2   | import { execFileSync } from 'node:child_process';
  3   | import { readFileSync, writeFileSync } from 'node:fs';
  4   | import { resolve } from 'node:path';
  5   | import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
  6   | import { protectTestSecrets } from '../privacy';
  7   | import { paceComparisonLogin } from './login-budget';
  8   | 
  9   | test('P13 account sessions: edit name, role, factory and self without another login', async ({
  10  |   page,
  11  |   browser,
  12  | }, info) => {
  13  |   test.setTimeout(180_000);
  14  |   page.setDefaultTimeout(15_000);
  15  |   const prototype = info.project.name === 'prototype';
  16  |   const kind = prototype ? 'python' : 'java';
  17  |   const fixture = (action: string) =>
  18  |     execFileSync(
  19  |       resolve('../../sn-center-python/.venv/Scripts/python.exe'),
  20  |       ['-X', 'utf8', resolve('../tools/testing/parity-account-session-fixture.py'), kind, action],
  21  |       { windowsHide: true, encoding: 'utf8' },
  22  |     );
  23  |   fixture('prepare');
  24  |   const path = resolve(`../deploy/.secrets/comparison-20261008/account-session-${kind}.json`);
  25  |   const state = JSON.parse(readFileSync(path, 'utf8')) as {
  26  |     names: string[];
  27  |     created: string[];
  28  |     password: string;
  29  |     changed: string;
  30  |   };
  31  |   const adminPassword = readFileSync(
  32  |     resolve(`../deploy/.secrets/comparison-20261008/${kind}-admin-password`),
  33  |     'utf8',
  34  |   ).trim();
  35  |   protectTestSecrets(adminPassword, state.password, state.changed);
  36  |   const memberContext = await browser.newContext({
  37  |     baseURL: String(info.project.use.baseURL),
  38  |     viewport: { width: 1440, height: 960 },
  39  |   });
  40  |   const member = await memberContext.newPage();
  41  |   member.setDefaultTimeout(15_000);
  42  |   const failures: string[] = [];
  43  |   const requests: { route: string; status: number }[] = [];
  44  |   const snapshots: unknown[] = [];
  45  |   let memberLogins = 0;
  46  |   let lastBearer = '';
  47  |   for (const tab of [page, member]) tab.on('pageerror', (error) => failures.push(error.message));
  48  |   member.on('response', (response) => {
  49  |     const route = new URL(response.url()).pathname;
  50  |     if (!route.startsWith('/api/')) return;
  51  |     requests.push({ route, status: response.status() });
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
> 66  |     const refreshed = tab.waitForResponse(
      |                           ^ TimeoutError: page.waitForResponse: Timeout 15000ms exceeded while waiting for event "response"
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
  152 |     expect(names.some((n) => n.trim() === zh.nav.acquire)).toBe(true);
  153 |     expect(names.some((n) => n.trim() === zh.nav.generate)).toBe(value === 'admin');
  154 |     if (bound)
  155 |       await expect(
  156 |         member.locator(prototype ? '.header .fac' : '.prototype-header .prototype-factory-tag'),
  157 |       ).toContainText(`比较测试${bound === 'A' ? '甲' : '乙'}厂`);
  158 |     snapshots.push({
  159 |       name,
  160 |       displayName,
  161 |       role: value,
  162 |       bound,
  163 |       menus: names.map((n) => n.trim()),
  164 |       logins: memberLogins,
  165 |     });
  166 |     expect(memberLogins).toBe(1);
```