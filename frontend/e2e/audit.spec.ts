import { test, expect } from '@playwright/test';
import { randomBytes } from 'node:crypto';
import { protectTestSecrets } from './privacy';
const password = process.env.ADTEK_E2E_INITIAL_PASSWORD;
if (!password) {
  throw new Error('Use the isolated browser harness.');
}
const wrong = 'Wrong9!' + randomBytes(12).toString('hex');
const next = 'Next9!' + randomBytes(12).toString('hex');
protectTestSecrets(password, wrong, next);

test('真实审计页面查询本人失败、字段diff、追踪号和三语移动布局', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.name));
  await page.goto('/login');
  await page.getByLabel('工号', { exact: true }).fill('browser_audit');
  await page.getByLabel('密码', { exact: true }).fill(password);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('menuitem', { name: '审计日志', exact: true })).toBeVisible();
  await expect(page.getByRole('menuitem', { name: '用户管理', exact: true })).toHaveCount(0);
  await page.goto('/change-password');
  await page.getByLabel('当前密码', { exact: true }).fill(wrong);
  await page.getByLabel('新密码', { exact: true }).fill(next);
  await page.getByLabel('确认新密码', { exact: true }).fill(next);
  await page.getByRole('button', { name: '修改密码', exact: true }).click();
  await expect(page.getByText('当前密码不正确。', { exact: true })).toBeVisible();
  await page.getByRole('menuitem', { name: '审计日志', exact: true }).click();
  await expect(page.getByRole('heading', { name: '操作日志', exact: true })).toBeVisible();
  await page.getByLabel('结果', { exact: true }).click();
  await page.locator('.ant-select-dropdown:visible').getByText('失败', { exact: true }).click();
  await page.getByRole('button', { name: '查询', exact: true }).click();
  const row = page.getByRole('row').filter({ hasText: '请求被拒绝' });
  await expect(row).toContainText('browser_audit');
  await expect(page.getByRole('row').filter({ hasText: 'browser_admin' })).toHaveCount(0);
  const id = await row.getAttribute('data-row-key');
  expect(id).toMatch(/^[1-9][0-9]+$/);
  expect(BigInt(id!)).toBeGreaterThan(BigInt(Number.MAX_SAFE_INTEGER));
  await row.getByRole('button', { name: '查看详情', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('当前密码不正确。');
  await expect(dialog).toContainText('/api/v1/system/auth/password');
  await expect(dialog).not.toContainText(wrong);
  await expect(dialog).not.toContainText(next);
  const trace = (await dialog.getByTestId('audit-trace').innerText()).trim();
  await page.screenshot({
    path: 'test-results/audit-detail-zh.png',
    fullPage: true,
    animations: 'disabled',
  });
  await dialog.getByRole('button', { name: '关闭', exact: true }).click();
  await page.getByLabel('追踪号', { exact: true }).fill(trace);
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.getByText('共 1 条记录', { exact: true })).toBeVisible();
  await page.getByLabel('操作者工号', { exact: true }).fill('browser_admin');
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.getByText('共 0 条记录', { exact: true })).toBeVisible();
  await page.goto('/profile');
  await page.getByRole('combobox').click();
  await page.getByText('English', { exact: true }).click();
  await page.getByRole('menuitem', { name: 'Audit log', exact: true }).click();
  await page.getByLabel('Action', { exact: true }).click();
  await page
    .locator('.ant-select-dropdown:visible')
    .getByText('Change language', { exact: true })
    .click();
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await page
    .getByRole('row')
    .filter({ hasText: 'Change language' })
    .getByRole('button', { name: 'View details', exact: true })
    .click();
  await expect(dialog).toContainText('zh-CN');
  await expect(dialog).toContainText('Language');
  await dialog
    .locator('.ant-modal-footer')
    .getByRole('button', { name: 'Close', exact: true })
    .click();
  await page.screenshot({
    path: 'test-results/audit-en.png',
    fullPage: true,
    animations: 'disabled',
  });
  await page.goto('/profile');
  await page.getByRole('combobox').click();
  await page.getByText('Tiếng Việt', { exact: true }).click();
  await page.getByRole('menuitem', { name: 'Nhật ký kiểm toán', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Nhật ký thao tác', exact: true })).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect
    .poll(async () => (await page.locator('.ant-layout-sider').boundingBox())?.width ?? 0)
    .toBeLessThan(2);
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
    .toBe(true);
  await page.screenshot({
    path: 'test-results/audit-mobile-vi.png',
    fullPage: true,
    animations: 'disabled',
  });
  expect(errors).toEqual([]);
});
