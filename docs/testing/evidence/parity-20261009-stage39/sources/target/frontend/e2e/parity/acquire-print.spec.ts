import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { readFileSync, writeFileSync, renameSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';
import { protectTestSecrets } from '../privacy';
import { paceComparisonLogin } from './login-budget';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import uiZh from '../../src/i18n/locales/zh-CN.json' with { type: 'json' };
import en from '../../src/i18n/prototype/en.json' with { type: 'json' };
import vi from '../../src/i18n/prototype/vi.json' with { type: 'json' };

const PIS = ['CMP-UI-ACQ-PI-A', 'CMP-UI-ACQ-PI-B'];
const BILLS = ['CMP-UI-ACQ-MO-A', 'CMP-UI-ACQ-MO-B'];
const extra = resolve('../tools/.runtime/comparison-20261008/extra-orders.json');
const source = (info: TestInfo) => info.project.name === 'prototype';
const kind = (info: TestInfo) => (source(info) ? 'python' : 'java');
const rowSelector = (info: TestInfo) => (source(info) ? '.el-table__row' : '.ant-table-row');
const panel = (page: Page) => page.getByRole('tabpanel').filter({ visible: true });
const row = (page: Page, info: TestInfo, pi: string) =>
  panel(page).locator(rowSelector(info)).filter({ hasText: pi });
const dialog = (page: Page, info: TestInfo) =>
  page.locator(source(info) ? '.el-message-box' : '.ant-modal-confirm');
const historyCase = (info: TestInfo) => info.title.startsWith('P09 paged history');
const transferCase = (info: TestInfo) => info.title.startsWith('P09 transferred print');
function fixture(
  info: TestInfo,
  action: 'begin' | 'seed' | 'history' | 'observe' | 'active' | 'clean',
) {
  return execFileSync(
    resolve('../../sn-center-python/.venv/Scripts/python.exe'),
    [
      '-X',
      'utf8',
      resolve('../tools/testing/parity-acquire-fixture.py'),
      kind(info),
      action,
      '--profile',
      historyCase(info) ? 'history' : transferCase(info) ? 'transfer' : 'small',
    ],
    { windowsHide: true, encoding: 'utf8' },
  );
}
type Facts = {
  items: { sn: string; pi: string; status: string; material: string }[];
  sn_acquire_batch: { pi: string; qty: number }[];
  sn_print: { pi: string; qty: number }[];
  current?: { sn: string; factory: string; status: string; hasBatch: number; hasPrint: number }[];
  transfers?: { qty: number; status: string; targetSource: string }[];
};
const facts = (info: TestInfo): Facts => JSON.parse(fixture(info, 'observe')) as Facts;
async function capture(page: Page, info: TestInfo, name: string) {
  if (name.includes('detail-'))
    await expect(page.locator('.el-message--success, .ant-message-success')).toHaveCount(0);
  if (name.includes('confirm')) await expect(dialog(page, info)).toBeVisible();
  await page.evaluate(async () => {
    await new Promise<void>((resolve) =>
      requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
    );
    await Promise.all(
      document
        .getAnimations()
        .filter((a) => a.effect?.getTiming().iterations !== Infinity)
        .map((a) => a.finished.catch(() => {})),
    );
  });
  if (name.includes('confirm')) {
    const motion = source(info) ? page.locator('.el-overlay-message-box') : dialog(page, info);
    await expect(motion).toHaveCSS('opacity', '1');
    await expect(motion).toHaveCSS('transform', 'none');
    await expect(motion).toHaveCSS('animation-name', 'none');
  }
  await page.screenshot({ path: info.outputPath(name + '.png'), fullPage: true });
  writeFileSync(info.outputPath(name + '.json'), fixture(info, 'observe'));
}
async function sync(page: Page, include: boolean, history = false) {
  const entries = include
    ? PIS.flatMap((pi, index) =>
        [0, 1].map((line) => ({
          'F_ora_kh.FNumber': 'CMP-ACQ-CUSTOMER',
          FBillNo: BILLS[index],
          F_HX_PO: 'CMP-ACQ-PO',
          F_HX_PI: pi,
          'FMaterialId.FNumber': 'CMP-ACQ-MAT-' + line,
          'FMaterialId.FName': 'CMP-ACQ-MAT-' + line,
          FQty: line === 0 ? (history && index === 0 ? '60' : '2') : '1',
          FStatus: '1',
          FSaleOrderNo: 'CMP-ACQ-SO',
          'FPrdOrgId.FName': '比较测试甲厂',
          FID: String(9007199254791001n + BigInt(index)),
          FTreeEntity_FEntryId: String(11 + line),
          FPrdOrgId: '101',
          'FPrdOrgId.FNumber': 'CMP-A',
        })),
      )
    : [];
  writeFileSync(extra + '.next', JSON.stringify(entries));
  renameSync(extra + '.next', extra);
  await page.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
  await page.getByRole('button', { name: zh.orders.syncAll, exact: true }).click();
  await expect(
    page
      .getByText(`已同步 ${include ? 5 : 3} 张单据，${include ? 8 : 4} 行`, { exact: true })
      .last(),
  ).toBeVisible();
}
async function setup(page: Page, info: TestInfo) {
  expect(JSON.parse(readFileSync(extra, 'utf8'))).toEqual([]);
  fixture(info, 'begin');
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
  await sync(page, true, historyCase(info));
  await paceComparisonLogin(page);
  fixture(info, 'seed');
  await page.getByRole('menuitem', { name: zh.nav.acquire, exact: true }).click();
  await expect(row(page, info, PIS[0]!)).toBeVisible();
  expect(facts(info).items).toHaveLength(historyCase(info) ? 54 : 6);
}
async function clean(page: Page, info: TestInfo) {
  if (!source(info))
    await expect
      .poll(() => Number(fixture(info, 'active')), { timeout: 60_000, intervals: [1000] })
      .toBe(0);
  await page.goto('/orders');
  await sync(page, false);
  fixture(info, 'clean');
  writeFileSync(
    info.outputPath('baseline-preserved.json'),
    readFileSync(resolve(`../tools/.runtime/comparison-20261008/acquire-clean-${kind(info)}.json`)),
  );
}
async function confirm(page: Page, info: TestInfo) {
  await expect(dialog(page, info)).toBeVisible();
  await dialog(page, info)
    .getByRole('button', { name: /^(确定|确认)$/ })
    .click();
  await expect(dialog(page, info)).toBeHidden();
}
async function language(page: Page, info: TestInfo, label: string) {
  const select = page.locator(source(info) ? '.header .lang' : '.prototype-language .ant-select');
  await expect(select).toBeVisible();
  if ((await select.innerText()).includes(label)) return;
  await select.click();
  const saved = page.waitForResponse(
    (response) =>
      response.request().method() === (source(info) ? 'PUT' : 'POST') &&
      response.url().endsWith(source(info) ? '/auth/lang' : '/system/auth/language'),
  );
  await page.getByRole('option', { name: label, exact: true }).click();
  expect((await saved).ok()).toBe(true);
}
const errors = new WeakMap<Page, string[]>();
const operationResponses = new WeakMap<Page, unknown[]>();
test.beforeEach(async ({ page }) => {
  page.setDefaultTimeout(20_000);
  const values: string[] = [];
  errors.set(page, values);
  page.on('pageerror', (e) => values.push(e.message));
  const responses: unknown[] = [];
  operationResponses.set(page, responses);
  page.on('response', async (response) => {
    const path = new URL(response.url()).pathname;
    if (!/^\/api\/v1\/sn\/(prints|acquisitions)(\/|$)/.test(path)) return;
    const body = response.headers()['content-type']?.includes('application/json')
      ? ((await response.json().catch(() => null)) as {
          code?: number;
          data?: Record<string, unknown>;
        } | null)
      : null;
    const data = body?.data;
    responses.push({
      path,
      method: response.request().method(),
      status: response.status(),
      code: body?.code,
      task:
        data &&
        Object.fromEntries(
          ['taskId', 'operationId', 'state', 'operationState', 'fileState', 'errorCode']
            .filter((key) => key in data)
            .map((key) => [key, data[key]]),
        ),
    });
  });
});
test.afterEach(async ({ page }, info) => {
  writeFileSync(
    info.outputPath('operation-responses.json'),
    JSON.stringify(operationResponses.get(page), null, 2),
  );
  expect(errors.get(page)).toEqual([]);
});

test('P09 single take and print: cancel, focus, material filtering, automatic XLSX and stable reload', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  await setup(page, info);
  try {
    await capture(page, info, '01-steps');
    await expect(page.getByRole('tab', { name: /^待领取/ })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    await expect(page.getByRole('switch')).toHaveCount(0);
    await row(page, info, PIS[0]!)
      .getByRole('button', { name: zh.acquire.take, exact: true })
      .click();
    await expect(dialog(page, info)).toContainText(
      `领取 比较测试甲厂 · PI ${PIS[0]} 下全部 3 枚待领取的号？`,
    );
    await capture(page, info, '02-take-confirm');
    await dialog(page, info).getByRole('button', { name: zh.common.cancel, exact: true }).click();
    expect(facts(info).sn_acquire_batch).toEqual([]);
    await row(page, info, PIS[0]!)
      .getByRole('button', { name: zh.acquire.segments, exact: true })
      .click();
    await expect(
      panel(page).getByText(`比较测试甲厂 · PI ${PIS[0]}`, { exact: true }),
    ).toBeVisible();
    await expect(panel(page).locator(rowSelector(info))).toHaveCount(2);
    await panel(page).getByPlaceholder(zh.common.material).fill('CMP-ACQ-MAT-1');
    await panel(page).getByPlaceholder(zh.common.material).press('Enter');
    await expect(panel(page).locator(rowSelector(info))).toHaveCount(1);
    await capture(page, info, '03-focused-material');
    await page.getByRole('tab', { name: zh.acquire.batches, exact: true }).click();
    await expect(
      panel(page).getByText(`比较测试甲厂 · PI ${PIS[0]}`, { exact: true }),
    ).toBeVisible();
    await panel(page).getByRole('button', { name: zh.acquire.showAll, exact: true }).click();
    await expect(
      panel(page).getByRole('button', { name: zh.acquire.showAll, exact: true }),
    ).toHaveCount(0);
    await page.getByRole('tab', { name: /^待领取/ }).click();
    await row(page, info, PIS[0]!)
      .getByRole('button', { name: zh.acquire.take, exact: true })
      .click();
    await confirm(page, info);
    await expect.poll(() => facts(info).sn_acquire_batch.map((v) => v.qty)).toEqual([3]);
    await expect(row(page, info, PIS[0]!)).toHaveCount(0);
    await page.getByRole('tab', { name: zh.acquire.batches, exact: true }).click();
    const batchRow = row(page, info, PIS[0]!);
    await expect(batchRow).toContainText(/B[0-9]{18}/);
    await expect(batchRow).toContainText('admin');
    const batchNo = (await batchRow.innerText()).match(/B[0-9]{18}/)![0];
    await capture(page, info, '04-batch-number-and-operator');
    await page.getByRole('tab', { name: /^待打印/ }).click();
    await expect(row(page, info, PIS[0]!)).toContainText('3');
    await capture(page, info, '04-taken');
    await row(page, info, PIS[0]!)
      .getByRole('button', { name: zh.acquire.print, exact: true })
      .click();
    await capture(page, info, '05-print-confirm');
    const downloaded = page.waitForEvent('download');
    await confirm(page, info);
    const file = await downloaded;
    expect(file.suggestedFilename()).toMatch(/^print_P[0-9]{18}\.xlsx$/);
    await file.saveAs(info.outputPath('single.xlsx'));
    await expect(row(page, info, PIS[0]!)).toHaveCount(0);
    expect(
      facts(info)
        .items.filter((v) => v.pi === PIS[0])
        .map((v) => v.status),
    ).toEqual(['PRINTED', 'PRINTED', 'PRINTED']);
    await page.reload();
    await page.getByRole('tab', { name: zh.acquire.prints, exact: true }).click();
    await expect(row(page, info, PIS[0]!)).toHaveCount(1);
    const printRow = row(page, info, PIS[0]!);
    await expect(printRow).toContainText(/P[0-9]{18}/);
    await expect(printRow).toContainText('admin');
    const printNo = (await printRow.innerText()).match(/P[0-9]{18}/)![0];
    expect(file.suggestedFilename()).toBe(`print_${printNo}.xlsx`);
    const csvDownloaded = page.waitForEvent('download');
    await printRow
      .getByRole('button', { name: source(info) ? 'csv' : 'CSV 下载', exact: true })
      .click();
    const csv = await csvDownloaded;
    expect(csv.suggestedFilename()).toBe(`print_${printNo}.csv`);
    await csv.saveAs(info.outputPath('single.csv'));
    const reDownloaded = page.waitForEvent('download');
    await printRow
      .getByRole('button', { name: source(info) ? 'xlsx' : 'XLSX 下载', exact: true })
      .click();
    const again = await reDownloaded;
    expect(again.suggestedFilename()).toBe(`print_${printNo}.xlsx`);
    await again.saveAs(info.outputPath('single-again.xlsx'));
    writeFileSync(
      info.outputPath('file-identities.json'),
      JSON.stringify(
        {
          batchNo,
          printNo,
          xlsx: file.suggestedFilename(),
          csv: csv.suggestedFilename(),
          actor: 'admin',
        },
        null,
        2,
      ),
    );
    expect(facts(info).sn_print).toHaveLength(1);
    await capture(page, info, '06-print-history');
  } finally {
    await clean(page, info);
  }
});

test('P09 paged history: current detail 50+1 rows, print status, eleven histories and filter reset', async ({
  page,
}, info) => {
  test.setTimeout(360_000);
  await setup(page, info);
  const drawer = page.locator(source(info) ? '.el-drawer' : '.sn-acquire-detail [role="dialog"]');
  const detailRows = () => drawer.locator(rowSelector(info));
  const next = (scope: ReturnType<Page['locator']>) =>
    scope.locator(source(info) ? '.btn-next' : '.ant-pagination-next button');
  const previous = (scope: ReturnType<Page['locator']>) =>
    scope.locator(source(info) ? '.btn-prev' : '.ant-pagination-prev button');
  const close = async () => {
    await drawer.locator(source(info) ? '.el-drawer__close-btn' : '.ant-drawer-close').click();
    await expect(drawer).toBeHidden();
  };
  const saveRows = async (name: string) => {
    await capture(page, info, name);
    const geometry = await drawer.evaluate((element) => {
      const inspect = (selector: string) => {
        const node = element.querySelector(selector)!;
        const style = getComputedStyle(node);
        const box = node.getBoundingClientRect();
        return {
          top: box.top,
          width: box.width,
          height: box.height,
          font: style.font,
          fontSize: style.fontSize,
          lineHeight: style.lineHeight,
          padding: style.padding,
          color: style.color,
        };
      };
      return {
        drawer: element.getBoundingClientRect().toJSON(),
        header: inspect('.el-drawer__header, .ant-drawer-header'),
        title: inspect('.el-drawer__title, .ant-drawer-title'),
        tableHeader: inspect('thead'),
        cell: inspect('.el-table__row td, .ant-table-row td'),
        row: inspect('.el-table__row, .ant-table-row'),
      };
    });
    expect(geometry.drawer.x).toBe(680);
    expect(geometry.drawer.width).toBe(760);
    expect(geometry.cell.fontSize).toBe('12px');
    expect(geometry.row.height).toBe(34);
    writeFileSync(info.outputPath(name + '-geometry.json'), JSON.stringify(geometry, null, 2));
    writeFileSync(
      info.outputPath(name + '-rows.json'),
      JSON.stringify(
        await detailRows().evaluateAll((rows) =>
          rows.map((row) =>
            Array.from(row.querySelectorAll('td')).map((cell) => cell.textContent?.trim() ?? ''),
          ),
        ),
        null,
        2,
      ),
    );
  };
  try {
    await page.getByPlaceholder('PI', { exact: true }).fill(PIS[0]!);
    await page.getByPlaceholder('PI', { exact: true }).press('Enter');
    await row(page, info, PIS[0]!)
      .getByRole('button', { name: zh.acquire.take, exact: true })
      .click();
    await confirm(page, info);
    await expect.poll(() => facts(info).sn_acquire_batch.map((v) => v.qty)).toEqual([51]);
    await page.getByRole('tab', { name: zh.acquire.batches, exact: true }).click();
    await row(page, info, PIS[0]!)
      .getByRole('button', { name: zh.common.detail, exact: true })
      .click();
    await expect(drawer).toBeVisible();
    await expect(detailRows()).toHaveCount(50);
    await expect(drawer.getByRole('columnheader')).toHaveText([
      zh.common.sn,
      zh.common.seqText,
      zh.common.seqDec,
      zh.common.material,
      zh.common.status,
      'PI',
    ]);
    await expect(drawer).toContainText('共 51 条');
    await expect(detailRows().first()).toContainText('A17A00001');
    await expect(detailRows().last()).toContainText('A17A00050');
    await saveRows('19-detail-first-page');
    await next(drawer).click();
    await expect(detailRows()).toHaveCount(1);
    await expect(detailRows().first()).toContainText('A17A00051');
    await expect(next(drawer)).toBeDisabled();
    await saveRows('20-detail-last-page');
    await previous(drawer).click();
    await expect(detailRows()).toHaveCount(50);
    await close();
    await row(page, info, PIS[0]!)
      .getByRole('button', { name: zh.common.detail, exact: true })
      .click();
    await expect(detailRows()).toHaveCount(50);
    await expect(previous(drawer)).toBeDisabled();
    await close();
    await page.getByRole('tab', { name: /^待打印/ }).click();
    await row(page, info, PIS[0]!)
      .getByRole('button', { name: zh.acquire.print, exact: true })
      .click();
    const download = page.waitForEvent('download');
    await confirm(page, info);
    await (await download).saveAs(info.outputPath('history-first.xlsx'));
    await page.getByRole('tab', { name: zh.acquire.batches, exact: true }).click();
    await row(page, info, PIS[0]!)
      .getByRole('button', { name: zh.common.detail, exact: true })
      .click();
    await expect(detailRows()).toHaveCount(50);
    await expect(detailRows().first()).toContainText(zh.status.PRINTED);
    await saveRows('21-detail-current-printed');
    await close();
    await paceComparisonLogin(page);
    fixture(info, 'history');
    expect(facts(info).sn_acquire_batch).toHaveLength(11);
    expect(facts(info).sn_print).toHaveLength(11);
    await page.reload();
    await page.getByPlaceholder('PI', { exact: true }).fill(PIS[0]!);
    await page.getByPlaceholder('PI', { exact: true }).press('Enter');
    for (const label of [zh.acquire.batches, zh.acquire.prints]) {
      await page.getByRole('tab', { name: label, exact: true }).click();
      await expect(panel(page).locator(rowSelector(info))).toHaveCount(10);
      await expect(panel(page)).toContainText('共 11 条');
      await next(panel(page)).click();
      await expect(panel(page).locator(rowSelector(info))).toHaveCount(1);
      await expect(row(page, info, PIS[0]!)).toContainText('51');
      await expect(next(panel(page))).toBeDisabled();
      await capture(
        page,
        info,
        label === zh.acquire.batches ? '22-batches-page-two' : '23-prints-page-two',
      );
      await page.getByPlaceholder('PI', { exact: true }).fill('CMP-UI-ACQ-NONE');
      await page.getByPlaceholder('PI', { exact: true }).press('Enter');
      await expect(panel(page).locator(rowSelector(info))).toHaveCount(0);
      await expect(panel(page)).toContainText('共 0 条');
      await expect(previous(panel(page))).toBeDisabled();
      await page.getByPlaceholder('PI', { exact: true }).fill(PIS[0]!);
      await page.getByPlaceholder('PI', { exact: true }).press('Enter');
      await expect(panel(page).locator(rowSelector(info))).toHaveCount(10);
      await expect(previous(panel(page))).toBeDisabled();
    }
  } finally {
    if (await drawer.isVisible()) await close();
    await clean(page, info);
  }
});

test('P09 bulk print continues when one selected PI was printed in another browser page', async ({
  page,
  context,
}, info) => {
  test.setTimeout(180_000);
  await setup(page, info);
  try {
    for (const pi of PIS)
      await row(page, info, pi)
        .locator(source(info) ? '.el-checkbox' : '.ant-checkbox-wrapper')
        .click();
    await page.getByRole('button', { name: zh.acquire.bulkTake, exact: true }).click();
    await confirm(page, info);
    await expect.poll(() => facts(info).sn_acquire_batch.length).toBe(2);
    await expect(row(page, info, PIS[1]!)).toHaveCount(0);
    await page.getByRole('tab', { name: /^待打印/ }).click();
    for (const pi of PIS)
      await row(page, info, pi)
        .locator(source(info) ? '.el-checkbox' : '.ant-checkbox-wrapper')
        .click();
    await page.getByRole('button', { name: zh.acquire.bulkPrint, exact: true }).click();
    const other = await context.newPage();
    other.setDefaultTimeout(20_000);
    try {
      await other.goto('/acquire');
      await other.getByRole('tab', { name: /^待打印/ }).click();
      await row(other, info, PIS[0]!)
        .getByRole('button', { name: zh.acquire.print, exact: true })
        .click();
      const downloaded = other.waitForEvent('download');
      await confirm(other, info);
      await (await downloaded).saveAs(info.outputPath('competing.xlsx'));
      await expect(row(other, info, PIS[0]!)).toHaveCount(0);
    } finally {
      await other.close();
    }
    await page.bringToFront();
    const downloaded = page.waitForEvent('download');
    await confirm(page, info);
    await (await downloaded).saveAs(info.outputPath('remaining.xlsx'));
    await expect(page.getByText('已完成 1 张 PI，共 3 枚', { exact: true }).last()).toBeVisible();
    await expect(page.getByText(new RegExp(`1 张 PI 未完成：${PIS[0]}`)).last()).toBeVisible();
    await capture(page, info, 'partial-failure-continues');
    expect(facts(info).sn_print.map((value) => value.qty)).toEqual([3, 3]);
    expect(facts(info).items.every((value) => value.status === 'PRINTED')).toBe(true);
  } finally {
    await clean(page, info);
  }
});

test('P09 factory cross-PI read-only and query-role controls preserve state', async ({
  page,
  browser,
}, info) => {
  test.setTimeout(180_000);
  await setup(page, info);
  try {
    const before = facts(info);
    const accounts = JSON.parse(
      readFileSync(resolve('../deploy/.secrets/comparison-20261008/role-accounts.json'), 'utf8'),
    ) as Record<string, Record<string, { current: string; changed: boolean }>>;
    for (const user of ['cmp_operator', 'cmp_query_all', 'cmp_query_factory']) {
      const account = accounts[kind(info)]![user]!;
      expect(account.changed).toBe(true);
      protectTestSecrets(account.current);
      const session = await browser.newContext({
        baseURL: info.project.use.baseURL,
        viewport: { width: 1440, height: 960 },
      });
      const rolePage = await session.newPage();
      rolePage.setDefaultTimeout(20_000);
      try {
        await rolePage.goto('/login');
        await paceComparisonLogin(rolePage);
        await rolePage.locator('input[autocomplete="username"]').fill(user);
        await rolePage.locator('input[autocomplete="current-password"]').fill(account.current);
        await rolePage.getByRole('button', { name: zh.login.submit, exact: true }).click();
        await rolePage.getByRole('menuitem', { name: zh.nav.acquire, exact: true }).click();
        await expect(row(rolePage, info, PIS[0]!)).toBeVisible();
        if (user === 'cmp_operator') {
          await expect(rolePage.getByRole('switch')).toBeDisabled();
          await rolePage.getByPlaceholder('PI', { exact: true }).fill(PIS[0]!);
          await (
            source(info) ? rolePage.locator('.el-switch__core') : rolePage.getByRole('switch')
          ).click();
          await expect(rolePage.getByText(zh.acquire.piAllReadOnly, { exact: true })).toBeVisible();
          await expect(rolePage.getByRole('tab', { name: /^待领取|^待打印/ })).toHaveCount(0);
          await expect(
            rolePage.getByRole('tab', { name: zh.acquire.segments, exact: true }),
          ).toHaveAttribute('aria-selected', 'true');
          await expect(panel(rolePage).locator(rowSelector(info))).toHaveCount(2);
        } else {
          await expect(rolePage.getByText(zh.acquire.queryReadOnly, { exact: true })).toBeVisible();
          await expect(
            panel(rolePage).getByRole('button', { name: zh.acquire.take, exact: true }),
          ).toHaveCount(0);
          await expect(panel(rolePage).getByRole('checkbox')).toHaveCount(0);
          await expect(rolePage.getByRole('switch')).toHaveCount(0);
        }
        await capture(rolePage, info, user);
      } finally {
        await session.close();
      }
    }
    expect(facts(info)).toEqual(before);
  } finally {
    await clean(page, info);
  }
});

test('P09 transferred print: real factory round trip, current batch details and historical files', async ({
  page,
}, info) => {
  test.setTimeout(360_000);
  await setup(page, info);
  const pi = PIS[0]!;
  const names = { 'CMP-A': '比较测试甲厂', 'CMP-B': '比较测试乙厂' };
  type Factory = keyof typeof names;
  type Identity = { batchNo: string; printNo: string; quantity: number; factory: Factory };
  const identities: Record<string, Identity> = {};
  const fileCases: { name: string; identity: string; sns: string[] }[] = [];
  const sns = ['A17A00001', 'A17A00002', 'A17A00003'];
  const drawer = page.locator(source(info) ? '.el-drawer' : '.sn-acquire-detail [role="dialog"]');
  const byNo = (no: string) => panel(page).locator(rowSelector(info)).filter({ hasText: no });
  const closeDrawer = async () => {
    await drawer.locator(source(info) ? '.el-drawer__close-btn' : '.ant-drawer-close').click();
    await expect(drawer).toBeHidden();
  };
  const acquirePage = async () => {
    if (new URL(page.url()).pathname !== '/acquire') {
      await page.getByRole('menuitem', { name: zh.nav.acquire, exact: true }).click();
      await expect(
        page.getByRole('heading', { name: zh.acquire.title, exact: true }),
      ).toBeVisible();
    }
    await page.getByPlaceholder('PI', { exact: true }).fill(pi);
    await page.getByPlaceholder('PI', { exact: true }).press('Enter');
    await expect(page.getByPlaceholder('PI', { exact: true })).toHaveValue(pi);
  };
  const takePrint = async (factory: Factory, quantity: number, key: string) => {
    await acquirePage();
    await page.getByRole('tab', { name: /^待领取/ }).click();
    const summary = row(page, info, pi).filter({ hasText: names[factory] });
    await summary.getByRole('button', { name: zh.acquire.take, exact: true }).click();
    await confirm(page, info);
    await expect(summary).toHaveCount(0);
    await page.getByRole('tab', { name: zh.acquire.batches, exact: true }).click();
    const known = Object.values(identities).map((v) => v.batchNo);
    await expect(row(page, info, pi)).toHaveCount(known.length + 1);
    const batches = await row(page, info, pi).allTextContents();
    const batchNo = batches
      .flatMap((text) => text.match(/B[0-9]{18}/g) ?? [])
      .find((v) => !known.includes(v));
    expect(batchNo).toBeTruthy();
    await page.getByRole('tab', { name: /^待打印/ }).click();
    await summary.getByRole('button', { name: zh.acquire.print, exact: true }).click();
    const download = page.waitForEvent('download');
    await confirm(page, info);
    const file = await download;
    const printNo = file.suggestedFilename().match(/^print_(P[0-9]{18})\.xlsx$/)?.[1];
    expect(printNo).toBeTruthy();
    await file.saveAs(info.outputPath(`${key}-automatic.xlsx`));
    await expect(summary).toHaveCount(0);
    identities[key] = { batchNo: batchNo!, printNo: printNo!, quantity, factory };
    fileCases.push({
      name: `${key}-automatic.xlsx`,
      identity: key,
      sns: key === 'at-b' ? sns.slice(0, 1) : sns,
    });
  };
  const detail = async (key: string, expected: string[], checkpoint: string) => {
    await acquirePage();
    await page.getByRole('tab', { name: zh.acquire.batches, exact: true }).click();
    const original = byNo(identities[key]!.batchNo);
    await expect(original.locator('td').nth(3)).toHaveText(String(identities[key]!.quantity));
    await original.getByRole('button', { name: zh.common.detail, exact: true }).click();
    await expect(drawer).toBeVisible();
    const currentRows = drawer.locator(rowSelector(info));
    await expect(currentRows).toHaveCount(expected.length);
    await expect(drawer).toContainText(`共 ${expected.length} 条`);
    expect(await currentRows.locator('td:first-child').allTextContents()).toEqual(expected);
    await capture(page, info, checkpoint + '-detail-current');
    if (!expected.length) {
      const empty = drawer.locator(
        source(info) ? '.el-table__empty-block' : '.ant-table-placeholder td',
      );
      const box = await empty.boundingBox();
      expect(box?.height).toBe(60);
      await expect(empty).toHaveText(zh.common.empty);
      expect(await empty.locator('svg').count()).toBe(0);
      writeFileSync(
        info.outputPath(checkpoint + '-empty-geometry.json'),
        JSON.stringify(box, null, 2),
      );
    }
    writeFileSync(
      info.outputPath(checkpoint + '-detail-rows.json'),
      JSON.stringify(
        await currentRows.evaluateAll((rows) =>
          rows.map((r) =>
            Array.from(r.querySelectorAll('td')).map((c) => c.textContent?.trim() ?? ''),
          ),
        ),
        null,
        2,
      ),
    );
    await closeDrawer();
  };
  const historical = async (key: string, expected: string[], checkpoint: string) => {
    await page.getByRole('tab', { name: zh.acquire.prints, exact: true }).click();
    const identity = identities[key]!;
    const original = byNo(identity.printNo);
    await expect(original.locator('td').nth(3)).toHaveText(String(identity.quantity));
    const before = facts(info);
    for (const format of ['csv', 'xlsx'] as const) {
      const download = page.waitForEvent('download');
      await original
        .getByRole('button', {
          name: source(info) ? format : `${format.toUpperCase()} 下载`,
          exact: true,
        })
        .click();
      const file = await download;
      expect(file.suggestedFilename()).toBe(`print_${identity.printNo}.${format}`);
      const name = `${checkpoint}-${key}.${format}`;
      await file.saveAs(info.outputPath(name));
      fileCases.push({ name, identity: key, sns: expected });
      if (format === 'csv') {
        const lines = readFileSync(info.outputPath(name), 'utf8')
          .replace(/^\uFEFF/, '')
          .trimEnd()
          .split('\r\n');
        expect(lines[0]).toBe(
          '序号,完整SN,进制流水,十进制流水,PI,物料编码,客户编码,工厂,状态,领取批次,来源订单',
        );
        expect(lines.slice(1).map((line) => line.split(',')[1])).toEqual(expected);
      }
    }
    expect(facts(info)).toEqual(before);
    await capture(page, info, checkpoint + '-history-current');
  };
  const transfer = async (from: Factory, to: Factory, scope: 'PI' | 'SINGLE', quantity: number) => {
    const count = facts(info).transfers!.length;
    let interruptedRead = 0;
    const resultPath = /\/api\/v1\/sn\/transfers\/[0-9a-f-]{36}$/;
    if (!source(info) && count === 0) {
      await page.route(resultPath, async (route) => {
        if (route.request().method() === 'GET' && interruptedRead === 0) {
          interruptedRead++;
          await route.fulfill({
            status: 503,
            contentType: 'text/plain',
            body: 'Comparison result temporarily unavailable',
          });
        } else await route.continue();
      });
    }
    await page.getByRole('menuitem', { name: zh.nav.transfer, exact: true }).click();
    await page.getByRole('tab', { name: zh.transfer.directTab, exact: true }).click();
    if (source(info)) {
      const field = (label: string) =>
        panel(page)
          .locator('.el-form-item')
          .filter({ has: page.locator('.el-form-item__label').getByText(label, { exact: true }) });
      const chooseFactory = async (label: string, factory: Factory) => {
        const select = field(label).getByRole('combobox');
        await select.click();
        const controls = await select.getAttribute('aria-controls');
        expect(controls).toBeTruthy();
        await page
          .locator(`[id="${controls}"]`)
          .getByRole('option', {
            name: `${names[factory]}（${factory}）`,
            exact: true,
          })
          .click();
      };
      await chooseFactory(zh.transfer.fromFactory, from);
      await field('PI').getByRole('textbox').fill(pi);
      await page
        .locator('.el-radio-button__inner')
        .getByText(zh.scope[scope], { exact: true })
        .click();
      await expect(page.getByRole('radio', { name: zh.scope[scope], exact: true })).toBeChecked();
      if (scope === 'SINGLE') await field(zh.common.sn).getByRole('textbox').fill(sns[0]!);
      await chooseFactory(zh.transfer.toFactory, to);
      await field(zh.transfer.toPi).getByRole('textbox').fill(pi);
      await field(zh.transfer.reason).getByRole('textbox').fill('CMP_UI_TRANSFER_PRINT');
    } else {
      await panel(page)
        .getByRole('combobox', { name: uiZh.snPages.common.factory, exact: true })
        .click();
      await page.getByRole('option', { name: `${names[from]}（${from}）`, exact: true }).click();
      await page
        .getByRole('textbox', { name: uiZh.snTransfer.source + ' PI', exact: true })
        .fill(pi);
      await page
        .locator('.ant-radio-button-wrapper')
        .filter({ has: page.getByRole('radio', { name: zh.scope[scope], exact: true }) })
        .click();
      await expect(page.getByRole('radio', { name: zh.scope[scope], exact: true })).toBeChecked();
      if (scope === 'SINGLE')
        await page.getByRole('textbox', { name: 'SN', exact: true }).fill(sns[0]!);
      const targetFactory = page.getByRole('combobox', {
        name: uiZh.snTransfer.toFactory,
        exact: true,
      });
      await targetFactory.click();
      const targetList = await targetFactory.getAttribute('aria-controls');
      await page
        .locator('.ant-select-dropdown')
        .filter({ has: page.locator(`[id="${targetList}"]`) })
        .locator('.ant-select-item-option-content')
        .getByText(`${names[to]}（${to}）`, { exact: true })
        .click();
      await page.getByRole('textbox', { name: uiZh.snTransfer.toPi, exact: true }).fill(pi);
      await page
        .getByRole('textbox', { name: uiZh.snTransfer.reason, exact: true })
        .fill('CMP_UI_TRANSFER_PRINT');
    }
    // 整批转回原厂时从真实订单快照回填，确认提交携带 SNAPSHOT 并检查落库结果。
    if (count === 2) {
      await page.getByRole('button', { name: zh.transfer.fromSnapshot, exact: true }).click();
      const picker = page.getByRole('dialog', { name: zh.transfer.fromSnapshot, exact: true });
      await picker.getByPlaceholder(zh.transfer.targetSearch).fill(pi);
      await picker.getByPlaceholder(zh.transfer.targetSearch).press('Enter');
      const option = picker
        .locator(
          source(info)
            ? '.el-table__body-wrapper tbody tr'
            : '.ant-table-body tbody tr[data-row-key]',
        )
        .filter({ has: page.locator('td').getByText('CMP-ACQ-MAT-0', { exact: true }) });
      await expect(option).toHaveCount(1);
      await option.click();
      await expect(picker).not.toBeVisible();
      await expect(panel(page).getByText(zh.transfer.srcSNAPSHOT, { exact: true })).toBeVisible();
      await capture(page, info, 'transfer-snapshot-before-submit');
    }
    await expect(panel(page)).toContainText(`可转移 ${quantity} 枚（范围内共 ${quantity} 枚）`);
    const submitted = page.waitForRequest(
      (request) => request.method() === 'POST' && request.url().endsWith('/transfers/direct'),
    );
    await page.getByRole('button', { name: zh.transfer.directSubmit, exact: true }).click();
    const modal = page.locator(source(info) ? '.el-message-box:visible' : '.ant-modal:visible');
    await expect(modal).toBeVisible();
    await modal.getByRole('button', { name: /^(确定|确认)$/ }).click();
    await expect(modal).toBeHidden();
    const command = (await submitted).postDataJSON() as Record<string, unknown>;
    expect(command[source(info) ? 'target_source' : 'targetSource']).toBe(
      count === 2 ? 'SNAPSHOT' : 'MANUAL',
    );
    await expect.poll(() => facts(info).transfers!.length).toBe(count + 1);
    expect(facts(info).transfers!.at(-1)).toMatchObject({
      targetSource: count === 2 ? 'SNAPSHOT' : 'MANUAL',
    });
    expect(facts(info).transfers!.at(-1)).toMatchObject({ qty: quantity, status: 'APPROVED' });
    if (!source(info) && count === 0) {
      await expect(
        page.getByRole('button', { name: uiZh.export.check, exact: true }),
      ).toBeVisible();
      expect(interruptedRead).toBe(1);
      await capture(page, info, 'transfer-result-read-interrupted');
      await page.unroute(resultPath);
      await page.reload();
    }
    await expect(page.getByRole('tab', { name: zh.transfer.records, exact: true })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    const newest = panel(page).locator(rowSelector(info)).first();
    await expect(newest).toContainText(/T[0-9]{18}/);
    await expect(newest).toContainText('admin');
    await expect(newest).toContainText(names[from]);
    await expect(newest).toContainText(names[to]);
    expect(facts(info).transfers).toHaveLength(count + 1);
    await capture(page, info, `transfer-${count + 1}-completed`);
  };
  try {
    await takePrint('CMP-A', 3, 'original');
    await detail('original', sns, '01-original');
    await historical('original', sns, '01-original');
    await transfer('CMP-A', 'CMP-B', 'SINGLE', 1);
    expect(facts(info).current![0]).toMatchObject({
      factory: 'CMP-B',
      status: 'TO_ACQUIRE',
      hasBatch: 0,
      hasPrint: 0,
    });
    await detail('original', sns.slice(1), '02-partial');
    await historical('original', sns.slice(1), '02-partial');
    await takePrint('CMP-B', 1, 'at-b');
    await detail('original', sns.slice(1), '03-reacquired');
    await historical('original', sns.slice(1), '03-reacquired');
    await historical('at-b', sns.slice(0, 1), '03-at-b');
    await transfer('CMP-A', 'CMP-B', 'PI', 2);
    await detail('original', [], '04-all-out');
    await historical('original', [], '04-all-out');
    await transfer('CMP-B', 'CMP-A', 'PI', 3);
    await takePrint('CMP-A', 3, 'returned');
    await detail('original', [], '05-returned-old');
    await historical('original', [], '05-returned-old');
    await detail('at-b', [], '06-returned-b');
    await historical('at-b', [], '06-returned-b');
    await detail('returned', sns, '07-returned-new');
    await historical('returned', sns, '07-returned-new');
    expect(
      facts(info).current!.every(
        (v) =>
          v.factory === 'CMP-A' && v.status === 'PRINTED' && v.hasBatch === 1 && v.hasPrint === 1,
      ),
    ).toBe(true);
    expect(
      facts(info)
        .sn_print.map((v) => v.qty)
        .sort(),
    ).toEqual([1, 3, 3]);
    writeFileSync(
      info.outputPath('transfer-file-cases.json'),
      JSON.stringify({ identities, fileCases }, null, 2),
    );
  } finally {
    if (await drawer.isVisible()) await closeDrawer();
    await clean(page, info);
  }
});

test('P09 two-PI bulk take and print: independent requests, per-PI files and focus history', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  await setup(page, info);
  try {
    for (const pi of PIS)
      await row(page, info, pi)
        .locator(source(info) ? '.el-checkbox' : '.ant-checkbox-wrapper')
        .click();
    await expect(page.getByText('已选 2 张 PI，共 6 枚', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: zh.acquire.bulkTake, exact: true }).click();
    await capture(page, info, '01-bulk-take-confirm');
    await confirm(page, info);
    await expect.poll(() => facts(info).sn_acquire_batch.map((v) => v.qty)).toEqual([3, 3]);
    await expect(row(page, info, PIS[1]!)).toHaveCount(0);
    await page.getByRole('tab', { name: /^待打印/ }).click();
    for (const pi of PIS)
      await row(page, info, pi)
        .locator(source(info) ? '.el-checkbox' : '.ant-checkbox-wrapper')
        .click();
    await capture(page, info, '02-bulk-selected');
    const downloads: Promise<void>[] = [];
    page.on('download', (file) => {
      downloads.push(file.saveAs(info.outputPath(`bulk-${downloads.length + 1}.xlsx`)));
    });
    await page.getByRole('button', { name: zh.acquire.bulkPrint, exact: true }).click();
    await confirm(page, info);
    await expect.poll(() => downloads.length).toBe(2);
    await Promise.all(downloads);
    await expect.poll(() => facts(info).items.every((v) => v.status === 'PRINTED')).toBe(true);
    await expect(row(page, info, PIS[1]!)).toHaveCount(0);
    await page.getByRole('tab', { name: zh.acquire.batches, exact: true }).click();
    await expect(row(page, info, PIS[0]!)).toHaveCount(1);
    await capture(page, info, '03-bulk-batches');
    await page.getByRole('tab', { name: zh.acquire.prints, exact: true }).click();
    await expect(row(page, info, PIS[1]!)).toHaveCount(1);
    await capture(page, info, '04-bulk-prints');
    expect(facts(info).sn_print.map((v) => v.qty)).toEqual([3, 3]);
  } finally {
    await clean(page, info);
  }
});

for (const [locale, label, words, yes, no] of [
  ['zh-CN', '简体中文', zh, '确定', '取消'],
  ['en', 'English', en, 'OK', 'Cancel'],
  ['vi', 'Tiếng Việt', vi, 'OK', 'Hủy'],
] as const) {
  test(`P09 download failure ${locale}: bulk succeeds, history retries without reprint and narrow detail`, async ({
    page,
  }, info) => {
    test.setTimeout(240_000);
    await setup(page, info);
    const drawer = page.locator(source(info) ? '.el-drawer' : '.sn-acquire-detail [role="dialog"]');
    const denied = words.common.downloadFailed.replace('{{status}}', '503');
    const done = words.acquire.bulkDone.replace('{{n}}', '2').replace('{{q}}', '6');
    const errorToast = page.locator(
      source(info) ? '.el-message--error' : '.sn-prototype-error-message',
    );
    const filePath = source(info)
      ? /^\/api\/acquire\/prints\/[^/]+\/file$/
      : /^\/api\/v1\/sn\/prints\/[^/]+\/file$/;
    const printPath = source(info) ? '/api/acquire/print' : '/api/v1/sn/prints';
    const acceptedPrints: number[] = [];
    page.on('response', (response) => {
      if (
        new URL(response.url()).pathname === printPath &&
        response.request().method() === 'POST' &&
        response.ok()
      )
        acceptedPrints.push(response.status());
    });
    let failures = 1;
    let intercepted = 0;
    await page.route(
      (url) => filePath.test(url.pathname),
      async (route) => {
        if (route.request().method() === 'GET' && failures > 0) {
          failures -= 1;
          intercepted += 1;
          await route.fulfill({
            status: 503,
            contentType: 'text/html',
            body: '<html>comparison download failure</html>',
          });
        } else await route.continue();
      },
    );
    const confirmLocalized = async () => {
      await expect(dialog(page, info).getByRole('button', { name: no, exact: true })).toBeVisible();
      await dialog(page, info).getByRole('button', { name: yes, exact: true }).click();
      await expect(dialog(page, info)).toBeHidden();
    };
    const captureDownloadError = async (name: string) => {
      await expect(errorToast).toBeVisible();
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
      await expect
        .poll(() =>
          errorToast.evaluate((element) => {
            let opacity = 1;
            for (let node: Element | null = element; node; node = node.parentElement)
              opacity *= Number(getComputedStyle(node).opacity);
            return opacity;
          }),
        )
        .toBe(1);
      await expect(errorToast).toHaveText(denied);
      await page.screenshot({ path: info.outputPath(name + '.png'), fullPage: true });
    };
    try {
      await language(page, info, label);
      for (const pi of PIS)
        await row(page, info, pi)
          .locator(source(info) ? '.el-checkbox' : '.ant-checkbox-wrapper')
          .click();
      await page.getByRole('button', { name: words.acquire.bulkTake, exact: true }).click();
      await capture(page, info, '01-localized-take-confirm');
      await dialog(page, info).getByRole('button', { name: no, exact: true }).click();
      expect(facts(info).sn_acquire_batch).toEqual([]);
      await page.getByRole('button', { name: words.acquire.bulkTake, exact: true }).click();
      await confirmLocalized();
      await expect.poll(() => facts(info).sn_acquire_batch.map((v) => v.qty)).toEqual([3, 3]);
      await expect(row(page, info, PIS[1]!)).toHaveCount(0);
      await expect(page.getByText(done, { exact: true })).toBeHidden();
      await page.getByRole('tab', { name: new RegExp('^' + words.status.TO_PRINT) }).click();
      for (const pi of PIS)
        await row(page, info, pi)
          .locator(source(info) ? '.el-checkbox' : '.ant-checkbox-wrapper')
          .click();
      await page.getByRole('button', { name: words.acquire.bulkPrint, exact: true }).click();
      await capture(page, info, '02-localized-print-confirm');
      const download = page.waitForEvent('download');
      await confirmLocalized();
      await expect(errorToast).toHaveText(denied);
      await expect(errorToast.locator('button, .el-message__closeBtn')).toHaveCount(0);
      await captureDownloadError('03-automatic-download-error');
      const file = await download;
      await file.saveAs(info.outputPath('automatic-second.xlsx'));
      await expect(page.getByText(done, { exact: true })).toBeVisible();
      await expect(row(page, info, PIS[1]!)).toHaveCount(0);
      expect(facts(info).items.every((v) => v.status === 'PRINTED')).toBe(true);
      expect(facts(info).sn_print.map((v) => v.qty)).toEqual([3, 3]);
      await expect(errorToast).toHaveCount(0, { timeout: 5000 });
      await page.getByRole('tab', { name: words.acquire.prints, exact: true }).click();
      await expect(row(page, info, PIS[0]!)).toHaveCount(1);
      await capture(page, info, '04-history-after-download-error');
      const before = facts(info);
      failures = 1;
      const csvButton = row(page, info, PIS[0]!).getByRole('button', { name: /^csv/i });
      await csvButton.click();
      await expect(errorToast).toHaveText(denied);
      const appeared = Date.now();
      await expect(errorToast.locator('button, .el-message__closeBtn')).toHaveCount(0);
      await expect(panel(page).locator('.ant-alert, .el-alert')).toHaveCount(0);
      await captureDownloadError('05-history-download-error');
      await expect(errorToast).toHaveCount(0, { timeout: 5000 });
      const elapsed = Date.now() - appeared;
      expect(elapsed).toBeGreaterThan(2000);
      expect(elapsed).toBeLessThan(4500);
      for (const format of ['csv', 'xlsx'] as const) {
        const retry = page.waitForEvent('download');
        await row(page, info, PIS[0]!)
          .getByRole('button', { name: new RegExp('^' + format, 'i') })
          .click();
        await (await retry).saveAs(info.outputPath('retried-first.' + format));
      }
      expect(facts(info)).toEqual(before);
      expect(acceptedPrints).toHaveLength(2);
      expect(intercepted).toBe(2);
      await page.setViewportSize({ width: 900, height: 900 });
      await page.getByRole('tab', { name: words.acquire.batches, exact: true }).click();
      await row(page, info, PIS[0]!)
        .getByRole('button', { name: words.common.detail, exact: true })
        .click();
      await expect(drawer).toBeVisible();
      await expect(drawer.locator(rowSelector(info))).toHaveCount(3);
      const headers = await drawer.locator('thead th').allTextContents();
      expect(headers.map((v) => v.trim())).toEqual([
        words.common.sn,
        words.common.seqText,
        words.common.seqDec,
        words.common.material,
        words.common.status,
        'PI',
      ]);
      await capture(page, info, '06-narrow-detail');
      const appearance = await page.evaluate(() => {
        const inspect = (selector: string) => {
          const element = document.querySelector(selector)!;
          const style = getComputedStyle(element);
          const box = element.getBoundingClientRect();
          return {
            color: style.color,
            background: style.backgroundColor,
            width: box.width,
            height: box.height,
          };
        };
        return {
          tag: inspect('.el-drawer .el-tag, .sn-acquire-detail .ant-tag'),
          close: inspect('.el-drawer__close-btn, .sn-acquire-detail .ant-drawer-close'),
          mask: inspect('.el-overlay:has(.el-drawer), .sn-acquire-detail .ant-drawer-mask'),
        };
      });
      expect(appearance.tag.color).toBe('rgb(63, 125, 90)');
      expect(appearance.tag.background).toBe('rgb(240, 249, 235)');
      expect(appearance.close).toMatchObject({ color: 'rgb(48, 49, 51)', width: 32, height: 22 });
      expect(appearance.mask.background).toBe('rgba(0, 0, 0, 0.5)');
      writeFileSync(
        info.outputPath('download-recovery.json'),
        JSON.stringify(
          {
            locale,
            injectedFailures: intercepted,
            successfulPrintCommands: acceptedPrints.length,
            downloadError: denied,
            confirm: yes,
            cancel: no,
            noticeElapsedMs: elapsed,
            noCloseButton: true,
            noPersistentAlert: true,
            retryLeavesBusinessUnchanged: true,
            detailHeaders: headers.map((v) => v.trim()),
            detailViewport: { width: 900, height: 900 },
            drawer: await drawer.boundingBox(),
            appearance,
          },
          null,
          2,
        ),
      );
    } finally {
      if (await drawer.isVisible())
        await drawer.locator(source(info) ? '.el-drawer__close-btn' : '.ant-drawer-close').click();
      await page.setViewportSize({ width: 1440, height: 960 });
      if (await dialog(page, info).isVisible())
        await dialog(page, info)
          .locator(source(info) ? '.el-message-box__headerbtn' : '.ant-modal-close')
          .click();
      await language(page, info, '简体中文');
      await clean(page, info);
    }
  });
}
