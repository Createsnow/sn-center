import { test, expect } from '@playwright/test';
import { protectTestSecrets } from './privacy';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

// 独立运行器通过子进程环境提供一次性账号；本机 smoke 可显式指定仍需改密的测试账号文件。
const password =
  process.env.ADTEK_E2E_INITIAL_PASSWORD ??
  readFileSync(
    process.env.ADTEK_E2E_PASSWORD_FILE ?? resolve('../deploy/.secrets/bootstrap-admin-password'),
    'utf8',
  ).trim();
const username = process.env.ADTEK_E2E_USERNAME ?? 'admin';
protectTestSecrets(password);

test('三种语言登录页、移动布局与拒绝错误凭据', async ({ page }) => {
  const pageErrors: string[] = [];
  page.on('pageerror', (error) => pageErrors.push(error.name));
  await page.goto('/');
  await expect(page.getByRole('heading', { name: '登录工作台' })).toBeVisible();
  await page.screenshot({ path: 'test-results/login-zh.png', fullPage: true });
  await page.getByRole('combobox', { name: '语言' }).click();
  await page.getByText('English', { exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Sign in to your workspace' })).toBeVisible();
  await page.getByRole('combobox', { name: 'Language' }).click();
  await page.getByText('Tiếng Việt', { exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Đăng nhập không gian làm việc' })).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByRole('button', { name: 'Đăng nhập', exact: true })).toBeVisible();
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
    .toBe(true);
  await page.getByLabel('Mã nhân viên', { exact: true }).fill(username);
  await page.getByLabel('Mật khẩu', { exact: true }).fill('wrong-credentials-9');
  await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
  await expect(
    page.getByText('Mã nhân viên hoặc mật khẩu không đúng, hoặc tài khoản đã bị vô hiệu hóa.', {
      exact: true,
    }),
  ).toBeVisible();
  await page.getByLabel('Mật khẩu', { exact: true }).clear();
  await page.screenshot({ path: 'test-results/login-vi-mobile.png', fullPage: true });
  expect(pageErrors).toEqual([]);
});

test('真实登录强制改密、刷新恢复、HttpOnly 与退出闭环', async ({ page, context }) => {
  await page.goto('/login');
  await page.getByLabel('工号', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill(password);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('heading', { name: '设置你的新密码' })).toBeVisible();
  await page.goto('/profile');
  await expect(page).toHaveURL(/\/change-password$/);
  await expect(page.getByRole('heading', { name: '设置你的新密码' })).toBeVisible();
  const cookies = await context.cookies();
  expect(cookies.find((cookie) => cookie.name === 'ADTEK-REFRESH')?.httpOnly).toBe(true);
  expect(cookies.find((cookie) => cookie.name === 'ADTEK-REFRESH')?.sameSite).toBe('Strict');
  const storage = await page.evaluate(() => ({
    cookieExposesSecret: /ADTEK-REFRESH|ADTEK-CSRF/.test(document.cookie),
    localKeys: Object.keys(localStorage),
    sessionKeys: Object.keys(sessionStorage),
  }));
  expect(storage.cookieExposesSecret).toBe(false);
  expect(storage.localKeys.every((key) => key === 'adtek-language')).toBe(true);
  expect(storage.sessionKeys).toEqual([]);
  const otherTab = await context.newPage();
  await otherTab.goto('/profile');
  await expect(otherTab.getByRole('heading', { name: '设置你的新密码' })).toBeVisible();
  await Promise.all([page.reload(), otherTab.reload()]);
  await expect(page.getByRole('heading', { name: '设置你的新密码' })).toBeVisible();
  await expect(otherTab.getByRole('heading', { name: '设置你的新密码' })).toBeVisible();
  await page.screenshot({ path: 'test-results/first-password.png', fullPage: true });
  await page.getByRole('button', { name: '退出登录' }).click();
  await expect(page.getByRole('heading', { name: '登录工作台' })).toBeVisible();
  await expect(otherTab.getByRole('heading', { name: '登录工作台' })).toBeVisible();
  await otherTab.close();
  await page.reload();
  await expect(page.getByRole('heading', { name: '登录工作台' })).toBeVisible();
});

test('连续失败锁定在真实登录页显示中英越提示', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('工号', { exact: true }).fill('lock_browser_unknown');
  await page.getByLabel('密码', { exact: true }).fill('wrong-lock-credentials');
  for (let i = 0; i < 6; i++) {
    const [response] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().endsWith('/system/auth/login') && r.request().method() === 'POST',
      ),
      page.getByRole('button', { name: '登录', exact: true }).click(),
    ]);
    expect(response.status()).toBe(i < 5 ? 401 : 429);
  }
  await expect(page.getByText('登录已锁定，请在 15 分钟后重试。', { exact: true })).toBeVisible();
  await page.getByLabel('密码', { exact: true }).clear();
  await page.screenshot({ path: 'test-results/login-locked-zh.png', fullPage: true });
  await page.getByRole('combobox', { name: '语言' }).click();
  await page.getByText('English', { exact: true }).click();
  await expect(
    page.getByText('Login is locked. Try again in 15 minute(s).', { exact: true }),
  ).toBeVisible();
  await page.getByRole('combobox', { name: 'Language' }).click();
  await page.getByText('Tiếng Việt', { exact: true }).click();
  await expect(
    page.getByText('Đăng nhập bị khóa. Vui lòng thử lại sau 15 phút.', { exact: true }),
  ).toBeVisible();
});
