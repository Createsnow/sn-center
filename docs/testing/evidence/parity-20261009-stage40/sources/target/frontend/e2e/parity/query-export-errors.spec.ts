import { expect, test } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { paceComparisonLogin } from './login-budget';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import en from '../../src/i18n/prototype/en.json' with { type: 'json' };
import vi from '../../src/i18n/prototype/vi.json' with { type: 'json' };
import errorsZh from '../../src/i18n/prototype/errors.zh-CN.json' with { type: 'json' };
import errorsEn from '../../src/i18n/prototype/errors.en.json' with { type: 'json' };
import errorsVi from '../../src/i18n/prototype/errors.vi.json' with { type: 'json' };
import {
  PIS,
  customer,
  run,
  source,
  kind,
  sns,
  fixture,
  login,
  language,
  sync,
  query,
  expectRows,
  capture,
  download,
} from './query-support';

async function captureError(
  page: import('@playwright/test').Page,
  info: import('@playwright/test').TestInfo,
  name: string,
  text: string,
) {
  const toast = page.getByText(text, { exact: true });
  await expect(toast).toBeVisible();
  // 可见性断言不检查透明度；等真实入场动画结束，防止截图落在透明首帧。
  await page.evaluate(async () => {
    await new Promise<void>((resolve) =>
      requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
    );
    await Promise.all(
      document
        .getAnimations()
        .filter((animation) => animation.effect?.getTiming().iterations !== Infinity)
        .map((animation) => animation.finished.catch(() => {})),
    );
  });
  const paint = () =>
    toast.evaluate((element) => {
      let opacity = 1;
      for (let node: Element | null = element; node; node = node.parentElement)
        opacity *= Number(getComputedStyle(node).opacity);
      return { opacity, text: element.textContent };
    });
  await expect.poll(async () => (await paint()).opacity).toBe(1);
  const before = await paint();
  await capture(page, info, name);
  const after = await paint();
  expect(after.opacity).toBe(1);
  writeFileSync(info.outputPath(name + '-paint.json'), JSON.stringify({ before, after }, null, 2));
}

test('P11 export errors: denied PI, HTTP download failure and retry', async ({
  page,
  browser,
}, info) => {
  test.setTimeout(240_000);
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
    const before = JSON.parse(fixture(info, 'observe'));
    const accounts = JSON.parse(
      readFileSync(resolve('../deploy/.secrets/comparison-20261008/role-accounts.json'), 'utf8'),
    ) as Record<string, Record<string, { current: string; changed: boolean }>>;
    const account = accounts[kind(info)]!.cmp_operator!;
    expect(account.changed).toBe(true);
    const context = await browser.newContext({
      baseURL: info.project.use.baseURL,
      viewport: { width: 1440, height: 960 },
    });
    const role = await context.newPage();
    try {
      await login(role, 'cmp_operator', account.current);
      await role.getByRole('menuitem', { name: zh.nav.query, exact: true }).click();
      await expect(role.getByRole('heading', { name: zh.query.title, exact: true })).toBeVisible();
      await role.getByPlaceholder(zh.common.customer, { exact: true }).fill(customer);
      await query(role, info);
      await expectRows(role, info, sns('A', 2, 1));
      await role.getByPlaceholder('PI', { exact: true }).fill(PIS[1]!);
      await (source(info) ? role.locator('.el-switch__core') : role.getByRole('switch')).click();
      for (const [code, label, words, errors] of [
        ['zh', '简体中文', zh, errorsZh],
        ['en', 'English', en, errorsEn],
        ['vi', 'Tiếng Việt', vi, errorsVi],
      ] as const) {
        await language(role, info, label, words.query.exportXlsx);
        for (const format of ['csv', 'xlsx']) {
          const response = role.waitForResponse(
            (r) =>
              new URL(r.url()).pathname ===
              (source(info) ? '/api/sn/export' : '/api/v1/sn/exports'),
          );
          await role
            .getByRole('button', {
              name: format === 'csv' ? 'CSV' : words.query.exportXlsx,
              exact: true,
            })
            .click();
          const denied = await response;
          expect(denied.status()).toBe(403);
          writeFileSync(
            info.outputPath(`denied-${code}-${format}-response.json`),
            JSON.stringify(await denied.json(), null, 2),
          );
          const toast = role.getByText(errors.QUERY_PI_NOT_OWNED.replace('{{pi}}', PIS[1]!), {
            exact: true,
          });
          await expect(toast).toBeVisible();
          await captureError(
            role,
            info,
            `01-denied-${code}-${format}`,
            errors.QUERY_PI_NOT_OWNED.replace('{{pi}}', PIS[1]!),
          );
          await expect(toast).toBeVisible();
          await expect(
            role.locator(
              source(info)
                ? '.page__actions button.is-loading'
                : '.sn-page-actions button.ant-btn-loading',
            ),
          ).toHaveCount(0);
          await expect(role.getByTestId('sn-operation')).toHaveCount(0);
          await expectRows(role, info, sns('A', 2, 1));
          await expect(toast).toBeHidden({ timeout: 5000 });
        }
      }
      await language(role, info, '简体中文', zh.query.exportXlsx);
      await role.getByPlaceholder('PI', { exact: true }).fill(PIS[0]!);
      await download(role, info, '01-corrected-pi', sns('A', 1, 2, 3));
      await expectRows(role, info, sns('A', 2, 1));
    } finally {
      await language(role, info, '简体中文', zh.query.exportXlsx);
      await context.close();
    }

    await page.getByRole('menuitem', { name: zh.nav.query, exact: true }).click();
    await expect(page.getByRole('heading', { name: zh.query.title, exact: true })).toBeVisible();
    await page.getByPlaceholder(zh.common.customer, { exact: true }).fill(customer);
    await query(page, info);
    const expected = [...sns('B', 3, 2, 1), ...sns('A', 3, 2, 1)];
    await expectRows(page, info, expected);
    const routePattern = source(info) ? '**/api/sn/export?*' : '**/api/v1/files/*';
    let failures = 0;
    await page.route(
      routePattern,
      async (route) => {
        failures++;
        await route.fulfill({
          status: 500,
          contentType: 'text/html',
          body: '<html>owned comparison download failure</html>',
        });
      },
      { times: 1 },
    );
    await page.getByRole('button', { name: 'CSV', exact: true }).click();
    await expect(
      page.getByText(zh.common.downloadFailed.replace('{{status}}', '500'), { exact: true }),
    ).toBeVisible({ timeout: 30_000 });
    expect(failures).toBe(1);
    await expectRows(page, info, expected);
    await captureError(
      page,
      info,
      '02-http-failure',
      zh.common.downloadFailed.replace('{{status}}', '500'),
    );
    await download(page, info, '03-retry', [...sns('A', 1, 2, 3), ...sns('B', 1, 2, 3)]);
    await expectRows(page, info, expected);
    await capture(page, info, '03-retried');
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
