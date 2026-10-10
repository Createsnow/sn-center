import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { protectTestSecrets } from '../privacy';
import { paceComparisonLogin } from './login-budget';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };

test('P14 organization changes: snapshot name, manual factory, changed ID/code, rename and directory filters', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  const prototype = info.project.name === 'prototype';
  const kind = prototype ? 'python' : 'java';
  const run = resolve('../tools/.runtime/comparison-20261008');
  const fixture = (action: string) =>
    execFileSync(
      resolve('../../sn-center-python/.venv/Scripts/python.exe'),
      ['-X', 'utf8', resolve('../tools/testing/parity-factory-identity-fixture.py'), kind, action],
      { windowsHide: true, encoding: 'utf8' },
    );
  const password = readFileSync(
    resolve(`../deploy/.secrets/comparison-20261008/${kind}-admin-password`),
    'utf8',
  ).trim();
  protectTestSecrets(password);
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  const writes: unknown[] = [],
    states: unknown[] = [],
    scrolls: unknown[] = [];
  const route = prototype ? '/api/factories' : '/api/v1/sn/factories';
  const org = (id: string, code: string, name: string, status = 'A') => ({
    FOrgID: id,
    FNumber: code,
    FName: name,
    FForbidStatus: status,
  });
  const menu = async (name: string) => {
    await page.getByRole('menuitem', { name, exact: true }).click();
    const title =
      name === zh.nav.factories
        ? zh.factories.title
        : name === zh.nav.orders
          ? zh.orders.title
          : zh.audit.title;
    await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible();
    await page.waitForLoadState('networkidle');
    await expect
      .poll(() =>
        page
          .locator(prototype ? 'main > .page' : 'main > .sn-page')
          .evaluate((el) => getComputedStyle(el).opacity),
      )
      .toBe('1');
  };
  const capture = async (label: string) => {
    await expect(page.locator('.ant-spin-spinning:visible,.el-loading-mask:visible')).toHaveCount(
      0,
    );
    await page.mouse.move(0, 0);
    states.push({
      label,
      scroll: await page.locator('main').evaluate((el) => el.scrollTop),
      data: JSON.parse(fixture('snapshot')),
      text: await page.locator('main').innerText(),
      warning: await page
        .locator(prototype ? 'main > .page > .el-alert' : '.sn-orders-page > .ant-alert')
        .evaluateAll((elements) =>
          elements.map((el) => {
            const measure = (node: Element) => {
              const style = getComputedStyle(node);
              const box = node.getBoundingClientRect();
              return {
                text: node.textContent,
                x: box.x,
                y: box.y,
                width: box.width,
                height: box.height,
                padding: style.padding,
                color: style.color,
                background: style.backgroundColor,
                fontSize: style.fontSize,
                lineHeight: style.lineHeight,
              };
            };
            return {
              box: measure(el),
              parts: Array.from(
                el.querySelectorAll(
                  '.el-alert__title,.el-alert__icon,.ant-alert-title,.ant-alert-icon',
                ),
              ).map(measure),
            };
          }),
        ),
    });
    await page.screenshot({ path: info.outputPath(label + '.png'), animations: 'disabled' });
  };
  const sync = async (orgs: unknown[], inserted: number, updated: number, conflicts = 0) => {
    writeFileSync(resolve(run, 'k3-control.json'), JSON.stringify({ organizations: orgs }));
    const pending = page.waitForResponse(
      (r) => r.request().method() === 'POST' && new URL(r.url()).pathname === route + '/sync',
    );
    await page.getByRole('button', { name: zh.factories.sync, exact: true }).click();
    const response = await pending;
    expect(response.status()).toBe(200);
    const body = await response.json();
    writes.push(body);
    const result = body.data ?? body;
    expect(Number(result.inserted)).toBe(inserted);
    expect(Number(result.updated)).toBe(updated);
    expect(result.conflicts).toHaveLength(conflicts);
    await page.waitForLoadState('networkidle');
  };
  const create = async (code: string, name: string) => {
    await page.getByRole('button', { name: zh.factories.create, exact: true }).click();
    const dialog = page.getByRole('dialog');
    await dialog.locator('input').nth(0).fill(code);
    await dialog.locator('input').nth(1).fill(name);
    const pending = page.waitForResponse(
      (r) => r.request().method() === 'POST' && new URL(r.url()).pathname === route,
    );
    await dialog.getByRole('button', { name: zh.common.save, exact: true }).click();
    expect((await pending).status()).toBe(200);
    await expect(dialog).toBeHidden();
    await page.waitForLoadState('networkidle');
  };
  const order = async (code: string | null, label: string) => {
    await menu(zh.nav.orders);
    const row = page
      .locator(prototype ? '.el-row .el-table__row' : '.sn-order-table .ant-table-row')
      .filter({ hasText: 'CMP-F46-MO' });
    await expect(row).toHaveCount(1);
    scrolls.push({
      label: label + '-before-select',
      top: await page.locator('main').evaluate((el) => el.scrollTop),
    });
    await row.click();
    await expect(
      page.getByRole('cell', { name: `比较46旧名（${code ?? '—'}）`, exact: true }),
    ).toBeVisible();
    await expect(row.getByText(zh.orders.noFactory, { exact: true })).toHaveCount(code ? 0 : 1);
    const observed = JSON.parse(fixture('snapshot'));
    expect(observed.orders).toHaveLength(1);
    expect(observed.orders[0].factory_code).toBe(code);
    await expect.poll(() => page.locator('main').evaluate((el) => el.scrollTop)).toBe(0);
    await capture(label);
    await menu(zh.nav.factories);
  };
  const scroll = async (label: string) => {
    scrolls.push({
      label,
      ...(await page.locator('main').evaluate((el) => ({
        top: el.scrollTop,
        height: el.scrollHeight,
        client: el.clientHeight,
      }))),
    });
  };
  fixture('prepare');
  try {
    await page.goto('/login');
    await paceComparisonLogin(page);
    await page.locator('input[autocomplete="username"]').fill('admin');
    await page.locator('input[autocomplete="current-password"]').fill(password);
    await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
    await menu(zh.nav.factories);
    await create('CMP_F46_OLD', '比较46旧名');
    await menu(zh.nav.orders);
    await page.getByPlaceholder(zh.orders.billToSync, { exact: true }).fill('CMP-F46-MO');
    await page.getByRole('button', { name: zh.orders.syncOne, exact: true }).click();
    await expect(page.getByText('比较46旧名', { exact: true }).first()).toBeVisible();
    await page.waitForLoadState('networkidle');
    await order('CMP_F46_OLD', '01-manual-name-matches-different-erp-code');
    await sync([org('946101', 'CMP_F46_MANUAL', '比较46旧名')], 0, 0, 1);
    await order('CMP_F46_OLD', '02-name-conflict-still-resolves-order');
    await sync([org('946101', 'CMP_F46_OLD', '比较46旧名')], 0, 0);
    await sync([org('946102', 'CMP_F46_OLD', '比较46旧名')], 0, 0);
    await order('CMP_F46_OLD', '03-replaced-id-preserves-factory');
    await sync([org('946102', 'CMP_F46_NEW', '比较46新编码')], 1, 0);
    await order('CMP_F46_OLD', '04-new-code-leaves-old-snapshot');
    await sync([org('946103', 'CMP_F46_OLD', '比较46改名')], 0, 1);
    await order(null, '05-rename-leaves-snapshot-unmapped');
    await create('CMP_F46_MANUAL', '比较46旧名');
    await order('CMP_F46_MANUAL', '06-create-matching-name-reprojects');
    await sync(
      [
        org('946201', 'CMP_F46_DUP', '比较46去重', 'a'),
        org('946202', 'CMP_F46_DISABLED', '比较46去重'),
        org('946203', 'CMP_F46_DISABLED', '比较46禁用', 'B'),
        org('bad', 'CMP_F46_NOID', '比较46无内码'),
        org('946204', '', '比较46空编码'),
      ],
      2,
      0,
    );
    await capture('07-deduplicated-active-directory');
    // 记录真实导航滚动，供双端数值比较；不注入布局或业务响应。
    await page.locator('main').evaluate((el) => {
      el.scrollTop = 151;
    });
    await scroll('factories-before');
    await menu(zh.nav.audit);
    await scroll('audit-after-long');
    await menu(zh.nav.factories);
    await scroll('factories-return');
    await menu(zh.nav.orders);
    await scroll('orders-after-long');
    await menu(zh.nav.factories);
    await scroll('factories-after-short');
    expect(errors).toEqual([]);
  } finally {
    writeFileSync(info.outputPath('states.json'), JSON.stringify(states, null, 2));
    writeFileSync(info.outputPath('writes.json'), JSON.stringify(writes, null, 2));
    writeFileSync(info.outputPath('scrolls.json'), JSON.stringify(scrolls, null, 2));
    fixture('clean');
  }
});
