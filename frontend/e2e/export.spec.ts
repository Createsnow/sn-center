import { test, expect } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import { protectTestSecrets } from './privacy';
const password = process.env.ADTEK_E2E_INITIAL_PASSWORD;
if (!password) throw new Error('Use the isolated browser harness.');
protectTestSecrets(password);

test('真实导出在响应丢失后按原操作恢复、刷新恢复及三语下载同一快照', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.name));
  await page.goto('/login');
  await page.getByLabel('工号', { exact: true }).fill('browser_export');
  await page.getByLabel('密码', { exact: true }).fill(password);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByRole('menuitem', { name: '审计日志', exact: true }).click();
  // 真实提交已由服务器处理后中断响应，不伪造API成功，验证未知结果恢复原操作。
  await page.route(
    '**/api/v1/system/audit-exports',
    async (route) => {
      await route.fetch();
      await route.abort('failed');
    },
    { times: 1 },
  );
  await page.getByRole('button', { name: '导出 CSV', exact: true }).click();
  await expect(page.getByRole('button', { name: '下载 CSV', exact: true })).toBeVisible({
    timeout: 20000,
  });
  const task = (await page.getByTestId('export-task').innerText()).trim();
  const operation = (await page.getByTestId('export-operation').innerText()).trim();
  const firstPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: '下载 CSV', exact: true }).click();
  const first = await firstPromise;
  await first.saveAs('test-results/task-export.csv');
  const csv = await readFile('test-results/task-export.csv', 'utf8');
  expect(csv).toContain('9007199254741005');
  expect(csv).toContain('browser_export');
  expect(csv).not.toContain('browser_admin');
  expect(csv).not.toContain('browser_audit');
  expect(csv).not.toContain(password);
  expect(csv.split('\r\n')[0]).toContain('id,created_at,user_id');
  await page.reload();
  await expect(page.getByTestId('export-task')).toHaveText(task);
  await expect(page.getByTestId('export-operation')).toContainText(operation);
  await page.getByRole('heading', { name: '导出任务', exact: true }).scrollIntoViewIfNeeded();
  await page.screenshot({
    path: 'test-results/task-export-zh.png',
    fullPage: true,
    animations: 'disabled',
  });
  await page.goto('/profile');
  await page.getByRole('combobox').click();
  await page.getByText('English', { exact: true }).click();
  await page.getByRole('menuitem', { name: 'Audit log', exact: true }).click();
  await expect(page.getByTestId('export-task')).toHaveText(task);
  const secondPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download CSV', exact: true }).click();
  const second = await secondPromise;
  await second.saveAs('test-results/task-export-repeat.csv');
  expect(await readFile('test-results/task-export-repeat.csv', 'utf8')).toBe(csv);
  await page.getByRole('heading', { name: 'Export task', exact: true }).scrollIntoViewIfNeeded();
  await page.screenshot({
    path: 'test-results/task-export-en.png',
    fullPage: true,
    animations: 'disabled',
  });
  await page.goto('/profile');
  await page.getByRole('combobox').click();
  await page.getByText('Tiếng Việt', { exact: true }).click();
  await page.getByRole('menuitem', { name: 'Nhật ký kiểm toán', exact: true }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByRole('button', { name: 'Tải CSV', exact: true })).toBeVisible();
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
    .toBe(true);
  await page.getByRole('heading', { name: 'Tác vụ xuất', exact: true }).scrollIntoViewIfNeeded();
  await page.screenshot({
    path: 'test-results/task-export-mobile-vi.png',
    fullPage: true,
    animations: 'disabled',
  });
  expect(errors).toEqual([]);
});
