import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { protectTestSecrets } from '../privacy';
import { paceComparisonLogin } from './login-budget';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };

const privateRoot = resolve('../deploy/.secrets/comparison-20261008');
const fixtureFile = resolve('../tools/.runtime/comparison-20261008/extra-orders.json');
const prototype = (info: TestInfo) => info.project.name === 'prototype';
const kind = (info: TestInfo) => (prototype(info) ? 'python' : 'java');
const errors = new WeakMap<Page, string[]>();
test.beforeEach(async ({ page }) => {
  page.setDefaultTimeout(10_000);
  const list: string[] = [];
  errors.set(page, list);
  page.on('pageerror', (error) => list.push(error.message));
});
test.afterEach(async ({ page }) => {
  expect(errors.get(page)).toEqual([]);
});

async function login(page: Page, info: TestInfo, username = 'admin') {
  const accounts = JSON.parse(
    readFileSync(resolve(privateRoot, 'role-accounts.json'), 'utf8'),
  ) as Record<string, Record<string, { current: string; changed: boolean }>>;
  const password =
    username === 'admin'
      ? readFileSync(resolve(privateRoot, `${kind(info)}-admin-password`), 'utf8').trim()
      : accounts[kind(info)]![username]!.current;
  if (username !== 'admin') expect(accounts[kind(info)]![username]!.changed).toBe(true);
  protectTestSecrets(password);
  await page.goto('/login');
  await paceComparisonLogin(page);
  await page.locator('input[autocomplete="username"]').fill(username);
  await page.locator('input[autocomplete="current-password"]').fill(password);
  await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
  await page.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
  await expect(orderRows(page, info)).toHaveCount(username === 'cmp_query_factory' ? 2 : 3);
}
function orderTable(page: Page, info: TestInfo) {
  return page.locator(prototype(info) ? '.el-row .el-table' : '.sn-order-table').first();
}
function orderRows(page: Page, info: TestInfo) {
  return orderTable(page, info).locator(prototype(info) ? '.el-table__row' : '.ant-table-row');
}
function factory(page: Page, info: TestInfo) {
  return page.locator(
    prototype(info) ? '.toolbar .factory-select' : '.sn-toolbar .sn-factory-picker',
  );
}
async function search(page: Page, text: string) {
  await page.getByPlaceholder(zh.orders.search, { exact: true }).fill(text);
  await page.getByRole('button', { name: zh.common.search, exact: true }).click();
}
async function capture(page: Page, info: TestInfo, name: string) {
  await expect(
    page.locator(prototype(info) ? '.el-loading-mask:visible' : '.ant-spin-spinning'),
  ).toHaveCount(0);
  if (!(await page.getByRole('dialog').isVisible())) {
    await page
      .getByRole('heading', { name: zh.orders.title, exact: true })
      .scrollIntoViewIfNeeded();
  }
  await expect
    .poll(() =>
      page
        .locator(prototype(info) ? '.page' : '.sn-orders-page')
        .evaluate((el) => getComputedStyle(el).opacity),
    )
    .toBe('1');
  await page.screenshot({ path: info.outputPath(name), animations: 'disabled' });
}
async function fullSync(page: Page, bills: number, rows: number) {
  await page.getByRole('button', { name: zh.orders.syncAll, exact: true }).click();
  await expect(page.getByText(`已同步 ${bills} 张单据，${rows} 行`, { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: zh.orders.syncAll, exact: true })).toBeEnabled();
}

test('P05 搜索匹配分录汇总 完整明细 小数整数额度和客户精确筛选', async ({ page }, info) => {
  await login(page, info);
  await fullSync(page, 3, 4);
  await search(page, 'CMP-MAT-A');
  await expect(orderRows(page, info)).toHaveCount(2);
  const row = orderRows(page, info).filter({ hasText: 'CMP-MO-001' });
  await expect(row.getByRole('cell').nth(5)).toHaveText('8');
  await expect(row.getByRole('cell').nth(6)).toHaveText('10');
  await expect(row.getByRole('cell').nth(7)).toHaveText('0');
  await row.click();
  await expect(page.getByRole('cell', { name: 'CMP-MAT-A', exact: true })).toBeVisible();
  await expect(page.getByRole('cell', { name: 'CMP-MAT-B', exact: true })).toBeVisible();
  await capture(page, info, 'orders-material-filter.png');
  await search(page, 'CMP-PI-FRACTION');
  await expect(orderRows(page, info)).toHaveCount(1);
  await expect(orderRows(page, info).getByRole('cell').nth(5)).toHaveText('2');
  await expect(orderRows(page, info).getByRole('cell').nth(7)).toHaveText('2');
  await expect(page.getByText(zh.orders.pick, { exact: true })).toBeVisible();
  await orderRows(page, info).click();
  await expect(page.getByRole('cell', { name: '2.5', exact: true })).toBeVisible();
  await capture(page, info, 'orders-fraction.png');
  await search(page, 'CMP-PO');
  await expect(orderRows(page, info)).toHaveCount(3);
  await search(page, 'CMP-CUSTOMER');
  await expect(orderRows(page, info)).toHaveCount(3);
  await page.getByPlaceholder(zh.common.customer, { exact: true }).fill('CMP-CUST');
  await page.getByRole('button', { name: zh.common.search, exact: true }).click();
  await expect(orderRows(page, info)).toHaveCount(0);
  await page.getByPlaceholder(zh.common.customer, { exact: true }).fill('CMP-CUSTOMER');
  await page.getByPlaceholder(zh.common.customer, { exact: true }).press('Enter');
  await expect(orderRows(page, info)).toHaveCount(3);
});

test('P05 工厂筛选自动加载及清空 选单清理', async ({ page }, info) => {
  await login(page, info);
  await factory(page, info).click();
  await page.getByRole('option', { name: '比较测试乙厂（CMP-B）', exact: true }).click();
  await expect(orderRows(page, info)).toHaveCount(1);
  await expect(orderRows(page, info)).toContainText('CMP-MO-002');
  await orderRows(page, info).click();
  await expect(page.getByRole('button', { name: zh.orders.toGenerate, exact: true })).toBeVisible();
  await factory(page, info).hover();
  await factory(page, info)
    .locator(prototype(info) ? '.el-select__clear' : '.ant-select-clear')
    .click();
  await expect(orderRows(page, info)).toHaveCount(3);
  await expect(page.getByText(zh.orders.pick, { exact: true })).toBeVisible();
});

for (const [username, bound] of [
  ['cmp_query_all', false],
  ['cmp_query_factory', true],
] as const) {
  test(`P05 ${username} 同步摘要 只读定时设置及订单范围`, async ({ page }, info) => {
    await login(page, info, username);
    const meta = page.locator(prototype(info) ? '.surface.meta' : '.sn-order-meta');
    await expect(meta).toContainText(zh.orders.sched.title);
    await expect(meta).toContainText(zh.orders.sched.off);
    await expect(meta.locator('b').nth(1)).toHaveText('3');
    await expect(meta.locator('b').nth(2)).toHaveText('4');
    await expect(factory(page, info)).toHaveCount(bound ? 0 : 1);
    await expect(
      page.getByRole('button', { name: zh.orders.sched.settings, exact: true }),
    ).toHaveCount(0);
    await expect(page.getByRole('button', { name: zh.orders.syncAll, exact: true })).toHaveCount(0);
    await expect(orderRows(page, info).filter({ hasText: 'CMP-MO-002' })).toHaveCount(
      bound ? 0 : 1,
    );
    await orderRows(page, info).filter({ hasText: 'CMP-MO-001' }).click();
    await expect(page.getByRole('cell', { name: 'CMP-MAT-B', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: zh.orders.toGenerate, exact: true })).toHaveCount(
      0,
    );
    await capture(page, info, `orders-${username}.png`);
    await page.reload();
    await expect(orderRows(page, info)).toHaveCount(bound ? 2 : 3);
    await expect(meta).toContainText(zh.orders.sched.title);
  });
}

test('P05 分页换页每页数量回首页 清除选择与单据倒序', async ({ page, request }, info) => {
  test.setTimeout(90_000);
  expect(await (await request.get('http://127.0.0.1:19090')).json()).toMatchObject({
    fixture: 'synthetic comparison only',
    extrasSupported: true,
  });
  await login(page, info);
  const rows = Array.from({ length: 51 }, (_, index) => ({
    'F_ora_kh.FNumber': 'CMP-CUSTOMER',
    FBillNo: `CMP-PAGE-${String(index + 1).padStart(3, '0')}`,
    F_HX_PO: 'CMP-PAGE-PO',
    F_HX_PI: `CMP-PAGE-PI-${index + 1}`,
    'FMaterialId.FNumber': 'CMP-PAGE-MAT',
    'FMaterialId.FName': '比较分页物料',
    FQty: '1',
    FStatus: '1',
    FSaleOrderNo: 'CMP-SO',
    'FPrdOrgId.FName': '比较测试甲厂',
    FID: (9007199254750000n + BigInt(index)).toString(),
    FTreeEntity_FEntryId: String(20000 + index),
    FPrdOrgId: '101',
    'FPrdOrgId.FNumber': 'CMP-A',
  }));
  writeFileSync(fixtureFile, JSON.stringify(rows), 'utf8');
  try {
    await fullSync(page, 54, 55);
    await expect(orderRows(page, info)).toHaveCount(20);
    await expect(orderRows(page, info).first()).toContainText('CMP-PAGE-051');
    const pager = page.locator(prototype(info) ? '.pager' : '.sn-order-table .ant-pagination');
    await pager
      .locator(prototype(info) ? '.el-pager li' : '.ant-pagination-item')
      .filter({ hasText: /^2$/ })
      .click();
    await expect(orderRows(page, info).first()).toContainText('CMP-PAGE-031');
    await orderRows(page, info).first().click();
    await expect(
      page.getByRole('button', { name: zh.orders.toGenerate, exact: true }),
    ).toBeVisible();
    await pager.locator(prototype(info) ? '.el-select' : '.ant-select').click();
    await page.getByRole('option', { name: /50/ }).click();
    await expect(orderRows(page, info)).toHaveCount(50);
    await expect(orderRows(page, info).first()).toContainText('CMP-PAGE-051');
    await expect(page.getByText(zh.orders.pick, { exact: true })).toBeVisible();
    await pager
      .locator(prototype(info) ? '.el-pager li' : '.ant-pagination-item')
      .filter({ hasText: /^2$/ })
      .click();
    await expect(orderRows(page, info)).toHaveCount(4);
    await expect(orderRows(page, info).first()).toContainText('CMP-PAGE-001');
    await capture(page, info, 'orders-page-two.png');
    await pager.locator(prototype(info) ? '.el-select' : '.ant-select').click();
    await page.getByRole('option', { name: /100/ }).click();
    await expect(orderRows(page, info)).toHaveCount(54);
    await expect(orderRows(page, info).first()).toContainText('CMP-PAGE-051');
  } finally {
    writeFileSync(fixtureFile, '[]', 'utf8');
    await fullSync(page, 3, 4);
    await expect(orderRows(page, info)).toHaveCount(3);
  }
});

test('P05 定时最小最大值 分钟换算 开关 更新人 保存取消', async ({ page }, info) => {
  await login(page, info);
  const settings = page.getByRole('button', { name: zh.orders.sched.settings, exact: true });
  const dialog = page.getByRole('dialog', { name: zh.orders.sched.dialogTitle });
  const amount = dialog.getByRole('spinbutton');
  async function unit(label: string) {
    await dialog.locator(prototype(info) ? '.el-select' : '.ant-select').click();
    await page.getByRole('option', { name: label, exact: true }).click();
  }
  async function save() {
    await dialog.getByRole('button', { name: zh.common.save, exact: true }).click();
    await expect(dialog).not.toBeVisible();
  }
  try {
    await settings.click();
    await expect(dialog).toContainText('admin ·');
    await unit(zh.orders.sched.unit.minute);
    await amount.fill('9');
    await dialog.getByRole('button', { name: zh.common.save, exact: true }).click();
    await expect(dialog).toBeVisible();
    await expect(
      page.locator(prototype(info) ? '.el-message--error' : '.ant-message-error'),
    ).toContainText(zh.orders.sched.intervalHint.replace('{{min}}', '10'));
    await amount.fill('10');
    await dialog.locator(prototype(info) ? '.el-switch' : '.ant-switch').click();
    await save();
    const meta = page.locator(prototype(info) ? '.surface.meta' : '.sn-order-meta');
    await expect(meta).toContainText('每 10 分钟');
    await expect(meta).toContainText(zh.orders.sched.next);
    await settings.click();
    await amount.fill('90');
    await save();
    await settings.click();
    await expect(amount).toHaveValue('90');
    await expect(dialog.locator(prototype(info) ? '.el-select' : '.ant-select')).toContainText(
      zh.orders.sched.unit.minute,
    );
    await unit(zh.orders.sched.unit.day);
    await amount.fill('7');
    await save();
    await expect(meta).toContainText('每 7 天');
    await settings.click();
    await expect(amount).toHaveValue('7');
    await expect(amount).toHaveAttribute('aria-valuemax', '7');
    await amount.fill('2');
    await dialog.getByRole('button', { name: zh.common.cancel, exact: true }).click();
    await settings.click();
    await expect(amount).toHaveValue('7');
    await capture(page, info, 'orders-schedule.png');
  } finally {
    if (!(await dialog.isVisible())) await settings.click();
    await unit(zh.orders.sched.unit.day);
    await amount.fill('1');
    if ((await dialog.getByRole('switch').getAttribute('aria-checked')) === 'true')
      await dialog.locator(prototype(info) ? '.el-switch' : '.ant-switch').click();
    await save();
  }
});
