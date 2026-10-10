import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync, renameSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { protectTestSecrets } from '../privacy';
import { paceComparisonLogin } from './login-budget';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import en from '../../src/i18n/prototype/en.json' with { type: 'json' };
import vi from '../../src/i18n/prototype/vi.json' with { type: 'json' };

const PIS = ['CMP-UI-QUERY-PI-A', 'CMP-UI-QUERY-PI-B'];
const BILLS = ['CMP-UI-QUERY-MO-A', 'CMP-UI-QUERY-MO-B'];
const customer = 'CMP-QUERY-CUSTOMER';
const run = resolve('../tools/.runtime/comparison-20261008');
const python = resolve('../../sn-center-python/.venv/Scripts/python.exe');
const source = (info: TestInfo) => info.project.name === 'prototype';
const kind = (info: TestInfo) => (source(info) ? 'python' : 'java');
const rows = (page: Page, info: TestInfo) =>
  page.locator(source(info) ? '.el-table__row' : '.ant-table-row');
const numbers = (prefix: 'A' | 'B', start: number, end: number) =>
  Array.from(
    { length: Math.abs(end - start) + 1 },
    (_, index) => 'Q28' + prefix + String(start + index * (end >= start ? 1 : -1)).padStart(5, '0'),
  );

function fixture(info: TestInfo, action: 'begin' | 'seed' | 'observe' | 'active' | 'clean') {
  return execFileSync(
    python,
    [
      '-X',
      'utf8',
      resolve('../tools/testing/parity-acquire-fixture.py'),
      kind(info),
      action,
      '--profile',
      'query',
    ],
    { windowsHide: true, encoding: 'utf8' },
  );
}

async function login(page: Page, info: TestInfo) {
  await page.goto('/login');
  await paceComparisonLogin(page);
  const password = readFileSync(
    resolve(`../deploy/.secrets/comparison-20261008/${kind(info)}-admin-password`),
    'utf8',
  ).trim();
  protectTestSecrets(password);
  await page.locator('input[autocomplete="username"]').fill('admin');
  await page.locator('input[autocomplete="current-password"]').fill(password);
  await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
  await expect(page.getByRole('menuitem', { name: zh.nav.query, exact: true })).toBeVisible();
}

async function sync(page: Page, include: boolean) {
  const entries = include
    ? PIS.flatMap((pi, index) =>
        [0, 1].map((line) => ({
          'F_ora_kh.FNumber': customer,
          FBillNo: BILLS[index],
          F_HX_PO: 'CMP-QUERY-PO',
          F_HX_PI: pi,
          'FMaterialId.FNumber': 'CMP-QUERY-MAT-' + (line === 0 ? 'Z' : 'A'),
          'FMaterialId.FName': 'CMP-QUERY-MAT-' + (line === 0 ? 'Z' : 'A'),
          FQty: String(index === 0 ? (line === 0 ? 30 : 21) : line === 0 ? 2 : 1),
          FStatus: '1',
          FSaleOrderNo: 'CMP-QUERY-SO',
          'FPrdOrgId.FName': '比较测试甲厂',
          FID: String(9007199254792001n + BigInt(index)),
          FTreeEntity_FEntryId: String(11 + line),
          FPrdOrgId: '101',
          'FPrdOrgId.FNumber': 'CMP-A',
        })),
      )
    : [];
  writeFileSync(resolve(run, 'extra-orders.json.next'), JSON.stringify(entries));
  renameSync(resolve(run, 'extra-orders.json.next'), resolve(run, 'extra-orders.json'));
  await page.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
  await page.getByRole('button', { name: zh.orders.syncAll, exact: true }).click();
  await expect(
    page
      .getByText(`已同步 ${include ? 5 : 3} 张单据，${include ? 8 : 4} 行`, { exact: true })
      .last(),
  ).toBeVisible();
}

async function query(page: Page, info: TestInfo) {
  const response = page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      new URL(r.url()).pathname === (source(info) ? '/api/sn' : '/api/v1/sn/items/page'),
  );
  await page.getByRole('button', { name: zh.common.search, exact: true }).click();
  expect((await response).status()).toBe(200);
}

async function expectRows(page: Page, info: TestInfo, expected: string[]) {
  await expect
    .poll(async () =>
      rows(page, info).evaluateAll((elements) =>
        elements.map((element) => element.querySelector('td')?.textContent?.trim() ?? ''),
      ),
    )
    .toEqual(expected);
}

async function capture(page: Page, info: TestInfo, name: string) {
  // 截图统一回到页面顶部；原始行序和尺寸直接记录，不通过后处理移位截图。
  await page.evaluate((old) => {
    let element = document.querySelector(old ? '.page__head' : '.sn-page-head');
    while (element) {
      element.scrollTop = 0;
      element = element.parentElement;
    }
  }, source(info));
  await page.mouse.move(0, 0);
  await page.evaluate(async () => {
    await new Promise<void>((r) => requestAnimationFrame(() => requestAnimationFrame(() => r())));
    await Promise.all(
      document
        .getAnimations()
        .filter((a) => a.effect?.getTiming().iterations !== Infinity)
        .map((a) => a.finished.catch(() => {})),
    );
  });
  await page.screenshot({ path: info.outputPath(name + '.png'), fullPage: true });
  const observed = await page.evaluate((old) => {
    const selectors = {
      head: old ? '.page__head' : '.sn-page-head',
      surface: old ? '.surface' : '.sn-surface',
      toolbar: old ? '.toolbar' : '.sn-toolbar',
      table: old ? '.el-table' : '.ant-table-wrapper',
      foot: old ? '.foot' : '.sn-query-foot',
      header: old ? '.el-table__header-wrapper th' : '.ant-table-thead th',
      row: old ? '.el-table__row' : '.ant-table-row',
      buttons: old
        ? '.page__actions > button, .toolbar > button'
        : '.sn-page-actions > button, .sn-toolbar > .ant-btn',
      mono: old ? '.el-table__row .sn-mono' : '.ant-table-row .sn-mono',
    };
    return Object.fromEntries(
      Object.entries(selectors).map(([key, selector]) => [
        key,
        [...document.querySelectorAll(selector)]
          .filter((el) => el.getBoundingClientRect().height > 0)
          .map((el) => {
            const box = el.getBoundingClientRect();
            return {
              text: el.textContent?.trim(),
              x: box.x,
              y: box.y,
              width: box.width,
              height: box.height,
              style: {
                color: getComputedStyle(el).color,
                font: getComputedStyle(el).font,
                letterSpacing: getComputedStyle(el).letterSpacing,
              },
              cells:
                key === 'row'
                  ? [...el.querySelectorAll('td')].map((cell) => cell.textContent?.trim())
                  : undefined,
            };
          }),
      ]),
    );
  }, source(info));
  writeFileSync(info.outputPath(name + '.json'), JSON.stringify(observed, null, 2));
  expect(observed.header?.map((cell) => cell.width)).toEqual([
    170, 110, 100, 150, 110, 90, 110, 90, 150, 130, 170, 80, 160, 160,
  ]);
}

async function size(page: Page, info: TestInfo, value: number) {
  const pager = page.locator(source(info) ? '.el-pagination' : '.sn-query-foot .ant-pagination');
  await pager.locator(source(info) ? '.el-select' : '.ant-select').click();
  await page.getByRole('option', { name: new RegExp('^' + value + '\\s*条/页$') }).click();
}

const header = [
  '序号',
  '完整SN',
  '进制流水',
  '十进制流水',
  'PI',
  '物料编码',
  '客户编码',
  '工厂',
  '状态',
  '领取批次',
  '来源订单',
];
async function download(
  page: Page,
  info: TestInfo,
  name: string,
  format: 'csv' | 'xlsx',
  expected: string[],
  batchNo = '',
) {
  const ready = page.waitForEvent('download');
  await page
    .getByRole('button', { name: format === 'csv' ? 'CSV' : zh.query.exportXlsx, exact: true })
    .click();
  const result = await ready.catch(async (error: unknown) => {
    await capture(page, info, name + '-failed-download');
    throw error;
  });
  expect(result.suggestedFilename()).toBe('sn_export.' + format);
  const path = info.outputPath(name + '.' + format);
  await result.saveAs(path);
  expect(await result.failure()).toBeNull();
  const parsed = JSON.parse(
    execFileSync(python, ['-X', 'utf8', resolve('../tools/testing/parity-query-file.py'), path], {
      windowsHide: true,
      encoding: 'utf8',
    }),
  ) as {
    rows: string[][];
    raw: unknown[][];
    types: string[][];
    sheet: string | null;
  };
  writeFileSync(
    info.outputPath(name + '-' + format + '.json'),
    JSON.stringify({ filename: result.suggestedFilename(), ...parsed }, null, 2),
  );
  expect(parsed.rows[0]).toEqual(header);
  expect(parsed.rows.slice(1).map((row) => row[1])).toEqual(expected);
  expect(parsed.rows.slice(1).every((row) => row.length === 11 && row[9] === batchNo)).toBe(true);
  if (format === 'xlsx') {
    expect(parsed.sheet).toBe('SN');
    for (const row of parsed.types.slice(1)) {
      expect(row[0]).toBe('n');
      expect(row[3]).toBe('n');
      expect(row[1]).toBe('s');
      expect(row[2]).toBe('s');
    }
  }
}

test('P11 query: current drafts, ordered pagination, business batch and actual CSV/XLSX', async ({
  page,
}, info) => {
  test.setTimeout(360_000);
  page.setDefaultTimeout(20_000);
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  const responses: {
    path: string;
    status: number;
    code?: number;
    state?: string;
    fileState?: string;
  }[] = [];
  page.on('response', async (response) => {
    const path = new URL(response.url()).pathname;
    if (!/^\/api\/(?:v1\/(?:sn\/(?:items|exports)|files)|sn)(?:\/|$)/.test(path)) return;
    const body = response.headers()['content-type']?.includes('application/json')
      ? ((await response.json().catch(() => null)) as {
          code?: number;
          data?: { state?: string; fileState?: string };
        } | null)
      : null;
    responses.push({
      path,
      status: response.status(),
      code: body?.code,
      state: body?.data?.state,
      fileState: body?.data?.fileState,
    });
  });
  expect(JSON.parse(readFileSync(resolve(run, 'extra-orders.json'), 'utf8'))).toEqual([]);
  fixture(info, 'begin');
  await login(page, info);
  try {
    await sync(page, true);
    await paceComparisonLogin(page);
    fixture(info, 'seed');
    await page.getByRole('menuitem', { name: zh.nav.query, exact: true }).click();
    await expect(page.getByRole('heading', { name: zh.query.title, exact: true })).toBeVisible();
    await page.getByPlaceholder(zh.common.customer, { exact: true }).fill(customer);
    await expect(page.getByPlaceholder(zh.common.customer, { exact: true })).toHaveValue(customer);
    await query(page, info);
    await expectRows(page, info, [...numbers('B', 3, 1), ...numbers('A', 51, 35)]);
    await capture(page, info, '01-default-descending-20');
    const next = page.locator(
      source(info) ? '.el-pagination .btn-next' : '.sn-query-foot .ant-pagination-next button',
    );
    await next.click();
    await expectRows(page, info, numbers('A', 34, 15));
    await next.click();
    await expectRows(page, info, numbers('A', 14, 1));
    await capture(page, info, '02-third-page');
    // 原型末页改条数有双请求竞态，另存阶段诊断；稳定主流程先通过查询回第一页。
    await query(page, info);
    await expectRows(page, info, [...numbers('B', 3, 1), ...numbers('A', 51, 35)]);
    await size(page, info, 50);
    await expectRows(page, info, [...numbers('B', 3, 1), ...numbers('A', 51, 5)]);
    await capture(page, info, '02-size-first-page');
    await page.getByPlaceholder('PI', { exact: true }).fill(PIS[0]!);
    await query(page, info);
    await expectRows(page, info, [...numbers('A', 31, 51), ...numbers('A', 1, 29)]);
    await capture(page, info, '03-pi-material-sequence');
    await next.click();
    await expectRows(page, info, ['Q28A00030']);
    await page.getByPlaceholder(zh.common.material, { exact: true }).fill('  ');
    await query(page, info);
    await expectRows(page, info, [...numbers('A', 31, 51), ...numbers('A', 1, 29)]);
    await capture(page, info, '03-whitespace-material-ignored');
    await page.getByPlaceholder(zh.common.material, { exact: true }).fill('CMP-QUERY-MAT-Z');
    await query(page, info);
    await expectRows(page, info, numbers('A', 1, 30));
    await page.getByPlaceholder(zh.common.sn, { exact: true }).fill('Q28A00002');
    await expect(rows(page, info)).toHaveCount(30);
    await download(page, info, '04-draft-single', 'csv', ['Q28A00002']);
    await download(page, info, '04-draft-single', 'xlsx', ['Q28A00002']);
    await expect(rows(page, info)).toHaveCount(30);
    await capture(page, info, '04-draft-export-keeps-query');
    await page.getByPlaceholder(zh.common.sn, { exact: true }).fill('');
    await page.getByPlaceholder(zh.common.material, { exact: true }).fill('');
    await download(page, info, '05-pi-creation-order', 'csv', numbers('A', 1, 51));
    await download(page, info, '05-pi-creation-order', 'xlsx', numbers('A', 1, 51));

    await page.getByRole('menuitem', { name: zh.nav.acquire, exact: true }).click();
    const acquireRow = page
      .getByRole('tabpanel')
      .filter({ visible: true })
      .locator(source(info) ? '.el-table__row' : '.ant-table-row')
      .filter({ hasText: PIS[0]! });
    await acquireRow.getByRole('button', { name: zh.acquire.take, exact: true }).click();
    const modal = page.locator(source(info) ? '.el-message-box' : '.ant-modal-confirm');
    await modal.getByRole('button', { name: /^(确定|确认)$/ }).click();
    await expect(modal).toBeHidden();
    await expect
      .poll(
        () =>
          (JSON.parse(fixture(info, 'observe')) as { sn_acquire_batch: unknown[] }).sn_acquire_batch
            .length,
      )
      .toBe(1);
    await page.getByRole('menuitem', { name: zh.nav.query, exact: true }).click();
    await expect(page.getByRole('heading', { name: zh.query.title, exact: true })).toBeVisible();
    await page.getByPlaceholder('PI', { exact: true }).fill(PIS[0]!);
    await query(page, info);
    await expectRows(page, info, numbers('A', 31, 50));
    const batchNo = (await rows(page, info).first().locator('td').nth(10).innerText()).trim();
    expect(batchNo).toMatch(/^B\d+$/);
    writeFileSync(
      info.outputPath('batch-identity.json'),
      JSON.stringify({ batchNo, pi: PIS[0], quantity: 51 }),
    );
    await page.getByPlaceholder('PI', { exact: true }).fill('');
    await page.getByPlaceholder(zh.acquire.batchNo, { exact: true }).fill(batchNo);
    await query(page, info);
    await expectRows(page, info, numbers('A', 51, 32));
    await capture(page, info, '06-business-batch');
    await download(page, info, '06-batch-all', 'csv', numbers('A', 1, 51), batchNo);
    await download(page, info, '06-batch-all', 'xlsx', numbers('A', 1, 51), batchNo);
    await page.getByPlaceholder(zh.acquire.batchNo, { exact: true }).fill('CMP-NO-SUCH-BATCH');
    await query(page, info);
    await expectRows(page, info, []);
    await download(page, info, '07-empty', 'csv', []);
    await download(page, info, '07-empty', 'xlsx', []);
    await capture(page, info, '07-empty-batch');
    await page.getByPlaceholder(zh.acquire.batchNo, { exact: true }).fill(batchNo);
    await query(page, info);
    for (const [label, code, words] of [
      ['简体中文', 'zh', zh],
      ['English', 'en', en],
      ['Tiếng Việt', 'vi', vi],
    ] as const) {
      const language = page.locator(
        source(info) ? '.header .lang' : '.prototype-language .ant-select',
      );
      if (!(await language.innerText()).includes(label)) {
        await language.click();
        const saved = page.waitForResponse(
          (r) =>
            r.request().method() === (source(info) ? 'PUT' : 'POST') &&
            r.url().endsWith(source(info) ? '/auth/lang' : '/system/auth/language'),
        );
        await page.getByRole('option', { name: label, exact: true }).click();
        expect((await saved).ok()).toBe(true);
      }
      await expect(
        page.getByRole('button', { name: words.query.exportXlsx, exact: true }),
      ).toBeVisible();
      await page.setViewportSize({ width: 1440, height: 960 });
      await capture(page, info, `08-layout-${code}-1440`);
      await page.setViewportSize({ width: 900, height: 960 });
      await capture(page, info, `08-layout-${code}-900`);
    }
    expect(errors).toEqual([]);
  } finally {
    await page.setViewportSize({ width: 1440, height: 960 });
    const language = page.locator(
      source(info) ? '.header .lang' : '.prototype-language .ant-select',
    );
    if (!(await language.innerText()).includes('中文')) {
      await language.click();
      const saved = page.waitForResponse(
        (r) =>
          r.request().method() === (source(info) ? 'PUT' : 'POST') &&
          r.url().endsWith(source(info) ? '/auth/lang' : '/system/auth/language'),
      );
      await page.getByRole('option', { name: '简体中文', exact: true }).click();
      expect((await saved).ok()).toBe(true);
    }
    if (!source(info))
      await expect.poll(() => Number(fixture(info, 'active')), { timeout: 60_000 }).toBe(0);
    await sync(page, false);
    fixture(info, 'clean');
    writeFileSync(
      info.outputPath('baseline-preserved.json'),
      readFileSync(resolve(run, `acquire-clean-${kind(info)}.json`)),
    );
    writeFileSync(
      info.outputPath('query-responses.json'),
      JSON.stringify({ errors, responses }, null, 2),
    );
  }
});
