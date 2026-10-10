import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import en from '../../src/i18n/prototype/en.json' with { type: 'json' };
import vi from '../../src/i18n/prototype/vi.json' with { type: 'json' };
import { kind, source, login, language, run } from './query-support';

const python = resolve('../../sn-center-python/.venv/Scripts/python.exe');
const pi = 'CMP-UI-AUDIT-VIEW-PI';
const operator = 'CMP_AUDIT_VIEW';
const fixture = (info: TestInfo, action: string) =>
  execFileSync(
    python,
    ['-X', 'utf8', resolve('../tools/testing/parity-audit-fixture.py'), kind(info), action],
    { windowsHide: true, encoding: 'utf8' },
  );
const rows = (page: Page, info: TestInfo) =>
  page.locator(source(info) ? '.el-table__row' : '[data-testid="sn-audit-items"] .ant-table-row');
const values = (start: number, end: number) =>
  Array.from({ length: start - end + 1 }, (_, i) => start - i);
async function expectRows(page: Page, info: TestInfo, expected: number[]) {
  await expect
    .poll(() =>
      rows(page, info).evaluateAll((elements) =>
        elements.map((el) => el.querySelectorAll('td')[8]?.textContent?.trim()),
      ),
    )
    .toEqual(expected.map((n) => 'AV' + String(n).padStart(5, '0')));
}
async function capture(page: Page, info: TestInfo, name: string) {
  await expect(page.locator('.el-loading-mask:visible,.ant-spin-spinning')).toHaveCount(0);
  await page.evaluate((prototype) => {
    let element = document.querySelector(prototype ? '.page' : '.sn-page') as HTMLElement | null;
    while (element) {
      element.scrollTop = 0;
      element = element.parentElement;
    }
  }, source(info));
  await page.mouse.move(0, 0);
  await page.evaluate(async () => {
    await new Promise<void>((resolve) =>
      requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
    );
    await Promise.all(
      document
        .getAnimations()
        .filter((animation) => animation.effect?.getTiming().iterations !== Infinity)
        .map((animation) => animation.finished.catch(() => {})),
    );
  });
  await page.screenshot({ path: info.outputPath(name + '.png'), fullPage: true });
  writeFileSync(
    info.outputPath(name + '.json'),
    JSON.stringify(
      {
        cells: await rows(page, info).evaluateAll((elements) =>
          elements.map((el) => [...el.querySelectorAll('td')].map((td) => td.textContent?.trim())),
        ),
        text: await page.locator('body').innerText(),
        details: await page.evaluate((prototype) => {
          const selector = prototype
            ? '.el-table__expanded-cell .el-descriptions__cell'
            : '.sn-audit-details .ant-descriptions-row > th, .sn-audit-details .ant-descriptions-row > td';
          return [...document.querySelectorAll(selector)].map((element) => {
            const rect = element.getBoundingClientRect();
            const style = getComputedStyle(element);
            return {
              text: element.textContent,
              x: rect.x,
              y: rect.y,
              width: rect.width,
              height: rect.height,
              padding: style.padding,
              lineHeight: style.lineHeight,
              border: style.border,
              background: style.backgroundColor,
            };
          });
        }, source(info)),
        layout: await page.evaluate((prototype) => {
          const selectors = prototype
            ? [
                '.page__head',
                '.surface',
                '.toolbar',
                '.el-table__header-wrapper th',
                '.el-table__row',
              ]
            : [
                '.sn-page-head',
                '.sn-surface',
                '.sn-toolbar',
                '.ant-table-thead th',
                '.ant-table-row',
              ];
          return selectors.map((selector, index) =>
            [
              ...(index === 4
                ? (document.querySelector(selector)?.querySelectorAll('td') ?? [])
                : document.querySelectorAll(selector)),
            ]
              .filter((element) => element.getBoundingClientRect().width > 0)
              .map((element) => {
                const rect = element.getBoundingClientRect();
                return { x: rect.x, y: rect.y, width: rect.width, height: rect.height };
              }),
          );
        }, source(info)),
      },
      null,
      2,
    ),
  );
}
async function query(page: Page, info: TestInfo) {
  const ready = page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      new URL(r.url()).pathname === (source(info) ? '/api/audits' : '/api/v1/sn/audits/page'),
  );
  await page.getByRole('button', { name: zh.common.search, exact: true }).click();
  expect((await ready).ok()).toBe(true);
}
const select = (page: Page, info: TestInfo, index: number) =>
  page.locator(source(info) ? '.toolbar .el-select' : '.sn-toolbar .ant-select').nth(index);
async function choose(page: Page, info: TestInfo, index: number, label: string) {
  await select(page, info, index).click();
  await page.getByRole('option', { name: label, exact: true }).click();
}
async function clear(page: Page, info: TestInfo, index: number) {
  const field = select(page, info, index);
  await field.hover();
  await field.locator(source(info) ? '.el-select__clear' : '.ant-select-clear').click();
}

test('P12 audit: numbered pages, draft export scope, filters and inline detail', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  page.setDefaultTimeout(20_000);
  const seeded = JSON.parse(fixture(info, 'begin')) as { date: string };
  try {
    await login(
      page,
      'admin',
      readFileSync(
        resolve(`../deploy/.secrets/comparison-20261008/${kind(info)}-admin-password`),
        'utf8',
      ).trim(),
    );
    const ready = page.waitForResponse(
      (r) =>
        new URL(r.url()).pathname === (source(info) ? '/api/audits' : '/api/v1/sn/audits/page'),
    );
    await page.getByRole('menuitem', { name: zh.nav.audit, exact: true }).click();
    expect((await ready).ok()).toBe(true);
    await expect(page.getByRole('heading', { name: zh.audit.title, exact: true })).toBeVisible();
    await page.getByPlaceholder('PI', { exact: true }).fill(pi);
    await page.getByPlaceholder(zh.audit.operator, { exact: true }).fill(operator);
    await query(page, info);
    await expectRows(page, info, values(54, 35));
    await capture(page, info, '01-default-20');
    const next = page.locator(
      source(info) ? '.el-pagination .btn-next' : '.ant-pagination-next button',
    );
    await next.click();
    await expectRows(page, info, values(34, 15));
    await capture(page, info, '02-second-page');
    await next.click();
    await expectRows(page, info, values(14, 1));
    await capture(page, info, '03-third-page');
    await query(page, info);
    await expectRows(page, info, values(54, 35));
    const pager = page.locator(source(info) ? '.el-pagination' : '.sn-query-foot .ant-pagination');
    await pager.locator(source(info) ? '.el-select' : '.ant-select').click();
    await page.getByRole('option', { name: /^50\s*条\/页$/ }).click();
    await expectRows(page, info, values(54, 5));
    await capture(page, info, '04-fifty-rows');
    const expand = rows(page, info)
      .first()
      .locator(source(info) ? '.el-table__expand-icon' : '.sn-audit-expand');
    await expand.click();
    const details = page.locator(
      source(info) ? '.el-table__expanded-cell' : '.ant-table-expanded-row',
    );
    await expect(details).toBeVisible();
    await expect(details).toContainText('CMP-AUDIT-MO-054');
    await expect(details).toContainText('CMP_AUDIT_VIEW_054');
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await capture(page, info, '05-inline-detail');
    await expand.click();
    await expect(details).toBeHidden();
    // 独立比较导出点击时草稿范围；完整表头/时间/值与格式差异另行留证，不冒称文件一致。
    await page.getByPlaceholder(zh.audit.keyword, { exact: true }).fill('AV00007');
    const downloaded = page.waitForEvent('download');
    await page.getByRole('button', { name: zh.query.exportXlsx, exact: true }).click();
    const file = await downloaded;
    await file.saveAs(info.outputPath('06-draft-export.xlsx'));
    const parsed = JSON.parse(
      execFileSync(
        python,
        [
          '-X',
          'utf8',
          resolve('../tools/testing/parity-query-file.py'),
          info.outputPath('06-draft-export.xlsx'),
        ],
        { windowsHide: true, encoding: 'utf8' },
      ),
    ) as { rows: string[][] };
    expect(parsed.rows.slice(1).map((r) => r[12])).toEqual(['AV00007']);
    writeFileSync(
      info.outputPath('06-draft-export.json'),
      JSON.stringify({ ...parsed, filename: file.suggestedFilename() }, null, 2),
    );
    await expectRows(page, info, values(54, 5));
    await capture(page, info, '06-draft-keeps-query');
    await query(page, info);
    await expectRows(page, info, [7]);
    await capture(page, info, '07-keyword');
    await page.getByPlaceholder(zh.audit.keyword, { exact: true }).fill('');
    await choose(page, info, 0, zh.actions.SN_ALLOCATE);
    await choose(page, info, 1, zh.audit.fail);
    await choose(page, info, 2, zh.audit.srcPAGE);
    await query(page, info);
    await expectRows(page, info, [54, 36, 18]);
    await capture(page, info, '08-combined');
    await select(page, info, 3).click();
    await page.getByRole('option').filter({ hasText: '比较测试甲厂' }).click();
    await query(page, info);
    await expectRows(page, info, []);
    await capture(page, info, '09-factory-empty');
    await clear(page, info, 3);
    for (const index of [0, 1, 2]) await clear(page, info, index);
    await page.getByPlaceholder(zh.audit.keyword, { exact: true }).fill('%_');
    await query(page, info);
    await expectRows(page, info, []);
    await capture(page, info, '10-literal-wildcard-empty');
    await page.getByPlaceholder(zh.audit.keyword, { exact: true }).fill('');
    await page.getByPlaceholder(zh.audit.from, { exact: true }).fill(seeded.date);
    await page.getByPlaceholder(zh.audit.to, { exact: true }).fill(seeded.date);
    await page.getByPlaceholder(zh.audit.to, { exact: true }).press('Enter');
    await page.getByRole('heading', { name: zh.audit.title, exact: true }).click();
    await expect(page.getByPlaceholder(zh.audit.from, { exact: true })).toHaveValue(seeded.date);
    await expect(page.getByPlaceholder(zh.audit.to, { exact: true })).toHaveValue(seeded.date);
    await query(page, info);
    await expectRows(page, info, values(54, 5));
    await capture(page, info, '10-date-inclusive');
    const range = page.locator(source(info) ? '.toolbar .el-date-editor' : '.sn-audit-range');
    await range.hover();
    await range.locator(source(info) ? '.el-range__close-icon' : '.ant-picker-clear').click();
    await expect(page.getByPlaceholder(zh.audit.from, { exact: true })).toHaveValue('');
    await expect(page.getByPlaceholder(zh.audit.to, { exact: true })).toHaveValue('');
    await query(page, info);
    await expectRows(page, info, values(54, 5));
    await capture(page, info, '10-date-cleared');
    await page.getByPlaceholder(zh.audit.keyword, { exact: true }).fill('AV00007');
    await query(page, info);
    await expectRows(page, info, [7]);
    for (const [code, label, words] of [
      ['zh', '简体中文', zh],
      ['en', 'English', en],
      ['vi', 'Tiếng Việt', vi],
    ] as const) {
      await language(page, info, label, words.query.exportXlsx);
      await capture(page, info, `11-layout-${code}`);
    }
    await language(page, info, '简体中文', zh.query.exportXlsx);
  } finally {
    fixture(info, 'clean');
    writeFileSync(
      info.outputPath('baseline-preserved.json'),
      readFileSync(resolve(run, `audit-view-clean-${kind(info)}.json`)),
    );
  }
});
