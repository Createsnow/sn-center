import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { readFileSync, writeFileSync, renameSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';
import { protectTestSecrets } from '../privacy';
import { paceComparisonLogin } from './login-budget';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };

const BILL = 'CMP-UI-GEN-MO';
const PI = 'CMP-UI-GEN-PI';
const extraFile = resolve('../tools/.runtime/comparison-20261008/extra-orders.json');
const prototype = (info: TestInfo) => info.project.name === 'prototype';
const kind = (info: TestInfo) => (prototype(info) ? 'python' : 'java');
type Line = readonly [entry: string, material: string, quantity: string, materialName?: string];
type Item = { sn: string; seq_dec: number; material: string; status: string };
const pageErrors = new WeakMap<Page, string[]>();
test.beforeEach(async ({ page }) => {
  page.setDefaultTimeout(20_000);
  const errors: string[] = [];
  pageErrors.set(page, errors);
  page.on('pageerror', (error) => errors.push(error.message));
});
test.afterEach(async ({ page }) => {
  expect(pageErrors.get(page)).toEqual([]);
});

function fixture(
  info: TestInfo,
  action: 'begin' | 'begin-factory' | 'seed-rule' | 'expire-preview' | 'observe' | 'clean',
) {
  return execFileSync(
    resolve('../../sn-center-python/.venv/Scripts/python.exe'),
    [resolve('../tools/testing/parity-generation-fixture.py'), kind(info), action],
    { windowsHide: true, encoding: 'utf8' },
  );
}
function extra(lines: readonly Line[], changes: Record<string, string> = {}) {
  const values = lines.map(([entry, material, qty, materialName]) => ({
    'F_ora_kh.FNumber': 'CMP-UI-GEN-CUSTOMER',
    FBillNo: BILL,
    F_HX_PO: 'CMP-UI-GEN-PO',
    F_HX_PI: PI,
    'FMaterialId.FNumber': material,
    'FMaterialId.FName': materialName ?? material,
    FQty: qty,
    FStatus: '1',
    FSaleOrderNo: 'CMP-UI-GEN-SO',
    'FPrdOrgId.FName': '比较测试甲厂',
    FID: '9007199254770001',
    FTreeEntity_FEntryId: entry,
    FPrdOrgId: '101',
    'FPrdOrgId.FNumber': 'CMP-A',
    ...changes,
  }));
  writeFileSync(extraFile + '.next', JSON.stringify(values));
  renameSync(extraFile + '.next', extraFile);
}
async function setup(page: Page, info: TestInfo, factoryChange = false) {
  expect(JSON.parse(readFileSync(extraFile, 'utf8'))).toEqual([]);
  fixture(info, factoryChange ? 'begin-factory' : 'begin');
  await page.goto('/login');
  await paceComparisonLogin(page);
  fixture(info, 'seed-rule');
  const password = readFileSync(
    resolve(`../deploy/.secrets/comparison-20261008/${kind(info)}-admin-password`),
    'utf8',
  ).trim();
  protectTestSecrets(password);
  await paceComparisonLogin(page);
  await page.locator('input[autocomplete="username"]').fill('admin');
  await page.locator('input[autocomplete="current-password"]').fill(password);
  await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
  await page.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
}
async function sync(page: Page, lines: readonly Line[], changes: Record<string, string> = {}) {
  extra(lines, changes);
  await page.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
  await page.getByRole('button', { name: zh.orders.syncAll, exact: true }).click();
  await expect(
    page
      .getByText(`已同步 ${lines.length ? 4 : 3} 张单据，${lines.length + 4} 行`, { exact: true })
      .last(),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: zh.orders.syncAll, exact: true })).toBeEnabled();
}
async function select(page: Page, info: TestInfo, quota: number) {
  await page.waitForLoadState('networkidle');
  const table = page.locator(prototype(info) ? '.el-row .el-table' : '.sn-order-table').first();
  await table
    .locator(prototype(info) ? '.el-table__row' : '.ant-table-row')
    .filter({ hasText: BILL })
    .click();
  await page.getByRole('button', { name: zh.orders.toGenerate, exact: true }).click();
  await stat(page, info, zh.orders.quota, quota);
}
async function stat(page: Page, info: TestInfo, label: string, number: number) {
  await expect(
    page
      .locator(prototype(info) ? '.stats > div' : '.sn-work-stats > div')
      .filter({ has: page.getByText(label, { exact: true }) })
      .locator('b'),
  ).toHaveText(String(number));
}
function lineRows(page: Page, info: TestInfo) {
  return page
    .locator(prototype(info) ? '.el-row .el-table' : '.sn-generation-grid .ant-table-wrapper')
    .first()
    .locator(prototype(info) ? '.el-table__row' : '.ant-table-row');
}
async function preview(
  page: Page,
  info: TestInfo,
  quantity: number,
  expected: { material: string; qty: number; start: number; end: number }[],
) {
  const input = page
    .locator(prototype(info) ? '.el-form-item' : '.ant-form-item')
    .filter({ has: page.locator('label', { hasText: new RegExp(`^${zh.generate.qty}$`) }) })
    .locator('input');
  await input.fill(String(quantity));
  await input.blur();
  const response = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' &&
      r.url().endsWith(prototype(info) ? '/generate/preview' : '/sn/generation/previews'),
  );
  await page.getByRole('button', { name: zh.generate.preview, exact: true }).click();
  const raw = await response;
  expect(raw.ok()).toBe(true);
  const body = await raw.json();
  const result = prototype(info) ? body : body.data;
  const segments = result.segments.map((s: Record<string, string | number>) => ({
    material: s.material ?? s.material_code,
    qty: Number(s.quantity ?? s.qty),
    start: Number(s.startSeq ?? s.start_seq),
    end: Number(s.endSeq ?? s.end_seq),
  }));
  expect(segments).toEqual(expected);
  await expect(page.getByRole('button', { name: zh.generate.generate, exact: true })).toBeEnabled();
  for (const segment of expected) {
    const first = `G06${String(segment.start).padStart(5, '0')}`;
    const last = `G06${String(segment.end).padStart(5, '0')}`;
    await expect(page.getByText(new RegExp(`${first}\\s*~\\s*${last}`)).first()).toBeVisible();
  }
  return result;
}
async function generate(page: Page, info: TestInfo, total: number, quota: number, pending = total) {
  await page.getByRole('button', { name: zh.generate.generate, exact: true }).click();
  const confirm = page.locator(prototype(info) ? '.el-message-box' : '.ant-modal-confirm');
  await expect(confirm).toBeVisible();
  await confirm.getByRole('button', { name: /^(确定|确认)$/ }).click();
  await stat(page, info, zh.orders.generated, total);
  await stat(page, info, zh.orders.quota, quota);
  await stat(page, info, zh.generate.pendingAlloc, pending);
  const observed = JSON.parse(fixture(info, 'observe')) as { items: Item[] };
  expect(observed.items).toHaveLength(total);
  for (const [index, item] of observed.items.entries()) {
    expect(item).toMatchObject({
      sn: `G06${String(index + 1).padStart(5, '0')}`,
      seq_dec: index + 1,
      status: index < total - pending ? 'TO_ACQUIRE' : 'PENDING_ALLOC',
    });
  }
  writeFileSync(info.outputPath(`issued-${total}.json`), JSON.stringify(observed, null, 2));
  return observed.items;
}
async function capture(page: Page, info: TestInfo, name: string) {
  await page.screenshot({
    path: info.outputPath(name + '.png'),
    fullPage: true,
    animations: 'disabled',
  });
  await page
    .getByRole('heading', { name: zh.generate.title, exact: true })
    .scrollIntoViewIfNeeded();
  await page.screenshot({ path: info.outputPath(name + '-top.png'), animations: 'disabled' });
}

function jobRows(page: Page, info: TestInfo) {
  return page.locator(
    prototype(info)
      ? '.el-tabs__content .el-tab-pane:visible .el-table__row'
      : '.ant-tabs [role="tabpanel"]:visible .ant-table-row',
  );
}

async function rejectedGeneration(page: Page, info: TestInfo, reason: string) {
  await page.getByRole('button', { name: zh.generate.generate, exact: true }).click();
  const confirm = page.locator(prototype(info) ? '.el-message-box' : '.ant-modal-confirm');
  await expect(confirm).toBeVisible();
  const response = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' &&
      r.url().endsWith(prototype(info) ? '/generate' : '/sn/generation'),
  );
  await confirm.getByRole('button', { name: /^(确定|确认)$/ }).click();
  const rejected = await response;
  expect(rejected.status()).toBe(
    prototype(info) && ['GEN_PREVIEW_EXPIRED', 'GEN_QUOTA_EXCEEDED'].includes(reason) ? 400 : 409,
  );
  const body = await rejected.json();
  expect(prototype(info) ? body.code : body.params.reason).toBe(reason);
  await expect(confirm).toBeHidden();
  return body;
}
async function clean(page: Page, info: TestInfo) {
  // 失败断言可能发生在确认框关闭动画内；完整导航后通过订单按钮恢复，避免清理覆盖原始错误。
  await page.goto('/orders');
  await sync(page, []);
  fixture(info, 'clean');
  writeFileSync(
    info.outputPath('baseline-preserved.json'),
    readFileSync(
      resolve(`../tools/.runtime/comparison-20261008/generation-clean-${kind(info)}.json`),
      'utf8',
    ),
  );
}

test('P07 ERP shrink and replacement preserve whole-bill quota and issued sources', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  await setup(page, info);
  try {
    await sync(page, [
      ['11', 'OLD-A', '8'],
      ['12', 'OLD-B', '3'],
      ['13', '', '2'],
    ]);
    await select(page, info, 13);
    await preview(page, info, 5, [{ material: 'OLD-A', qty: 5, start: 1, end: 5 }]);
    const original = await generate(page, info, 5, 8);
    await sync(page, [
      ['11', 'OLD-A', '2.5'],
      ['14', 'NEW', '7.9'],
    ]);
    await select(page, info, 4);
    await expect(lineRows(page, info)).toHaveCount(2);
    await expect(lineRows(page, info).nth(0).locator('td').last()).toHaveText('2');
    await expect(lineRows(page, info).nth(1).locator('td').last()).toHaveText('7');
    await preview(page, info, 4, [{ material: 'NEW', qty: 4, start: 6, end: 9 }]);
    await capture(page, info, 'shrink-preview');
    expect((await generate(page, info, 9, 0)).slice(0, 5)).toEqual(original);
    await expect(
      page.getByRole('button', { name: zh.generate.preview, exact: true }),
    ).toBeDisabled();
    await sync(page, [['15', 'REPLACED', '12.4']]);
    await select(page, info, 3);
    await preview(page, info, 3, [{ material: 'REPLACED', qty: 3, start: 10, end: 12 }]);
    const final = await generate(page, info, 12, 0);
    expect(final.slice(0, 5)).toEqual(original);
    expect(final.slice(5).map((i) => i.material)).toEqual([
      'NEW',
      'NEW',
      'NEW',
      'NEW',
      'REPLACED',
      'REPLACED',
      'REPLACED',
    ]);
    await capture(page, info, 'replacement-complete');
  } finally {
    await clean(page, info);
  }
});

test('P07 ERP returned order controls segments after the whole-bill offset', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  await setup(page, info);
  try {
    await sync(page, [
      ['11', 'M1', '8'],
      ['12', 'M1', '3'],
      ['13', '', '2'],
    ]);
    await select(page, info, 13);
    await preview(page, info, 5, [{ material: 'M1', qty: 5, start: 1, end: 5 }]);
    const original = await generate(page, info, 5, 8);
    await sync(page, [
      ['13', '', '4.7'],
      ['12', 'M2', '3.8'],
      ['11', 'M1', '8.9'],
    ]);
    await select(page, info, 10);
    await expect(lineRows(page, info).nth(0)).toContainText(zh.orders.noMaterial);
    await preview(page, info, 5, [
      { material: 'M2', qty: 2, start: 6, end: 7 },
      { material: 'M1', qty: 3, start: 8, end: 10 },
    ]);
    await capture(page, info, 'reordered-preview');
    const final = await generate(page, info, 10, 5);
    expect(final.slice(0, 5)).toEqual(original);
    expect(final.slice(5).map((i) => i.material)).toEqual(['M2', 'M2', 'M1', 'M1', 'M1']);
  } finally {
    await clean(page, info);
  }
});

test('P07 all 21 fractional lines remain visible and generate separate integer segments', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  await setup(page, info);
  try {
    await sync(
      page,
      Array.from({ length: 21 }, (_, i) => [String(100 + i), `LINE-${i + 1}`, '1.9'] as const),
    );
    await select(page, info, 21);
    await expect(lineRows(page, info)).toHaveCount(21);
    for (let i = 0; i < 21; i++)
      await expect(lineRows(page, info).nth(i).locator('td').last()).toHaveText('1');
    const expected = Array.from({ length: 21 }, (_, i) => ({
      material: `LINE-${i + 1}`,
      qty: 1,
      start: i + 1,
      end: i + 1,
    }));
    await preview(page, info, 21, expected);
    await capture(page, info, 'all-lines-preview');
    expect((await generate(page, info, 21, 0)).map((i) => i.material)).toEqual(
      expected.map((s) => s.material),
    );
  } finally {
    await clean(page, info);
  }
});

function quantityInput(page: Page, info: TestInfo, label: string) {
  return page
    .locator(prototype(info) ? '.el-form-item' : '.ant-form-item')
    .filter({
      has: page.locator('label', {
        hasText: new RegExp('^' + label.replace(/[.*+?^$()|[\]\\]/g, '\\$&') + '$'),
      }),
    })
    .locator('input');
}
async function allocateQuantity(
  page: Page,
  info: TestInfo,
  qty: number,
  allocated: number,
  pending: number,
  cancel = false,
) {
  await quantityInput(page, info, zh.generate.allocQty).fill(String(qty));
  await quantityInput(page, info, zh.generate.allocQty).blur();
  await page.getByRole('button', { name: zh.generate.allocate, exact: true }).click();
  const confirm = page.locator(prototype(info) ? '.el-message-box' : '.ant-modal-confirm');
  await expect(confirm).toBeVisible();
  await expect(confirm).toContainText('确认把 ' + qty + ' 枚分配给');
  await confirm.getByRole('button', { name: cancel ? /^取消$/ : /^(确定|确认)$/ }).click();
  await expect(confirm).not.toBeVisible();
  await stat(page, info, zh.generate.allocated, allocated);
  await stat(page, info, zh.generate.pendingAlloc, pending);
  if (!cancel)
    await expect(page.getByText(new RegExp('已分配 ' + qty + ' 枚：')).last()).toBeVisible();
  return JSON.parse(fixture(info, 'observe')) as {
    items: Item[];
    allocations: {
      qty: number;
      start_sn: string;
      end_sn: string;
      created_by: string;
      factory_code: string;
    }[];
  };
}

test('P07 quantity controls, cancelled confirmations and partial allocation preserve the exact remainder', async ({
  page,
}, info) => {
  test.setTimeout(200_000);
  await setup(page, info);
  try {
    await sync(page, [
      ['11', 'PART-A', '8'],
      ['12', 'PART-B', '5'],
    ]);
    await select(page, info, 13);
    const limit = quantityInput(page, info, zh.generate.limit);
    const qty = quantityInput(page, info, zh.generate.qty);
    await qty.fill('5');
    await qty.blur();
    await limit.fill('3');
    await limit.blur();
    await expect(qty).toHaveValue('3');
    await limit.fill('10000');
    await limit.blur();
    const start = quantityInput(page, info, zh.generate.start);
    await start.fill('1.5');
    await page.getByRole('button', { name: zh.generate.preview, exact: true }).click();
    await expect(page.getByText(zh.generate.startInvalid, { exact: true }).last()).toBeVisible();
    await start.fill('');
    const p = await preview(page, info, 10, [
      { material: 'PART-A', qty: 8, start: 1, end: 8 },
      { material: 'PART-B', qty: 2, start: 9, end: 10 },
    ]);
    const expiry = Date.parse(p.expiresAt ?? p.expires_at);
    expect(expiry - Date.now()).toBeGreaterThan(29 * 60_000);
    expect(expiry - Date.now()).toBeLessThan(31 * 60_000);
    await page.getByRole('button', { name: zh.generate.generate, exact: true }).click();
    const confirm = page.locator(prototype(info) ? '.el-message-box' : '.ant-modal-confirm');
    await expect(confirm).toContainText('确认生成 10 枚');
    await capture(page, info, 'partial-generation-confirm');
    await confirm.getByRole('button', { name: '取消', exact: true }).click();
    expect(JSON.parse(fixture(info, 'observe')).items).toHaveLength(0);
    await expect(
      page.getByRole('button', { name: zh.generate.generate, exact: true }),
    ).toBeEnabled();
    await generate(page, info, 10, 3);
    await expect(
      page.getByRole('button', { name: zh.generate.generate, exact: true }),
    ).toBeDisabled();
    await expect(quantityInput(page, info, zh.generate.allocQty)).toHaveValue('10');
    await expect(qty).toHaveValue('3');
    let observed = await allocateQuantity(page, info, 3, 0, 10, true);
    expect(observed.allocations).toHaveLength(0);
    expect(observed.items.every((row) => row.status === 'PENDING_ALLOC')).toBe(true);
    observed = await allocateQuantity(page, info, 3, 3, 7);
    expect(observed.items.slice(0, 3).every((row) => row.status === 'TO_ACQUIRE')).toBe(true);
    expect(observed.items.slice(3).every((row) => row.status === 'PENDING_ALLOC')).toBe(true);
    expect(observed.allocations).toEqual([
      {
        qty: 3,
        start_sn: 'G0600001',
        end_sn: 'G0600003',
        created_by: 'admin',
        factory_code: 'CMP-A',
      },
    ]);
    await page.getByRole('tab', { name: zh.generate.allocations, exact: true }).click();
    await expect(page.getByText('G0600001 ~ G0600003', { exact: true }).last()).toBeVisible();
    await capture(page, info, 'partial-three-allocated');
    await page.reload();
    await stat(page, info, zh.generate.pendingAlloc, 7);
    await expect(quantityInput(page, info, zh.generate.allocQty)).toHaveValue('7');
    observed = await allocateQuantity(page, info, 7, 10, 0);
    expect(observed.allocations[0]).toMatchObject({
      qty: 7,
      start_sn: 'G0600004',
      end_sn: 'G0600010',
    });
    await expect(
      page.getByRole('button', { name: zh.generate.allocate, exact: true }),
    ).toBeDisabled();
    // 已分配过但仍有额度时，流程应回到第一步；再生成只建议分配刚生成的 3 枚。
    await preview(page, info, 3, [{ material: 'PART-B', qty: 3, start: 11, end: 13 }]);
    await generate(page, info, 13, 0, 3);
    await expect(quantityInput(page, info, zh.generate.allocQty)).toHaveValue('3');
    observed = await allocateQuantity(page, info, 3, 13, 0);
    expect(observed.items.every((row) => row.status === 'TO_ACQUIRE')).toBe(true);
    expect(observed.allocations.map((row) => row.qty)).toEqual([3, 7, 3]);
    expect(
      observed.allocations.every(
        (row) => row.created_by === 'admin' && row.factory_code === 'CMP-A',
      ),
    ).toBe(true);
    await page.getByRole('tab', { name: zh.generate.allocations, exact: true }).click();
    const history = page.locator(
      prototype(info)
        ? '.el-tabs__content .el-tab-pane:visible .el-table__row'
        : '.ant-tabs [role="tabpanel"]:visible .ant-table-row',
    );
    await expect(history).toHaveCount(3);
    await expect(history.first()).toContainText('G0600011 ~ G0600013');
    await expect(history.first()).toContainText('admin');
    await capture(page, info, 'partial-all-allocated');
    await page.getByRole('tab', { name: zh.generate.jobs, exact: true }).click();
    const jobs = page.locator(
      prototype(info)
        ? '.el-tabs__content .el-tab-pane:visible .el-table__row'
        : '.ant-tabs [role="tabpanel"]:visible .ant-table-row',
    );
    await expect(jobs).toHaveCount(2);
    await expect(jobs.first().locator('td').first()).toHaveText(/^\d+$/);
    await expect(jobs.first()).toContainText('admin');
    writeFileSync(
      info.outputPath('partial-allocation-result.json'),
      JSON.stringify(observed, null, 2),
    );
  } catch (error) {
    writeFileSync(info.outputPath('partial-failure.txt'), String(error));
    await page
      .screenshot({
        path: info.outputPath('partial-failure.png'),
        fullPage: true,
        mask: [page.locator('input[autocomplete="current-password"]')],
        timeout: 5000,
      })
      .catch(() => undefined);
    throw error;
  } finally {
    await clean(page, info);
  }
});

// 第二页通过真实同步按钮更新 ERP 快照；第一页面始终保留原预演，不用 API 重新创建。
async function syncTab(page: Page) {
  const other = await page.context().newPage();
  other.setDefaultTimeout(20_000);
  other.on('pageerror', (error) => pageErrors.get(page)?.push(error.message));
  await other.goto('/orders');
  await expect(other.getByRole('button', { name: zh.orders.syncAll, exact: true })).toBeVisible();
  return other;
}

test('P07 resync keeps unchanged preview and adopts current source metadata', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  await setup(page, info);
  let other: Page | undefined;
  try {
    const rows: Line[] = [
      ['11', 'SAME-A', '8'],
      ['12', 'SAME-B', '5'],
    ];
    await sync(page, rows);
    await select(page, info, 13);
    await preview(page, info, 3, [{ material: 'SAME-A', qty: 3, start: 1, end: 3 }]);
    other = await syncTab(page);
    await sync(other, rows);
    await generate(page, info, 3, 10);
    await preview(page, info, 3, [{ material: 'SAME-A', qty: 3, start: 4, end: 6 }]);
    await sync(other, [['99', 'SAME-A', '21.7']], {
      'F_ora_kh.FNumber': 'CMP-UI-GEN-CUSTOMER-NEW',
      F_HX_PO: 'CMP-UI-GEN-PO-NEW',
      FSaleOrderNo: 'CMP-UI-GEN-DEMAND-NEW',
      FStatus: '2',
    });
    await generate(page, info, 6, 15);
    await expect(lineRows(page, info)).toHaveCount(1);
    await expect(lineRows(page, info).first().locator('td').last()).toHaveText('21');
    const observed = JSON.parse(fixture(info, 'observe'));
    expect(observed.items.slice(0, 3).map((row: { customer: string }) => row.customer)).toEqual(
      Array(3).fill('CMP-UI-GEN-CUSTOMER'),
    );
    expect(observed.items.slice(3).map((row: { customer: string }) => row.customer)).toEqual(
      Array(3).fill('CMP-UI-GEN-CUSTOMER-NEW'),
    );
    writeFileSync(info.outputPath('resync-current-source.json'), JSON.stringify(observed, null, 2));
    await capture(page, info, 'resync-current-source');
  } finally {
    await other?.close();
    await clean(page, info);
  }
});

const invalidPreviewCases: {
  name: string;
  rows: Line[];
  segments: { material: string; qty: number; start: number; end: number }[];
}[] = [
  {
    name: 'material name',
    rows: [
      ['11', 'BEFORE-A', '8', 'CHANGED-NAME'],
      ['12', 'BEFORE-B', '5'],
    ],
    segments: [{ material: 'BEFORE-A', qty: 3, start: 1, end: 3 }],
  },
  {
    name: 'material code',
    rows: [
      ['11', 'CHANGED-CODE', '8'],
      ['12', 'BEFORE-B', '5'],
    ],
    segments: [{ material: 'CHANGED-CODE', qty: 3, start: 1, end: 3 }],
  },
  {
    name: 'segment split',
    rows: [
      ['11', 'BEFORE-A', '2'],
      ['12', 'BEFORE-B', '11'],
    ],
    segments: [
      { material: 'BEFORE-A', qty: 2, start: 1, end: 2 },
      { material: 'BEFORE-B', qty: 1, start: 3, end: 3 },
    ],
  },
  {
    name: 'line order',
    rows: [
      ['12', 'BEFORE-B', '5'],
      ['11', 'BEFORE-A', '8'],
    ],
    segments: [{ material: 'BEFORE-B', qty: 3, start: 1, end: 3 }],
  },
];
for (const scenario of invalidPreviewCases) {
  test(`P07 changed ${scenario.name} rejects retained preview without issuing and recovers`, async ({
    page,
  }, info) => {
    test.setTimeout(180_000);
    await setup(page, info);
    let other: Page | undefined;
    try {
      await sync(page, [
        ['11', 'BEFORE-A', '8'],
        ['12', 'BEFORE-B', '5'],
      ]);
      await select(page, info, 13);
      await preview(page, info, 3, [{ material: 'BEFORE-A', qty: 3, start: 1, end: 3 }]);
      other = await syncTab(page);
      await sync(other, scenario.rows);
      await page.getByRole('button', { name: zh.generate.generate, exact: true }).click();
      const confirm = page.locator(prototype(info) ? '.el-message-box' : '.ant-modal-confirm');
      await expect(confirm).toBeVisible();
      const response = page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          r.url().endsWith(prototype(info) ? '/generate' : '/sn/generation'),
      );
      await confirm.getByRole('button', { name: /^(确定|确认)$/ }).click();
      const rejected = await response;
      expect(rejected.status()).toBe(409);
      const error = await rejected.json();
      expect(error.code).toBe(prototype(info) ? 'GEN_PREVIEW_CHANGED' : 1004000016);
      await expect(page.getByText(/预演条件已/).last()).toBeVisible();
      await expect(
        page.getByRole('button', { name: zh.generate.generate, exact: true }),
      ).toBeDisabled();
      await stat(page, info, zh.orders.generated, 0);
      await expect(quantityInput(page, info, zh.generate.qty)).toHaveValue('13');
      await expect(lineRows(page, info)).toHaveCount(scenario.rows.length);
      for (const [index, row] of scenario.rows.entries()) {
        await expect(lineRows(page, info).nth(index).locator('td').nth(1)).toHaveText(row[1]);
        await expect(lineRows(page, info).nth(index).locator('td').nth(2)).toHaveText(
          row[3] ?? row[1],
        );
        await expect(lineRows(page, info).nth(index).locator('td').last()).toHaveText(row[2]);
      }
      expect(JSON.parse(fixture(info, 'observe')).items).toHaveLength(0);
      await page.getByRole('tab', { name: zh.generate.jobs, exact: true }).click();
      await expect(jobRows(page, info)).toHaveCount(1);
      const failed = jobRows(page, info).first();
      await expect(failed.locator('td').first()).toHaveText(/^\d+$/);
      await expect(failed).toContainText('G0600001 ~ G0600003');
      await expect(failed).toContainText('admin');
      await expect(failed).toContainText('预演条件已');
      await expect(failed.locator('[role="progressbar"]')).toHaveAttribute('aria-valuenow', '0');
      expect(JSON.parse(fixture(info, 'observe')).jobs).toMatchObject([
        { state: 'FAILED', qty: 3 },
      ]);
      await capture(page, info, 'changed-preview-rejected');
      await page.reload();
      await stat(page, info, zh.orders.generated, 0);
      await expect(jobRows(page, info)).toHaveCount(1);
      await expect(jobRows(page, info).first()).toContainText('预演条件已');
      await preview(page, info, 3, scenario.segments);
      await generate(page, info, 3, 10);
      await expect(jobRows(page, info)).toHaveCount(2);
      expect(
        JSON.parse(fixture(info, 'observe'))
          .jobs.map((row: { state: string }) => row.state)
          .sort(),
      ).toEqual(['FAILED', 'SUCCESS']);
      await capture(page, info, 'changed-preview-recovered');
      writeFileSync(
        info.outputPath('preview-rejection.json'),
        JSON.stringify(
          {
            status: rejected.status(),
            code: error.code,
            emptyBeforeRetry: true,
            afterRetry: JSON.parse(fixture(info, 'observe')),
          },
          null,
          2,
        ),
      );
    } finally {
      await other?.close();
      await clean(page, info);
    }
  });
}

test('P07 expired preview is rejected before creating a job and repreview recovers', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  await setup(page, info);
  try {
    await sync(page, [['11', 'EXPIRY', '13']]);
    await select(page, info, 13);
    await preview(page, info, 3, [{ material: 'EXPIRY', qty: 3, start: 1, end: 3 }]);
    expect(JSON.parse(fixture(info, 'expire-preview'))).toEqual({ expiredPreviewCount: 1 });
    await rejectedGeneration(page, info, 'GEN_PREVIEW_EXPIRED');
    await expect(page.getByText(/预演已过期/).last()).toBeVisible();
    await expect(quantityInput(page, info, zh.generate.qty)).toHaveValue('13');
    await expect(jobRows(page, info)).toHaveCount(0);
    expect(JSON.parse(fixture(info, 'observe')).jobs).toEqual([]);
    await capture(page, info, 'expired-preview-no-job');
    await preview(page, info, 3, [{ material: 'EXPIRY', qty: 3, start: 1, end: 3 }]);
    await generate(page, info, 3, 10);
  } finally {
    await clean(page, info);
  }
});

test('P07 generation in another tab makes retained preview stale without adding a failed job', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  await setup(page, info);
  let other: Page | undefined;
  try {
    await sync(page, [['11', 'STALE', '13']]);
    await select(page, info, 13);
    await preview(page, info, 3, [{ material: 'STALE', qty: 3, start: 1, end: 3 }]);
    other = await syncTab(page);
    await select(other, info, 13);
    await preview(other, info, 2, [{ material: 'STALE', qty: 2, start: 1, end: 2 }]);
    await generate(other, info, 2, 11);
    await rejectedGeneration(page, info, 'GEN_PREVIEW_STALE');
    await expect(
      page
        .getByText(`PI ${PI} 的最大号已从 0 变为 2，预演失效，请重新预演`, { exact: true })
        .last(),
    ).toBeVisible();
    await stat(page, info, zh.orders.generated, 2);
    await expect(quantityInput(page, info, zh.generate.qty)).toHaveValue('11');
    await expect(jobRows(page, info)).toHaveCount(1);
    expect(JSON.parse(fixture(info, 'observe')).jobs).toMatchObject([{ state: 'SUCCESS', qty: 2 }]);
    await capture(page, info, 'stale-preview-existing-success');
    await preview(page, info, 3, [{ material: 'STALE', qty: 3, start: 3, end: 5 }]);
    await generate(page, info, 5, 8);
  } finally {
    await other?.close();
    await clean(page, info);
  }
});

test('P07 quota shrink after preview leaves a failed job with planned range and zero progress', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  await setup(page, info);
  let other: Page | undefined;
  try {
    await sync(page, [['11', 'QUOTA', '13']]);
    await select(page, info, 13);
    await preview(page, info, 3, [{ material: 'QUOTA', qty: 3, start: 1, end: 3 }]);
    other = await syncTab(page);
    await sync(other, [['11', 'QUOTA', '2']]);
    await rejectedGeneration(page, info, 'GEN_QUOTA_EXCEEDED');
    await expect(
      page.getByText('生成数量 3 超出可生成额度 2', { exact: true }).last(),
    ).toBeVisible();
    await expect(quantityInput(page, info, zh.generate.qty)).toHaveValue('2');
    await expect(jobRows(page, info)).toHaveCount(1);
    await expect(jobRows(page, info).first()).toContainText('G0600001 ~ G0600003');
    await expect(jobRows(page, info).first().locator('[role="progressbar"]')).toHaveAttribute(
      'aria-valuenow',
      '0',
    );
    expect(JSON.parse(fixture(info, 'observe')).items).toEqual([]);
    await jobRows(page, info).first().scrollIntoViewIfNeeded();
    await capture(page, info, 'quota-shrink-failed-history');
    await page.reload();
    await stat(page, info, zh.orders.quota, 2);
    await expect(jobRows(page, info)).toHaveCount(1);
    await preview(page, info, 2, [{ material: 'QUOTA', qty: 2, start: 1, end: 2 }]);
    await generate(page, info, 2, 0);
    await expect(jobRows(page, info)).toHaveCount(2);
  } finally {
    await other?.close();
    await clean(page, info);
  }
});

test('P07 changed ERP factory rejects old allocation then new generation permits mixed-source members', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  await setup(page, info, true);
  try {
    await sync(page, [['11', 'FACTORY-CHANGE', '13']]);
    await select(page, info, 13);
    await preview(page, info, 2, [{ material: 'FACTORY-CHANGE', qty: 2, start: 1, end: 2 }]);
    await generate(page, info, 2, 11);
    await sync(page, [['11', 'FACTORY-CHANGE', '13']], {
      'F_ora_kh.FNumber': 'CMP-UI-CHANGED-CUSTOMER',
      FPrdOrgId: '102',
      'FPrdOrgId.FNumber': 'CMP-B',
      'FPrdOrgId.FName': '比较测试乙厂',
    });
    await select(page, info, 11);
    const summary = page
      .locator(prototype(info) ? '.surface' : '.sn-surface')
      .filter({ has: page.getByText(`工厂 + PI 汇总：CMP-B · ${PI}`, { exact: true }) });
    const summaryRow = summary.locator(prototype(info) ? '.el-table__row' : '.ant-table-row');
    await expect(summaryRow.locator('td')).toHaveText([BILL, '13', '0', '0', '13']);
    await page.getByRole('button', { name: zh.generate.allocate, exact: true }).click();
    const confirm = page.locator(prototype(info) ? '.el-message-box' : '.ant-modal-confirm');
    await expect(confirm).toContainText('比较测试乙厂');
    const rejected = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        r.url().endsWith(prototype(info) ? '/allocate' : '/sn/allocations'),
    );
    await confirm.getByRole('button', { name: /^(确定|确认)$/ }).click();
    expect((await rejected).status()).toBe(prototype(info) ? 400 : 409);
    await expect(confirm).toBeHidden();
    await expect(
      page.getByText('只能分到该订单的生产组织 CMP-A', { exact: true }).last(),
    ).toBeVisible();
    const before = JSON.parse(fixture(info, 'observe'));
    expect(before.allocations).toEqual([]);
    expect(before.generationIdentity).toEqual([
      { pi: PI, customer: 'CMP-UI-GEN-CUSTOMER', factory_code: 'CMP-A' },
    ]);
    await capture(page, info, 'factory-change-rejected');
    await preview(page, info, 3, [{ material: 'FACTORY-CHANGE', qty: 3, start: 3, end: 5 }]);
    await generate(page, info, 5, 8);
    await expect(summaryRow.locator('td')).toHaveText([BILL, '13', '5', '0', '8']);
    await allocateQuantity(page, info, 3, 3, 2);
    const after = JSON.parse(fixture(info, 'observe'));
    expect(after.generationIdentity).toEqual([
      { pi: PI, customer: 'CMP-UI-GEN-CUSTOMER', factory_code: 'CMP-B' },
    ]);
    expect(after.allocations).toMatchObject([
      { qty: 3, factory_code: 'CMP-B', start_sn: 'G0600001', end_sn: 'G0600003' },
    ]);
    expect(after.identities.slice(0, 3)).toMatchObject([
      { sn: 'G0600001', customer: 'CMP-UI-GEN-CUSTOMER', factory_code: 'CMP-B' },
      { sn: 'G0600002', customer: 'CMP-UI-GEN-CUSTOMER', factory_code: 'CMP-B' },
      { sn: 'G0600003', customer: 'CMP-UI-CHANGED-CUSTOMER', factory_code: 'CMP-B' },
    ]);
    if (!prototype(info))
      expect(
        after.identities.map((item: { source_factory_code: string }) => item.source_factory_code),
      ).toEqual(['CMP-A', 'CMP-A', 'CMP-B', 'CMP-B', 'CMP-B']);
    writeFileSync(
      info.outputPath('factory-change-facts.json'),
      JSON.stringify({ before, after }, null, 2),
    );
    await page.reload();
    await stat(page, info, zh.generate.pendingAlloc, 2);
    await expect(summaryRow.locator('td')).toHaveText([BILL, '13', '5', '3', '8']);
    await summaryRow.scrollIntoViewIfNeeded();
    await capture(page, info, 'factory-change-allocated');
  } finally {
    await clean(page, info);
  }
});
