import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { protectTestSecrets } from '../privacy';
import { paceComparisonLogin } from './login-budget';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import en from '../../src/i18n/prototype/en.json' with { type: 'json' };
import vi from '../../src/i18n/prototype/vi.json' with { type: 'json' };

const prototype = (info: TestInfo) => info.project.name === 'prototype';
const selectors = (page: Page, info: TestInfo) =>
  page.locator(prototype(info) ? '.picker-bar .el-select' : '.sn-generation-picker .ant-select');
async function choosePi(page: Page, info: TestInfo, value: string) {
  const picker = selectors(page, info).nth(1);
  await picker.click();
  await picker.locator('input').fill(value);
  await page
    .locator(
      prototype(info) ? '.el-select-dropdown__item:visible' : '.ant-select-item-option:visible',
    )
    .filter({ hasText: value })
    .click();
}
async function language(page: Page, info: TestInfo, label: string) {
  await page.locator(prototype(info) ? '.header .lang' : '.prototype-language .ant-select').click();
  const saved = page.waitForResponse(
    (r) =>
      r.request().method() === (prototype(info) ? 'PUT' : 'POST') &&
      r.url().endsWith(prototype(info) ? '/auth/lang' : '/system/auth/language'),
  );
  await page.getByRole('option', { name: label, exact: true }).click();
  expect((await saved).ok()).toBe(true);
}

test('P07 PI auto-selection, clearing filters, pending toggle and three-language layout', async ({
  page,
}, info) => {
  test.setTimeout(150_000);
  page.setDefaultTimeout(20_000);
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto('/login');
  await paceComparisonLogin(page);
  const password = readFileSync(
    resolve(
      '../deploy/.secrets/comparison-20261008/' +
        (prototype(info) ? 'python' : 'java') +
        '-admin-password',
    ),
    'utf8',
  ).trim();
  protectTestSecrets(password);
  await page.locator('input[autocomplete="username"]').fill('admin');
  await page.locator('input[autocomplete="current-password"]').fill(password);
  await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
  await page.getByRole('menuitem', { name: zh.nav.generate, exact: true }).click();
  await expect(page.getByText(zh.generate.empty, { exact: true })).toBeVisible();
  await choosePi(page, info, 'CMP-PI-002');
  const stat = (label: string) =>
    page
      .locator(prototype(info) ? '.stats > div' : '.sn-work-stats > div')
      .filter({ has: page.getByText(label, { exact: true }) })
      .locator('b');
  await expect(stat(zh.orders.quota)).toHaveText('5');
  await expect(selectors(page, info).nth(2)).toContainText('CMP-MO-002');
  await page.getByText(zh.generate.onlyPending, { exact: true }).click();
  await expect(
    page.getByRole('checkbox', { name: zh.generate.onlyPending, exact: true }),
  ).not.toBeChecked();
  await expect(stat(zh.orders.quota)).toHaveText('5');
  const customer = selectors(page, info).nth(0);
  await customer.hover();
  await customer.locator(prototype(info) ? '.el-select__clear' : '.ant-select-clear').click();
  await expect(stat(zh.orders.quota)).toHaveText('5');
  await choosePi(page, info, 'CMP-PI-001');
  await expect(stat(zh.orders.generated)).toHaveText('10');
  await expect(stat(zh.orders.quota)).toHaveText('0');
  const bill = selectors(page, info).nth(2);
  await bill.hover();
  await bill.locator(prototype(info) ? '.el-select__clear' : '.ant-select-clear').click();
  await expect(page.getByText(zh.generate.empty, { exact: true })).toBeVisible();
  await choosePi(page, info, 'CMP-PI-002');
  await expect(stat(zh.orders.quota)).toHaveText('5');
  try {
    for (const [label, words, key] of [
      ['English', en, 'en'],
      ['Tiếng Việt', vi, 'vi'],
      ['简体中文', zh, 'zh'],
    ] as const) {
      await language(page, info, label);
      await expect(
        page.getByRole('heading', { name: words.generate.title, exact: true }),
      ).toBeVisible();
      await expect(
        page.getByRole('button', { name: words.generate.preview, exact: true }),
      ).toBeEnabled();
      await expect(
        page.getByRole('button', { name: words.generate.allocate, exact: true }),
      ).toBeDisabled();
      await page.screenshot({
        path: info.outputPath('generation-picker-' + key + '.png'),
        fullPage: true,
        animations: 'disabled',
      });
    }
    await page.setViewportSize({ width: 900, height: 1000 });
    await page.screenshot({
      path: info.outputPath('generation-picker-narrow.png'),
      fullPage: true,
      animations: 'disabled',
    });
    expect(errors).toEqual([]);
  } finally {
    if (!(await page.getByRole('heading', { name: zh.generate.title, exact: true }).isVisible()))
      await language(page, info, '简体中文');
  }
});
