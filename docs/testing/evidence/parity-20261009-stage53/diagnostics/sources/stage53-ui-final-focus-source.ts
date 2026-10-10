import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { protectTestSecrets } from '../privacy';
import { settleOrderPage } from './settleOrderPage';
import { clickVisibleControl } from './clickVisibleControl';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };

// 专用a48服务中的真实页面操作；记录焦点/滚动变化，不通过脚本改写浏览器业务数据。
test('P05 focus: schedule closing, unit text, keyboard steps and scroll restoration', async ({
  page,
}, info) => {
  test.setTimeout(150_000);
  page.setDefaultTimeout(15_000);
  const prototype = info.project.name === 'prototype';
  const kind = prototype ? 'python' : 'java';
  const run = process.env.ADTEK_ORDER_FOCUS_RUN!;
  expect(run).toMatch(/^[0-9]{8,14}$/);
  const fixture = (action: string) =>
    JSON.parse(
      execFileSync(
        resolve('../../sn-center-python/.venv/Scripts/python.exe'),
        [
          '-X',
          'utf8',
          resolve('../tools/testing/parity-order-audit-fixture.py'),
          kind,
          action,
          run,
        ],
        { encoding: 'utf8', windowsHide: true },
      ),
    );
  fixture('prepare');
  const initialSchedule = fixture('snapshot').schedule[0];
  expect(initialSchedule.enabled).toBe(0);
  expect(initialSchedule.interval_minutes).toBe(1440);
  const password = readFileSync(
    resolve('../deploy/.secrets/comparison-20261008/' + kind + '-admin-password'),
    'utf8',
  ).trim();
  protectTestSecrets(password);
  const states: unknown[] = [];
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  const capture = async (label: string) => {
    await settleOrderPage(page);
    const state = await page.evaluate((source) => {
      const read = (el: Element) => {
        const s = getComputedStyle(el),
          r = el.getBoundingClientRect();
        return {
          tag: el.tagName,
          cls: el.getAttribute('class'),
          text: (el as HTMLElement).innerText,
          rect: [r.x, r.y, r.width, r.height],
          display: s.display,
          font: [s.fontFamily, s.fontSize, s.fontWeight, s.lineHeight],
          color: s.color,
          padding: s.padding,
          border: s.border,
          margin: s.margin,
        };
      };
      const main = document.querySelector('main')!;
      const select = document.querySelector(
        source ? '.el-dialog .el-select' : '.sn-schedule-modal .ant-select',
      );
      const selectors = source
        ? [
            '.el-dialog',
            '.el-dialog__headerbtn',
            '.el-dialog .el-switch__core',
            '.el-input-number__increase',
            '.el-input-number__decrease',
            '.el-input-number input',
            '.el-message',
            '.el-table__row:first-child td',
            '.el-descriptions__cell',
          ]
        : [
            '.sn-schedule-modal .ant-modal-container',
            '.sn-schedule-modal .ant-modal-close',
            '.sn-schedule-modal .ant-switch',
            '.sn-schedule-modal .ant-input-number-action-up',
            '.sn-schedule-modal .ant-input-number-action-down',
            '.sn-schedule-modal .ant-input-number-input',
            '.ant-message-notice',
            '.sn-order-table .ant-table-row td',
            '.sn-order-lines .ant-descriptions-item-label,.sn-order-lines .ant-descriptions-item-content',
          ];
      return {
        scroll: main.scrollTop,
        scrollHeight: main.scrollHeight,
        height: main.clientHeight,
        fonts: document.fonts.status,
        focus: document.activeElement ? read(document.activeElement) : null,
        groups: selectors.map((s) =>
          [...document.querySelectorAll(s)]
            .filter(
              (el) => el.getClientRects().length && getComputedStyle(el).visibility !== 'hidden',
            )
            .map(read),
        ),
        select: select
          ? {
              html: select.outerHTML,
              children: [select, ...select.querySelectorAll('*')].map(read),
            }
          : null,
        layout: [
          ...document.querySelectorAll(
            source
              ? 'main,.page,.page__head,.meta,.el-row,.el-col,.surface,.surface__title'
              : 'main,.sn-orders-page,.sn-page-head,.sn-order-meta,.sn-orders-grid,.sn-surface,.sn-surface-title',
          ),
        ].map(read),
      };
    }, prototype);
    states.push({ label, ...state, schedule: fixture('snapshot').schedule });
    await page.screenshot({ path: info.outputPath(label + '.png'), animations: 'disabled' });
  };
  const open = async () => {
    await page.getByRole('button', { name: zh.orders.sched.settings, exact: true }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(prototype ? page.locator('.el-dialog') : page.getByRole('dialog')).toBeFocused();
  };
  const close = async () => {
    await page
      .getByRole('dialog')
      .getByRole('button', { name: zh.common.cancel, exact: true })
      .click();
    await expect(page.getByRole('dialog')).toBeHidden();
    await expect(
      page.getByRole('button', { name: zh.orders.sched.settings, exact: true }),
    ).toBeFocused();
  };
  const unit = async (name: string) => {
    await page
      .getByRole('dialog')
      .locator(prototype ? '.el-select' : '.ant-select')
      .click();
    await page.getByRole('option', { name, exact: true }).click();
  };
  const messages = page.locator(prototype ? '.el-message' : '.ant-message-notice');
  const save = async (minutes: number) => {
    await open();
    await unit(zh.orders.sched.unit.minute);
    await page.getByRole('spinbutton').fill(String(minutes));
    const response = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        new URL(r.url()).pathname.endsWith('/orders/sync-schedule'),
    );
    await page
      .getByRole('dialog')
      .getByRole('button', { name: zh.common.save, exact: true })
      .click();
    expect((await response).status()).toBe(200);
    await expect(page.getByRole('dialog')).toBeHidden();
    await expect(messages).toHaveCount(1);
    await capture('saved-' + minutes);
    await expect(messages).toHaveCount(0);
  };
  try {
    await page.goto((prototype ? 'http://127.0.0.1:8001' : 'http://127.0.0.1:15174') + '/login');
    await page.locator('input[autocomplete=username]').fill('audit' + run);
    await page.locator('input[autocomplete=current-password]').fill(password);
    await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
    await page.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
    await expect(page.getByText('CMP-MO-002', { exact: true })).toBeVisible();
    const synced = page.waitForResponse(
      (r) => r.request().method() === 'POST' && new URL(r.url()).pathname.endsWith('/orders/sync'),
    );
    await clickVisibleControl(
      page,
      page.getByRole('button', { name: zh.orders.syncAll, exact: true }),
    );
    expect((await synced).status()).toBe(200);
    await expect(messages).toHaveCount(1);
    await expect(messages).toHaveCount(0);
    await capture('loaded');
    await page.getByText('CMP-MO-002', { exact: true }).click();
    await expect(
      page.getByText(zh.orders.linesOf.replace('{{bill}}', 'CMP-MO-002'), { exact: true }),
    ).toBeVisible();
    await capture('selected');
    const detailRow = page
      .locator(
        prototype
          ? '.el-col:last-child .el-table__row'
          : '[data-testid=order-lines] .ant-table-row',
      )
      .first();
    await detailRow.hover();
    await expect
      .poll(() =>
        detailRow
          .locator('td')
          .evaluateAll((cells) => cells.map((cell) => getComputedStyle(cell).backgroundColor)),
      )
      .toEqual(Array(4).fill('rgb(245, 247, 250)'));
    await capture('detail-row-hover');
    await page.mouse.move(0, 0);
    await page.getByText('CMP-MO-002', { exact: true }).click();
    await page.keyboard.press('ArrowDown');
    await capture('table-arrow-down');
    await page.keyboard.press('ArrowUp');
    await capture('table-arrow-up');
    await open();
    await capture('opened');
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).toBeHidden();
    await capture('escape');
    await open();
    await page
      .getByRole('dialog')
      .locator(prototype ? '.el-switch' : '.ant-switch')
      .click();
    await capture('switch-edited');
    await close();
    await capture('cancel');
    expect(fixture('snapshot').schedule[0].enabled).toBe(0);
    await open();
    await page
      .getByRole('dialog')
      .locator(prototype ? '.el-dialog__headerbtn' : '.ant-modal-close')
      .click();
    await expect(page.getByRole('dialog')).toBeHidden();
    await capture('close-button');
    await open();
    await page.mouse.click(10, 500);
    await expect(page.getByRole('dialog')).toBeHidden();
    await capture('backdrop');
    expect(await page.evaluate(() => document.activeElement?.tagName)).toBe('BODY');
    await page.setViewportSize({ width: 768, height: 960 });
    await open();
    await capture('narrow-open');
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).toBeHidden();
    await page.setViewportSize({ width: 1440, height: 960 });
    await open();
    await capture('wide-reopened');
    await close();
    // 不等待弹窗获得初始焦点：复现打开动画期间立即操作单位下拉。
    for (let attempt = 0; attempt < 3; attempt++) {
      await page.getByRole('button', { name: zh.orders.sched.settings, exact: true }).click();
      await expect(page.getByRole('dialog')).toBeVisible();
      await page
        .getByRole('dialog')
        .locator(prototype ? '.el-select' : '.ant-select')
        .click();
      await settleOrderPage(page);
      await expect(
        page.getByRole('option', { name: zh.orders.sched.unit.minute, exact: true }),
      ).toBeVisible();
      await capture('fast-unit-' + attempt);
      await page.getByRole('option', { name: zh.orders.sched.unit.minute, exact: true }).click();
      await close();
    }
    await open();
    await unit(zh.orders.sched.unit.minute);
    const amount = page.getByRole('spinbutton');
    await amount.fill('10');
    await amount.press('ArrowUp');
    await expect(amount).toHaveValue('11');
    await capture('keyboard-up');
    await amount.press('ArrowDown');
    await expect(amount).toHaveValue('10');
    await capture('keyboard-down');
    await page
      .getByRole('dialog')
      .locator(prototype ? '.el-input-number__increase' : '.ant-input-number-action-up')
      .click();
    await expect(amount).toHaveValue('11');
    await capture('mouse-step-up');
    await page
      .getByRole('dialog')
      .locator(prototype ? '.el-input-number__decrease' : '.ant-input-number-action-down')
      .click();
    await expect(amount).toHaveValue('10');
    await capture('mouse-step-down');
    await amount.fill('1');
    await amount.press('ArrowDown');
    await expect(amount).toHaveValue('1');
    await capture('step-minimum');
    await amount.fill('10080');
    await amount.press('ArrowUp');
    await expect(amount).toHaveValue('10080');
    await capture('step-maximum');
    await close();
    await page.mouse.move(500, 700);
    await page.mouse.wheel(0, 1000);
    await expect.poll(() => page.locator('main').evaluate((el) => el.scrollTop)).toBeGreaterThan(0);
    await capture('wheel-bottom');
    await open();
    await capture('opened-after-scroll');
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).toBeHidden();
    await capture('escape-after-scroll');
    await save(120);
    await save(1440);
    expect(errors).toEqual([]);
  } finally {
    writeFileSync(
      info.outputPath('observations.json'),
      JSON.stringify({ states, errors }, null, 2),
    );
  }
});
