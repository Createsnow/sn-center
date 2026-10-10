import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { protectTestSecrets } from '../privacy';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };

// 全量替换和故障注入仅使用专用8001/15174/18081服务及tx47库，不能指向常驻比较库。
test('P05 full sync commits factories separately and continues after factory write/audit failures', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  const prototype = info.project.name === 'prototype';
  const kind = prototype ? 'python' : 'java';
  const fixture = (action: string) =>
    JSON.parse(
      execFileSync(
        resolve('../../sn-center-python/.venv/Scripts/python.exe'),
        [
          '-X',
          'utf8',
          resolve('../tools/testing/parity-order-transactions-fixture.py'),
          kind,
          action,
        ],
        { windowsHide: true, encoding: 'utf8' },
      ),
    );
  const password = readFileSync(
    resolve(`../deploy/.secrets/comparison-20261008/${kind}-admin-password`),
    'utf8',
  ).trim();
  protectTestSecrets(password);
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  const states: unknown[] = [];
  const menu = async (factory: boolean) => {
    await page
      .getByRole('menuitem', { name: factory ? zh.nav.factories : zh.nav.orders, exact: true })
      .click();
    await expect(
      page.getByRole('heading', {
        name: factory ? zh.factories.title : zh.orders.title,
        exact: true,
      }),
    ).toBeVisible();
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
    const state = {
      label,
      database: fixture('snapshot'),
      text: await page.locator('main').innerText(),
      warnings: await page
        .locator(prototype ? 'main > .page > .el-alert' : '.sn-orders-page > .ant-alert')
        .allTextContents(),
      messages: await page
        .locator(prototype ? '.el-message' : '.ant-message-notice')
        .allTextContents(),
      buttons: await page
        .getByRole('button')
        .filter({ hasText: /全量同步|按单据同步/ })
        .evaluateAll((nodes) =>
          nodes.map((el) => {
            const st = getComputedStyle(el);
            const r = el.getBoundingClientRect();
            return {
              text: el.textContent,
              x: r.x,
              y: r.y,
              width: r.width,
              height: r.height,
              padding: st.padding,
              margin: st.margin,
              gap: st.gap,
              fontSize: st.fontSize,
              fontWeight: st.fontWeight,
              color: st.color,
              background: st.backgroundColor,
              borderColor: st.borderColor,
              boxShadow: st.boxShadow,
            };
          }),
        ),
      rows: await page
        .locator(prototype ? '.el-table__body .el-table__row' : '.ant-table-tbody .ant-table-row')
        .allTextContents(),
    };
    states.push(state);
    await page.screenshot({ path: info.outputPath(label + '.png'), animations: 'disabled' });
    return state;
  };
  try {
    fixture('baseline');
    await page.goto(prototype ? 'http://127.0.0.1:8001/login' : 'http://127.0.0.1:15174/login');
    await page.locator('input[autocomplete="username"]').fill('admin');
    await page.locator('input[autocomplete="current-password"]').fill(password);
    await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
    await menu(false);
    for (const [stage, bills, lines, factoryCount] of [
      ['baseline', 3, 4, 2],
      ['factory-write', 4, 5, 2],
      ['factory-audit', 5, 6, 2],
      ['order-write', 5, 6, 3],
      ['order-audit', 5, 6, 4],
      ['recovery', 6, 7, 6],
    ] as const) {
      fixture(stage);
      const before = fixture('snapshot');
      const failed = stage.startsWith('order-');
      const pending = page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          /\/orders\/sync(?:-all)?$/.test(new URL(r.url()).pathname),
      );
      await page.getByRole('button', { name: zh.orders.syncAll, exact: true }).click();
      const response = await pending;
      expect(response.status()).toBe(failed ? 500 : 200);
      if (failed) {
        await expect(
          page.locator(prototype ? '.el-message--error' : '.sn-prototype-error-message'),
        ).toHaveText('Internal Server Error');
      } else {
        await expect(
          page.getByText(`已同步 ${bills} 张单据，${lines} 行`, { exact: true }),
        ).toBeVisible();
      }
      await expect(
        page.getByRole('button', { name: zh.orders.syncAll, exact: true }),
      ).toBeEnabled();
      await page.waitForLoadState('networkidle');
      const state = await capture(stage + '-orders');
      expect(state.database.factories).toHaveLength(factoryCount);
      if (failed) {
        expect(state.database.orders).toEqual(before.orders);
        expect(state.database.entries).toEqual(before.entries);
        const message = page.locator(
          prototype ? '.el-message--error' : '.sn-prototype-error-message',
        );
        await message.locator(prototype ? '.el-message__closeBtn' : 'button').click();
      }
      if (stage.startsWith('factory-')) {
        expect(state.warnings).toHaveLength(1);
        expect(state.warnings[0]).toContain('事务测试工厂1');
        expect(state.database.factories).toEqual(before.factories);
      }
      fixture('clear');
      await menu(true);
      await capture(stage + '-factories');
      await menu(false);
    }
    fixture('bill-only');
    const before = fixture('snapshot');
    await page.getByPlaceholder(zh.orders.billToSync, { exact: true }).fill('CMP-TX-001');
    await page.getByRole('button', { name: zh.orders.syncOne, exact: true }).click();
    await expect(page.getByText('单据 CMP-TX-001 已同步，1 行', { exact: true })).toBeVisible();
    await page.waitForLoadState('networkidle');
    const last = await capture('bill-only-orders');
    expect(last.database.factories).toEqual(before.factories);
    expect(errors).toEqual([]);
  } finally {
    fixture('clear');
    writeFileSync(info.outputPath('states.json'), JSON.stringify(states, null, 2));
  }
});
