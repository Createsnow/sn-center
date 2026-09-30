import { test, expect } from '@playwright/test';
import { protectTestSecrets } from './privacy';
import { randomBytes } from 'node:crypto';

const adminPassword = process.env.ADTEK_E2E_INITIAL_PASSWORD;
if (!adminPassword) {
  throw new Error('Use tools/verify-users-browser.ps1 with an isolated backend.');
}
const initial = `It9!${randomBytes(16).toString('hex')}`;
const reset = `Reset8!${randomBytes(16).toString('hex')}`;
const changed = `Changed7!${randomBytes(16).toString('hex')}`;
protectTestSecrets(adminPassword, initial, reset, changed);

test('真实用户页面完成新增、编辑、启停、重置、首次改密及三语菜单', async ({ page, context }) => {
  const pageErrors: string[] = [];
  page.on('pageerror', (error) => pageErrors.push(error.name));
  await page.goto('/login');
  await page.getByLabel('工号', { exact: true }).fill('browser_admin');
  await page.getByLabel('密码', { exact: true }).fill(adminPassword);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByRole('menuitem', { name: '用户管理' }).click();
  await expect(page.getByRole('heading', { name: '用户管理', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '新增用户', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('工号', { exact: true }).fill('browser_user');
  await dialog.getByLabel('姓名', { exact: true }).fill('浏览器测试用户');
  await dialog.getByLabel('部门', { exact: true }).click();
  await page.locator('.ant-select-dropdown:visible').getByText('总部', { exact: true }).click();
  await dialog.getByLabel('角色', { exact: true }).click();
  await page
    .locator('.ant-select-dropdown:visible')
    .getByText('工厂操作员 · 本人', { exact: true })
    .click();
  await page.keyboard.press('Escape');
  await dialog.getByLabel('新密码', { exact: true }).fill(initial);
  await dialog.getByRole('button', { name: '保存', exact: true }).click();
  await expect(dialog).toBeHidden();
  let row = page.getByRole('row').filter({ hasText: 'browser_user' });
  await expect(row).toContainText('浏览器测试用户');
  await expect(row).toContainText('总部');
  await row.getByRole('button', { name: '编辑', exact: true }).click();
  await dialog.getByLabel('姓名', { exact: true }).fill('用户管理样板');
  await dialog.getByRole('button', { name: '保存', exact: true }).click();
  await expect(dialog).toBeHidden();
  await expect(row).toContainText('用户管理样板');
  await row.getByRole('button', { name: '停用', exact: true }).click();
  await page.getByRole('button', { name: '确认', exact: true }).click();
  await expect(row).toContainText('已停用');
  await row.getByRole('button', { name: '启用', exact: true }).click();
  await page.getByRole('button', { name: '确认', exact: true }).click();
  await expect(row).toContainText('已启用');
  await row.getByRole('button', { name: '重置密码', exact: true }).click();
  await dialog.getByLabel('新密码', { exact: true }).fill(reset);
  await dialog.getByRole('button', { name: '保存', exact: true }).click();
  await expect(dialog).toBeHidden();
  await page.screenshot({ path: 'test-results/users-zh.png', fullPage: true });
  await page.reload();
  await expect(page.getByRole('heading', { name: '用户管理', exact: true })).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect
    .poll(async () => (await page.locator('.ant-layout-sider').boundingBox())?.width ?? 0)
    .toBeLessThan(2);
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
    .toBe(true);
  await page.screenshot({ path: 'test-results/users-mobile.png', fullPage: true });
  await page.setViewportSize({ width: 1440, height: 960 });
  await expect
    .poll(async () => (await page.locator('.ant-layout-sider').boundingBox())?.width ?? 0)
    .toBe(220);
  // 偏好 API 已有 HTTP 覆盖；这里通过个人设置切换并核验菜单与用户页文案。
  for (const locale of [
    { name: 'English', profile: 'Personal settings', menu: 'Users' },
    { name: 'Tiếng Việt', profile: 'Cài đặt cá nhân', menu: 'Người dùng' },
  ]) {
    await page.goto('/profile');
    await page.getByRole('combobox').click();
    await page.getByText(locale.name, { exact: true }).click();
    await expect(page.getByRole('menuitem', { name: locale.menu })).toBeVisible();
    await page.getByRole('menuitem', { name: locale.menu }).click();
    await expect(page.getByRole('heading', { name: locale.menu, exact: true })).toBeVisible();
  }
  // 独立浏览器上下文验证真实重置密码和首次改密；不覆盖管理员的共享刷新 Cookie。
  const targetContext = await context.browser()!.newContext({ baseURL: process.env.ADTEK_E2E_URL });
  const target = await targetContext.newPage();
  try {
    await target.goto('/login');
    await target.getByLabel('工号', { exact: true }).fill('browser_user');
    await target.getByLabel('密码', { exact: true }).fill(reset);
    await target.getByRole('button', { name: '登录', exact: true }).click();
    await expect(target.getByRole('heading', { name: '设置你的新密码' })).toBeVisible();
    await target.getByLabel('当前密码', { exact: true }).fill(reset);
    await target.getByLabel('新密码', { exact: true }).fill(changed);
    await target.getByLabel('确认新密码', { exact: true }).fill(changed);
    await target.getByRole('button', { name: '修改密码', exact: true }).click();
    await expect(target.getByRole('heading', { name: '登录工作台' })).toBeVisible();
    await target.getByLabel('工号', { exact: true }).fill('browser_user');
    await target.getByLabel('密码', { exact: true }).fill(changed);
    await target.getByRole('button', { name: '登录', exact: true }).click();
    await expect(target.getByRole('heading', { name: '工作台', exact: true })).toBeVisible();
    await target.goto('/system/users');
    await expect(target).toHaveURL(/\/profile$/);
    await expect(target.getByRole('menuitem', { name: '用户管理' })).toHaveCount(0);
  } finally {
    await targetContext.close();
  }
  row = page.getByRole('row').filter({ hasText: 'browser_user' });
  await expect(row).toBeVisible();
  expect(pageErrors).toEqual([]);
});
