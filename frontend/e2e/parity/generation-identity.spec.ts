import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { readFileSync, writeFileSync, renameSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';
import { protectTestSecrets } from '../privacy';
import { paceComparisonLogin } from './login-budget';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import en from '../../src/i18n/prototype/en.json' with { type: 'json' };
import vi from '../../src/i18n/prototype/vi.json' with { type: 'json' };

const PIS = ['CMP-UI-ID-PI-A', 'CMP-UI-ID-PI-B'] as const;
const BILLS = ['CMP-UI-ID-MO', 'CMP-UI-ID-SIBLING', 'CMP-UI-ID-UNUSED'] as const;
const extraFile = resolve('../tools/.runtime/comparison-20261008/extra-orders.json');
const prototype = (info: TestInfo) => info.project.name === 'prototype';
const kind = (info: TestInfo) => (prototype(info) ? 'python' : 'java');
type Order = { index: 0 | 1 | 2; pi?: string; qty: string; customer?: string; unmapped?: boolean };
type Facts = {
  items: {
    sn: string;
    pi: string;
    source_pi: string;
    customer: string;
    status: string;
    factory_code: string | null;
  }[];
  allocations: {
    pi: string;
    customer: string;
    start_sn: string;
    end_sn: string;
    qty: number;
    factory_code: string;
  }[];
  ledger: { bill_no: string; pi: string; customer: string; factory_code: string }[];
};
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

function fixture(info: TestInfo, action: 'begin' | 'seed-rules' | 'observe' | 'clean') {
  return execFileSync(
    resolve('../../sn-center-python/.venv/Scripts/python.exe'),
    [resolve('../tools/testing/parity-identity-fixture.py'), kind(info), action],
    { windowsHide: true, encoding: 'utf8' },
  );
}
const facts = (info: TestInfo) => JSON.parse(fixture(info, 'observe')) as Facts;
async function setup(page: Page, info: TestInfo) {
  expect(JSON.parse(readFileSync(extraFile, 'utf8'))).toEqual([]);
  fixture(info, 'begin');
  await page.goto('/login');
  await paceComparisonLogin(page);
  fixture(info, 'seed-rules');
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
async function sync(page: Page, orders: Order[]) {
  const rows = orders.map((o) => ({
    'F_ora_kh.FNumber': o.customer ?? 'CMP-ID-CUSTOMER-A',
    FBillNo: BILLS[o.index],
    F_HX_PO: 'CMP-ID-PO',
    F_HX_PI: o.pi ?? PIS[0],
    'FMaterialId.FNumber': 'CMP-ID-MAT',
    'FMaterialId.FName': 'CMP-ID-MAT',
    FQty: o.qty,
    FStatus: '1',
    FSaleOrderNo: 'CMP-ID-SO',
    'FPrdOrgId.FName': o.unmapped ? '比较测试未映射组织' : '比较测试甲厂',
    FID: String(9007199254771001n + BigInt(o.index)),
    FTreeEntity_FEntryId: '11',
    FPrdOrgId: o.unmapped ? '199' : '101',
    'FPrdOrgId.FNumber': o.unmapped ? 'CMP-ID-UNMAPPED' : 'CMP-A',
  }));
  writeFileSync(extraFile + '.next', JSON.stringify(rows));
  renameSync(extraFile + '.next', extraFile);
  await page.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
  await page.getByRole('button', { name: zh.orders.syncAll, exact: true }).click();
  await expect(
    page
      .getByText(`已同步 ${3 + rows.length} 张单据，${4 + rows.length} 行`, { exact: true })
      .last(),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: zh.orders.syncAll, exact: true })).toBeEnabled();
}
async function stat(page: Page, info: TestInfo, label: string, value: string) {
  await expect(
    page
      .locator(prototype(info) ? '.stats > div' : '.sn-work-stats > div')
      .filter({ has: page.getByText(label, { exact: true }) })
      .locator('b'),
  ).toHaveText(value);
}
async function select(page: Page, info: TestInfo, index: 0 | 1 | 2, quota: string) {
  await page.waitForLoadState('networkidle');
  const table = page.locator(prototype(info) ? '.el-row .el-table' : '.sn-order-table').first();
  await table
    .locator(prototype(info) ? '.el-table__row' : '.ant-table-row')
    .filter({ hasText: BILLS[index] })
    .click();
  await page.getByRole('button', { name: zh.orders.toGenerate, exact: true }).click();
  await stat(page, info, zh.orders.quota, quota);
}
function input(page: Page, info: TestInfo, label: string) {
  return page
    .locator(prototype(info) ? '.el-form-item' : '.ant-form-item')
    .filter({ has: page.locator('label').filter({ hasText: new RegExp('^' + label + '$') }) })
    .locator('input');
}
async function confirm(page: Page, info: TestInfo, button: string) {
  await page.getByRole('button', { name: button, exact: true }).click();
  const dialog = page.locator(prototype(info) ? '.el-message-box' : '.ant-modal-confirm');
  await expect(dialog).toBeVisible();
  await dialog.getByRole('button', { name: /^(确定|确认)$/ }).click();
  await expect(dialog).toBeHidden();
}
async function generate(
  page: Page,
  info: TestInfo,
  qty: string,
  first: string,
  last: string,
  total: string,
  quota: string,
  start?: string,
) {
  await input(page, info, zh.generate.qty).fill(qty);
  await input(page, info, zh.generate.qty).blur();
  if (start) await input(page, info, zh.generate.start).fill(start);
  await page.getByRole('button', { name: zh.generate.preview, exact: true }).click();
  await expect(page.getByText(new RegExp(`${first}\\s*~\\s*${last}`)).first()).toBeVisible();
  await confirm(page, info, zh.generate.generate);
  await stat(page, info, zh.orders.generated, total);
  await stat(page, info, zh.orders.quota, quota);
}
async function allocate(page: Page, info: TestInfo, qty: string, total: string, pending: string) {
  await input(page, info, zh.generate.allocQty).fill(qty);
  await input(page, info, zh.generate.allocQty).blur();
  await confirm(page, info, zh.generate.allocate);
  await stat(page, info, zh.generate.allocated, total);
  await stat(page, info, zh.generate.pendingAlloc, pending);
}
type SummaryRow = { index: 0 | 1 | 2; historical?: boolean; values: string[] };
async function summary(
  page: Page,
  info: TestInfo,
  pi: string,
  rows: SummaryRow[],
  factory = 'CMP-A',
) {
  const panel = page
    .locator(prototype(info) ? '.surface' : '.sn-surface')
    .filter({ has: page.getByText(`工厂 + PI 汇总：${factory} · ${pi}`, { exact: true }) });
  const tableRows = panel.locator(prototype(info) ? '.el-table__row' : '.ant-table-row');
  await expect(tableRows).toHaveCount(rows.length);
  for (const [i, r] of rows.entries()) {
    const cells = tableRows.nth(i).locator('td');
    await expect(cells.nth(0)).toContainText(BILLS[r.index]);
    if (r.historical) await expect(cells.nth(0)).toContainText(zh.generate.leftSnapshot);
    else await expect(cells.nth(0)).not.toContainText(zh.generate.leftSnapshot);
    for (const [j, value] of r.values.entries()) await expect(cells.nth(j + 1)).toHaveText(value);
  }
  await panel.scrollIntoViewIfNeeded();
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
  writeFileSync(info.outputPath(name + '-facts.json'), JSON.stringify(facts(info), null, 2));
}
async function clean(page: Page, info: TestInfo) {
  await page.goto('/orders');
  await sync(page, []);
  fixture(info, 'clean');
  writeFileSync(
    info.outputPath('baseline-preserved.json'),
    readFileSync(
      resolve(`../tools/.runtime/comparison-20261008/identity-clean-${kind(info)}.json`),
    ),
  );
}

async function language(page: Page, info: TestInfo, label: string) {
  await page.locator(prototype(info) ? '.header .lang' : '.prototype-language .ant-select').click();
  const saved = page.waitForResponse(
    (response) =>
      response.request().method() === (prototype(info) ? 'PUT' : 'POST') &&
      response.url().endsWith(prototype(info) ? '/auth/lang' : '/system/auth/language'),
  );
  await page.getByRole('option', { name: label, exact: true }).click();
  expect((await saved).ok()).toBe(true);
}

for (const restarted of [false, true]) {
  test(`P07 ERP changed PI retains first identity with ${restarted ? 'overlapping' : 'separate'} sequence ranges`, async ({
    page,
  }, info) => {
    test.setTimeout(180_000);
    await setup(page, info);
    try {
      await sync(page, [{ index: 0, qty: '13' }]);
      await select(page, info, 0, '13');
      await generate(page, info, '3', 'IDA00001', 'IDA00003', '3', '10');
      const orders: Order[] = [
        { index: 0, qty: '13', pi: PIS[1], customer: 'CMP-ID-CUSTOMER-B' },
        { index: 1, qty: '5' },
      ];
      await sync(page, orders);
      await select(page, info, 0, '10');
      await summary(page, info, PIS[1], [{ index: 0, values: ['13', '0', '0', '13'] }]);
      await capture(page, info, 'changed-pi-before-generation');
      await generate(
        page,
        info,
        '2',
        restarted ? 'IDB00001' : 'IDB00010',
        restarted ? 'IDB00002' : 'IDB00011',
        '5',
        '8',
        restarted ? undefined : '10',
      );
      await allocate(page, info, '3', '3', '2');
      const observed = facts(info);
      expect(observed.ledger).toEqual([
        { bill_no: BILLS[0], pi: PIS[0], customer: 'CMP-ID-CUSTOMER-A', factory_code: 'CMP-A' },
      ]);
      expect(observed.items.filter((i) => i.status === 'TO_ACQUIRE').map((i) => i.sn)).toEqual(
        restarted ? ['IDA00001', 'IDA00002', 'IDB00001'] : ['IDA00001', 'IDA00002', 'IDA00003'],
      );
      expect(observed.allocations).toEqual([
        {
          pi: PIS[0],
          customer: 'CMP-ID-CUSTOMER-A',
          start_sn: 'IDA00001',
          end_sn: restarted ? 'IDA00002' : 'IDA00003',
          qty: 3,
          factory_code: 'CMP-A',
        },
      ]);
      for (const item of observed.items) {
        const old = item.sn.startsWith('IDA');
        expect(item.pi).toBe(old ? PIS[0] : PIS[1]);
        expect(item.source_pi).toBe(item.pi);
        expect(item.customer).toBe(old ? 'CMP-ID-CUSTOMER-A' : 'CMP-ID-CUSTOMER-B');
      }
      await page.getByRole('tab', { name: zh.generate.allocations, exact: true }).click();
      await expect(
        page
          .getByText(new RegExp(`IDA00001\\s*~\\s*${restarted ? 'IDA00002' : 'IDA00003'}`))
          .last(),
      ).toBeVisible();
      await capture(page, info, 'changed-pi-allocation-history');
      if (restarted) {
        await allocate(page, info, '2', '5', '0');
        const remaining = facts(info);
        expect(remaining.items.every((item) => item.status === 'TO_ACQUIRE')).toBe(true);
        expect(remaining.allocations).toHaveLength(2);
        expect(remaining.allocations[1]).toEqual({
          pi: PIS[0],
          customer: 'CMP-ID-CUSTOMER-A',
          start_sn: 'IDB00002',
          end_sn: 'IDA00003',
          qty: 2,
          factory_code: 'CMP-A',
        });
        await expect(page.getByText(/IDB00002\s*~\s*IDA00003/).last()).toBeVisible();
        await capture(page, info, 'overlapping-remainder-history');
      }
      await page.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
      await select(page, info, 1, '5');
      await summary(page, info, PIS[0], [
        { index: 1, values: ['5', '0', '0', '5'] },
        { index: 0, historical: true, values: ['0', '5', restarted ? '5' : '3', '0'] },
      ]);
      await page.reload();
      await summary(page, info, PIS[0], [
        { index: 1, values: ['5', '0', '0', '5'] },
        { index: 0, historical: true, values: ['0', '5', restarted ? '5' : '3', '0'] },
      ]);
      await capture(page, info, 'old-pi-historical-summary');
    } finally {
      await clean(page, info);
    }
  });
}

test('P07 departed generated bill remains historical while unused departed bill disappears and return restores totals', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  await setup(page, info);
  try {
    await sync(page, [
      { index: 0, qty: '13' },
      { index: 1, qty: '5' },
      { index: 2, qty: '7' },
    ]);
    await select(page, info, 0, '13');
    await generate(page, info, '3', 'IDA00001', 'IDA00003', '3', '10');
    await allocate(page, info, '1', '1', '2');
    await sync(page, [{ index: 1, qty: '5' }]);
    await select(page, info, 1, '5');
    await summary(page, info, PIS[0], [
      { index: 1, values: ['5', '0', '0', '5'] },
      { index: 0, historical: true, values: ['0', '3', '1', '0'] },
    ]);
    await page.reload();
    await summary(page, info, PIS[0], [
      { index: 1, values: ['5', '0', '0', '5'] },
      { index: 0, historical: true, values: ['0', '3', '1', '0'] },
    ]);
    await capture(page, info, 'departed-bill-summary');
    await sync(page, [
      { index: 0, qty: '13' },
      { index: 1, qty: '5' },
    ]);
    await select(page, info, 0, '10');
    await summary(page, info, PIS[0], [
      { index: 0, values: ['13', '3', '1', '10'] },
      { index: 1, values: ['5', '0', '0', '5'] },
    ]);
    await stat(page, info, zh.generate.pendingAlloc, '2');
    await capture(page, info, 'returned-bill-summary');
  } finally {
    await clean(page, info);
  }
});

test('P07 unmapped organization still shows its current PI summary without foreign organization rows', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  await setup(page, info);
  try {
    await sync(page, [
      { index: 0, qty: '13', unmapped: true },
      { index: 1, qty: '5', unmapped: true },
      { index: 2, qty: '7' },
    ]);
    await select(page, info, 0, '13');
    await summary(
      page,
      info,
      PIS[0],
      [
        { index: 0, values: ['13', '0', '0', '13'] },
        { index: 1, values: ['5', '0', '0', '5'] },
      ],
      '比较测试未映射组织',
    );
    await expect(
      page.getByRole('button', { name: zh.generate.preview, exact: true }),
    ).toBeDisabled();
    expect(facts(info).items).toEqual([]);
    await expect(
      page.getByText(zh.generate.issue.FACTORY_UNMAPPED.replace('{{org}}', '比较测试未映射组织'), {
        exact: true,
      }),
    ).toBeVisible();
    await capture(page, info, 'unmapped-organization-summary');
    for (const [label, words, key] of [
      ['English', en, 'en'],
      ['Tiếng Việt', vi, 'vi'],
      ['简体中文', zh, 'zh'],
    ] as const) {
      await language(page, info, label);
      await expect(
        page.getByText(
          words.generate.issue.FACTORY_UNMAPPED.replace('{{org}}', '比较测试未映射组织'),
          { exact: true },
        ),
      ).toBeVisible();
      await expect(
        page.getByRole('button', { name: words.generate.preview, exact: true }),
      ).toBeDisabled();
      await page.screenshot({
        path: info.outputPath(`unmapped-issue-${key}.png`),
        fullPage: true,
        animations: 'disabled',
      });
    }
  } finally {
    if (!(await page.getByRole('heading', { name: zh.generate.title, exact: true }).isVisible()))
      await language(page, info, '简体中文');
    await clean(page, info);
  }
});
