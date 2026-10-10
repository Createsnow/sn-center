import { test, expect, type Page } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import { protectTestSecrets } from '../privacy';
import { paceComparisonLogin } from './login-budget';

test('P13 account actions: disable, enable, repeated same-password reset and self reset', async ({
  page,
  browser,
}, info) => {
  test.setTimeout(240_000);
  page.setDefaultTimeout(15_000);
  const prototype = info.project.name === 'prototype';
  const kind = prototype ? 'python' : 'java';
  const fixture = (action: string) =>
    execFileSync(
      resolve('../../sn-center-python/.venv/Scripts/python.exe'),
      ['-X', 'utf8', resolve('../tools/testing/parity-account-actions-fixture.py'), kind, action],
      { windowsHide: true, encoding: 'utf8' },
    );
  fixture('prepare');
  const path = resolve(`../deploy/.secrets/comparison-20261008/account-actions-${kind}.json`);
  const state = JSON.parse(readFileSync(path, 'utf8')) as {
    names: string[];
    created: string[];
    password: string;
    changed: string;
  };
  const adminPassword = readFileSync(
    resolve(`../deploy/.secrets/comparison-20261008/${kind}-admin-password`),
    'utf8',
  ).trim();
  protectTestSecrets(adminPassword, state.password, state.changed);
  const context = await browser.newContext({
    baseURL: String(info.project.use.baseURL),
    viewport: { width: 1440, height: 960 },
  });
  const member = await context.newPage();
  member.setDefaultTimeout(15_000);
  const snapshots: unknown[] = [];
  const requests: { route: string; status: number }[] = [];
  const pageErrors: string[] = [];
  for (const tab of [page, member]) {
    tab.on('pageerror', (error) => pageErrors.push(error.message));
    tab.on('response', (response) => {
      const route = new URL(response.url()).pathname;
      if (route.startsWith('/api/')) requests.push({ route, status: response.status() });
    });
  }
  const login = async (tab: Page, username: string, password: string) => {
    await tab.goto('/login');
    await paceComparisonLogin(tab);
    await tab.locator('input[autocomplete="username"]').fill(username);
    await tab.locator('input[autocomplete="current-password"]').fill(password);
    await tab.getByRole('button', { name: zh.login.submit, exact: true }).click();
  };
  const rows = (tab: Page) =>
    tab.locator(
      prototype
        ? '.el-table__body tbody tr'
        : '.ant-table-tbody > tr:not(.ant-table-measure-row):not(.ant-table-placeholder)',
    );
  const search = async (tab: Page) => {
    await tab.getByRole('menuitem', { name: zh.nav.users, exact: true }).click();
    await tab.getByPlaceholder(zh.users.search).fill(state.names[0]!);
    const pending = tab.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        new URL(r.url()).pathname === (prototype ? '/api/users' : '/api/v1/system/users'),
    );
    await tab.getByPlaceholder(zh.users.search).press('Enter');
    expect((await pending).status()).toBe(200);
    await expect(rows(tab)).toHaveCount(1);
    return rows(tab).first();
  };
  const capture = async (tab: Page, name: string, dialog = false) => {
    await expect(tab.locator('.ant-spin-spinning:visible,.el-loading-mask:visible')).toHaveCount(0);
    await tab.mouse.move(0, 0);
    if (dialog) await expect(tab.getByRole('dialog')).toBeVisible();
    // Vue/Antd进入动画均约300ms；先等待本帧动画稳定再测量，避免采到起始缩放。
    if (dialog) await tab.waitForTimeout(350);
    if (dialog)
      await expect
        .poll(async () => {
          const b = await tab
            .locator(prototype ? '.el-message-box' : '.sn-account-action .ant-modal-container')
            .boundingBox();
          return b ? Math.abs(b.y - (960 - b.height) / 2) : 999;
        })
        .toBeLessThan(0.05);
    const selectors = prototype
      ? [
          '.el-message-box',
          '.el-message-box__header',
          '.el-message-box__container',
          '.el-message-box__input',
          '.el-message-box__btns',
          '.el-message-box__errormsg',
        ]
      : [
          '.sn-account-action .ant-modal-container',
          '.sn-account-action .ant-modal-header',
          '.sn-account-action-message',
          '.sn-account-action-input',
          '.sn-account-action .ant-modal-footer',
          '.sn-account-action-error',
        ];
    const measurements = await tab.evaluate(
      (items) =>
        items.map((selector) => {
          const e = document.querySelector<HTMLElement>(selector);
          if (!e || !e.getClientRects().length) return null;
          const b = e.getBoundingClientRect();
          const style = getComputedStyle(e);
          return {
            x: b.x,
            y: b.y,
            width: b.width,
            height: b.height,
            text: e.innerText,
            color: style.color,
            font: style.fontFamily,
            fontSize: style.fontSize,
            lineHeight: style.lineHeight,
          };
        }),
      selectors,
    );
    snapshots.push({ name, measurements });
    await tab.screenshot({
      path: info.outputPath(name + '.png'),
      fullPage: true,
      animations: 'disabled',
    });
  };
  const reset = async (tab: Page, capturePrompt = false) => {
    await rows(tab).first().getByRole('button', { name: zh.users.resetPwd, exact: true }).click();
    const dialog = tab.getByRole('dialog');
    if (capturePrompt) {
      await capture(tab, '04-reset-empty', true);
      await dialog.getByRole('button', { name: '确定', exact: true }).click();
      await expect(dialog.getByText(zh.password.policy, { exact: true })).toBeVisible();
      await capture(tab, '05-reset-invalid', true);
      await dialog.locator('input[type="password"]').fill(state.changed);
      await expect(dialog.getByText(zh.password.policy, { exact: true })).toHaveCount(0);
      await dialog.locator('input[type="password"]').fill('abc');
      await expect(dialog.getByText(zh.password.policy, { exact: true })).toBeVisible();
    }
    await dialog.locator('input[type="password"]').fill(state.changed);
    const response = tab.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new URL(r.url()).pathname.endsWith(prototype ? '/reset-password' : '/password'),
    );
    await dialog.getByRole('button', { name: '确定', exact: true }).click();
    expect((await response).status()).toBe(200);
    await expect(tab.getByText(zh.users.resetDone, { exact: true }).last()).toBeVisible();
    await expect(dialog).toBeHidden();
  };
  const changePassword = async (previous: string, next: string) => {
    await expect(
      member.getByRole('heading', { name: zh.password.title, exact: true }),
    ).toBeVisible();
    await member.locator('input[autocomplete="current-password"]').fill(previous);
    await member.locator('input[autocomplete="new-password"]').nth(0).fill(next);
    await member.locator('input[autocomplete="new-password"]').nth(1).fill(next);
    await member.getByRole('button', { name: zh.common.save, exact: true }).click();
    await expect(member.getByRole('menuitem', { name: zh.nav.users, exact: true })).toBeVisible();
  };
  try {
    await login(page, 'admin', adminPassword);
    await page.getByRole('menuitem', { name: zh.nav.users, exact: true }).click();
    await page.getByRole('button', { name: zh.users.create, exact: true }).click();
    const dialog = page.getByRole('dialog');
    const inputs = dialog.locator(
      prototype
        ? '.el-form-item .el-input__inner:not([role="combobox"]):not([type="password"])'
        : '.ant-form-item input.ant-input:not([type="password"])',
    );
    await inputs.nth(0).fill(state.names[0]!);
    await inputs.nth(1).fill('启停重置测试');
    await dialog
      .locator(prototype ? '.el-radio-button' : '.ant-radio-button-wrapper')
      .filter({ hasText: zh.roles.admin })
      .click();
    await dialog.locator('input[type="password"]').fill(state.password);
    const created = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new URL(r.url()).pathname === (prototype ? '/api/users' : '/api/v1/sn/users'),
    );
    await dialog.getByRole('button', { name: zh.common.save, exact: true }).click();
    const response = await created;
    expect(response.status()).toBe(200);
    const body = await response.json();
    state.created.push(String(prototype ? body.id : body.data.id));
    writeFileSync(path, JSON.stringify(state));
    await login(member, state.names[0]!, state.password);
    await changePassword(state.password, state.changed);
    const row = await search(page);
    await expect(row.getByText(zh.users.mustChange, { exact: true })).toHaveCount(0);
    await row.getByRole('button', { name: zh.users.disable, exact: true }).click();
    await capture(page, '01-disable-confirm', true);
    await page.getByRole('dialog').getByRole('button', { name: '取消', exact: true }).click();
    await expect(row.getByText(zh.users.active, { exact: true })).toBeVisible();
    await row.getByRole('button', { name: zh.users.disable, exact: true }).click();
    await page.getByRole('dialog').getByRole('button', { name: '确定', exact: true }).click();
    await expect(page.getByText(zh.common.done, { exact: true })).toBeVisible();
    await expect(row.getByText(zh.users.disabled, { exact: true })).toBeVisible();
    await capture(page, '02-disabled');
    await member.reload();
    await expect(member).toHaveURL(/\/login/);
    await row.getByRole('button', { name: zh.users.enable, exact: true }).click();
    await expect(row.getByText(zh.users.active, { exact: true })).toBeVisible();
    await expect(page.getByText(zh.common.done, { exact: true }).last()).toBeVisible();
    await capture(page, '03-enabled');
    await member.reload();
    await expect(member).toHaveURL(/\/login/);
    await login(member, state.names[0]!, state.changed);
    await expect(member.getByRole('menuitem', { name: zh.nav.users, exact: true })).toBeVisible();
    await search(page);
    const beforeReset = requests.length;
    await reset(page, true);
    await expect(rows(page).first().getByText(zh.users.mustChange, { exact: true })).toHaveCount(0);
    await capture(page, '06-reset-done-no-reload');
    expect(
      requests
        .slice(beforeReset)
        .filter((r) => r.route === (prototype ? '/api/users' : '/api/v1/system/users')),
    ).toHaveLength(0);
    await reset(page);
    await member.reload();
    await expect(member).toHaveURL(/\/login/);
    await login(member, state.names[0]!, state.changed);
    await changePassword(state.changed, state.password);
    const own = await search(member);
    await expect(own.getByRole('button', { name: zh.users.disable, exact: true })).toBeDisabled();
    await expect(own.getByRole('button', { name: zh.users.resetPwd, exact: true })).toBeEnabled();
    await capture(member, '07-self-actions');
    await reset(member);
    await capture(member, '08-self-reset-done');
    await member.reload();
    await expect(member).toHaveURL(/\/login/);
    await login(member, state.names[0]!, state.changed);
    await expect(
      member.getByRole('heading', { name: zh.password.title, exact: true }),
    ).toBeVisible();
    await capture(member, '09-self-reset-forced-change');
    // 原型令牌失效会冒泡已显示的业务拒绝；保留原始记录，只豁免该已知401。
    expect(
      pageErrors.filter((message) => !prototype || message !== '登录已失效，请重新登录'),
    ).toEqual([]);
  } finally {
    writeFileSync(
      info.outputPath('account-actions.json'),
      JSON.stringify({ snapshots, requests, pageErrors }, null, 2),
    );
    await context.close();
    fixture('clean');
  }
});
