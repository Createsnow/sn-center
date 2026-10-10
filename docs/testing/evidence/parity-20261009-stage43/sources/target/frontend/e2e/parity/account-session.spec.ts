import { test, expect, type Page } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import { protectTestSecrets } from '../privacy';
import { paceComparisonLogin } from './login-budget';

test('P13 account sessions: edit name, role, factory and self without another login', async ({
  page,
  browser,
}, info) => {
  test.setTimeout(180_000);
  page.setDefaultTimeout(15_000);
  const prototype = info.project.name === 'prototype';
  const kind = prototype ? 'python' : 'java';
  const fixture = (action: string) =>
    execFileSync(
      resolve('../../sn-center-python/.venv/Scripts/python.exe'),
      ['-X', 'utf8', resolve('../tools/testing/parity-account-session-fixture.py'), kind, action],
      { windowsHide: true, encoding: 'utf8' },
    );
  fixture('prepare');
  const path = resolve(`../deploy/.secrets/comparison-20261008/account-session-${kind}.json`);
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
  const memberContext = await browser.newContext({
    baseURL: String(info.project.use.baseURL),
    viewport: { width: 1440, height: 960 },
  });
  const member = await memberContext.newPage();
  member.setDefaultTimeout(15_000);
  const failures: string[] = [];
  const requests: { route: string; status: number; authenticated: boolean }[] = [];
  const snapshots: unknown[] = [];
  let memberLogins = 0;
  let lastBearer = '';
  for (const tab of [page, member]) tab.on('pageerror', (error) => failures.push(error.message));
  member.on('response', (response) => {
    const route = new URL(response.url()).pathname;
    if (!route.startsWith('/api/')) return;
    requests.push({ route, status: response.status(), authenticated: memberLogins > 0 });
    if (route.endsWith('/auth/login') && response.request().method() === 'POST') memberLogins++;
    const bearer = response.request().headers().authorization;
    if (bearer) lastBearer = bearer;
  });
  const login = async (tab: Page, username: string, password: string) => {
    await tab.goto('/login');
    await paceComparisonLogin(tab);
    await tab.locator('input[autocomplete="username"]').fill(username);
    await tab.locator('input[autocomplete="current-password"]').fill(password);
    await tab.getByRole('button', { name: zh.login.submit, exact: true }).click();
  };
  const search = async (tab: Page) => {
    await tab.getByRole('menuitem', { name: zh.nav.users, exact: true }).click();
    await tab.getByPlaceholder(zh.users.search).fill(state.names[0]!);
    const refreshed = tab.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        new URL(r.url()).pathname === (prototype ? '/api/users' : '/api/v1/system/users'),
    );
    await tab.getByPlaceholder(zh.users.search).press('Enter');
    expect((await refreshed).status()).toBe(200);
    const rows = tab.locator(
      prototype
        ? '.el-table__body tbody tr'
        : '.ant-table-tbody > tr:not(.ant-table-measure-row):not(.ant-table-placeholder)',
    );
    await expect(rows).toHaveCount(1);
    return rows.first();
  };
  const role = async (tab: Page, value: 'admin' | 'query' | 'factory_operator') => {
    await tab
      .getByRole('dialog')
      .locator(prototype ? '.el-radio-button' : '.ant-radio-button-wrapper')
      .filter({ hasText: zh.roles[value] })
      .click();
  };
  const factory = async (tab: Page, code: 'A' | 'B') => {
    await tab
      .getByRole('dialog')
      .locator(prototype ? '.el-select' : '.ant-select')
      .click();
    await tab
      .getByRole('option', {
        name: `比较测试${code === 'A' ? '甲' : '乙'}厂（CMP-${code}）`,
        exact: true,
      })
      .click();
  };
  const inputs = (tab: Page) =>
    tab
      .getByRole('dialog')
      .locator(
        prototype
          ? '.el-form-item .el-input__inner:not([role="combobox"]):not([type="password"])'
          : '.ant-form-item input.ant-input:not([type="password"])',
      );
  const save = async (tab: Page, creating = false) => {
    const pending = tab.waitForResponse(
      (r) =>
        ['POST', 'PUT'].includes(r.request().method()) &&
        /\/(?:sn\/)?users(?:\/[^/]+(?:\/update)?)?$/.test(new URL(r.url()).pathname),
    );
    await tab
      .getByRole('dialog')
      .getByRole('button', { name: zh.common.save, exact: true })
      .click();
    const response = await pending;
    expect(response.status()).toBe(200);
    if (creating) {
      const body = await response.json();
      state.created.push(String(prototype ? body.id : body.data.id));
      writeFileSync(path, JSON.stringify(state));
    }
    await expect(tab.getByRole('dialog')).toBeHidden();
  };
  const edit = async (
    tab: Page,
    name: string,
    value: 'admin' | 'query' | 'factory_operator',
    code?: 'A' | 'B',
  ) => {
    const row = await search(tab);
    await row.getByRole('button', { name: zh.common.edit, exact: true }).click();
    await inputs(tab).nth(1).fill(name);
    await role(tab, value);
    if (code) await factory(tab, code);
    await save(tab);
  };
  const capture = async (
    name: string,
    displayName: string,
    value: 'admin' | 'query' | 'factory_operator',
    bound?: 'A' | 'B',
  ) => {
    await expect(member).toHaveURL(
      name.startsWith('05-') ? /\/users$/ : name.startsWith('06-') ? /\/$/ : /\/query$/,
    );
    await expect(member.locator(prototype ? '.who-name' : '.prototype-user-name')).toHaveText(
      `${displayName} · ${zh.roles[value]}`,
    );
    const menu = member.getByRole('menuitem');
    const names = await menu.allTextContents();
    expect(names.some((n) => n.trim() === zh.nav.users)).toBe(value === 'admin');
    expect(names.some((n) => n.trim() === zh.nav.acquire)).toBe(true);
    expect(names.some((n) => n.trim() === zh.nav.generate)).toBe(value === 'admin');
    if (bound)
      await expect(
        member.locator(prototype ? '.header .fac' : '.prototype-header .prototype-factory-tag'),
      ).toContainText(`比较测试${bound === 'A' ? '甲' : '乙'}厂`);
    const currentPath = new URL(member.url()).pathname;
    if (currentPath === '/query')
      await expect(
        member.getByRole('heading', { name: zh.query.title, exact: true }),
      ).toBeVisible();
    else if (currentPath === '/users')
      await expect(
        member.getByRole('heading', { name: zh.users.title, exact: true }),
      ).toBeVisible();
    else await expect(member.locator('main').getByRole('heading').first()).toBeVisible();
    await expect(
      member.locator('.ant-spin-spinning:visible, .el-loading-mask:visible'),
    ).toHaveCount(0);
    let queryRows: string[][] | undefined;
    if (new URL(member.url()).pathname === '/query') {
      const rows = member.locator(
        prototype
          ? '.el-table__body tbody tr'
          : '.ant-table-tbody > tr:not(.ant-table-measure-row):not(.ant-table-placeholder)',
      );
      await expect.poll(() => rows.count()).toBeGreaterThan(0);
      if (bound)
        await expect(rows.locator('td:nth-child(7)')).toHaveText(
          Array(await rows.count()).fill(`比较测试${bound === 'A' ? '甲' : '乙'}厂`),
        );
      queryRows = await rows.evaluateAll((elements) =>
        elements.map((row) =>
          [...row.querySelectorAll('td')].slice(0, 8).map((cell) => cell.textContent?.trim() ?? ''),
        ),
      );
      if (bound)
        expect(
          queryRows.every((row) => row[6] === `比较测试${bound === 'A' ? '甲' : '乙'}厂`),
        ).toBe(true);
    }
    snapshots.push({
      queryRows,
      name,
      displayName,
      role: value,
      bound,
      menus: names.map((n) => n.trim()),
      logins: memberLogins,
    });
    expect(memberLogins).toBe(1);
    await member.mouse.move(0, 0);
    await member.screenshot({
      path: info.outputPath(name + '.png'),
      fullPage: true,
      animations: 'disabled',
    });
  };
  try {
    await login(page, 'admin', adminPassword);
    await page.getByRole('menuitem', { name: zh.nav.users, exact: true }).click();
    await page.getByRole('button', { name: zh.users.create, exact: true }).click();
    await inputs(page).nth(0).fill(state.names[0]!);
    await inputs(page).nth(1).fill('会话测试成员');
    await factory(page, 'A');
    await page.getByRole('dialog').locator('input[type="password"]').fill(state.password);
    await save(page, true);
    await login(member, state.names[0]!, state.password);
    await expect(
      member.getByRole('heading', { name: zh.password.title, exact: true }),
    ).toBeVisible();
    await member.locator('input[autocomplete="current-password"]').fill(state.password);
    await member.locator('input[autocomplete="new-password"]').nth(0).fill(state.changed);
    await member.locator('input[autocomplete="new-password"]').nth(1).fill(state.changed);
    await member.getByRole('button', { name: zh.common.save, exact: true }).click();
    await member.getByRole('menuitem', { name: zh.nav.query, exact: true }).click();
    await capture('01-operator', '会话测试成员', 'factory_operator', 'A');
    await edit(page, '管理修改姓名', 'factory_operator', 'A');
    const oldBearer = lastBearer;
    await member.getByRole('menuitem', { name: zh.nav.dashboard, exact: true }).click();
    await member.getByRole('menuitem', { name: zh.nav.query, exact: true }).click();
    const queryResponse = member.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        new URL(r.url()).pathname === (prototype ? '/api/sn' : '/api/v1/sn/items/page'),
    );
    await member.getByRole('button', { name: zh.common.search, exact: true }).click();
    const queried = await queryResponse;
    expect(queried.status()).toBe(200);
    expect(queried.request().headers().authorization === oldBearer && oldBearer.length > 0).toBe(
      true,
    );
    snapshots.push({
      name: 'original-token-query-after-edit',
      status: queried.status(),
      sameBearer: true,
    });
    await member.reload();
    await capture('02-renamed', '管理修改姓名', 'factory_operator', 'A');
    await edit(page, '乙厂只读成员', 'query', 'B');
    await member.reload();
    await capture('03-query-b', '乙厂只读成员', 'query', 'B');
    await expect(member.getByRole('switch')).toHaveCount(0);
    await edit(page, '会话测试管理员', 'admin');
    await member.reload();
    await capture('04-admin', '会话测试管理员', 'admin');
    await edit(member, '本人修改姓名', 'admin');
    await member.reload();
    await capture('05-self-edited', '本人修改姓名', 'admin');
    await edit(page, '恢复甲厂操作员', 'factory_operator', 'A');
    await member.goto('/');
    await capture('06-operator-again', '恢复甲厂操作员', 'factory_operator', 'A');
    expect(requests.some((r) => r.authenticated && r.status === 401)).toBe(false);
    expect(failures).toEqual([]);
  } finally {
    writeFileSync(
      info.outputPath('session-evidence.json'),
      JSON.stringify({ snapshots, requests, memberLogins, failures }, null, 2),
    );
    await memberContext.close();
    fixture('clean');
  }
});
