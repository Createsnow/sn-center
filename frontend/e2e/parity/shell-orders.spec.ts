import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { protectTestSecrets } from '../privacy';
import { paceComparisonLogin } from './login-budget';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import en from '../../src/i18n/prototype/en.json' with { type: 'json' };
import vi from '../../src/i18n/prototype/vi.json' with { type: 'json' };

const passwords = {
  prototype: readFileSync(
    resolve('../deploy/.secrets/comparison-20261008/python-admin-password'),
    'utf8',
  ).trim(),
  migration: readFileSync(
    resolve('../deploy/.secrets/comparison-20261008/java-admin-password'),
    'utf8',
  ).trim(),
};
protectTestSecrets(...Object.values(passwords));
const browserErrors = new WeakMap<Page, string[]>();
test.beforeEach(async ({ page }) => {
  const errors: string[] = [];
  browserErrors.set(page, errors);
  page.on('pageerror', (error) => errors.push(error.message));
});
test.afterEach(async ({ page }) => {
  expect(browserErrors.get(page), '页面不得出现未处理的 JavaScript 错误').toEqual([]);
});
const isPrototype = (info: TestInfo) => info.project.name === 'prototype';
async function login(page: Page, info: TestInfo) {
  await page.goto('/login');
  await paceComparisonLogin(page);
  await page.locator('input[autocomplete="username"]').fill('admin');
  await page
    .locator('input[autocomplete="current-password"]')
    .fill(isPrototype(info) ? passwords.prototype : passwords.migration);
  await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
  await expect(page.getByRole('menuitem').first()).toBeVisible();
  if (!(await page.getByRole('menuitem', { name: zh.nav.orders, exact: true }).isVisible())) {
    await page
      .locator(isPrototype(info) ? '.header .lang' : '.prototype-language .ant-select')
      .click();
    const saved = page.waitForResponse(
      (response) =>
        response.request().method() === (isPrototype(info) ? 'PUT' : 'POST') &&
        response.url().endsWith(isPrototype(info) ? '/auth/lang' : '/system/auth/language'),
    );
    await page.getByRole('option', { name: '简体中文', exact: true }).click();
    expect((await saved).ok()).toBe(true);
  }
  await expect(page.getByRole('menuitem', { name: zh.nav.orders, exact: true })).toBeVisible();
}

test('P02 登录三语文案与移动布局', async ({ page }, info) => {
  await page.goto('/login');
  for (const [label, words] of [
    ['简体中文', zh],
    ['English', en],
    ['Tiếng Việt', vi],
  ] as const) {
    const select = page.locator(
      isPrototype(info) ? '.panel .el-select' : '.prototype-login-top .ant-select',
    );
    await select.click();
    await page.getByRole('option', { name: label, exact: true }).click();
    await expect(page.getByRole('heading', { name: words.login.title, exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: words.app.title, exact: true })).toBeVisible();
    await expect(page.getByText(words.login.point1, { exact: true })).toBeVisible();
    await expect(page.getByText(words.login.hint, { exact: true })).toBeVisible();
  }
  await page.screenshot({ path: info.outputPath('login-desktop.png') });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByRole('button', { name: vi.login.submit, exact: true })).toBeVisible();
  // 原型窄屏也存在水平溢出；先记录双方几何，不把“无溢出”冒充原型行为。
  await info.attach('login-mobile-geometry', {
    body: JSON.stringify(
      await page.evaluate(() => ({
        viewport: window.innerWidth,
        scrollWidth: document.documentElement.scrollWidth,
      })),
    ),
    contentType: 'application/json',
  });
  await page.screenshot({ path: info.outputPath('login-mobile.png') });
});

test('P01 菜单分组顺序 收起展开 用户菜单与退出', async ({ page }, info) => {
  await login(page, info);
  const expected = [
    'dashboard',
    'orders',
    'generate',
    'piInit',
    'rules',
    'acquire',
    'transfer',
    'query',
    'audit',
    'users',
    'factories',
  ] as const;
  const menu = page.locator(isPrototype(info) ? '.aside .el-menu' : '.prototype-navigation');
  const actual = await menu.getByRole('menuitem').allTextContents();
  expect(actual.map((value) => value.trim())).toEqual(expected.map((key) => zh.nav[key]));
  for (const group of [
    'groupWork',
    'groupHq',
    'groupFactory',
    'groupQuery',
    'groupSystem',
  ] as const)
    await expect(menu.getByText(zh.nav[group], { exact: true })).toBeVisible();
  await page.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
  await expect(page).toHaveURL(/\/orders$/);
  await expect(
    page.locator(isPrototype(info) ? '.el-breadcrumb' : '.ant-breadcrumb'),
  ).toContainText(zh.nav.orders);
  const toggle = page.locator(
    isPrototype(info) ? '.header .left > button' : '.prototype-header-left > button',
  );
  const aside = page.locator(isPrototype(info) ? '.aside' : '.prototype-sidebar');
  await toggle.click();
  await expect.poll(async () => Math.round((await aside.boundingBox())!.width)).toBe(64);
  await toggle.click();
  await expect.poll(async () => Math.round((await aside.boundingBox())!.width)).toBe(220);
  await expect(page.getByRole('heading', { name: zh.orders.title, exact: true })).toBeVisible();
  await expect(page.getByRole('cell', { name: 'CMP-MO-001', exact: true })).toBeVisible();
  const orderCell = page
    .locator(isPrototype(info) ? '.el-table__row td' : '.sn-order-table .ant-table-row td')
    .first();
  await expect.poll(async () => Math.round((await orderCell.boundingBox())!.height)).toBe(32);
  await page.screenshot({ path: info.outputPath('orders-desktop.png') });
  await page.locator(isPrototype(info) ? '.who' : '.prototype-user').click();
  await expect(
    page.getByRole('menuitem', { name: zh.app.changePassword, exact: true }),
  ).toBeVisible();
  await page.getByRole('menuitem', { name: zh.app.logout, exact: true }).click();
  await expect(page.getByRole('heading', { name: zh.login.title, exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { name: zh.login.title, exact: true })).toBeVisible();
});

test('P03 修改密码打开 校验和取消', async ({ page }, info) => {
  await login(page, info);
  await page.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
  await page.locator(isPrototype(info) ? '.who' : '.prototype-user').click();
  await page.getByRole('menuitem', { name: zh.app.changePassword, exact: true }).click();
  await expect(page).toHaveURL(/\/password$/);
  await expect(page.getByRole('heading', { name: zh.password.title, exact: true })).toBeVisible();
  await page.getByRole('button', { name: zh.common.save, exact: true }).click();
  await expect(page.getByText(zh.common.required, { exact: true }).first()).toBeVisible();
  await expect(page.locator('input[type="password"]')).toHaveCount(3);
  await page.getByRole('button', { name: zh.common.cancel, exact: true }).click();
  await expect(page).toHaveURL(/\/orders$/);
});

test('P05 全量与按单同步 搜索清空 订单分录和生成入口', async ({ page }, info) => {
  await login(page, info);
  await page.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
  await page.getByRole('button', { name: zh.orders.syncAll, exact: true }).click();
  await expect(page.getByText('已同步 3 张单据，4 行', { exact: true })).toBeVisible();
  await expect(page.getByRole('cell', { name: 'CMP-MO-001', exact: true })).toBeVisible();
  const bill = page.getByPlaceholder(zh.orders.billToSync, { exact: true });
  await bill.fill('CMP-MO-002');
  await page.getByRole('button', { name: zh.orders.syncOne, exact: true }).click();
  await expect(page.getByText('单据 CMP-MO-002 已同步，1 行', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: zh.orders.syncOne, exact: true })).toBeEnabled();
  const search = page.getByPlaceholder(zh.orders.search, { exact: true });
  await search.fill('CMP-MO-001');
  await page.getByRole('button', { name: zh.common.search, exact: true }).click();
  await expect(page.getByRole('cell', { name: 'CMP-MO-002', exact: true })).toHaveCount(0);
  await page.getByRole('cell', { name: 'CMP-MO-001', exact: true }).click();
  await expect(page.getByRole('cell', { name: 'CMP-MAT-A', exact: true }).first()).toBeVisible();
  await expect(page.getByRole('cell', { name: 'CMP-MAT-B', exact: true }).first()).toBeVisible();
  await page.getByRole('button', { name: zh.orders.toGenerate, exact: true }).click();
  await expect(page).toHaveURL(/\/generate\?/);
  await expect(page.getByRole('heading', { name: zh.generate.title, exact: true })).toBeVisible();
  await page.goBack();
  await expect(page.getByRole('heading', { name: zh.orders.title, exact: true })).toBeVisible();
  await page.getByPlaceholder(zh.orders.search, { exact: true }).fill('CMP-MO-002');
  await page.getByRole('button', { name: zh.common.search, exact: true }).click();
  await page.getByPlaceholder(zh.orders.search, { exact: true }).fill('');
  await page.getByRole('button', { name: zh.common.search, exact: true }).click();
  await expect(page.getByRole('cell', { name: 'CMP-MO-001', exact: true })).toBeVisible();
  await expect(page.getByRole('cell', { name: 'CMP-MO-002', exact: true })).toBeVisible();
});

test('P05 定时弹窗 换算 保存和取消', async ({ page }, info) => {
  await login(page, info);
  await page.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
  const settings = page.getByRole('button', { name: zh.orders.sched.settings, exact: true });
  await settings.click();
  const dialog = page.getByRole('dialog', { name: zh.orders.sched.dialogTitle });
  await expect(dialog).toBeVisible();
  const amount = dialog.getByRole('spinbutton');
  await expect(amount).toHaveValue('1');
  const select = dialog.locator(isPrototype(info) ? '.el-select' : '.ant-select');
  await select.click();
  await page.getByRole('option', { name: zh.orders.sched.unit.hour, exact: true }).click();
  await amount.fill('2');
  await dialog.getByRole('button', { name: zh.common.save, exact: true }).click();
  await expect(dialog).not.toBeVisible();
  await settings.click();
  await expect(amount).toHaveValue('2');
  await expect(select).toContainText(zh.orders.sched.unit.hour);
  await amount.fill('3');
  await dialog.getByRole('button', { name: zh.common.cancel, exact: true }).click();
  await settings.click();
  await expect(amount).toHaveValue('2');
  await select.click();
  await page.getByRole('option', { name: zh.orders.sched.unit.day, exact: true }).click();
  await amount.fill('1');
  await dialog.getByRole('button', { name: zh.common.save, exact: true }).click();
  await expect(dialog).not.toBeVisible();
});

test('P01 三语导航 用户偏好刷新持久化 窄屏收起与展开', async ({ page }, info) => {
  await login(page, info);
  for (const [label, words] of [
    ['English', en],
    ['Tiếng Việt', vi],
    ['简体中文', zh],
  ] as const) {
    const select = page.locator(
      isPrototype(info) ? '.header .lang' : '.prototype-language .ant-select',
    );
    await select.click();
    const saved = page.waitForResponse(
      (response) =>
        response.request().method() === (isPrototype(info) ? 'PUT' : 'POST') &&
        response.url().endsWith(isPrototype(info) ? '/auth/lang' : '/system/auth/language'),
    );
    await page.getByRole('option', { name: label, exact: true }).click();
    expect((await saved).ok()).toBe(true);
    await expect(page.getByRole('menuitem', { name: words.nav.orders, exact: true })).toBeVisible();
    await page.reload();
    await expect(page.getByRole('menuitem', { name: words.nav.orders, exact: true })).toBeVisible();
    await expect(page).toHaveTitle(words.app.title);
  }
  await page.setViewportSize({ width: 760, height: 844 });
  const aside = page.locator(isPrototype(info) ? '.aside' : '.prototype-sidebar');
  await expect.poll(async () => Math.round((await aside.boundingBox())!.width)).toBe(64);
  await page
    .locator(isPrototype(info) ? '.header .left > button' : '.prototype-header-left > button')
    .click();
  await expect.poll(async () => Math.round((await aside.boundingBox())!.width)).toBe(220);
  await page.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
  await expect(page.getByRole('heading', { name: zh.orders.title, exact: true })).toBeVisible();
  await page.screenshot({ path: info.outputPath('orders-narrow.png') });
});

test('P02 首次访问跟随浏览器语言', async ({ browser }, info) => {
  for (const [locale, words] of [
    ['en-US', en],
    ['vi-VN', vi],
    ['zh-TW', zh],
  ] as const) {
    const context = await browser.newContext({ locale, baseURL: info.project.use.baseURL });
    try {
      const page = await context.newPage();
      await page.goto('/login');
      await expect(
        page.getByRole('heading', { name: words.login.title, exact: true }),
      ).toBeVisible();
      await expect(page).toHaveTitle(words.app.title);
    } finally {
      await context.close();
    }
  }
});

test('P05 超过一页的分录连续展示 按单据删除过期快照', async ({ page, request }, info) => {
  const health = await request.get('http://127.0.0.1:19090');
  expect(await health.json()).toMatchObject({
    fixture: 'synthetic comparison only',
    extrasSupported: true,
  });
  const fixtureFile = resolve('../tools/.runtime/comparison-20261008/extra-orders.json');
  const bill = 'CMP-MO-MANY';
  const rows = Array.from({ length: 101 }, (_, index) => ({
    'F_ora_kh.FNumber': 'CMP-CUSTOMER',
    FBillNo: bill,
    F_HX_PO: 'CMP-PO-MANY',
    F_HX_PI: 'CMP-PI-MANY',
    'FMaterialId.FNumber': `CMP-LINE-${String(index + 1).padStart(3, '0')}`,
    'FMaterialId.FName': `比较物料 ${index + 1}`,
    FQty: '1',
    FStatus: '1',
    FSaleOrderNo: 'CMP-SO',
    'FPrdOrgId.FName': '比较测试甲厂',
    FID: '9007199254741999',
    FTreeEntity_FEntryId: String(9100 + index),
    FPrdOrgId: '101',
    'FPrdOrgId.FNumber': 'CMP-A',
  }));
  writeFileSync(fixtureFile, JSON.stringify(rows), 'utf8');
  try {
    await login(page, info);
    await page.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
    await page.getByPlaceholder(zh.orders.billToSync, { exact: true }).fill(bill);
    await page.getByRole('button', { name: zh.orders.syncOne, exact: true }).click();
    await expect(page.getByText(`单据 ${bill} 已同步，101 行`, { exact: true })).toBeVisible();
    await page.getByRole('cell', { name: bill, exact: true }).click();
    await expect(page.getByRole('cell', { name: /^CMP-LINE-\d{3}$/, exact: true })).toHaveCount(
      101,
    );
    await page.getByRole('cell', { name: 'CMP-LINE-101', exact: true }).scrollIntoViewIfNeeded();
    await expect(page.getByRole('cell', { name: 'CMP-LINE-101', exact: true })).toBeVisible();
    await page.screenshot({ path: info.outputPath('order-101-lines.png') });
    writeFileSync(fixtureFile, '[]', 'utf8');
    await page.getByRole('button', { name: zh.orders.syncOne, exact: true }).click();
    await expect(
      page.getByText(zh.orders.syncedRemoved.replace('{{bill}}', bill), { exact: true }),
    ).toBeVisible();
    await expect(page.getByRole('cell', { name: bill, exact: true })).toHaveCount(0);
  } finally {
    writeFileSync(fixtureFile, '[]', 'utf8');
  }
});
