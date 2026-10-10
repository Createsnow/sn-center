import { expect, test } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { paceComparisonLogin } from './login-budget';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import {
  PIS,
  BILLS,
  customer,
  run,
  source,
  kind,
  rows,
  sns,
  fixture,
  login,
  sync,
  query,
  expectRows,
  capture,
  download,
} from './query-support';

test('P11 roles: factory scope, cross-factory read-only, denied PI and scoped exports', async ({
  page,
  browser,
}, info) => {
  test.setTimeout(360_000);
  page.setDefaultTimeout(20_000);
  expect(JSON.parse(readFileSync(resolve(run, 'extra-orders.json'), 'utf8'))).toEqual([]);
  fixture(info, 'begin');
  await login(
    page,
    'admin',
    readFileSync(
      resolve(`../deploy/.secrets/comparison-20261008/${kind(info)}-admin-password`),
      'utf8',
    ).trim(),
  );
  try {
    await sync(page, true);
    await paceComparisonLogin(page);
    fixture(info, 'seed');
    const before = JSON.parse(fixture(info, 'observe')) as {
      current: { sn: string; factory: string }[];
    };
    expect(before.current.map((row) => [row.sn, row.factory])).toEqual([
      ['Q29A00001', 'CMP-A'],
      ['Q29A00002', 'CMP-A'],
      ['Q29A00003', 'CMP-B'],
    ]);
    writeFileSync(info.outputPath('prepared.json'), JSON.stringify(before, null, 2));
    const accounts = JSON.parse(
      readFileSync(resolve('../deploy/.secrets/comparison-20261008/role-accounts.json'), 'utf8'),
    ) as Record<string, Record<string, { current: string; changed: boolean }>>;
    for (const user of ['cmp_operator', 'cmp_query_all', 'cmp_query_factory']) {
      const account = accounts[kind(info)]![user]!;
      expect(account.changed).toBe(true);
      const context = await browser.newContext({
        baseURL: info.project.use.baseURL,
        viewport: { width: 1440, height: 960 },
      });
      const rolePage = await context.newPage();
      rolePage.setDefaultTimeout(20_000);
      try {
        await login(rolePage, user, account.current);
        await rolePage.getByRole('menuitem', { name: zh.nav.query, exact: true }).click();
        await expect(
          rolePage.getByRole('heading', { name: zh.query.title, exact: true }),
        ).toBeVisible();
        await rolePage.getByPlaceholder(zh.common.customer, { exact: true }).fill(customer);
        await query(rolePage, info);
        const all = user === 'cmp_query_all';
        await expectRows(
          rolePage,
          info,
          all ? [...sns('B', 3, 2, 1), ...sns('A', 3, 2, 1)] : sns('A', 2, 1),
        );
        await capture(rolePage, info, user + '-01-bound');
        await expect(rows(rolePage, info).getByRole('button')).toHaveCount(0);
        await download(
          rolePage,
          info,
          user + '-01-bound',
          all ? [...sns('A', 1, 2, 3), ...sns('B', 1, 2, 3)] : sns('A', 1, 2),
        );
        if (user === 'cmp_operator') {
          await expect(rolePage.getByRole('switch')).toBeDisabled();
          await rolePage.getByPlaceholder('PI', { exact: true }).fill(PIS[0]!);
          await (
            source(info) ? rolePage.locator('.el-switch__core') : rolePage.getByRole('switch')
          ).click();
          await expect(rolePage.getByText(zh.acquire.piAllReadOnly, { exact: true })).toBeVisible();
          await query(rolePage, info);
          await expectRows(rolePage, info, sns('A', 3, 1, 2));
          await capture(rolePage, info, user + '-02-cross-factory');
          await download(rolePage, info, user + '-02-cross-factory', sns('A', 1, 2, 3), 'xlsx');
          await rolePage.getByPlaceholder('PI', { exact: true }).fill(PIS[1]!);
          const response = await query(rolePage, info, 403);
          writeFileSync(
            info.outputPath('denied-query-response.json'),
            JSON.stringify(await response.json(), null, 2),
          );
          // 拒绝后继续显示最后成功的结果；错误为原型五秒可关闭提示。
          await expectRows(rolePage, info, sns('A', 3, 1, 2));
          await expect(
            rolePage.getByText(`本厂没有 PI ${PIS[1]} 的号，不能查看其他工厂`, { exact: true }),
          ).toBeVisible({ timeout: 3000 });
          await capture(rolePage, info, user + '-03-denied');
          await rolePage
            .locator(source(info) ? '.el-message__closeBtn' : '.sn-prototype-error-message button')
            .click();
          await expect(
            rolePage.getByText(`本厂没有 PI ${PIS[1]} 的号，不能查看其他工厂`, { exact: true }),
          ).toBeHidden();
          await rolePage.getByPlaceholder('PI', { exact: true }).fill('');
          await expect(rolePage.getByRole('switch')).toBeDisabled();
          await expect(rolePage.getByText(zh.acquire.piAllReadOnly, { exact: true })).toHaveCount(
            0,
          );
          await query(rolePage, info);
          await expectRows(rolePage, info, sns('A', 2, 1));
          await capture(rolePage, info, user + '-04-cleared');
        } else {
          await expect(rolePage.getByRole('switch')).toHaveCount(0);
          await rolePage.getByPlaceholder('PI', { exact: true }).fill(PIS[1]!);
          await query(rolePage, info);
          await expectRows(rolePage, info, all ? sns('B', 3, 1, 2) : []);
          await download(
            rolePage,
            info,
            user + '-02-other-pi',
            all ? sns('B', 1, 2, 3) : [],
            'xlsx',
          );
          await capture(rolePage, info, user + '-02-other-pi');
          if (all) {
            await rolePage.getByPlaceholder('PI', { exact: true }).fill('');
            const factorySelect = rolePage.locator(
              source(info)
                ? '.toolbar .factory-select'
                : '.sn-toolbar .sn-factory-picker .ant-select',
            );
            await factorySelect.click();
            await rolePage
              .getByRole('option', { name: '比较测试乙厂（CMP-B）', exact: true })
              .click();
            await query(rolePage, info);
            await expectRows(rolePage, info, [...sns('B', 3, 2, 1), ...sns('A', 3)]);
            await capture(rolePage, info, user + '-03-factory-b');
            const statusSelect = rolePage.locator(
              source(info)
                ? '.toolbar > .el-select:not(.factory-select)'
                : '.sn-toolbar > .ant-select',
            );
            await statusSelect.click();
            await rolePage.getByRole('option', { name: zh.status.PRINTED, exact: true }).click();
            await query(rolePage, info);
            await expectRows(rolePage, info, []);
            await statusSelect.click();
            await rolePage.getByRole('option', { name: zh.status.TO_ACQUIRE, exact: true }).click();
            await query(rolePage, info);
            await expectRows(rolePage, info, [...sns('B', 3, 2, 1), ...sns('A', 3)]);
            await rolePage.getByPlaceholder(zh.common.sn, { exact: true }).fill('Q29B00002');
            await query(rolePage, info);
            await expectRows(rolePage, info, sns('B', 2));
            await capture(rolePage, info, user + '-04-status-exact-sn');
            await rolePage.getByPlaceholder(zh.common.sn, { exact: true }).fill('');
            await rolePage.getByPlaceholder(zh.query.sourceBill, { exact: true }).fill(BILLS[1]!);
            await rolePage
              .getByPlaceholder(zh.common.material, { exact: true })
              .fill('CMP-SCOPE-MAT-Z');
            await query(rolePage, info);
            await expectRows(rolePage, info, sns('B', 2, 1));
            await capture(rolePage, info, user + '-05-bill-material');
            await rolePage.getByPlaceholder(zh.query.sourceBill, { exact: true }).fill('');
            await rolePage.getByPlaceholder(zh.common.material, { exact: true }).fill('');
            await factorySelect.hover();
            await factorySelect
              .locator(source(info) ? '.el-select__clear' : '.ant-select-clear')
              .click();
            await statusSelect.hover();
            await statusSelect
              .locator(source(info) ? '.el-select__clear' : '.ant-select-clear')
              .click();
            await query(rolePage, info);
            await expectRows(rolePage, info, [...sns('B', 3, 2, 1), ...sns('A', 3, 2, 1)]);
            await capture(rolePage, info, user + '-06-cleared-selects');
          }
        }
      } finally {
        await context.close();
      }
    }
    expect(JSON.parse(fixture(info, 'observe'))).toEqual(before);
  } finally {
    if (!source(info))
      await expect.poll(() => Number(fixture(info, 'active')), { timeout: 60_000 }).toBe(0);
    await sync(page, false);
    fixture(info, 'clean');
    writeFileSync(
      info.outputPath('baseline-preserved.json'),
      readFileSync(resolve(run, `acquire-clean-${kind(info)}.json`)),
    );
  }
});
