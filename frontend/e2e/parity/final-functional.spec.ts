import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { protectTestSecrets } from '../privacy';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };

/** 收尾验收仅写入 a48 隔离环境；不复用或重置原有人员账号。 */
test('P01-P04 P08 P13-P14 functional closure through actual pages', async ({ page }, info) => {
  test.setTimeout(180_000);
  const source = info.project.name === 'prototype';
  const kind = source ? 'python' : 'java';
  const run = process.env.ADTEK_FINAL_FUNCTIONAL_RUN!;
  expect(run).toMatch(/^[0-9]{14}$/);
  const admin = 'audit' + run;
  execFileSync(
    resolve('../../sn-center-python/.venv/Scripts/python.exe'),
    ['-X', 'utf8', resolve('../tools/testing/parity-order-audit-fixture.py'), kind, 'prepare', run],
    { windowsHide: true },
  );
  const password = readFileSync(
    resolve('../deploy/.secrets/comparison-20261008/' + kind + '-admin-password'),
    'utf8',
  ).trim();
  const initial = 'Initial54-' + randomUUID();
  const changed = 'Changed54-' + randomUUID();
  protectTestSecrets(password, initial, changed);
  const origin = source ? 'http://127.0.0.1:8001' : 'http://127.0.0.1:15174';
  const pi = 'CMP-FINAL-' + run;
  const sns = ['F' + run + '-ONE', 'F' + run + '-TWO'];
  const username = 'query' + run;
  const facts: unknown[] = [];
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  const rows = () => page.locator(source ? '.el-table__row' : '.ant-table-row');
  const go = (name: string) => page.getByRole('menuitem', { name, exact: true }).click();
  const capture = async (name: string) =>
    page.screenshot({ path: info.outputPath(name + '.png'), animations: 'disabled' });
  const login = async (name: string, secret: string) => {
    await page.goto(origin + '/login');
    await page.locator('input[autocomplete="username"]').fill(name);
    await page.locator('input[autocomplete="current-password"]').fill(secret);
    await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
  };
  const logout = async () => {
    await page.locator(source ? '.who' : '.prototype-user').click();
    await page.getByText(zh.app.logout, { exact: true }).click();
    await expect(page).toHaveURL(/\/login/);
  };
  const field = (label: string) =>
    page.locator(source ? '.el-form-item' : '.ant-form-item').filter({
      has: page.locator('label').filter({
        hasText: new RegExp('^\\s*' + label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\s*$'),
      }),
    });
  const queryImported = async () => {
    await go(zh.nav.query);
    await expect(page.getByRole('heading', { name: zh.query.title, exact: true })).toBeVisible();
    await expect(page.locator('.el-loading-mask:visible,.ant-spin-spinning:visible')).toHaveCount(
      0,
    );
    await page.getByPlaceholder('PI', { exact: true }).fill(pi);
    const response = page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        new URL(r.url()).pathname === (source ? '/api/sn' : '/api/v1/sn/items/page') &&
        new URL(r.url()).searchParams.get('pi') === pi,
    );
    await page.getByRole('button', { name: zh.common.search, exact: true }).click();
    const raw = await response;
    expect(raw.status()).toBe(200);
    const body = await raw.json();
    const values = (source ? body.items : body.data.items) as Record<string, unknown>[];
    const members = values
      .map((r) => ({
        sn: r.sn,
        pi: r.pi_no ?? r.pi,
        factory: r.factory_code ?? r.factoryCode,
        status: r.status,
      }))
      .sort((a, b) => String(a.sn).localeCompare(String(b.sn)));
    expect(members).toEqual(sns.map((sn) => ({ sn, pi, factory: 'CMP-A', status: 'PRINTED' })));
    await expect(rows()).toHaveCount(2);
    facts.push({ stage: 'imported-members', members });
  };
  try {
    await login(admin, password);
    await expect(page.getByRole('menuitem', { name: zh.nav.dashboard, exact: true })).toBeVisible();
    for (const key of [
      'dashboard',
      'orders',
      'generate',
      'piInit',
      'rules',
      'acquire',
      'transfer',
      'query',
      'audit',
      'users',
      'factories',
    ] as const) {
      const name = zh.nav[key];
      await go(name);
      const heading = zh[key].title.replace('{{name}}', '同步痕迹操作员');
      await expect(page.locator('main h2').first()).toHaveText(heading);
      await expect(page.locator('.el-loading-mask:visible,.ant-spin-spinning:visible')).toHaveCount(
        0,
      );
      facts.push({
        stage: 'page-entry',
        name,
        heading,
      });
    }
    await page.getByRole('button', { name: zh.factories.create, exact: true }).click();
    const dialog = page.getByRole('dialog');
    await dialog
      .locator('input')
      .nth(0)
      .fill('F54-' + run);
    await dialog
      .locator('input')
      .nth(1)
      .fill('收尾工厂 ' + run);
    await dialog.getByRole('button', { name: zh.common.save, exact: true }).click();
    await expect(dialog).toBeHidden();
    await expect(rows().filter({ hasText: 'F54-' + run })).toHaveCount(1);
    await capture('01-factory');
    facts.push({ stage: 'factory-created', code: 'F54-' + run });

    await go(zh.nav.piInit);
    await page.getByPlaceholder(zh.piInit.piPlaceholder, { exact: true }).fill(pi);
    await page.getByRole('button', { name: zh.common.search, exact: true }).click();
    await expect(page.locator(source ? '.el-descriptions b' : '.ant-descriptions b')).toHaveText(
      pi,
    );
    await field(zh.generate.start).locator('input').fill('10');
    await field(zh.piInit.snList).locator('textarea').fill(sns.join('\n'));
    await field(zh.piInit.factory)
      .locator(source ? '.el-select' : '.ant-select')
      .click();
    await page.getByRole('option', { name: '比较测试甲厂（CMP-A）', exact: true }).click();
    await page.getByRole('button', { name: zh.piInit.submit, exact: true }).click();
    const confirmation = page.locator(
      source ? '.el-message-box:visible' : '.ant-modal-confirm:visible',
    );
    await expect(confirmation).toContainText(pi);
    await confirmation.getByRole('button', { name: /^(确定|确认)$/ }).click();
    await expect(confirmation).toBeHidden();
    await expect(
      page.getByText(zh.piInit.locked.replace('{{start}}', '10'), { exact: true }),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: zh.piInit.submit, exact: true })).toHaveCount(0);
    await expect(rows()).toHaveCount(1);
    await capture('02-initialization');
    await queryImported();
    await capture('03-imported-query');

    await go(zh.nav.users);
    await page.getByRole('button', { name: zh.users.create, exact: true }).click();
    const textInputs = dialog.locator(
      source
        ? '.el-form-item .el-input__inner:not([role="combobox"]):not([type="password"])'
        : '.ant-form-item input.ant-input:not([type="password"])',
    );
    await textInputs.nth(0).fill(username);
    await textInputs.nth(1).fill('收尾查询员');
    await dialog
      .locator(source ? '.el-radio-button' : '.ant-radio-button-wrapper')
      .filter({ hasText: zh.roles.query })
      .click();
    await dialog.locator(source ? '.el-select' : '.ant-select').click();
    await page.getByRole('option', { name: '比较测试甲厂（CMP-A）', exact: true }).click();
    await dialog.locator('input[type="password"]').fill(initial);
    await dialog.getByRole('button', { name: zh.common.save, exact: true }).click();
    await expect(dialog).toBeHidden();
    await page.getByPlaceholder(zh.users.search).fill(username);
    await page.getByPlaceholder(zh.users.search).press('Enter');
    await expect(rows()).toHaveCount(1);
    await expect(rows().first()).toContainText(zh.users.mustChange);
    await capture('04-account-created');
    await logout();
    await login(username, initial);
    await expect(page.getByRole('heading', { name: zh.password.title, exact: true })).toBeVisible();
    await expect(page.getByText(zh.password.forced, { exact: true })).toBeVisible();
    await page.locator('input[autocomplete="current-password"]').fill(initial);
    await page.locator('input[autocomplete="new-password"]').nth(0).fill(changed);
    await page.locator('input[autocomplete="new-password"]').nth(1).fill(changed);
    await page.getByRole('button', { name: zh.common.save, exact: true }).click();
    await expect(page.getByRole('menuitem', { name: zh.nav.query, exact: true })).toBeVisible();
    await expect(page.getByRole('menuitem', { name: zh.nav.users, exact: true })).toHaveCount(0);
    await expect(page.getByRole('menuitem', { name: zh.nav.generate, exact: true })).toHaveCount(0);
    facts.push({ stage: 'forced-password-and-role', usersHidden: true, generationHidden: true });
    await queryImported();
    await capture('05-scoped-query');
    await logout();
    const rejected = page.waitForResponse(
      (r) => r.request().method() === 'POST' && r.url().endsWith('/auth/login'),
    );
    await login(username, initial);
    expect((await rejected).status()).toBe(401);
    await expect(page).toHaveURL(/\/login/);
    await login(username, changed);
    await expect(page.getByRole('menuitem', { name: zh.nav.query, exact: true })).toBeVisible();
    await page.reload();
    await expect(page.getByRole('menuitem', { name: zh.nav.query, exact: true })).toBeVisible();
    facts.push({
      stage: 'credential-relogin',
      oldRejected: true,
      newAccepted: true,
      refreshPreserved: true,
    });
    await capture('06-relogin');
    await logout();
    expect(errors).toEqual([]);
  } finally {
    writeFileSync(
      info.outputPath('observations.json'),
      JSON.stringify({ pi, facts, errors }, null, 2),
    );
  }
});
