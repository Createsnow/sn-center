import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';
import { protectTestSecrets } from '../privacy';
import { paceComparisonLogin } from './login-budget';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import en from '../../src/i18n/prototype/en.json' with { type: 'json' };
import vi from '../../src/i18n/prototype/vi.json' with { type: 'json' };

const privateRoot = resolve('../deploy/.secrets/comparison-20261008');
const accountsPath = resolve(privateRoot, 'role-accounts.json');
type Account = { name: string; initial: string; current: string; changed: boolean };
type Accounts = Record<'java' | 'python', Record<string, Account>>;
const accounts = () => JSON.parse(readFileSync(accountsPath, 'utf8')) as Accounts;
const prototype = (info: TestInfo) => info.project.name === 'prototype';
const kind = (info: TestInfo) => (prototype(info) ? 'python' : 'java');
const errors = new WeakMap<Page, string[]>();
test.beforeEach(async ({ page }) => {
  const list: string[] = [];
  errors.set(page, list);
  page.on('pageerror', (error) => list.push(error.message));
});
test.afterEach(async ({ page }) => {
  expect(errors.get(page)).toEqual([]);
});

async function credentials(page: Page, username: string, password: string) {
  await paceComparisonLogin(page);
  protectTestSecrets(password);
  await page.locator('input[autocomplete="username"]').fill(username);
  await page.locator('input[autocomplete="current-password"]').fill(password);
  await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
}

async function login(page: Page, info: TestInfo, username = 'admin') {
  await page.goto('/login');
  if (username === 'admin') {
    await credentials(
      page,
      username,
      readFileSync(resolve(privateRoot, `${kind(info)}-admin-password`), 'utf8').trim(),
    );
  } else {
    const all = accounts();
    const account = all[kind(info)][username]!;
    protectTestSecrets(account.initial, account.current);
    await credentials(page, username, account.changed ? account.current : account.initial);
    if (!account.changed) {
      await expect(page).toHaveURL(/\/password$/);
      await expect(page.getByText(zh.password.forced, { exact: true })).toBeVisible();
      await expect(page.getByRole('menubar')).toHaveCount(0);
      await page.locator('input[autocomplete="current-password"]').fill(account.initial);
      await page.locator('input[autocomplete="new-password"]').nth(0).fill(account.current);
      await page.locator('input[autocomplete="new-password"]').nth(1).fill(account.current);
      const saved = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          response.url().endsWith(prototype(info) ? '/auth/password' : '/system/auth/password'),
      );
      await page.getByRole('button', { name: zh.common.save, exact: true }).click();
      expect((await saved).ok()).toBe(true);
      // 在服务端已接受改密后立即记录，断言失败时下次继续使用当前凭据，不重置账号。
      account.changed = true;
      writeFileSync(accountsPath, JSON.stringify(all, null, 2));
      await expect(page).toHaveURL(/\/$/);
    }
  }
  await expect(page.getByRole('menuitem', { name: zh.nav.dashboard, exact: true })).toBeVisible();
}

function dashboard(page: Page, info: TestInfo) {
  return page.locator(prototype(info) ? '.page' : '.sn-dashboard-page');
}
function shortcuts(page: Page, info: TestInfo) {
  return page.locator(prototype(info) ? '.shortcuts' : '.sn-dashboard-shortcuts');
}
async function capture(page: Page, info: TestInfo, filename: string) {
  await expect
    .poll(() => dashboard(page, info).evaluate((element) => getComputedStyle(element).opacity))
    .toBe('1');
  await page.screenshot({ path: info.outputPath(filename), animations: 'disabled' });
}
async function assertKpis(page: Page, info: TestInfo, values: string[], labels: string[]) {
  const kpis = page.locator(prototype(info) ? '.kpi' : '.sn-dashboard-kpi');
  await expect(kpis).toHaveCount(values.length);
  await expect(kpis.locator(prototype(info) ? '.v' : '.value')).toHaveText(values);
  await expect(kpis.locator(prototype(info) ? '.l' : '.label')).toHaveText(labels);
}

test('P04 管理员首页指标 近期订单进度 快捷入口和窄屏', async ({ page }, info) => {
  await login(page, info);
  await page.getByRole('menuitem', { name: zh.nav.dashboard, exact: true }).click();
  await assertKpis(
    page,
    info,
    ['0', '1', '0', '0', '0', '10'],
    [
      zh.status.PENDING_ALLOC,
      zh.status.TO_ACQUIRE,
      zh.status.TO_PRINT,
      zh.status.APPLYING,
      zh.dashboard.pendingTransferKpi,
      zh.dashboard.totalGenerated,
    ],
  );
  await expect(
    dashboard(page, info).getByText(zh.dashboard.scopeAll, { exact: true }),
  ).toBeVisible();
  const expected = ['orders', 'generate', 'piInit', 'rules', 'acquire', 'transfer'] as const;
  await expect(shortcuts(page, info).getByRole('button')).toHaveText(
    expected.map((key) => zh.nav[key]),
  );
  const jobs = prototype(info)
    ? dashboard(page, info).locator('.surface').last()
    : page.getByTestId('dashboard-jobs');
  await expect(jobs.getByRole('cell', { name: 'CMP-MO-001', exact: true })).toBeVisible();
  await expect(
    jobs.getByRole('row').filter({ hasText: 'CMP-MO-001' }).getByRole('cell').first(),
  ).toHaveText('1');
  await expect(jobs.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '100');
  const today = prototype(info)
    ? dashboard(page, info).locator('.surface').first()
    : page.getByTestId('dashboard-today');
  await expect(today.getByRole('row').filter({ has: page.getByRole('cell') })).toHaveCount(4);
  const auditTotals = JSON.parse(
    execFileSync(
      resolve('../../sn-center-python/.venv/Scripts/python.exe'),
      [resolve('../tools/testing/parity-generation-fixture.py'), kind(info), 'today'],
      { windowsHide: true, encoding: 'utf8' },
    ),
  ) as Record<string, string>;
  writeFileSync(info.outputPath('today-audit-totals.json'), JSON.stringify(auditTotals, null, 2));
  for (const action of ['SN_ACQUIRE', 'SN_ALLOCATE', 'SN_GENERATE', 'SN_PRINT'] as const) {
    await expect(
      today.getByRole('row').filter({ hasText: zh.actions[action] }).getByRole('cell').last(),
    ).toHaveText(BigInt(auditTotals[action]!).toLocaleString());
  }
  await capture(page, info, 'dashboard-admin-desktop.png');
  for (const key of expected) {
    await shortcuts(page, info).getByRole('button', { name: zh.nav[key], exact: true }).click();
    await expect(page).toHaveURL(new RegExp('/' + (key === 'piInit' ? 'pi-init' : key) + '$'));
    await page.getByRole('menuitem', { name: zh.nav.dashboard, exact: true }).click();
    await expect(shortcuts(page, info)).toBeVisible();
  }
  await page.setViewportSize({ width: 760, height: 960 });
  await expect
    .poll(async () =>
      Math.round(
        (await page.locator(prototype(info) ? '.aside' : '.prototype-sidebar').boundingBox())!
          .width,
      ),
    )
    .toBe(64);
  await expect(shortcuts(page, info)).toBeVisible();
  await expect
    .poll(async () => Math.round((await page.getByRole('menuitem').first().boundingBox())!.y))
    .toBe(54);
  await capture(page, info, 'dashboard-admin-narrow.png');
});

for (const [username, role, bound, menuNames] of [
  [
    'cmp_operator',
    'factory_operator',
    true,
    ['dashboard', 'acquire', 'transfer', 'query', 'audit'],
  ],
  [
    'cmp_query_all',
    'query',
    false,
    ['dashboard', 'orders', 'acquire', 'transfer', 'query', 'audit'],
  ],
  [
    'cmp_query_factory',
    'query',
    true,
    ['dashboard', 'orders', 'acquire', 'transfer', 'query', 'audit'],
  ],
] as const) {
  test(`P01 P03 P04 ${username} 首次改密 角色范围与首页入口`, async ({ page }, info) => {
    await login(page, info, username);
    const menu = page.locator(prototype(info) ? '.aside .el-menu' : '.prototype-navigation');
    await expect(menu.getByRole('menuitem')).toHaveText(menuNames.map((key) => zh.nav[key]));
    await expect(
      page.locator(prototype(info) ? '.who-name' : '.prototype-user-name'),
    ).toContainText(zh.roles[role]);
    await expect(
      dashboard(page, info).getByText(
        bound ? zh.dashboard.scopeFactory.replace('{{f}}', '比较测试甲厂') : zh.dashboard.scopeAll,
        { exact: true },
      ),
    ).toBeVisible();
    const labels = [
      zh.status.TO_ACQUIRE,
      zh.status.TO_PRINT,
      zh.status.APPLYING,
      zh.dashboard.pendingTransferKpi,
    ];
    if (!bound) labels.push(zh.dashboard.totalGenerated);
    await assertKpis(page, info, bound ? ['0', '0', '0', '0'] : ['1', '0', '0', '0', '10'], labels);
    await expect(
      dashboard(page, info).getByText(zh.dashboard.recentJobs, { exact: true }),
    ).toHaveCount(0);
    const shortNames = menuNames.filter((key) => key !== 'dashboard');
    await expect(shortcuts(page, info).getByRole('button')).toHaveText(
      shortNames.map((key) => zh.nav[key]),
    );
    await capture(page, info, username + '-dashboard.png');
    await shortcuts(page, info).getByRole('button', { name: zh.nav.transfer, exact: true }).click();
    await expect(page).toHaveURL(/\/transfer$/);
    await page.getByRole('menuitem', { name: zh.nav.dashboard, exact: true }).click();
    await expect(shortcuts(page, info)).toBeVisible();
    await page.reload();
    await expect(menu.getByRole('menuitem')).toHaveText(menuNames.map((key) => zh.nav[key]));
    if (bound)
      await expect(
        page.locator(prototype(info) ? '.header .fac' : '.prototype-factory-tag'),
      ).toContainText('比较测试甲厂');
    await page.goto('/factories');
    await expect(page).toHaveURL(/\/$/);
    // 通过退出后的登录表单证明旧凭据失效、新凭据生效。
    await page.locator(prototype(info) ? '.who' : '.prototype-user').click();
    await page.getByRole('menuitem', { name: zh.app.logout, exact: true }).click();
    await expect(page).toHaveURL(/\/login$/);
    const account = accounts()[kind(info)][username]!;
    await credentials(page, username, account.initial);
    await expect(page.getByRole('button', { name: zh.login.submit, exact: true })).toBeEnabled();
    await expect(page).toHaveURL(/\/login$/);
    await credentials(page, username, account.current);
    await expect(menu.getByRole('menuitem')).toHaveText(menuNames.map((key) => zh.nav[key]));
  });
}

test('P04 首页三语文案与刷新保持', async ({ page }, info) => {
  await login(page, info);
  for (const [label, words] of [
    ['English', en],
    ['Tiếng Việt', vi],
    ['简体中文', zh],
  ] as const) {
    await page
      .locator(prototype(info) ? '.header .lang' : '.prototype-language .ant-select')
      .click();
    const saved = page.waitForResponse(
      (response) =>
        response.request().method() === (prototype(info) ? 'PUT' : 'POST') &&
        response.url().endsWith(prototype(info) ? '/auth/lang' : '/system/auth/language'),
    );
    await page.getByRole('option', { name: label, exact: true }).click();
    expect((await saved).ok()).toBe(true);
    await expect(
      dashboard(page, info).getByText(words.dashboard.totalGenerated, { exact: true }),
    ).toBeVisible();
    await expect(
      dashboard(page, info).getByText(words.dashboard.today, { exact: true }),
    ).toBeVisible();
    await expect(shortcuts(page, info).getByRole('button')).toHaveText(
      ['orders', 'generate', 'piInit', 'rules', 'acquire', 'transfer'].map(
        (key) => words.nav[key as keyof typeof words.nav],
      ),
    );
    await page.reload();
    await expect(
      dashboard(page, info).getByText(words.dashboard.shortcuts, { exact: true }),
    ).toBeVisible();
  }
});
