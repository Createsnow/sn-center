import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import en from '../../src/i18n/prototype/en.json' with { type: 'json' };
import vi from '../../src/i18n/prototype/vi.json' with { type: 'json' };
import { kind, source, login, language, run } from './query-support';

const python = resolve('../../sn-center-python/.venv/Scripts/python.exe');
const fixture = (info: TestInfo, action: string) =>
  execFileSync(
    python,
    ['-X', 'utf8', resolve('../tools/testing/parity-audit-fixture.py'), kind(info), action],
    { windowsHide: true, encoding: 'utf8' },
  );

async function capture(page: Page, info: TestInfo, name: string) {
  await page.mouse.move(0, 0);
  await expect(page.locator('.el-loading-mask:visible,.ant-spin-spinning')).toHaveCount(0);
  await page.evaluate(async () => {
    await new Promise<void>((done) =>
      requestAnimationFrame(() => requestAnimationFrame(() => done())),
    );
    await Promise.all(
      document
        .getAnimations()
        .filter((a) => a.effect?.getTiming().iterations !== Infinity)
        .map((a) => a.finished.catch(() => {})),
    );
  });
  const data = await page.evaluate((prototype) => {
    const selectors = prototype
      ? {
          surface: '.surface',
          toolbar: '.toolbar',
          headers: '.el-table__header th',
          cells: '.el-table__row:first-child td',
          pager: '.pager',
          total: '.el-pagination__total',
          size: '.el-pagination__sizes',
          prev: '.btn-prev',
          pages: '.el-pager li',
          next: '.btn-next',
          date: '.el-date-editor',
          dateInput: '.el-range-input',
          dateIcon: '.el-range__icon',
          separator: '.el-range-separator',
          table: '.el-table__body',
          scroll: '.el-table__body-wrapper .el-scrollbar__wrap',
        }
      : {
          surface: '.sn-surface',
          toolbar: '.sn-toolbar',
          headers: '.ant-table-thead th',
          cells: '.ant-table-row td',
          pager: '.sn-acquire-pager',
          total: '.ant-pagination-total-text',
          size: '.ant-pagination-options',
          prev: '.ant-pagination-prev',
          pages: '.ant-pagination-item',
          next: '.ant-pagination-next',
          date: '.sn-audit-range',
          dateInput: '.sn-audit-range input',
          dateIcon: '.ant-picker-suffix',
          separator: '.ant-picker-range-separator',
          table: '.ant-table-container table',
          scroll: '.ant-table-content',
        };
    const result: Record<string, unknown> = {};
    for (const [key, selector] of Object.entries(selectors)) {
      result[key] = [...document.querySelectorAll(selector)].map((element) => {
        const r = element.getBoundingClientRect(),
          s = getComputedStyle(element);
        return {
          text: (element instanceof HTMLInputElement ? element.value : element.textContent)?.trim(),
          x: r.x,
          y: r.y,
          width: r.width,
          height: r.height,
          color: s.color,
          font: s.font,
          padding: s.padding,
          gap: s.gap,
          margin: s.margin,
          scrollLeft: element.scrollLeft,
          scrollWidth: element.scrollWidth,
          clientWidth: element.clientWidth,
        };
      });
    }
    return result;
  }, source(info));
  writeFileSync(info.outputPath(name + '.json'), JSON.stringify(data, null, 2));
  await page.screenshot({ path: info.outputPath(name + '.png'), fullPage: true });
}

test('P12 layout: single row table, date range and pagination across widths and languages', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  fixture(info, 'begin');
  try {
    await login(
      page,
      'admin',
      readFileSync(
        resolve(`../deploy/.secrets/comparison-20261008/${kind(info)}-admin-password`),
        'utf8',
      ).trim(),
    );
    await page.getByRole('menuitem', { name: zh.nav.audit, exact: true }).click();
    await page.getByPlaceholder('PI', { exact: true }).fill('CMP-UI-AUDIT-VIEW-PI');
    await page.getByPlaceholder(zh.audit.operator, { exact: true }).fill('CMP_AUDIT_VIEW');
    await page.getByPlaceholder(zh.audit.keyword, { exact: true }).fill('AV00001');
    const ready = page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        new URL(r.url()).pathname === (source(info) ? '/api/audits' : '/api/v1/sn/audits/page') &&
        new URL(r.url()).searchParams.get('keyword') === 'AV00001',
    );
    await page.getByRole('button', { name: zh.common.search, exact: true }).click();
    expect((await ready).ok()).toBe(true);
    await expect(page.locator(source(info) ? '.el-table__row' : '.ant-table-row')).toHaveCount(1);
    for (const width of [1920, 1440, 900, 390]) {
      await page.setViewportSize({ width, height: 960 });
      await capture(page, info, `width-${width}`);
    }
    const pager = page.locator(source(info) ? '.el-pagination' : '.sn-acquire-pager');
    const sizes = pager.locator(source(info) ? '.el-select' : '.ant-select');
    await expect(sizes).toBeVisible();
    await sizes.click();
    await page.getByRole('option', { name: /^50\s*条\/页$/ }).click();
    await expect(sizes).toContainText('50');
    await capture(page, info, 'narrow-fifty');
    await sizes.click();
    await page.getByRole('option', { name: /^20\s*条\/页$/ }).click();
    await expect(sizes).toContainText('20');
    await page.setViewportSize({ width: 1440, height: 960 });
    const scrolling = page.locator(
      source(info) ? '.el-table__body-wrapper .el-scrollbar__wrap' : '.ant-table-content',
    );
    await expect.poll(() => scrolling.evaluate((element) => element.clientWidth)).toBe(1138);
    await scrolling.evaluate((element) => {
      element.scrollTo({ left: element.scrollWidth, behavior: 'instant' });
    });
    await expect.poll(() => scrolling.evaluate((element) => element.scrollLeft)).toBe(330);
    await capture(page, info, 'table-scroll-end');
    await scrolling.evaluate((element) => {
      element.scrollTo({ left: 0, behavior: 'instant' });
    });
    await expect.poll(() => scrolling.evaluate((element) => element.scrollLeft)).toBe(0);
    for (const [code, label, words] of [
      ['en', 'English', en],
      ['vi', 'Tiếng Việt', vi],
      ['zh', '简体中文', zh],
    ] as const) {
      await language(page, info, label, words.query.exportXlsx);
      await capture(page, info, `language-${code}`);
    }
  } finally {
    fixture(info, 'clean');
    writeFileSync(
      info.outputPath('baseline-preserved.json'),
      readFileSync(resolve(run, `audit-view-clean-${kind(info)}.json`)),
    );
  }
});
