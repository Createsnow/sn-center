import { expect, test, type Page, type TestInfo, type APIRequestContext } from '@playwright/test';
import { readFileSync, writeFileSync, renameSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';
import { protectTestSecrets } from '../privacy';
import { paceComparisonLogin } from './login-budget';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import en from '../../src/i18n/prototype/en.json' with { type: 'json' };
import vi from '../../src/i18n/prototype/vi.json' with { type: 'json' };

const privateRoot = resolve('../deploy/.secrets/comparison-20261008');
const controlFile = resolve('../tools/.runtime/comparison-20261008/k3-control.json');
const prototype = (info: TestInfo) => info.project.name === 'prototype';
const kind = (info: TestInfo) => (prototype(info) ? 'python' : 'java');
const pageErrors = new WeakMap<Page, string[]>();

test.beforeEach(async ({ page }) => {
  page.setDefaultTimeout(10_000);
  const errors: string[] = [];
  pageErrors.set(page, errors);
  page.on('pageerror', (error) => errors.push(error.message));
});
test.afterEach(async ({ page }) => {
  expect(pageErrors.get(page)).toEqual([]);
});

function control(value: {
  hold?: boolean;
  failure?: 'query' | 'http' | 'login';
  orders?: 'empty';
}) {
  writeFileSync(controlFile + '.next', JSON.stringify(value), 'utf8');
  renameSync(controlFile + '.next', controlFile);
}
function rows(page: Page, info: TestInfo) {
  return page
    .locator(prototype(info) ? '.el-row .el-table' : '.sn-order-table')
    .first()
    .locator(prototype(info) ? '.el-table__row' : '.ant-table-row');
}
function meta(page: Page, info: TestInfo) {
  return page.locator(prototype(info) ? '.surface.meta' : '.sn-order-meta');
}
async function login(page: Page, info: TestInfo, request: APIRequestContext) {
  expect(await (await request.get('http://127.0.0.1:19090')).json()).toMatchObject({
    fixture: 'synthetic comparison only',
    controlsSupported: true,
    orders: 3,
    entries: 4,
  });
  const password = readFileSync(
    resolve(privateRoot, `${kind(info)}-admin-password`),
    'utf8',
  ).trim();
  protectTestSecrets(password);
  await page.goto('/login');
  await paceComparisonLogin(page);
  await page.locator('input[autocomplete="username"]').fill('admin');
  await page.locator('input[autocomplete="current-password"]').fill(password);
  await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
  await page.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
  await expect(rows(page, info)).toHaveCount(3);
  // 原型挂载时同时触发分页加载和摘要刷新，等待初始请求结束再选单。
  await page.waitForLoadState('networkidle');
}
async function sync(page: Page, count: number, lineCount: number) {
  await page.getByRole('button', { name: zh.orders.syncAll, exact: true }).click();
  await expect(
    page.getByText(`已同步 ${count} 张单据，${lineCount} 行`, { exact: true }),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: zh.orders.syncAll, exact: true })).toBeEnabled();
}
async function screenshot(page: Page, info: TestInfo, name: string) {
  await expect(
    page.locator(prototype(info) ? '.el-loading-mask:visible' : '.ant-spin-spinning'),
  ).toHaveCount(0);
  const dialog = page.getByRole('dialog');
  if (await dialog.isVisible()) {
    await dialog.getByRole('button', { name: /取消|Cancel|Hủy/ }).hover();
    await expect.poll(() => dialog.evaluate((el) => getComputedStyle(el).opacity)).toBe('1');
    await page.mouse.move(0, 0);
  }
  await page.screenshot({ path: info.outputPath(name), animations: 'disabled' });
  const selectors = prototype(info)
    ? ['.el-dialog', '.el-dialog__title', '.el-message--error', '.el-dialog .bad']
    : [
        '.ant-modal',
        '.ant-modal-title',
        '.sn-prototype-error-message',
        '.ant-modal .sn-schedule-bad',
      ];
  const geometry = await page.locator(selectors.join(',')).evaluateAll((elements) =>
    elements.map((el) => {
      const style = getComputedStyle(el);
      return {
        classes: el.className,
        rect: el.getBoundingClientRect().toJSON(),
        color: style.color,
        background: style.backgroundColor,
        fontSize: style.fontSize,
        lineHeight: style.lineHeight,
        opacity: style.opacity,
      };
    }),
  );
  writeFileSync(
    info.outputPath(name.replace('.png', '.json')),
    JSON.stringify(geometry, null, 2),
    'utf8',
  );
}
async function closeError(page: Page, info: TestInfo) {
  const message = page.locator(
    prototype(info) ? '.el-message--error' : '.sn-prototype-error-message',
  );
  await message.locator(prototype(info) ? '.el-message__closeBtn' : 'button').click();
  await expect(message).toHaveCount(0);
}
async function settings(page: Page) {
  await page.getByRole('button', { name: zh.orders.sched.settings, exact: true }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  return dialog;
}
async function language(page: Page, info: TestInfo, label: string) {
  const select = page.locator(
    prototype(info) ? '.header .lang' : '.prototype-language .ant-select',
  );
  if ((await select.innerText()).includes(label)) return;
  await select.click();
  const saved = page.waitForResponse(
    (response) =>
      response.request().method() === (prototype(info) ? 'PUT' : 'POST') &&
      response.url().endsWith(prototype(info) ? '/auth/lang' : '/system/auth/language'),
  );
  await page.getByRole('option', { name: label, exact: true }).click();
  expect((await saved).ok()).toBe(true);
}
async function configure(page: Page, info: TestInfo, enabled: boolean) {
  const dialog = await settings(page);
  await dialog.locator(prototype(info) ? '.el-select' : '.ant-select').click();
  await page
    .getByRole('option', {
      name: enabled ? zh.orders.sched.unit.minute : zh.orders.sched.unit.day,
      exact: true,
    })
    .click();
  await dialog.getByRole('spinbutton').fill(enabled ? '10' : '1');
  const toggle = dialog.getByRole('switch');
  if ((await toggle.getAttribute('aria-checked')) !== String(enabled))
    await dialog.locator(prototype(info) ? '.el-switch' : '.ant-switch').click();
  await dialog.getByRole('button', { name: zh.common.save, exact: true }).click();
  await expect(dialog).not.toBeVisible();
}
async function due(request: APIRequestContext, info: TestInfo) {
  execFileSync(
    resolve('../../sn-center-python/.venv/Scripts/python.exe'),
    [resolve('../tools/testing/parity-schedule-clock.py'), kind(info)],
    { encoding: 'utf8', windowsHide: true },
  );
  await expect
    .poll(async () => (await (await request.get('http://127.0.0.1:19090')).json()).inflight, {
      timeout: 45_000,
      intervals: [500, 1000],
    })
    .toBeGreaterThan(0);
}
async function lastText(page: Page, info: TestInfo) {
  await page.reload();
  await expect(rows(page, info)).toHaveCount(3);
  const dialog = await settings(page);
  const text = await dialog.innerText();
  await dialog.getByRole('button', { name: zh.common.cancel, exact: true }).click();
  await expect(dialog).not.toBeVisible();
  return text;
}

test('P05 全量查询失败 单据HTTP失败 登录失败 提示关闭及恢复', async ({ page, request }, info) => {
  await login(page, info, request);
  await rows(page, info).filter({ hasText: 'CMP-MO-001' }).click();
  await expect(page.getByRole('cell', { name: 'CMP-MAT-B', exact: true })).toBeVisible();
  const before = await meta(page, info).locator('b').first().textContent();
  try {
    control({ failure: 'query' });
    await page.getByRole('button', { name: zh.orders.syncAll, exact: true }).click();
    await expect(page.getByText('金蝶查询失败：比较查询失败', { exact: true })).toBeVisible();
    await expect(rows(page, info)).toHaveCount(3);
    await expect(meta(page, info).locator('b').first()).toHaveText(before!);
    await expect(page.getByRole('cell', { name: 'CMP-MAT-B', exact: true })).toBeVisible();
    await screenshot(page, info, 'orders-query-failed.png');
    await closeError(page, info);
    control({ failure: 'http' });
    await page.getByPlaceholder(zh.orders.billToSync, { exact: true }).fill('CMP-MO-001');
    await page.getByRole('button', { name: zh.orders.syncOne, exact: true }).click();
    await expect(page.getByText('金蝶查询失败：HTTP 503', { exact: true })).toBeVisible();
    await expect(page.getByRole('cell', { name: 'CMP-MAT-B', exact: true })).toBeVisible();
    await closeError(page, info);
    control({ failure: 'login' });
    await page.getByRole('button', { name: zh.orders.syncAll, exact: true }).click();
    await expect(page.getByText('金蝶登录失败：比较登录失败', { exact: true })).toBeVisible();
    await closeError(page, info);
  } finally {
    control({});
    await sync(page, 3, 4);
  }
  await expect(rows(page, info)).toHaveCount(3);
  await expect(page.getByText(zh.orders.pick, { exact: true })).toBeVisible();
});

test('P05 全量空快照 清空摘要和选单 恢复原订单', async ({ page, request }, info) => {
  await login(page, info, request);
  await rows(page, info).filter({ hasText: 'CMP-MO-001' }).click();
  await expect(page.getByRole('cell', { name: 'CMP-MAT-B', exact: true })).toBeVisible();
  try {
    control({ orders: 'empty' });
    await sync(page, 0, 0);
    await expect(rows(page, info)).toHaveCount(0);
    await expect(meta(page, info).locator('b').nth(0)).toHaveText('—');
    await expect(meta(page, info).locator('b').nth(1)).toHaveText('0');
    await expect(meta(page, info).locator('b').nth(2)).toHaveText('0');
    await expect(page.getByText(zh.orders.pick, { exact: true })).toBeVisible();
    await screenshot(page, info, 'orders-empty-snapshot.png');
    await page.reload();
    await expect(rows(page, info)).toHaveCount(0);
    await expect(meta(page, info).locator('b').nth(1)).toHaveText('0');
  } finally {
    control({});
    await sync(page, 3, 4);
  }
  await expect(rows(page, info)).toHaveCount(3);
});

test('P05 真实定时worker 成功 失败 恢复 执行中保留上一轮结果', async ({ page, request }, info) => {
  test.setTimeout(240_000);
  await login(page, info, request);
  await configure(page, info, true);
  try {
    control({ hold: true });
    await due(request, info);
    control({});
    await expect
      .poll(() => lastText(page, info), { timeout: 20_000, intervals: [2500] })
      .toContain('成功：3 张单据，4 行');
    control({ hold: true, failure: 'query' });
    await due(request, info);
    expect(await lastText(page, info)).toContain('成功：3 张单据，4 行');
    const beforeFailure = await meta(page, info).locator('b').first().textContent();
    control({ failure: 'query' });
    await expect
      .poll(() => lastText(page, info), { timeout: 20_000, intervals: [2500] })
      .toContain('失败：金蝶查询失败：比较查询失败（快照未改动）');
    await expect(meta(page, info).locator('b').first()).toHaveText(beforeFailure!);
    await settings(page);
    await screenshot(page, info, 'orders-scheduled-failure.png');
    await page
      .getByRole('dialog')
      .getByRole('button', { name: zh.common.cancel, exact: true })
      .click();
    await expect(page.getByRole('dialog')).not.toBeVisible();
    for (const [label, words] of [
      ['English', en],
      ['Tiếng Việt', vi],
    ] as const) {
      await language(page, info, label);
      await page.getByRole('button', { name: words.orders.sched.settings, exact: true }).click();
      const dialog = page.getByRole('dialog');
      await expect(dialog).toContainText('金蝶查询失败：比较查询失败');
      await expect(dialog).toContainText(
        words.orders.sched.lastFail.split('{{time}}')[1]!.split('{{msg}}')[0]!.trim(),
      );
      await screenshot(
        page,
        info,
        `orders-scheduled-failure-${label === 'English' ? 'en' : 'vi'}.png`,
      );
      await dialog.getByRole('button', { name: words.common.cancel, exact: true }).click();
      await expect(dialog).not.toBeVisible();
    }
    await language(page, info, '简体中文');
    control({ hold: true });
    await due(request, info);
    expect(await lastText(page, info)).toContain('失败：金蝶查询失败：比较查询失败（快照未改动）');
    control({});
    await expect
      .poll(() => lastText(page, info), { timeout: 20_000, intervals: [2500] })
      .toContain('成功：3 张单据，4 行');
    await settings(page);
    await screenshot(page, info, 'orders-scheduled-recovered.png');
    await page
      .getByRole('dialog')
      .getByRole('button', { name: zh.common.cancel, exact: true })
      .click();
    await expect(page.getByRole('dialog')).not.toBeVisible();
  } finally {
    control({});
    if (await page.getByRole('dialog').isVisible())
      await page
        .getByRole('dialog')
        .getByRole('button', { name: zh.common.cancel, exact: true })
        .click();
    await language(page, info, '简体中文');
    await configure(page, info, false);
  }
});
