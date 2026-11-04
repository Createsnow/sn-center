import { test, expect, type Page } from '@playwright/test';
import { protectTestSecrets } from './privacy';
const password = process.env.ADTEK_E2E_INITIAL_PASSWORD;
if (!password) throw new Error('Run tools/verify-browser.ps1 with isolated fixtures.');
protectTestSecrets(password);
async function login(page: Page, username: string) {
  await page.goto('/login');
  await page.getByLabel('工号', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill(password!);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('heading', { name: '设备台账', exact: true })).toBeVisible();
}
test('设备新增、详情、编辑、筛选、重复编码与审计', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await login(page, 'browser_equipment1');
  await page.getByRole('button', { name: '新增设备', exact: true }).click();
  let dialog = page.getByRole('dialog');
  await dialog.getByLabel('设备编码', { exact: true }).fill('BROWSER-EQ');
  await dialog.getByLabel('设备名称', { exact: true }).fill('测量设备');
  await dialog.getByRole('button', { name: '保存', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  const row = page.getByRole('row').filter({ hasText: 'BROWSER-EQ' });
  await expect(row).toHaveCount(1);
  await row.getByRole('button', { name: '设备详情', exact: true }).click();
  dialog = page.getByRole('dialog');
  await expect(dialog.getByText('9007199254741101', { exact: true })).toBeVisible();
  await dialog.getByRole('button', { name: '关闭', exact: true }).click();
  await row.getByRole('button', { name: '编辑设备', exact: true }).click();
  dialog = page.getByRole('dialog');
  await expect(dialog.getByLabel('设备编码', { exact: true })).toBeDisabled();
  await dialog.getByLabel('设备名称', { exact: true }).fill('校验设备');
  await dialog.getByRole('switch').click();
  await dialog.getByRole('button', { name: '保存', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect(row).toContainText('校验设备');
  await expect(row).toContainText('停用');
  await page.getByRole('button', { name: '新增设备', exact: true }).click();
  dialog = page.getByRole('dialog');
  await dialog.getByLabel('设备编码', { exact: true }).fill('BROWSER-EQ');
  await dialog.getByLabel('设备名称', { exact: true }).fill('重复');
  await dialog.getByRole('button', { name: '保存', exact: true }).click();
  await expect(dialog.getByText('设备编码已存在，请使用其他编码。', { exact: true })).toBeVisible();
  await dialog.getByRole('button', { name: '取消', exact: true }).click();
  await page.getByRole('searchbox', { name: '搜索编码或名称', exact: true }).fill('NO-MATCH');
  await page.getByRole('searchbox', { name: '搜索编码或名称', exact: true }).press('Enter');
  await expect(page.getByText('暂无设备', { exact: true })).toBeVisible();
  await page.getByRole('searchbox', { name: '搜索编码或名称', exact: true }).fill('BROWSER');
  await page.getByRole('searchbox', { name: '搜索编码或名称', exact: true }).press('Enter');
  await expect(row).toHaveCount(1);
  await page.screenshot({
    path: 'test-results/equipment-zh.png',
    fullPage: true,
    animations: 'disabled',
  });
  expect(errors).toEqual([]);
});
test('只读权限不显示写按钮，另一个本人范围账号看不到其他设备', async ({ page }) => {
  await login(page, 'browser_equipment_read');
  await expect(page.getByRole('button', { name: '新增设备', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '编辑设备', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '设备详情', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '退出登录', exact: true }).click();
  await expect(page.getByRole('heading', { name: '登录工作台', exact: true })).toBeVisible();
  await login(page, 'browser_equipment2');
  await expect(page.getByRole('row').filter({ hasText: 'BROWSER-EQ' })).toHaveCount(0);
});
test('设备页面英语、越南语与移动布局', async ({ page }) => {
  await login(page, 'browser_equipment3');
  await page.goto('/profile');
  await page.getByRole('combobox', { name: '语言', exact: true }).click();
  await page.getByText('English', { exact: true }).click();
  await expect(page.getByRole('heading', { name: 'My settings', exact: true })).toBeVisible();
  await page.goto('/equipment');
  await expect(
    page.getByRole('heading', { name: 'Equipment register', exact: true }),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Add equipment', exact: true }).click();
  await expect(
    page.getByRole('dialog').getByLabel('Equipment code', { exact: true }),
  ).toBeVisible();
  await page.getByRole('dialog').getByLabel('Equipment code', { exact: true }).fill('EN-PROOF');
  await page
    .getByRole('dialog')
    .getByLabel('Equipment name', { exact: true })
    .fill('Calibration gauge');
  // 输入和按钮稳定后再保存视觉证据，避免捕获弹窗进入动画的透明帧。
  await page.getByRole('dialog').getByRole('button', { name: 'Save', exact: true }).hover();
  await page.screenshot({
    path: 'test-results/equipment-en.png',
    fullPage: true,
    animations: 'disabled',
  });
  await page.getByRole('dialog').getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByRole('row').filter({ hasText: 'EN-PROOF' })).toContainText(
    'Calibration gauge',
  );
  await page.goto('/profile');
  await page.getByRole('combobox', { name: 'Language', exact: true }).click();
  await page.getByText('Tiếng Việt', { exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Cài đặt cá nhân', exact: true })).toBeVisible();
  await page.goto('/equipment');
  await expect(page.getByRole('heading', { name: 'Danh mục thiết bị', exact: true })).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
    .toBe(true);
  await page.screenshot({
    path: 'test-results/equipment-vi-mobile.png',
    fullPage: true,
    animations: 'disabled',
  });
});
