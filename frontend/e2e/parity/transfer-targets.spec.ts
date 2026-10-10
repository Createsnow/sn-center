import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync, renameSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { protectTestSecrets } from '../privacy';
import { paceComparisonLogin } from './login-budget';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };

const source = (info: TestInfo) => info.project.name === 'prototype';
const kind = (info: TestInfo) => (source(info) ? 'python' : 'java');
const run = resolve('../tools/.runtime/comparison-20261008');
const pi = 'CMP-UI-PICK-PI';
const fixture = (info: TestInfo, action: string) =>
  execFileSync(
    resolve('../../sn-center-python/.venv/Scripts/python.exe'),
    ['-X', 'utf8', resolve('../tools/testing/parity-target-fixture.py'), kind(info), action],
    { windowsHide: true, encoding: 'utf8' },
  );
const field = (page: Page, info: TestInfo, label: string) =>
  page
    .getByRole('tabpanel')
    .filter({ visible: true })
    .locator(source(info) ? '.el-form-item' : '.ant-form-item')
    .filter({
      has: page
        .locator(source(info) ? '.el-form-item__label' : '.ant-form-item-label')
        .getByText(label, { exact: true }),
    });

function extraOrders(include: boolean) {
  const row = (
    index: number,
    bill: string,
    entry: number,
    material: string,
    name: string,
    customer = 'PICK-CUSTOMER',
    targetPi = pi,
    mapped = true,
  ) => ({
    'F_ora_kh.FNumber': customer,
    FBillNo: bill,
    F_HX_PO: 'PICK-PO',
    F_HX_PI: targetPi,
    'FMaterialId.FNumber': material,
    'FMaterialId.FName': name,
    FQty: '1',
    FStatus: '1',
    FSaleOrderNo: 'PICK-SO',
    'FPrdOrgId.FName': mapped ? '比较测试乙厂' : '比较未映射组织',
    FID: String(9007199254800000n + BigInt(index)),
    FTreeEntity_FEntryId: String(entry),
    FPrdOrgId: mapped ? '102' : '999',
    'FPrdOrgId.FNumber': mapped ? 'CMP-B' : 'PICK-UNKNOWN',
  });
  const entries = include
    ? [
        ...Array.from({ length: 55 }, (_, i) =>
          row(
            1,
            'CMP-UI-PICK-LOOK%_UP',
            i + 1,
            'PICK-M' + String(i).padStart(3, '0'),
            'A 物料' + String(i).padStart(3, '0'),
          ),
        ),
        row(2, 'CMP-UI-PICK-DUP', 1, 'PICK-M000', 'Z 物料000'),
        row(
          3,
          'CMP-UI-PICK-UNMAPPED',
          1,
          'PICK-U',
          '未映射物料',
          'PICK-CUSTOMER',
          'CMP-UI-PICK-UNMAPPED',
          false,
        ),
        row(4, 'CMP-UI-PICK-NO-CUSTOMER', 1, 'PICK-NO-C', '空客户', '', 'CMP-UI-PICK-EMPTY'),
        row(5, 'CMP-UI-PICK-NO-PI', 1, 'PICK-NO-PI', '空 PI', 'PICK-CUSTOMER', ''),
      ]
    : [];
  writeFileSync(resolve(run, 'extra-orders.json.next'), JSON.stringify(entries));
  renameSync(resolve(run, 'extra-orders.json.next'), resolve(run, 'extra-orders.json'));
}
async function sync(page: Page, include: boolean) {
  extraOrders(include);
  await page.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
  await page.getByRole('button', { name: zh.orders.syncAll, exact: true }).click();
  await expect(
    page
      .getByText(`已同步 ${include ? 8 : 3} 张单据，${include ? 63 : 4} 行`, { exact: true })
      .last(),
  ).toBeVisible();
}
async function login(page: Page, info: TestInfo) {
  const password = readFileSync(
    resolve('../deploy/.secrets/comparison-20261008', kind(info) + '-admin-password'),
    'utf8',
  ).trim();
  protectTestSecrets(password);
  await page.goto('/login');
  await paceComparisonLogin(page);
  await page.locator('input[autocomplete="username"]').fill('admin');
  await page.locator('input[autocomplete="current-password"]').fill(password);
  await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
  await expect(page.getByRole('menuitem', { name: zh.nav.transfer, exact: true })).toBeVisible();
}
async function capture(page: Page, info: TestInfo, name: string) {
  await page.evaluate(async () => {
    await new Promise<void>((r) => requestAnimationFrame(() => requestAnimationFrame(() => r())));
    await Promise.all(
      document
        .getAnimations()
        .filter((a) => a.effect?.getTiming().iterations !== Infinity)
        .map((a) => a.finished.catch(() => {})),
    );
  });
  const modal = page.getByRole('dialog', { name: zh.transfer.fromSnapshot, exact: true });
  const body = modal.locator(
    source(info) ? '.el-table__body-wrapper tbody tr' : '.ant-table-body tbody tr[data-row-key]',
  );
  const data = await body.evaluateAll((rows) =>
    rows.map((r) =>
      [...r.querySelectorAll('td')].map((cell) => cell.textContent?.trim().replace(/\s+/g, ' ')),
    ),
  );
  const box = await (source(info) ? modal.locator('.el-dialog') : modal).boundingBox();
  const titleInfo = await modal
    .locator(source(info) ? '.el-dialog__title' : '.ant-modal-title')
    .evaluate((el) => {
      const style = getComputedStyle(el);
      const r = el.getBoundingClientRect();
      return {
        text: el.textContent,
        rect: { x: r.x, y: r.y, width: r.width, height: r.height },
        color: style.color,
        opacity: style.opacity,
        visibility: style.visibility,
        display: style.display,
      };
    });
  writeFileSync(info.outputPath(name + '-title.json'), JSON.stringify(titleInfo));
  writeFileSync(info.outputPath(name + '-rows.json'), JSON.stringify(data, null, 2));
  writeFileSync(info.outputPath(name + '-geometry.json'), JSON.stringify(box));
  await page.screenshot({ path: info.outputPath(name + '.png'), fullPage: true });
  writeFileSync(info.outputPath(name + '-baseline.json'), fixture(info, 'observe'));
}

test('P10 snapshot picker: fifty rows, literal bill search, grouping, unmapped target and persistent selection', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  fixture(info, 'begin');
  await login(page, info);
  try {
    await sync(page, true);
    await page.getByRole('menuitem', { name: zh.nav.transfer, exact: true }).click();
    const open = page.getByRole('button', { name: zh.transfer.fromSnapshot, exact: true });
    await open.click();
    const modal = page.getByRole('dialog', { name: zh.transfer.fromSnapshot, exact: true });
    const input = modal.getByPlaceholder(zh.transfer.targetSearch, { exact: true });
    const rows = modal.locator(
      source(info) ? '.el-table__body-wrapper tbody tr' : '.ant-table-body tbody tr[data-row-key]',
    );
    await expect(rows).toHaveCount(50);
    expect((await (source(info) ? modal.locator('.el-dialog') : modal).boundingBox())!.width).toBe(
      820,
    );
    await expect(modal.getByRole('button', { name: zh.common.search, exact: true })).toHaveCount(0);
    await expect(modal.locator('.el-pagination,.ant-pagination')).toHaveCount(0);
    await expect(modal.locator('th')).toHaveText([
      zh.orders.org,
      zh.common.customer,
      'PI',
      zh.common.material,
      zh.orders.materialName,
    ]);
    await capture(page, info, '01-initial-fifty');
    const search = async (text: string, count: number) => {
      await input.fill(text);
      const reply = page.waitForResponse(
        (r) => r.request().method() === 'GET' && r.url().includes('/transfers/targets'),
        { timeout: 15000 },
      );
      await input.press('Enter');
      expect((await reply).status()).toBe(200);
      await expect(rows).toHaveCount(count);
    };
    await search('  LOOK%_  ', 50);
    await expect(rows.first()).toContainText('A 物料000');
    await expect(rows.last()).toContainText('PICK-M049');
    await capture(page, info, '02-bill-literal-search');
    const viewport = modal.locator(source(info) ? '.el-table__body-wrapper' : '.ant-table-body');
    await viewport.hover();
    await page.mouse.wheel(0, 10000);
    await expect(rows.last()).toBeInViewport();
    await capture(page, info, '02b-scroll-last-row');
    await rows.last().click();
    await expect(modal).not.toBeVisible();
    await expect(field(page, info, zh.transfer.toMaterial).getByRole('textbox')).toHaveValue(
      'PICK-M049',
    );
    await open.click();
    await expect(input).toHaveValue('  LOOK%_  ');
    await expect(rows).toHaveCount(50);
    await input.press('Escape');
    await expect(modal).not.toBeVisible();
    await open.click();
    await expect(input).toHaveValue('  LOOK%_  ');
    await expect(rows).toHaveCount(50);
    await search('PICK-M054', 1);
    await expect(rows.first()).toContainText('A 物料054');
    await capture(page, info, '03-search-beyond-fifty');
    await search('PICK-M000', 1);
    await expect(rows.first()).toContainText('Z 物料000');
    await capture(page, info, '04-grouped-max-name');
    await rows.first().click();
    await expect(modal).not.toBeVisible();
    await expect(field(page, info, zh.transfer.toPi).getByRole('textbox')).toHaveValue(pi);
    await expect(field(page, info, zh.transfer.toCustomer).getByRole('textbox')).toHaveValue(
      'PICK-CUSTOMER',
    );
    await expect(field(page, info, zh.transfer.toMaterial).getByRole('textbox')).toHaveValue(
      'PICK-M000',
    );
    await expect(
      page
        .getByRole('tabpanel')
        .filter({ visible: true })
        .getByText(zh.transfer.srcSNAPSHOT, { exact: true }),
    ).toBeVisible();
    await expect(field(page, info, zh.transfer.toFactory)).toContainText('比较测试乙厂');
    await page.screenshot({ path: info.outputPath('05-mapped-filled.png'), fullPage: true });
    await field(page, info, zh.transfer.toPi)
      .getByRole('textbox')
      .fill(pi + '-MANUAL');
    await expect(
      page
        .getByRole('tabpanel')
        .filter({ visible: true })
        .getByText(zh.transfer.srcMANUAL, { exact: true }),
    ).toBeVisible();
    await open.click();
    await expect(input).toHaveValue('PICK-M000');
    await search('UNMAPPED', 1);
    await expect(rows.first()).toContainText(zh.orders.noFactory);
    await capture(page, info, '06-unmapped-organization');
    await rows.first().click();
    await expect(field(page, info, zh.transfer.toPi).getByRole('textbox')).toHaveValue(
      'CMP-UI-PICK-UNMAPPED',
    );
    await expect(field(page, info, zh.transfer.toFactory).getByRole('combobox')).toHaveValue('');
    await expect(field(page, info, zh.transfer.toFactory)).not.toContainText('比较测试乙厂');
    await expect(
      page
        .getByRole('tabpanel')
        .filter({ visible: true })
        .getByText(zh.transfer.srcSNAPSHOT, { exact: true }),
    ).toBeVisible();
    await expect(
      page.getByRole('button', { name: zh.transfer.directSubmit, exact: true }),
    ).toBeDisabled();
    await page.screenshot({ path: info.outputPath('07-unmapped-filled.png'), fullPage: true });
    await open.click();
    await search('NO-CUSTOMER', 0);
    await search('NO-PI', 0);
    await search('Z 物料000', 0);
    await capture(page, info, '08-empty-name-not-search-key');
    await input.fill('');
    await expect(rows).toHaveCount(0);
    await search('', 50);
    await search('PICK-CUSTOMER', 50);
    await search('PICK-CUSTOMER', 50);
    await expect(rows.first()).toContainText('Z 物料000');
    await capture(page, info, '09-customer-repeat-search');
    await input.press('Escape');
    expect(errors).toEqual([]);
  } catch (error) {
    await page.screenshot({ path: info.outputPath('diagnostic.png'), fullPage: true });
    throw error;
  } finally {
    await page.goto('/orders');
    await sync(page, false);
    writeFileSync(info.outputPath('baseline-preserved.json'), fixture(info, 'clean'));
    writeFileSync(info.outputPath('page-errors.json'), JSON.stringify(errors));
  }
});

test('P10 snapshot pending search: retain visible rows and selection while a real request waits', async ({
  page,
}, info) => {
  test.setTimeout(90_000);
  fixture(info, 'begin');
  await login(page, info);
  let release: () => void = () => {};
  try {
    await sync(page, true);
    await page.getByRole('menuitem', { name: zh.nav.transfer, exact: true }).click();
    const open = page.getByRole('button', { name: zh.transfer.fromSnapshot, exact: true });
    await open.click();
    const modal = page.getByRole('dialog', { name: zh.transfer.fromSnapshot, exact: true });
    const rows = modal.locator(
      source(info) ? '.el-table__body-wrapper tbody tr' : '.ant-table-body tbody tr[data-row-key]',
    );
    await expect(rows).toHaveCount(50);
    const previous = await rows.allTextContents();
    let started = false;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const endpoint = '**/transfers/targets?**';
    await page.route(endpoint, async (route) => {
      started = true;
      await gate;
      await route.continue();
    });
    const input = modal.getByPlaceholder(zh.transfer.targetSearch, { exact: true });
    await input.fill('PICK-M054');
    await input.press('Enter');
    await expect.poll(() => started, { timeout: 15000 }).toBe(true);
    await expect(rows).toHaveCount(50);
    expect(await rows.allTextContents()).toEqual(previous);
    await expect(modal.locator('.el-loading-mask:visible,.ant-spin-spinning:visible')).toHaveCount(
      0,
    );
    await capture(page, info, '01-pending-keeps-previous-rows');
    await input.press('Escape');
    await expect(modal).not.toBeVisible();
    await open.click();
    await expect(input).toHaveValue('PICK-M054');
    await expect(rows).toHaveCount(50);
    const replied = page.waitForResponse(
      (r) => r.url().includes('/transfers/targets') && r.status() === 200,
      { timeout: 15000 },
    );
    release();
    await replied;
    await expect(rows).toHaveCount(1);
    await expect(rows.first()).toContainText('A 物料054');
    await page.unroute(endpoint);
    await capture(page, info, '02-pending-resolved');
    await rows.first().click();
    await expect(field(page, info, zh.transfer.toMaterial).getByRole('textbox')).toHaveValue(
      'PICK-M054',
    );
    await expect(field(page, info, zh.transfer.toPi).getByRole('textbox')).toHaveValue(pi);
  } finally {
    release();
    await page.goto('/orders');
    await sync(page, false);
    writeFileSync(info.outputPath('baseline-preserved.json'), fixture(info, 'clean'));
  }
});
