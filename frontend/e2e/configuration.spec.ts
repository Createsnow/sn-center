import { test, expect } from '@playwright/test';
import { protectTestSecrets } from './privacy';

const password = process.env.ADTEK_E2E_INITIAL_PASSWORD;
if (!password) {
  throw new Error('Run the isolated browser harness.');
}
protectTestSecrets(password);

test('真实配置页面完成部门、角色范围、菜单和三语字典维护', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.name));
  await page.goto('/login');
  await page.getByLabel('工号', { exact: true }).fill('browser_config');
  await page.getByLabel('密码', { exact: true }).fill(password);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByRole('menuitem', { name: '部门管理', exact: true }).click();
  await page.getByRole('button', { name: '新增', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('名称', { exact: true }).fill('E2E 部门');
  await dialog.getByRole('button', { name: '保存', exact: true }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByRole('row').filter({ hasText: 'E2E 部门' })).toBeVisible();
  await page.getByRole('menuitem', { name: '角色管理', exact: true }).click();
  await page.getByRole('button', { name: '新增', exact: true }).click();
  await dialog.getByLabel('编码', { exact: true }).fill('E2E_READER');
  await dialog.getByLabel('名称', { exact: true }).fill('E2E 只读角色');
  await dialog.getByLabel('部门数据范围', { exact: true }).click();
  await page
    .locator('.ant-select-dropdown:visible')
    .getByText('自定义部门', { exact: true })
    .click();
  await dialog.getByLabel('自定义部门', { exact: true }).click();
  await page
    .locator('.ant-select-dropdown:visible')
    .getByText(/E2E 部门/)
    .click();
  await page.keyboard.press('Escape');
  await dialog.getByLabel('菜单和操作权限', { exact: true }).click();
  await page
    .locator('.ant-select-dropdown:visible')
    .getByText('用户管理 · system:user:list', { exact: true })
    .click();
  await page.keyboard.press('Escape');
  await dialog.getByRole('button', { name: '保存', exact: true }).click();
  await expect(dialog).toBeHidden();
  let row = page.getByRole('row').filter({ hasText: 'E2E_READER' });
  await expect(row).toContainText('自定义部门');
  await row.getByRole('button', { name: '编辑', exact: true }).click();
  await dialog.getByLabel('部门数据范围', { exact: true }).click();
  await page.locator('.ant-select-dropdown:visible').getByText('本人', { exact: true }).click();
  await dialog.getByRole('button', { name: '保存', exact: true }).click();
  await expect(dialog).toBeHidden();
  await expect(row).toContainText('本人');
  await page.screenshot({ path: 'test-results/configuration-roles-zh.png', fullPage: true });
  await page.getByRole('menuitem', { name: '菜单管理', exact: true }).click();
  await page.getByRole('button', { name: '新增', exact: true }).click();
  await dialog.getByLabel('名称', { exact: true }).fill('E2E 目录');
  await dialog.getByRole('button', { name: '保存', exact: true }).click();
  await expect(dialog).toBeHidden();
  // SN目录增加后新菜单不再必在第一页，使用原有搜索定位目标。
  await page.getByLabel('搜索名称或编码', { exact: true }).fill('E2E');
  row = page.getByRole('row').filter({ hasText: 'E2E 目录' });
  await row.getByRole('button', { name: '编辑', exact: true }).click();
  await dialog.getByLabel('名称', { exact: true }).fill('E2E 导航');
  await dialog.getByRole('switch', { name: '启用', exact: true }).click();
  await dialog.getByRole('button', { name: '保存', exact: true }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByRole('row').filter({ hasText: 'E2E 导航' })).toContainText('已停用');
  await page.getByRole('menuitem', { name: '字典管理', exact: true }).click();
  await page.getByRole('button', { name: '新增', exact: true }).click();
  await dialog.getByLabel('编码', { exact: true }).fill('E2E_STATE');
  await dialog.getByLabel('名称', { exact: true }).fill('E2E 状态');
  await dialog.getByRole('button', { name: '保存', exact: true }).click();
  await expect(dialog).toBeHidden();
  await page
    .getByRole('row')
    .filter({ hasText: 'E2E_STATE' })
    .getByRole('button', { name: '管理字典值' })
    .click();
  await page.getByRole('button', { name: '新增', exact: true }).click();
  await dialog.getByLabel('字典值', { exact: true }).fill('READY');
  await dialog.getByLabel('中文名称', { exact: true }).fill('就绪');
  await dialog.getByLabel('英文名称', { exact: true }).fill('Ready');
  await dialog.getByLabel('越文名称', { exact: true }).fill('Sẵn sàng');
  await dialog.getByRole('button', { name: '保存', exact: true }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByRole('row').filter({ hasText: 'READY' })).toContainText('就绪');
  for (const locale of [
    { name: 'English', menu: 'Dictionaries', items: 'Manage values', label: 'Ready' },
    { name: 'Tiếng Việt', menu: 'Từ điển', items: 'Quản lý giá trị', label: 'Sẵn sàng' },
  ]) {
    await page.goto('/profile');
    await page.getByRole('combobox').click();
    await page.getByText(locale.name, { exact: true }).click();
    await page.getByRole('menuitem', { name: locale.menu, exact: true }).click();
    await page
      .getByRole('row')
      .filter({ hasText: 'E2E_STATE' })
      .getByRole('button', { name: locale.items, exact: true })
      .click();
    await expect(page.getByRole('row').filter({ hasText: 'READY' })).toContainText(locale.label);
  }
  await page.screenshot({ path: 'test-results/configuration-dictionary-vi.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect
    .poll(async () => (await page.locator('.ant-layout-sider').boundingBox())?.width ?? 0)
    .toBeLessThan(2);
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
    .toBe(true);
  await page.screenshot({ path: 'test-results/configuration-mobile.png', fullPage: true });
  expect(errors).toEqual([]);
});
