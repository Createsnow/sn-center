import { test, expect } from '@playwright/test';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { protectTestSecrets } from './privacy';

const secrets = resolve('../deploy/.secrets/container-test');
const currentFile = resolve(secrets, 'container-admin-current-password');
const password = readFileSync(
  existsSync(currentFile) ? currentFile : resolve(secrets, 'container-admin-password'),
  'utf8',
).trim();
const changed = 'Changed9!' + randomBytes(20).toString('hex');
protectTestSecrets(password, changed);
const restoring = process.env.ADTEK_CONTAINER_VERIFY_RESTORE === '1';
const fixtureFile = resolve('test-results/container-fixture.json');
type Fixture = { pi: string; factoryCode: string; files: Record<string, string> };
const digest = (file: string) => createHash('sha256').update(readFileSync(file)).digest('hex');

test('HTTPS容器完成登录、原SN导入与双格式下载；恢复模式核验原快照', async ({ page, context }) => {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.name));
  const response = await page.goto('/login');
  expect(response?.status()).toBe(200);
  expect((await response!.allHeaders())['content-security-policy']).toContain(
    "frame-ancestors 'none'",
  );
  const login = async (value: string) => {
    await page.getByLabel('工号', { exact: true }).fill('container_admin');
    await page.getByLabel('密码', { exact: true }).fill(value);
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await expect(page).toHaveURL(/\/(dashboard|change-password)$/);
  };
  await login(password);
  if (page.url().endsWith('/change-password')) {
    expect(restoring).toBe(false);
    await page.getByLabel('当前密码', { exact: true }).fill(password);
    await page.getByLabel('新密码', { exact: true }).fill(changed);
    await page.getByLabel('确认新密码', { exact: true }).fill(changed);
    await page.getByRole('button', { name: '修改密码', exact: true }).click();
    await expect(page).toHaveURL(/\/login$/);
    writeFileSync(currentFile, changed, { mode: 0o600 });
    await login(changed);
  }
  await expect(page.getByRole('heading', { name: '工作台', exact: true })).toBeVisible();
  // 认证成功会清理旧CSRF值；显式获取新令牌后再检查延迟生成的Cookie。
  expect((await page.request.get('/api/v1/system/auth/csrf')).status()).toBe(200);
  for (const name of ['ADTEK-REFRESH', 'ADTEK-CSRF']) {
    const cookie = (await context.cookies()).find((c) => c.name === name);
    expect(cookie?.secure).toBe(true);
    expect(cookie?.httpOnly).toBe(true);
    expect(cookie?.sameSite).toBe('Strict');
  }
  await page.reload();
  await expect(page.getByRole('heading', { name: '工作台', exact: true })).toBeVisible();
  const suffix = randomUUID().slice(0, 8);
  const fixture: Fixture = restoring
    ? (JSON.parse(readFileSync(fixtureFile, 'utf8')) as Fixture)
    : { pi: 'CONTAINER-' + suffix, factoryCode: 'CT-' + suffix, files: {} };
  if (!restoring) {
    await page.goto('/factories');
    await page.getByRole('button', { name: '手工新增', exact: true }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('工厂编码', { exact: true }).fill(fixture.factoryCode);
    await dialog.getByLabel('工厂名称', { exact: true }).fill('容器演练工厂' + suffix);
    await dialog.getByRole('button', { name: /^保\s*存$/ }).click();
    await expect(dialog).toBeHidden();
    await page.goto('/pi-init?pi=' + fixture.pi);
    await expect(page.getByTestId('sn-watermark')).toHaveText('0');
    await page.getByLabel('起始号（十进制，可选）', { exact: true }).fill('9007199254740993');
    await page
      .getByLabel('历史 SN 清单（可选）', { exact: true })
      .fill('LEGACY-A\nLEGACY-B\nLEGACY-C');
    await page.getByRole('combobox', { name: '工厂', exact: true }).click();
    await page
      .locator('.ant-select-dropdown:visible')
      .getByText(new RegExp(fixture.factoryCode))
      .click();
    await page.getByRole('button', { name: '执行初始化', exact: true }).click();
    await page.getByRole('button', { name: /^确\s*认$/ }).click();
    await expect(page.getByTestId('sn-task-state')).toHaveText('已完成', { timeout: 30_000 });
    await expect(page.getByTestId('sn-watermark')).toHaveText('9007199254740992');
  }
  await page.goto('/query');
  await page.getByRole('textbox', { name: 'PI', exact: true }).fill(fixture.pi);
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.getByText('共 3 枚', { exact: true })).toBeVisible();
  await expect(page.getByTestId('sn-query-items')).toContainText('LEGACY-C');
  await page.screenshot({
    path: 'test-results/container-' + (restoring ? 'restored' : 'source') + '.png',
    fullPage: true,
    animations: 'disabled',
  });
  if (!restoring) {
    for (const format of ['CSV', 'XLSX']) {
      await page.getByRole('button', { name: '导出 ' + format, exact: true }).click();
      await page.getByRole('button', { name: /^确\s*认$/ }).click();
      await expect(page.getByTestId('sn-task-state').last()).toHaveText('已完成', {
        timeout: 30_000,
      });
    }
  }
  await page.getByRole('tab', { name: '导出记录', exact: true }).click();
  for (const format of ['CSV', 'XLSX']) {
    const button = page
      .getByTestId('sn-query-exports')
      .getByRole('button', { name: format + ' 下载', exact: true })
      .first();
    await expect(button).toBeEnabled({ timeout: 30_000 });
    const pending = page.waitForEvent('download');
    await button.click();
    const download = await pending;
    const file =
      'test-results/container-' + (restoring ? 'restored.' : 'source.') + format.toLowerCase();
    await download.saveAs(file);
    if (restoring) expect(digest(file)).toBe(fixture.files[format]);
    else fixture.files[format] = digest(file);
    await expect(button).not.toHaveClass(/ant-btn-loading/);
  }
  if (!restoring) writeFileSync(fixtureFile, JSON.stringify(fixture, null, 2));
  if (process.env.ADTEK_CONTAINER_VERIFY_ARCHIVE === '1') {
    // 独立演练的合成8月痕迹；证明在线移除后原页面仍从永久归档读取同一条事实。
    await page.goto('/audit');
    await page.locator('input[type="date"]').first().fill('2026-08-01');
    await page.locator('input[type="date"]').last().fill('2026-08-31');
    await page.getByLabel('操作人工号', { exact: true }).fill('archive_rehearsal');
    for (const [label, quantity] of [
      ['在线记录', '0'],
      ['归档记录', '1'],
      ['在线与归档', '1'],
    ] as const) {
      await page.getByRole('combobox', { name: '记录范围', exact: true }).click();
      await page.locator('.ant-select-dropdown:visible').getByText(label, { exact: true }).click();
      await page.getByRole('button', { name: '查询', exact: true }).click();
      await expect(page.getByText('共 ' + quantity + ' 条', { exact: true })).toBeVisible();
    }
    await expect(page.getByTestId('sn-audit-items')).toContainText('9007199254740993');
    await page.screenshot({ path: 'test-results/container-archive.png', fullPage: true });
  }
  await page.getByRole('button', { name: '退出登录' }).click();
  await expect(page).toHaveURL(/\/login$/);
  expect(errors).toEqual([]);
});
