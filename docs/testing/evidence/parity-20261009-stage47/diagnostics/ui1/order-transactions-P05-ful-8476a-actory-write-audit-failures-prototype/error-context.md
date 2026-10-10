# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: order-transactions.spec.ts >> P05 full sync commits factories separately and continues after factory write/audit failures
- Location: e2e\parity\order-transactions.spec.ts:9:1

# Error details

```
Error: Command failed: D:\AdtekProjects\mf-sn-uniq-guard\sn-center-python\.venv\Scripts\python.exe -X utf8 D:\AdtekProjects\mf-sn-uniq-guard\adtek-platform-sn\tools\testing\parity-order-transactions-fixture.py python factory-write
Traceback (most recent call last):
  File "D:\AdtekProjects\mf-sn-uniq-guard\adtek-platform-sn\tools\testing\parity-order-transactions-fixture.py", line 86, in <module>
    c.execute("CREATE TRIGGER tx47_fault_" + event.lower() + " BEFORE " + event + " ON " + table
  File "D:\AdtekProjects\mf-sn-uniq-guard\sn-center-python\.venv\Lib\site-packages\pymysql\cursors.py", line 170, in execute
    result = self._query(query)
             ^^^^^^^^^^^^^^^^^^
  File "D:\AdtekProjects\mf-sn-uniq-guard\sn-center-python\.venv\Lib\site-packages\pymysql\cursors.py", line 335, in _query
    conn.query(q)
  File "D:\AdtekProjects\mf-sn-uniq-guard\sn-center-python\.venv\Lib\site-packages\pymysql\connections.py", line 581, in query
    self._affected_rows = self._read_query_result(unbuffered=unbuffered)
                          ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
  File "D:\AdtekProjects\mf-sn-uniq-guard\sn-center-python\.venv\Lib\site-packages\pymysql\connections.py", line 857, in _read_query_result
    result.read()
  File "D:\AdtekProjects\mf-sn-uniq-guard\sn-center-python\.venv\Lib\site-packages\pymysql\connections.py", line 1290, in read
    first_packet = self.connection._read_packet()
                   ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
  File "D:\AdtekProjects\mf-sn-uniq-guard\sn-center-python\.venv\Lib\site-packages\pymysql\connections.py", line 810, in _read_packet
    packet.raise_for_error()
  File "D:\AdtekProjects\mf-sn-uniq-guard\sn-center-python\.venv\Lib\site-packages\pymysql\protocol.py", line 219, in raise_for_error
    err.raise_mysql_exception(self._data)
  File "D:\AdtekProjects\mf-sn-uniq-guard\sn-center-python\.venv\Lib\site-packages\pymysql\err.py", line 154, in raise_mysql_exception
    raise errorclass(errno, errval, sqlstate=sqlstate)
pymysql.err.OperationalError: (1419, 'You do not have the SUPER privilege and binary logging is enabled (you *might* want to use the less safe log_bin_trust_function_creators variable)')

```

# Test source

```ts
  1   | import { test, expect } from '@playwright/test';
  2   | import { execFileSync } from 'node:child_process';
  3   | import { readFileSync, writeFileSync } from 'node:fs';
  4   | import { resolve } from 'node:path';
  5   | import { protectTestSecrets } from '../privacy';
  6   | import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
  7   | 
  8   | // 全量替换和故障注入仅使用专用8001/15174/18081服务及tx47库，不能指向常驻比较库。
  9   | test('P05 full sync commits factories separately and continues after factory write/audit failures', async ({
  10  |   page,
  11  | }, info) => {
  12  |   test.setTimeout(180_000);
  13  |   const prototype = info.project.name === 'prototype';
  14  |   const kind = prototype ? 'python' : 'java';
  15  |   const fixture = (action: string) =>
  16  |     JSON.parse(
> 17  |       execFileSync(
      |       ^ Error: Command failed: D:\AdtekProjects\mf-sn-uniq-guard\sn-center-python\.venv\Scripts\python.exe -X utf8 D:\AdtekProjects\mf-sn-uniq-guard\adtek-platform-sn\tools\testing\parity-order-transactions-fixture.py python factory-write
  18  |         resolve('../../sn-center-python/.venv/Scripts/python.exe'),
  19  |         [
  20  |           '-X',
  21  |           'utf8',
  22  |           resolve('../tools/testing/parity-order-transactions-fixture.py'),
  23  |           kind,
  24  |           action,
  25  |         ],
  26  |         { windowsHide: true, encoding: 'utf8' },
  27  |       ),
  28  |     );
  29  |   const password = readFileSync(
  30  |     resolve(`../deploy/.secrets/comparison-20261008/${kind}-admin-password`),
  31  |     'utf8',
  32  |   ).trim();
  33  |   protectTestSecrets(password);
  34  |   const errors: string[] = [];
  35  |   page.on('pageerror', (e) => errors.push(e.message));
  36  |   const states: unknown[] = [];
  37  |   const menu = async (factory: boolean) => {
  38  |     await page
  39  |       .getByRole('menuitem', { name: factory ? zh.nav.factories : zh.nav.orders, exact: true })
  40  |       .click();
  41  |     await expect(
  42  |       page.getByRole('heading', {
  43  |         name: factory ? zh.factories.title : zh.orders.title,
  44  |         exact: true,
  45  |       }),
  46  |     ).toBeVisible();
  47  |     await page.waitForLoadState('networkidle');
  48  |     await expect
  49  |       .poll(() =>
  50  |         page
  51  |           .locator(prototype ? 'main > .page' : 'main > .sn-page')
  52  |           .evaluate((el) => getComputedStyle(el).opacity),
  53  |       )
  54  |       .toBe('1');
  55  |   };
  56  |   const capture = async (label: string) => {
  57  |     await expect(page.locator('.ant-spin-spinning:visible,.el-loading-mask:visible')).toHaveCount(
  58  |       0,
  59  |     );
  60  |     const state = {
  61  |       label,
  62  |       database: fixture('snapshot'),
  63  |       text: await page.locator('main').innerText(),
  64  |       warnings: await page
  65  |         .locator(prototype ? 'main > .page > .el-alert' : '.sn-orders-page > .ant-alert')
  66  |         .allTextContents(),
  67  |       messages: await page
  68  |         .locator('.el-message,.sn-prototype-error-message,.sn-prototype-success-message')
  69  |         .allTextContents(),
  70  |       rows: await page
  71  |         .locator(prototype ? '.el-table__body .el-table__row' : '.ant-table-tbody .ant-table-row')
  72  |         .allTextContents(),
  73  |     };
  74  |     states.push(state);
  75  |     await page.screenshot({ path: info.outputPath(label + '.png'), animations: 'disabled' });
  76  |     return state;
  77  |   };
  78  |   try {
  79  |     fixture('baseline');
  80  |     await page.goto(prototype ? 'http://127.0.0.1:8001/login' : 'http://127.0.0.1:15174/login');
  81  |     await page.locator('input[autocomplete="username"]').fill('admin');
  82  |     await page.locator('input[autocomplete="current-password"]').fill(password);
  83  |     await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
  84  |     await menu(false);
  85  |     for (const [stage, bills, lines, factoryCount] of [
  86  |       ['baseline', 3, 4, 2],
  87  |       ['factory-write', 4, 5, 2],
  88  |       ['factory-audit', 5, 6, 2],
  89  |       ['order-write', 5, 6, 3],
  90  |       ['order-audit', 5, 6, 4],
  91  |       ['recovery', 6, 7, 6],
  92  |     ] as const) {
  93  |       fixture(stage);
  94  |       const before = fixture('snapshot');
  95  |       const failed = stage.startsWith('order-');
  96  |       const pending = page.waitForResponse(
  97  |         (r) =>
  98  |           r.request().method() === 'POST' &&
  99  |           /\/orders\/sync(?:-all)?$/.test(new URL(r.url()).pathname),
  100 |       );
  101 |       await page.getByRole('button', { name: zh.orders.syncAll, exact: true }).click();
  102 |       const response = await pending;
  103 |       expect(response.status()).toBe(failed ? 500 : 200);
  104 |       if (failed) {
  105 |         await expect(
  106 |           page.locator(prototype ? '.el-message--error' : '.sn-prototype-error-message'),
  107 |         ).toBeVisible();
  108 |       } else {
  109 |         await expect(
  110 |           page.getByText(`已同步 ${bills} 张单据，${lines} 行`, { exact: true }),
  111 |         ).toBeVisible();
  112 |       }
  113 |       await expect(
  114 |         page.getByRole('button', { name: zh.orders.syncAll, exact: true }),
  115 |       ).toBeEnabled();
  116 |       await page.waitForLoadState('networkidle');
  117 |       const state = await capture(stage + '-orders');
```