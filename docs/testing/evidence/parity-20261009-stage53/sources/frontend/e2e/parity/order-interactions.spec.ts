import { test, expect, type Locator } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, renameSync } from 'node:fs';
import { resolve } from 'node:path';
import { protectTestSecrets } from '../privacy';
import { settleOrderPage } from './settleOrderPage';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import en from '../../src/i18n/prototype/en.json' with { type: 'json' };
import vi from '../../src/i18n/prototype/vi.json' with { type: 'json' };

// 只操作专用比较服务；长字段经真实合成K3同步，禁止伪造浏览器业务响应。
test('P05 interactions: overflow, line details, schedule limits and cancellation in three languages', async ({
  page,
}, info) => {
  test.setTimeout(360_000);
  page.setDefaultTimeout(15_000);
  const prototype = info.project.name === 'prototype';
  const kind = prototype ? 'python' : 'java';
  const run = process.env.ADTEK_ORDER_INTERACTIONS_RUN!;
  expect(run).toMatch(/^[0-9]{8,14}$/);
  const username = 'audit' + run;
  const helper = (action: string) =>
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
  helper('prepare');
  const password = readFileSync(
    resolve('../deploy/.secrets/comparison-20261008/' + kind + '-admin-password'),
    'utf8',
  ).trim();
  protectTestSecrets(password);
  const extraFile = resolve('../tools/.runtime/comparison-20261008/order-audits/extra-orders.json');
  const original = readFileSync(extraFile, 'utf8');
  const writeExtras = (data: string) => {
    writeFileSync(extraFile + '.next', data);
    renameSync(extraFile + '.next', extraFile);
  };
  const billNo = 'CMP-TOOLTIP-LONG-ORDER-00000051';
  const extras = [1, 2].map((status) => ({
    'F_ora_kh.FNumber': 'CMP-CUSTOMER-WITH-LONG-CODE-000051',
    FBillNo: billNo,
    F_HX_PO: 'PO-TOOLTIP-51',
    F_HX_PI: 'PI-TOOLTIP-00000000000000000051',
    'FMaterialId.FNumber': 'MAT-LONG-51-' + status,
    'FMaterialId.FName': '用于核对完整悬浮提示的生产物料名称 第五十一阶段',
    FQty: '1',
    FStatus: String(status),
    FSaleOrderNo: 'SD-TOOLTIP-00000000000000000051',
    'FPrdOrgId.FName': '比较测试未建档长名称工厂 第五十一阶段',
    FID: '9007199254741051',
    FTreeEntity_FEntryId: String(510 + status),
    FPrdOrgId: '151',
    'FPrdOrgId.FNumber': 'CMP-UNMAPPED-51',
  }));
  const states: unknown[] = [],
    writes: unknown[] = [],
    errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('response', async (response) => {
    if (
      response.request().method() === 'PUT' &&
      new URL(response.url()).pathname.endsWith('/orders/sync-schedule')
    ) {
      writes.push({
        status: response.status(),
        request: response.request().postDataJSON(),
        response: await response.json(),
      });
    }
  });
  const messages = page.locator(prototype ? '.el-message' : '.ant-message-notice');
  const tooltip = page.locator('[role="tooltip"]').filter({ visible: true });
  const capture = async (label: string) => {
    // 等待实际进入/离开动画结束，再读取几何；截图的动画快进不能代替测量稳定状态。
    await settleOrderPage(page);
    const amount = page.getByRole('dialog').getByRole('spinbutton');
    const amountValue = (await amount.count()) ? await amount.inputValue() : null;
    const geometry = await page.evaluate((source) => {
      const selectors = source
        ? {
            dialog: '.el-dialog',
            title: '.el-dialog__title',
            labels: '.el-dialog .el-form-item__label',
            number: '.el-dialog .el-input-number',
            unit: '.el-dialog .interval .el-select',
            hint: '.el-dialog .hint',
            alert: '.el-dialog .el-alert',
            buttons: '.el-dialog__footer .el-button',
            switch: '.el-dialog .el-switch__core',
            message: '.el-message',
            tooltip: '.el-popper[role=tooltip]',
            numberInput: '.el-dialog .el-input__inner',
            unitText: '.el-dialog .el-select__selected-item:not(.el-select__input-wrapper)',
            alertText: '.el-dialog .el-alert__title',
            messageText: '.el-message__content',
            detailTitle: '.el-col:last-child .surface__title',
            detailButton: '.el-col:last-child .surface__title .el-button',
            details: '.el-descriptions__cell',
            lineCells: '.el-col:last-child .el-table__row td',
          }
        : {
            dialog: '.sn-schedule-modal .ant-modal-container',
            title: '.sn-schedule-modal .ant-modal-title',
            labels: '.sn-schedule-modal .ant-form-item-label',
            number: '.sn-schedule-modal .ant-input-number',
            unit: '.sn-schedule-modal .sn-schedule-interval .ant-select',
            hint: '.sn-schedule-modal .sn-schedule-interval + .sn-muted',
            alert: '.sn-schedule-modal .ant-alert',
            buttons: '.sn-schedule-modal .ant-modal-footer .ant-btn',
            switch: '.sn-schedule-modal .ant-switch',
            message: '.ant-message-notice',
            tooltip: '.ant-tooltip[role=tooltip],.ant-tooltip-container[role=tooltip]',
            numberInput: '.sn-schedule-modal .ant-input-number-input',
            unitText: '.sn-schedule-modal .ant-select-content',
            alertText: '.sn-schedule-modal .ant-alert-title',
            messageText: '.ant-message-notice-title',
            detailTitle: '.sn-order-lines .sn-surface-title',
            detailButton: '.sn-order-lines .sn-surface-title .ant-btn',
            details:
              '.sn-order-lines .ant-descriptions-item-label,.sn-order-lines .ant-descriptions-item-content',
            lineCells: '[data-testid=order-lines] .ant-table-row td',
          };
      return Object.fromEntries(
        Object.entries(selectors).map(([key, selector]) => [
          key,
          [...document.querySelectorAll(selector)]
            .filter(
              (el) => el.getClientRects().length && getComputedStyle(el).visibility !== 'hidden',
            )
            .map((el) => {
              const r = el.getBoundingClientRect(),
                s = getComputedStyle(el);
              // 表格框架把padding/line-height放在不同层，另测实际文字以区分DOM与显示差异。
              const paint = [];
              if (key === 'details' || key === 'lineCells') {
                const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
                let node: Node | null;
                while ((node = walker.nextNode())) {
                  if (!node.textContent?.trim() || !node.parentElement) continue;
                  const style = getComputedStyle(node.parentElement);
                  if (style.visibility === 'hidden' || style.display === 'none') continue;
                  const range = document.createRange();
                  range.selectNodeContents(node);
                  const rects = [...range.getClientRects()].map((rect) => [
                    rect.x,
                    rect.y,
                    rect.width,
                    rect.height,
                  ]);
                  if (rects.length)
                    paint.push({
                      text: node.textContent,
                      rects,
                      font: [
                        style.fontFamily,
                        style.fontSize,
                        style.fontWeight,
                        style.letterSpacing,
                      ],
                      color: style.color,
                      lineHeight: style.lineHeight,
                    });
                }
              }
              return {
                text: (el as HTMLElement).innerText,
                box: [r.x, r.y, r.width, r.height],
                font: [s.fontFamily, s.fontSize, s.fontWeight, s.lineHeight],
                color: s.color,
                background: s.backgroundColor,
                padding: s.padding,
                shadow: s.boxShadow,
                filter: s.filter,
                paint,
              };
            }),
        ]),
      );
    }, prototype);
    states.push({
      label,
      geometry,
      mainScroll: await page.locator('main').evaluate((el) => el.scrollTop),
      focus: await page.evaluate(() => ({
        tag: document.activeElement?.tagName,
        text: document.activeElement?.textContent?.slice(0, 120),
      })),
      tooltips: await tooltip.allTextContents(),
      message: await messages.allTextContents(),
      amount: amountValue,
      schedule: helper('snapshot').schedule,
    });
    await page.screenshot({ path: info.outputPath(label + '.png'), animations: 'disabled' });
  };
  const hover = async (cell: Locator, label: string) => {
    await cell.hover();
    await page.waitForTimeout(250); // 原型Tooltip含show-after延迟；测量稳定展开状态。
    await expect(tooltip).toHaveCount(1);
    await capture(label);
    await page.mouse.move(0, 0);
    await expect(tooltip).toHaveCount(0);
  };
  let text = zh;
  let languageKey = 'zh-CN';
  const sync = async (label: string) => {
    await expect(messages).toHaveCount(0);
    const ready = page.waitForResponse(
      (r) => r.request().method() === 'POST' && new URL(r.url()).pathname.endsWith('/orders/sync'),
    );
    await page.getByRole('button', { name: text.orders.syncAll, exact: true }).click();
    expect((await ready).status()).toBe(200);
    await expect(messages).toHaveCount(1);
    await capture(label);
    await expect(page.locator('.ant-spin-spinning:visible,.el-loading-mask:visible')).toHaveCount(
      0,
    );
    await expect(messages).toHaveCount(0);
  };
  const open = async () => {
    await page.getByRole('button', { name: text.orders.sched.settings, exact: true }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
  };
  const choose = async (unit: 'minute' | 'hour' | 'day') => {
    await page
      .getByRole('dialog')
      .locator(prototype ? '.el-select' : '.ant-select')
      .click();
    await page.getByRole('option', { name: text.orders.sched.unit[unit], exact: true }).click();
  };
  const save = async (minutes: number) => {
    await open();
    await choose('minute');
    await page.getByRole('dialog').getByRole('spinbutton').fill(String(minutes));
    const saved = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        new URL(r.url()).pathname.endsWith('/orders/sync-schedule'),
    );
    await page
      .getByRole('dialog')
      .getByRole('button', { name: text.common.save, exact: true })
      .click();
    expect((await saved).status()).toBe(200);
    await expect(page.getByRole('dialog')).toBeHidden();
    await expect(messages).toHaveCount(1);
    await capture(`${languageKey}-saved-${minutes}`);
    await expect(messages).toHaveCount(0);
  };
  try {
    writeExtras(JSON.stringify(extras));
    await page.goto((prototype ? 'http://127.0.0.1:8001' : 'http://127.0.0.1:15174') + '/login');
    await page.locator('input[autocomplete="username"]').fill(username);
    await page.locator('input[autocomplete="current-password"]').fill(password);
    await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
    await page.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
    await expect(page.getByText('CMP-MO-002', { exact: true })).toBeVisible();
    await expect(
      page.getByRole('button', { name: zh.orders.sched.settings, exact: true }),
    ).toBeVisible();
    await expect(page.locator('.ant-spin-spinning:visible,.el-loading-mask:visible')).toHaveCount(
      0,
    );
    await capture('initial-orders');
    for (const [locale, language, translations] of [
      ['zh-CN', '简体中文', zh],
      ['en', 'English', en],
      ['vi', 'Tiếng Việt', vi],
    ] as const) {
      text = translations;
      languageKey = locale;
      await page.setViewportSize({ width: 1440, height: 960 });
      const languageControl = page.locator(
        prototype ? '.header .lang' : '.prototype-language .ant-select',
      );
      if (!(await languageControl.innerText()).includes(language)) {
        const changed = page.waitForResponse(
          (r) =>
            r.request().method() !== 'GET' &&
            new URL(r.url()).pathname.endsWith(prototype ? '/auth/lang' : '/system/auth/language'),
        );
        await languageControl.click();
        await page.getByRole('option', { name: language, exact: true }).click();
        await changed;
      }
      await sync(locale + '-synced');
      for (const width of [1440, 768]) {
        await page.setViewportSize({ width, height: 960 });
        const rows = page.locator(prototype ? '.el-table__row' : '.sn-order-table .ant-table-row');
        const row = rows.filter({ has: page.getByText(billNo, { exact: true }) });
        await expect(row).toHaveCount(1);
        for (let i = 0; i < 5; i++)
          await hover(row.locator('td').nth(i), `${locale}-${width}-overflow-${i}`);
        const short = rows
          .filter({ has: page.getByText('CMP-MO-002', { exact: true }) })
          .locator('td')
          .first();
        await short.hover();
        await page.waitForTimeout(250);
        await expect(tooltip).toHaveCount(0);
        await capture(`${locale}-${width}-short-no-tooltip`);
        await page.mouse.move(0, 0);
        if (width === 768) {
          const scroller = page.locator(
            prototype
              ? '.el-col:first-child .el-table__body-wrapper .el-scrollbar__wrap'
              : '.sn-order-table .ant-table-body',
          );
          await row.locator('td').nth(4).hover();
          await expect(tooltip).toHaveCount(1);
          const beforeScroll = await scroller.evaluate((el) => el.scrollLeft);
          await page.mouse.wheel(120, 0);
          await expect
            .poll(() => scroller.evaluate((el) => el.scrollLeft))
            .toBeGreaterThan(beforeScroll);
          await expect(tooltip).toHaveCount(0);
          await capture(`${locale}-${width}-scroll-closes-tooltip`);
        }
        await row.locator('td').first().click();
        await expect(
          page.getByText(text.orders.linesOf.replace('{{bill}}', billNo), { exact: true }),
        ).toBeVisible();
        await expect(
          page.locator('.ant-spin-spinning:visible,.el-loading-mask:visible'),
        ).toHaveCount(0);
        const lines = page.locator(
          prototype
            ? '.el-col:last-child .el-table__row'
            : '[data-testid=order-lines] .ant-table-row',
        );
        await expect(lines).toHaveCount(2);
        await hover(lines.first().locator('td').nth(2), `${locale}-${width}-line-overflow`);
        await open();
        await page.mouse.move(0, 0);
        await capture(`${locale}-${width}-dialog`);
        await page.keyboard.press('Escape');
        await expect(page.getByRole('dialog')).toBeHidden();
      }
      await page.setViewportSize({ width: 1440, height: 960 });
      await open();
      await choose('minute');
      await page.getByRole('dialog').getByRole('spinbutton').fill('9');
      const before = helper('snapshot').schedule;
      await page
        .getByRole('dialog')
        .getByRole('button', { name: text.common.save, exact: true })
        .click();
      await expect(messages).toHaveCount(1);
      await capture(locale + '-invalid-minimum');
      expect(helper('snapshot').schedule).toEqual(before);
      await expect(messages).toHaveCount(0);
      await page.getByRole('dialog').getByRole('spinbutton').fill('10080');
      await choose('day');
      await capture(locale + '-unit-max-change');
      await page
        .getByRole('dialog')
        .getByRole('button', { name: text.common.save, exact: true })
        .click();
      await expect(messages).toHaveCount(1);
      await capture(locale + '-invalid-maximum');
      expect(helper('snapshot').schedule).toEqual(before);
      await expect(messages).toHaveCount(0);
      await page
        .getByRole('dialog')
        .getByRole('button', { name: text.common.cancel, exact: true })
        .click();
      await expect(page.getByRole('dialog')).toBeHidden();
      expect(helper('snapshot').schedule).toEqual(before);
      await open();
      await page.getByRole('dialog').getByRole('spinbutton').fill('');
      await page
        .getByRole('dialog')
        .getByRole('button', { name: text.common.save, exact: true })
        .click();
      await expect(messages).toHaveCount(1);
      await capture(locale + '-empty-number');
      await expect(messages).toHaveCount(0);
      if (await page.getByRole('dialog').isVisible())
        await page
          .getByRole('dialog')
          .getByRole('button', { name: text.common.cancel, exact: true })
          .click();
      await save(120);
      await open();
      await capture(locale + '-saved-two-hours');
      await page
        .getByRole('dialog')
        .locator(prototype ? '.el-switch' : '.ant-switch')
        .click();
      await page.keyboard.press('Escape');
      await expect(page.getByRole('dialog')).toBeHidden();
      expect(helper('snapshot').schedule[0].enabled).toBe(0);
      await save(1440);
    }
    writeExtras(original);
    await sync('restored-original-orders');
    expect(errors).toEqual([]);
  } finally {
    writeExtras(original);
    writeFileSync(
      info.outputPath('observations.json'),
      JSON.stringify({ states, writes, errors }, null, 2),
    );
  }
});
