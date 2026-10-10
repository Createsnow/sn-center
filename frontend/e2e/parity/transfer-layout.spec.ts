import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { protectTestSecrets } from '../privacy';
import { paceComparisonLogin } from './login-budget';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import en from '../../src/i18n/prototype/en.json' with { type: 'json' };
import vi from '../../src/i18n/prototype/vi.json' with { type: 'json' };

const source = (info: TestInfo) => info.project.name === 'prototype';
const active = (page: Page) => page.getByRole('tabpanel').filter({ visible: true });
const field = (page: Page, info: TestInfo, label: string) =>
  active(page)
    .locator(source(info) ? '.el-form-item' : '.ant-form-item')
    .filter({
      has: page
        .locator(source(info) ? '.el-form-item__label' : '.ant-form-item-label')
        .getByText(label, { exact: true }),
    });

async function language(page: Page, info: TestInfo, label: string) {
  const select = page.locator(source(info) ? '.header .lang' : '.prototype-language .ant-select');
  if ((await select.innerText()).includes(label)) return;
  await select.click();
  const saved = page.waitForResponse(
    (r) =>
      r.request().method() === (source(info) ? 'PUT' : 'POST') &&
      r.url().endsWith(source(info) ? '/auth/lang' : '/system/auth/language'),
  );
  await page.getByRole('option', { name: label, exact: true }).click();
  expect((await saved).ok()).toBe(true);
}

async function select(page: Page, info: TestInfo, label: string, value: string) {
  const combo = field(page, info, label).getByRole('combobox');
  await combo.click();
  const id = await combo.getAttribute('aria-controls');
  expect(id).toBeTruthy();
  if (source(info))
    await page.locator(`[id="${id}"]`).getByRole('option', { name: value, exact: true }).click();
  else
    await page
      .locator('.ant-select-dropdown')
      .filter({ has: page.locator(`[id="${id}"]`) })
      .locator('.ant-select-item-option-content')
      .getByText(value, { exact: true })
      .click();
}

async function settle(page: Page) {
  await page.evaluate(async () => {
    await new Promise<void>((r) => requestAnimationFrame(() => requestAnimationFrame(() => r())));
    await Promise.all(
      document
        .getAnimations()
        .filter((a) => a.effect?.getTiming().iterations !== Infinity)
        .map((a) => a.finished.catch(() => {})),
    );
  });
}

/** 只记录本页布局及真实文字，不归一化尺寸、行序或提示内容。 */
async function capture(page: Page, info: TestInfo, name: string, snapshot = false) {
  await page.mouse.move(0, 0);
  await settle(page);
  const metrics = await page.evaluate(
    ({ old, snapshot }) => {
      const selectors = snapshot
        ? {
            modal: old ? '.el-dialog' : '.sn-transfer-snapshot',
            close: old ? '.el-dialog__headerbtn' : '.sn-transfer-snapshot .ant-modal-close',
            closeIcon: old
              ? '.el-dialog__headerbtn svg'
              : '.sn-transfer-snapshot .ant-modal-close svg',
            search: old ? '.el-dialog .el-input' : '.sn-transfer-snapshot .ant-input-affix-wrapper',
            table: old ? '.el-dialog .el-table' : '.sn-transfer-snapshot .ant-table-wrapper',
            header: old
              ? '.el-dialog .el-table__header-wrapper'
              : '.sn-transfer-snapshot .ant-table-header',
            body: old
              ? '.el-dialog .el-table__body-wrapper'
              : '.sn-transfer-snapshot .ant-table-body',
          }
        : {
            headings: old
              ? '.el-tab-pane:not([style*="display: none"]) h4'
              : '.sn-transfer-grid h4',
            fields: old
              ? '.el-tab-pane:not([style*="display: none"]) .el-form-item'
              : '.sn-transfer-grid .ant-form-item',
            alerts: old
              ? '.el-tab-pane:not([style*="display: none"]) .el-alert'
              : '.sn-transfer-grid .ant-alert',
            icons: old
              ? '.el-tab-pane:not([style*="display: none"]) .el-alert__icon'
              : '.sn-transfer-grid .ant-alert-icon',
            radioButtons: old
              ? '.el-radio-button__inner'
              : '.sn-transfer-grid .ant-radio-button-wrapper',
            textarea: old ? '.el-textarea__inner' : '.sn-transfer-grid textarea',
            rule: old ? '.el-col > p.muted' : '.sn-transfer-grid > section > .sn-muted',
          };
      return Object.fromEntries(
        Object.entries(selectors).map(([key, selector]) => [
          key,
          [...document.querySelectorAll(selector)]
            .filter((el) => el.getBoundingClientRect().height > 0)
            .map((el) => {
              const r = el.getBoundingClientRect();
              return { x: r.x, y: r.y, width: r.width, height: r.height };
            }),
        ]),
      );
    },
    { old: source(info), snapshot },
  );
  writeFileSync(info.outputPath(name + '-geometry.json'), JSON.stringify(metrics, null, 2));
  await page.screenshot({ path: info.outputPath(name + '.png'), fullPage: true });
  if (snapshot) {
    const table = metrics.table?.[0];
    const body = metrics.body?.[0];
    if (!table || !body) throw new Error('未找到快照表格及滚动视区');
    expect(table.height).toBe(360);
    expect(body.y + body.height, '表体应填满表头以外的剩余视区，且不越界').toBe(
      table.y + table.height,
    );
  }
}

for (const [locale, label, words] of [
  ['zh-CN', '简体中文', zh],
  ['en', 'English', en],
  ['vi', 'Tiếng Việt', vi],
] as const) {
  test(`P10 layout ${locale}: field rhythm, clear control, alerts and snapshot at wide and narrow widths`, async ({
    page,
  }, info) => {
    test.setTimeout(150_000);
    const password = readFileSync(
      resolve(
        '../deploy/.secrets/comparison-20261008',
        (source(info) ? 'python' : 'java') + '-admin-password',
      ),
      'utf8',
    ).trim();
    protectTestSecrets(password);
    const errors: string[] = [];
    page.on('pageerror', (e) => errors.push(e.message));
    await page.goto('/login');
    await paceComparisonLogin(page);
    await page.locator('input[autocomplete="username"]').fill('admin');
    await page.locator('input[autocomplete="current-password"]').fill(password);
    await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
    await expect(page.getByRole('menuitem', { name: zh.nav.transfer, exact: true })).toBeVisible();
    try {
      await language(page, info, label);
      await page.getByRole('menuitem', { name: words.nav.transfer, exact: true }).click();
      const send = page.getByRole('button', { name: words.transfer.directSubmit, exact: true });
      await expect(send).toBeDisabled();
      await select(page, info, words.transfer.fromFactory, '比较测试甲厂（CMP-A）');
      const pi = field(page, info, 'PI').getByRole('textbox');
      await pi.fill('CMP-PI-001');
      await pi.blur();
      await select(page, info, words.transfer.toFactory, '比较测试乙厂（CMP-B）');
      await field(page, info, words.transfer.toPi).getByRole('textbox').fill('CMP-PI-002');
      await field(page, info, words.transfer.toPi).getByRole('textbox').blur();
      await field(page, info, words.transfer.reason)
        .getByRole('textbox')
        .fill('CMP_UI_VISUAL_ONLY');
      await expect(send).toBeEnabled();
      const clear = field(page, info, 'PI').locator(
        source(info) ? '.el-input__clear' : '.ant-input-clear-icon',
      );
      await page.mouse.move(0, 0);
      await expect(clear).toBeHidden();
      await pi.hover();
      await expect(clear).toBeVisible();
      await clear.click();
      await expect(pi).toHaveValue('');
      await expect(send).toBeDisabled();
      await pi.fill('CMP-PI-001');
      await pi.blur();
      await expect(send).toBeEnabled();
      await capture(page, info, '01-wide-pi');
      const chooseSingle = source(info)
        ? active(page)
            .locator('.el-radio-button__inner')
            .getByText(words.scope.SINGLE, { exact: true })
        : active(page)
            .locator('.ant-radio-button-wrapper')
            .filter({ has: page.getByRole('radio', { name: words.scope.SINGLE, exact: true }) });
      await chooseSingle.click();
      const sn = field(page, info, words.common.sn).getByRole('textbox');
      await sn.fill('CMP00002');
      await sn.blur();
      await expect(send).toBeEnabled();
      for (const [name, width] of [
        ['wide', 1440],
        ['narrow', 900],
      ] as const) {
        await page.setViewportSize({ width, height: 960 });
        await capture(page, info, `02-${name}-single`);
        await page.getByRole('button', { name: words.transfer.fromSnapshot, exact: true }).click();
        const modal = page.getByRole('dialog', { name: words.transfer.fromSnapshot, exact: true });
        const query = modal.getByPlaceholder(words.transfer.targetSearch, { exact: true });
        await query.fill('CMP-PI-001');
        await query.press('Enter');
        const rows = modal.locator(
          source(info)
            ? '.el-table__body-wrapper tbody tr'
            : '.ant-table-body tbody tr[data-row-key]',
        );
        await expect(rows).toHaveCount(2);
        expect(await modal.getByRole('columnheader').allTextContents()).toEqual([
          words.orders.org,
          words.common.customer,
          'PI',
          words.common.material,
          words.orders.materialName,
        ]);
        writeFileSync(
          info.outputPath(`03-${name}-snapshot-rows.json`),
          JSON.stringify(
            await rows.evaluateAll((items) =>
              items.map((row) =>
                [...row.querySelectorAll('td')].map((td) =>
                  td.textContent?.trim().replace(/\s+/g, ' '),
                ),
              ),
            ),
          ),
        );
        await capture(page, info, `03-${name}-snapshot`, true);
        await modal.locator(source(info) ? '.el-dialog__headerbtn' : '.ant-modal-close').click();
        await expect(modal).toBeHidden();
        await expect(sn).toHaveValue('CMP00002');
      }
      expect(errors).toEqual([]);
    } finally {
      await page.keyboard.press('Escape');
      await page.setViewportSize({ width: 1440, height: 960 });
      await language(page, info, '简体中文');
      writeFileSync(info.outputPath('page-errors.json'), JSON.stringify(errors));
    }
  });
}
