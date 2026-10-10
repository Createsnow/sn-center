import { test, expect, type Page } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { protectTestSecrets } from '../privacy';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import en from '../../src/i18n/prototype/en.json' with { type: 'json' };
import vi from '../../src/i18n/prototype/vi.json' with { type: 'json' };
import errZh from '../../src/i18n/prototype/errors.zh-CN.json' with { type: 'json' };
import errEn from '../../src/i18n/prototype/errors.en.json' with { type: 'json' };
import errVi from '../../src/i18n/prototype/errors.vi.json' with { type: 'json' };

// 复用专用a48合成库，新增本轮身份；不清除既有痕迹或连接常驻比较库。
test('P05 boundaries: Unicode blank bill, three-language validation and schedule owner handover', async ({
  page,
  browser,
  request,
}, info) => {
  test.setTimeout(180_000);
  page.setDefaultTimeout(15_000);
  const prototype = info.project.name === 'prototype';
  const kind = prototype ? 'python' : 'java';
  const base = process.env.ADTEK_BOUNDARY_RUN!;
  expect(base).toMatch(/^[0-9]{8,12}$/);
  const tag = base + '01',
    otherTag = base + '02',
    username = 'audit' + tag,
    otherName = 'audit' + otherTag;
  const fixture = (action: string, run = tag) =>
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
        { windowsHide: true, encoding: 'utf8' },
      ),
    );
  fixture('prepare');
  fixture('prepare', otherTag);
  const password = readFileSync(
    resolve(`../deploy/.secrets/comparison-20261008/${kind}-admin-password`),
    'utf8',
  ).trim();
  protectTestSecrets(password);
  const url = prototype ? 'http://127.0.0.1:8001' : 'http://127.0.0.1:15174';
  const otherContext = await browser.newContext({ viewport: { width: 1440, height: 960 } });
  const other = await otherContext.newPage();
  const pageErrors: string[] = [];
  for (const tab of [page, other]) tab.on('pageerror', (e) => pageErrors.push(e.message));
  const states: unknown[] = [];
  const writes: unknown[] = [];
  const postPaths: string[] = [];
  page.on('request', (r) => {
    if (r.method() === 'POST' && new URL(r.url()).pathname.endsWith('/orders/sync'))
      postPaths.push(r.url());
  });
  const login = async (tab: Page, name: string) => {
    await tab.goto(url + '/login');
    await tab.locator('input[autocomplete="username"]').fill(name);
    await tab.locator('input[autocomplete="current-password"]').fill(password);
    await tab.getByRole('button', { name: zh.login.submit, exact: true }).click();
    await tab.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
    await tab.waitForLoadState('networkidle');
  };
  const language = async (label: string) => {
    const control = page.locator(prototype ? '.header .lang' : '.prototype-language .ant-select');
    if ((await control.innerText()).includes(label)) return;
    const saved = page.waitForResponse(
      (r) =>
        r.request().method() === (prototype ? 'PUT' : 'POST') &&
        new URL(r.url()).pathname.endsWith(prototype ? '/auth/lang' : '/system/auth/language'),
    );
    await control.click();
    await page.getByRole('option', { name: label, exact: true }).click();
    expect((await saved).ok()).toBe(true);
  };
  const capture = async (label: string, tab = page) => {
    await tab.mouse.move(0, 0);
    await expect(tab.locator('.ant-spin-spinning:visible,.el-loading-mask:visible')).toHaveCount(0);
    await expect(tab.locator('.sn-page-actions [aria-busy="true"]')).toHaveCount(0);
    await expect(tab.locator('.sn-page-actions .ant-btn-loading-icon:visible')).toHaveCount(0);
    states.push({
      label,
      body: await tab.locator('main').innerText(),
      messages: await tab
        .locator(prototype ? '.el-message' : '.ant-message-notice')
        .allTextContents(),
      database: fixture('snapshot'),
      geometry: await tab.evaluate((source) => {
        const groups = source
          ? {
              buttons: '.page__actions .el-button',
              headers: '.el-table__header th',
              meta: '.surface.meta > span',
              input: '.page__actions .el-input',
              summary: '.sched > *',
              inputText: '.page__actions input',
              toolbarButtons: '.toolbar .el-button',
              table: '.el-table',
              pager: '.el-pagination',
              pagerTotal: '.el-pagination__total',
              pagerSize: '.el-pagination__sizes',
              pagerSelect: '.el-pagination .el-select',
              pagerPrev: '.el-pagination .btn-prev',
              pagerNext: '.el-pagination .btn-next',
              pagerNumber: '.el-pagination .el-pager .number',
            }
          : {
              buttons: '.sn-page-actions .ant-btn',
              headers: '.ant-table-thead th',
              meta: '.sn-order-meta > span',
              input: '.sn-page-actions .sn-sync-bill',
              summary: '.sn-schedule-summary > *',
              inputText: '.sn-page-actions input',
              toolbarButtons: '.sn-toolbar > .ant-btn',
              table: '.sn-order-table .ant-table-container',
              pager: '.sn-order-table .ant-pagination',
              pagerTotal: '.sn-order-pager .ant-pagination-total-text',
              pagerSize: '.sn-order-pager .ant-pagination-options',
              pagerSelect: '.sn-order-pager .ant-select',
              pagerPrev: '.sn-order-pager .ant-pagination-prev',
              pagerNext: '.sn-order-pager .ant-pagination-next',
              pagerNumber: '.sn-order-pager .ant-pagination-item',
            };
        return Object.fromEntries(
          Object.entries(groups).map(([key, selector]) => [
            key,
            [...document.querySelectorAll(selector)].map((el) => {
              const r = el.getBoundingClientRect(),
                s = getComputedStyle(el);
              return {
                text: el.textContent,
                rect: [r.x, r.y, r.width, r.height],
                font: s.font,
                padding: s.padding,
                gap: s.gap,
                color: s.color,
                background: s.backgroundColor,
                border: s.border,
                whiteSpace: s.whiteSpace,
                overflow: s.overflow,
                fontFamily: s.fontFamily,
                textBoxes:
                  key === 'headers'
                    ? (() => {
                        const boxes = [];
                        const nodes = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
                        while (nodes.nextNode()) {
                          if (!nodes.currentNode.textContent?.trim()) continue;
                          const range = document.createRange();
                          range.selectNodeContents(nodes.currentNode);
                          boxes.push(
                            ...[...range.getClientRects()].map((box) => [
                              box.x,
                              box.y,
                              box.width,
                              box.height,
                            ]),
                          );
                        }
                        return boxes;
                      })()
                    : undefined,
              };
            }),
          ]),
        );
      }, prototype),
    });
    await tab.screenshot({ path: info.outputPath(label + '.png'), animations: 'disabled' });
  };
  const saveSchedule = async (tab: Page, enabled: boolean, minutes: number) => {
    await tab.getByRole('button', { name: zh.orders.sched.settings, exact: true }).click();
    const dialog = tab.getByRole('dialog');
    await dialog.locator(prototype ? '.el-select' : '.ant-select').click();
    await tab.getByRole('option', { name: zh.orders.sched.unit.minute, exact: true }).click();
    await dialog.getByRole('spinbutton').fill(String(minutes));
    if ((await dialog.getByRole('switch').getAttribute('aria-checked')) !== String(enabled))
      await dialog.locator(prototype ? '.el-switch' : '.ant-switch').click();
    const response = tab.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        new URL(r.url()).pathname.endsWith('/orders/sync-schedule'),
    );
    await dialog.getByRole('button', { name: zh.common.save, exact: true }).click();
    const result = await response;
    expect(result.ok()).toBe(true);
    const body = await result.json();
    await expect(dialog).toBeHidden();
    return prototype ? body : body.data;
  };
  try {
    await login(page, username);
    const ordersBefore = fixture('snapshot').orders;
    const erpBefore = (await (await request.get('http://127.0.0.1:19091')).json()).queryRequests;
    for (const [locale, label, text, errors] of [
      ['zh-CN', '简体中文', zh, errZh],
      ['en', 'English', en, errEn],
      ['vi', 'Tiếng Việt', vi, errVi],
    ] as const) {
      await language(label);
      const input = page.getByPlaceholder(text.orders.billToSync, { exact: true });
      const button = page.getByRole('button', { name: text.orders.syncOne, exact: true });
      await input.fill('   ');
      await expect(button).toBeDisabled();
      const before = postPaths.length;
      await input.press('Enter');
      await page.waitForLoadState('networkidle');
      expect(postPaths).toHaveLength(before);
      await capture(locale + '-blank-disabled');
      for (const [suffix, value] of [
        ['nel', '\u0085'],
        ['separator', '\u001c'],
      ] as const) {
        await input.fill(value);
        await expect(button).toBeEnabled();
        const pending = page.waitForResponse(
          (r) =>
            r.request().method() === 'POST' && new URL(r.url()).pathname.endsWith('/orders/sync'),
        );
        await button.click();
        const response = await pending;
        expect(response.status()).toBe(400);
        writes.push({ locale, suffix, body: await response.json() });
        const message = page.locator(
          prototype ? '.el-message--error' : '.sn-prototype-error-message',
        );
        await expect(message).toHaveText(errors.ORDER_BILL_REQUIRED);
        expect(fixture('snapshot').orders).toEqual(ordersBefore);
        expect((await (await request.get('http://127.0.0.1:19091')).json()).queryRequests).toBe(
          erpBefore,
        );
        await capture(locale + '-' + suffix);
        await message.locator(prototype ? '.el-message__closeBtn' : 'button').click();
        await expect(message).toHaveCount(0);
      }
      await page.getByRole('button', { name: text.orders.sched.settings, exact: true }).click();
      const dialog = page.getByRole('dialog');
      await dialog.locator(prototype ? '.el-select' : '.ant-select').click();
      await page.getByRole('option', { name: text.orders.sched.unit.minute, exact: true }).click();
      await dialog.getByRole('spinbutton').fill('9');
      const beforeSettings = fixture('snapshot').schedule;
      await dialog.getByRole('button', { name: text.common.save, exact: true }).click();
      const intervalMessage = page.locator(
        prototype ? '.el-message--error' : '.ant-message-notice-error',
      );
      await expect(intervalMessage).toHaveText(
        text.orders.sched.intervalHint.replace('{{min}}', '10'),
      );
      await expect(dialog).toBeVisible();
      expect(fixture('snapshot').schedule).toEqual(beforeSettings);
      await capture(locale + '-interval-nine');
      await dialog.getByRole('button', { name: text.common.cancel, exact: true }).click();
      await expect(intervalMessage).toHaveCount(0);
      const sizeSuffix = locale === 'zh-CN' ? '条/页' : locale === 'en' ? '/page' : '/trang';
      for (const size of [100, 20]) {
        const changed = page.waitForResponse((r) => {
          const address = new URL(r.url());
          return (
            r.request().method() === 'GET' &&
            address.pathname === (prototype ? '/api/orders' : '/api/v1/sn/orders') &&
            address.searchParams.get(prototype ? 'page_size' : 'size') === String(size)
          );
        });
        await page
          .locator(prototype ? '.el-pagination .el-select' : '.sn-order-pager .ant-select')
          .click();
        await page.getByRole('option', { name: String(size) + sizeSuffix, exact: true }).click();
        expect((await changed).ok()).toBe(true);
        await expect(
          page.locator(prototype ? '.el-pagination .el-select' : '.sn-order-pager .ant-select'),
        ).toHaveText(String(size) + sizeSuffix);
      }
      for (let repeat = 0; repeat < 2; repeat++) {
        const refreshed = page.waitForResponse(
          (r) =>
            r.request().method() === 'GET' &&
            new URL(r.url()).pathname === (prototype ? '/api/orders' : '/api/v1/sn/orders'),
        );
        await page.getByRole('button', { name: text.common.search, exact: true }).click();
        expect((await refreshed).ok()).toBe(true);
      }
      await page.locator(prototype ? '.el-pagination' : '.sn-order-pager').scrollIntoViewIfNeeded();
      await capture(locale + '-pager');
      await page.mouse.move(900, 400);
      await page.mouse.wheel(0, -1500);
      await expect.poll(() => page.locator('main').evaluate((el) => el.scrollTop)).toBe(0);
    }
    await language('简体中文');
    const initial = await saveSchedule(page, true, 60);
    await capture('schedule-owner-a');
    await login(other, otherName);
    const changed = await saveSchedule(other, true, 60);
    expect(changed[prototype ? 'next_run_at' : 'nextRunAt']).toBe(
      initial[prototype ? 'next_run_at' : 'nextRunAt'],
    );
    expect(changed[prototype ? 'updated_by' : 'ownerName']).toBe(otherName);
    await capture('schedule-owner-b', other);
    await saveSchedule(other, false, 1440);
    await page.getByRole('menuitem', { name: zh.nav.audit, exact: true }).click();
    await page.getByPlaceholder(zh.audit.operator, { exact: true }).fill(username);
    await page
      .locator(prototype ? '.toolbar .el-select' : '.sn-toolbar .ant-select')
      .nth(0)
      .click();
    await page.getByRole('option', { name: zh.actions.ORDER_SYNC, exact: true }).click();
    const pending = page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        new URL(r.url()).pathname === (prototype ? '/api/audits' : '/api/v1/sn/audits/page') &&
        new URL(r.url()).searchParams.get('operator') === username,
    );
    await page.getByRole('button', { name: zh.common.search, exact: true }).click();
    const body = await (await pending).json();
    const facts = prototype ? body.items : body.data.items;
    expect(facts).toHaveLength(6);
    for (const fact of facts) {
      expect(fact[prototype ? 'error_msg' : 'errorMessage']).toBe(errZh.ORDER_BILL_REQUIRED);
      expect(fact.detail).toBe('POST /api/orders/sync');
      expect(fact[prototype ? 'qty' : 'quantity']).toBeNull();
    }
    await capture('audit-six-rejections');
    const rows = page.locator(prototype ? '.el-table__row' : '.ant-table-row');
    await expect(rows).toHaveCount(6);
    await rows
      .first()
      .locator(prototype ? '.el-table__expand-icon' : '.sn-audit-expand')
      .click();
    await expect(
      page
        .locator(prototype ? '.el-descriptions' : '.sn-audit-details')
        .getByText(errZh.ORDER_BILL_REQUIRED, { exact: true }),
    ).toBeVisible();
    await capture('audit-rejection-detail');
    expect(pageErrors).toEqual([]);
  } finally {
    await otherContext.close();
    writeFileSync(info.outputPath('states.json'), JSON.stringify(states, null, 2));
    writeFileSync(info.outputPath('writes.json'), JSON.stringify({ writes, pageErrors }, null, 2));
  }
});
