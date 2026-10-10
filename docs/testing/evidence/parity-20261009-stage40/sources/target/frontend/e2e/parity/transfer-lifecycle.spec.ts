import { expect, test, type Locator, type Page, type TestInfo } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, renameSync } from 'node:fs';
import { resolve } from 'node:path';
import { protectTestSecrets } from '../privacy';
import { paceComparisonLogin } from './login-budget';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };

const source = (info: TestInfo) => info.project.name === 'prototype';
const kind = (info: TestInfo) => (source(info) ? 'python' : 'java');
const pi = 'CMP-UI-ACQ-PI-A';
const run = resolve('../tools/.runtime/comparison-20261008');
const fixture = (info: TestInfo, action: string) =>
  execFileSync(
    resolve('../../sn-center-python/.venv/Scripts/python.exe'),
    [
      '-X',
      'utf8',
      resolve('../tools/testing/parity-acquire-fixture.py'),
      kind(info),
      action,
      '--profile',
      'lifecycle',
    ],
    { windowsHide: true, encoding: 'utf8' },
  );
const active = (page: Page) => page.getByRole('tabpanel').filter({ visible: true });
const field = (page: Page, info: TestInfo, label: string) =>
  active(page)
    .locator(source(info) ? '.el-form-item' : '.ant-form-item')
    .filter({
      has: page
        .locator(source(info) ? '.el-form-item__label' : '.ant-form-item-label')
        .getByText(label, { exact: true }),
    });
async function scope(page: Page, info: TestInfo, value: 'PI' | 'MATERIAL' | 'SINGLE') {
  if (source(info))
    await active(page)
      .locator('.el-radio-button__inner')
      .getByText(zh.scope[value], { exact: true })
      .click();
  else
    await page
      .locator('.ant-radio-button-wrapper')
      .filter({ has: page.getByRole('radio', { name: zh.scope[value], exact: true }) })
      .click();
  await expect(page.getByRole('radio', { name: zh.scope[value], exact: true })).toBeChecked();
}
async function select(page: Page, info: TestInfo, label: string, value: string) {
  const combo = field(page, info, label).getByRole('combobox');
  await combo.click();
  if (source(info)) {
    const id = await combo.getAttribute('aria-controls');
    await page.locator(`[id="${id}"]`).getByRole('option', { name: value, exact: true }).click();
  } else
    await page
      .locator('.ant-select-dropdown:visible .ant-select-item-option-content')
      .getByText(value, { exact: true })
      .click();
}
async function login(page: Page, info: TestInfo, user = 'admin') {
  const privateDir = resolve('../deploy/.secrets/comparison-20261008');
  const password =
    user === 'admin'
      ? readFileSync(resolve(privateDir, kind(info) + '-admin-password'), 'utf8').trim()
      : (
          JSON.parse(readFileSync(resolve(privateDir, 'role-accounts.json'), 'utf8')) as Record<
            string,
            Record<string, { current: string }>
          >
        )[kind(info)]![user]!.current;
  protectTestSecrets(password);
  await page.goto('/login');
  await paceComparisonLogin(page);
  await page.locator('input[autocomplete="username"]').fill(user);
  await page.locator('input[autocomplete="current-password"]').fill(password);
  await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
  await expect(page.getByRole('menuitem', { name: zh.nav.transfer, exact: true })).toBeVisible();
}
async function sync(page: Page, include: boolean) {
  const entries = include
    ? ['A', 'B'].flatMap((letter, index) =>
        [0, 1].map((line) => ({
          'F_ora_kh.FNumber': 'CMP-ACQ-CUSTOMER',
          FBillNo: 'CMP-UI-ACQ-MO-' + letter,
          F_HX_PO: 'CMP-ACQ-PO',
          F_HX_PI: 'CMP-UI-ACQ-PI-' + letter,
          'FMaterialId.FNumber': 'CMP-ACQ-MAT-' + line,
          'FMaterialId.FName': 'CMP-ACQ-MAT-' + line,
          FQty: line === 0 ? (index === 0 ? '60' : '2') : '1',
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
async function setup(page: Page, info: TestInfo) {
  fixture(info, 'begin');
  await login(page, info);
  await sync(page, true);
  await paceComparisonLogin(page);
  fixture(info, 'seed');
}
async function clean(page: Page, info: TestInfo) {
  if (!source(info)) await expect.poll(() => Number(fixture(info, 'active'))).toBe(0);
  await page.goto('/orders');
  await sync(page, false);
  fixture(info, 'clean');
  writeFileSync(
    info.outputPath('baseline-preserved.json'),
    readFileSync(resolve(run, 'acquire-clean-' + kind(info) + '.json')),
  );
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
  await page.screenshot({ path: info.outputPath(name + '.png'), fullPage: true });
  writeFileSync(info.outputPath(name + '.json'), fixture(info, 'observe'));
}

type Facts = {
  current: { sn: string; factory: string; status: string; hasBatch: number; hasPrint: number }[];
  decisions: {
    status: string;
    qty: number;
    scope: string;
    reason: string;
    actor: string;
    decider: string | null;
    note: string;
  }[];
};
const facts = (info: TestInfo) => JSON.parse(fixture(info, 'observe')) as Facts;
const rowSelector = (info: TestInfo) => (source(info) ? '.el-table__row' : '.ant-table-row');
const modal = (page: Page, info: TestInfo) =>
  page.locator(source(info) ? '.el-message-box' : '.ant-modal-confirm');
const record = (page: Page, info: TestInfo, reason: string) =>
  active(page).locator(rowSelector(info)).filter({ hasText: reason });
async function confirm(page: Page, info: TestInfo) {
  await modal(page, info)
    .getByRole('button', { name: /^(确定|确认)$/ })
    .click();
  await expect(modal(page, info)).toBeHidden();
}
async function records(page: Page) {
  await page.getByRole('menuitem', { name: zh.nav.transfer, exact: true }).click();
  await page.getByRole('tab', { name: zh.transfer.records, exact: true }).click();
  await active(page).getByPlaceholder('PI', { exact: true }).fill(pi);
  await active(page).getByRole('button', { name: zh.common.search, exact: true }).click();
}
async function create(page: Page, info: TestInfo, reason: string, single = false) {
  await page.getByRole('menuitem', { name: zh.nav.transfer, exact: true }).click();
  await page.getByRole('tab', { name: zh.transfer.applyTab, exact: true }).click();
  await scope(page, info, single ? 'SINGLE' : 'PI');
  await field(page, info, 'PI').getByRole('textbox').fill(pi);
  if (single) {
    await field(page, info, zh.common.sn).getByRole('textbox').fill('A17A00001');
    await field(page, info, zh.common.sn).getByRole('textbox').blur();
  }
  await field(page, info, 'PI').getByRole('textbox').blur();
  const qty = single ? 1 : 51;
  await expect(active(page)).toContainText(`可转移 ${qty} 枚（范围内共 ${qty} 枚）`);
  await select(page, info, zh.transfer.toFactory, '比较测试乙厂（CMP-B）');
  await field(page, info, zh.transfer.toPi).getByRole('textbox').fill(pi);
  await field(page, info, zh.transfer.reason).getByRole('textbox').fill(reason);
  await capture(page, info, reason + '-ready');
  writeFileSync(
    info.outputPath(reason + '-ready-dom.json'),
    JSON.stringify(
      await active(page)
        .locator('button')
        .filter({ hasText: zh.transfer.applySubmit })
        .evaluateAll((elements) =>
          elements.map((element) => {
            const ancestors = [];
            let p: Element | null = element;
            while (p) {
              ancestors.push({
                tag: p.tagName,
                role: p.getAttribute('role'),
                hidden: p.getAttribute('aria-hidden'),
                inert: p.hasAttribute('inert'),
              });
              p = p.parentElement;
            }
            return { html: element.outerHTML, ancestors };
          }),
        ),
    ),
  );
  await expect(
    page.getByRole('button', { name: zh.transfer.applySubmit, exact: true }),
  ).toBeEnabled();
  await page.getByRole('button', { name: zh.transfer.applySubmit, exact: true }).click();
  await expect(modal(page, info)).toContainText(`提交申请后这 ${qty} 枚号变为申请中`);
  await expect(modal(page, info)).toContainText(`转入：比较测试乙厂 · ${pi}`);
  await capture(page, info, reason + '-apply-confirm');
  await confirm(page, info);
  await expect(page.getByRole('tab', { name: zh.transfer.records, exact: true })).toHaveAttribute(
    'aria-selected',
    'true',
  );
  await expect(record(page, info, reason)).toContainText(zh.transferStatus.PENDING);
  await expect(record(page, info, reason)).toContainText(zh.transfer.dirOUT);
  await expect(
    record(page, info, reason).getByRole('button', { name: zh.transfer.approve, exact: true }),
  ).toHaveCount(0);
  await expect(
    record(page, info, reason).getByRole('button', { name: zh.transfer.reject, exact: true }),
  ).toHaveCount(0);
  const no = (await record(page, info, reason).innerText()).match(/T\d{18}/)?.[0];
  expect(no).toBeTruthy();
  await expect
    .poll(() => facts(info).current.filter((r) => r.status === 'APPLYING').length)
    .toBe(qty);
  await capture(page, info, reason + '-pending');
  return no!;
}
async function details(page: Page, info: TestInfo, reason: string, status: string, name: string) {
  await record(page, info, reason)
    .getByRole('button', { name: zh.common.detail, exact: true })
    .click();
  const drawer = page.locator(source(info) ? '.el-drawer' : '.sn-transfer-detail [role="dialog"]');
  await expect(drawer).toBeVisible();
  await expect(drawer).toContainText(/T\d{18}/);
  const rows = drawer.locator(rowSelector(info));
  await expect(rows).toHaveCount(50);
  await expect(rows.first().locator('td').nth(4)).toHaveText(status);
  const first = await rows.evaluateAll((all) =>
    all.map((r) => Array.from(r.querySelectorAll('td')).map((c) => c.textContent?.trim() ?? '')),
  );
  expect(first.map((r) => r[0])).toEqual(
    Array.from({ length: 50 }, (_, i) => `A17A${String(i + 1).padStart(5, '0')}`),
  );
  for (const cells of first) {
    expect(cells[3]).toBe(zh.status.TO_PRINT);
    expect(cells[4]).toBe(status);
    expect(cells[6]).toMatch(/^B\d{18}$/);
  }
  await capture(page, info, name + '-page1');
  await drawer
    .locator(source(info) ? '.el-pager .number' : '.ant-pagination-item')
    .filter({ hasText: /^2$/ })
    .click();
  await expect(rows).toHaveCount(1);
  await expect(rows.first()).toContainText('A17A00051');
  await expect(rows.first().locator('td').nth(4)).toHaveText(status);
  const last = await rows.evaluateAll((all) =>
    all.map((r) => Array.from(r.querySelectorAll('td')).map((c) => c.textContent?.trim() ?? '')),
  );
  expect(last[0]![4]).toBe(status);
  expect(last[0]![6]).toBe(first[0]![6]);
  writeFileSync(info.outputPath(name + '-raw-rows.json'), JSON.stringify({ first, last }));
  await capture(page, info, name + '-page2');
  await drawer.locator(source(info) ? '.el-drawer__close-btn' : '.ant-drawer-close').click();
  await expect(drawer).toBeHidden();
}

async function chooseOption(page: Page, info: TestInfo, combo: Locator, label: string | RegExp) {
  await (
    source(info)
      ? combo.locator('xpath=ancestor::div[contains(@class,"el-select__wrapper")][1]')
      : combo
  ).click();
  const id = await combo.getAttribute('aria-controls');
  if (source(info))
    await page.locator(`[id="${id}"]`).getByRole('option', { name: label, exact: true }).click();
  else
    await page
      .locator('.ant-select-dropdown')
      .filter({ has: page.locator(`[id="${id}"]`) })
      .locator('.ant-select-item-option-content')
      .getByText(label, { exact: true })
      .click();
}
async function filters(page: Page, info: TestInfo) {
  const rows = active(page).locator(rowSelector(info));
  await expect(rows).toHaveCount(3);
  const pager = active(page).locator(source(info) ? '.el-pagination' : '.ant-pagination');
  for (const size of [50, 100]) {
    const response = page.waitForResponse(
      (r) => r.request().method() === 'GET' && r.url().includes('/transfers?'),
    );
    void response.catch(() => {});
    await chooseOption(page, info, pager.getByRole('combobox'), new RegExp(`^${size}\\s*条/页$`));
    const received = await response;
    expect(received.ok()).toBe(true);
    expect(new URL(received.url()).searchParams.get(source(info) ? 'page_size' : 'size')).toBe(
      String(size),
    );
    await expect(rows).toHaveCount(3);
  }
  const status = active(page)
    .locator(source(info) ? '.toolbar' : '.sn-toolbar')
    .getByRole('combobox')
    .first();
  await chooseOption(page, info, status, zh.transferStatus.REJECTED);
  await expect(rows).toHaveCount(1);
  await expect(rows).toContainText('CMP_UI_TRANSFER_REJECT');
  await capture(page, info, '12-filter-rejected');
  const piInput = active(page).getByPlaceholder('PI', { exact: true });
  await piInput.fill('CMP_UI_ABSENT_PI');
  await piInput.press('Enter');
  await expect(rows).toHaveCount(0);
  await expect(active(page)).toContainText(zh.common.empty);
  await capture(page, info, '13-filter-empty');
  await piInput.fill(pi);
  await piInput.press('Enter');
  await expect(rows).toHaveCount(1);
  await chooseOption(page, info, status, zh.transferStatus.APPROVED);
  await expect(rows).toHaveCount(1);
  await expect(rows).toContainText('CMP_UI_TRANSFER_APPROVE');
  await capture(page, info, '14-filter-approved');
}

test('P10 applied transfers: withdraw, reject, approve and fixed 50-row detail pages', async ({
  page,
  browser,
}, info) => {
  test.setTimeout(300_000);
  page.setDefaultTimeout(20_000);
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await setup(page, info);
  const context = await browser.newContext({
    baseURL: info.project.use.baseURL,
    viewport: { width: 1440, height: 960 },
  });
  const operator = await context.newPage();
  operator.setDefaultTimeout(20_000);
  operator.on('pageerror', (e) => errors.push(e.message));
  let complete = false;
  try {
    await login(operator, info, 'cmp_operator');
    await operator.getByRole('menuitem', { name: zh.nav.acquire, exact: true }).click();
    await active(operator)
      .locator(rowSelector(info))
      .filter({ hasText: pi })
      .getByRole('button', { name: zh.acquire.take, exact: true })
      .click();
    await expect(modal(operator, info)).toContainText(`全部 51 枚待领取的号`);
    await confirm(operator, info);
    await expect
      .poll(() => facts(info).current.filter((r) => r.status === 'TO_PRINT').length)
      .toBe(51);
    await capture(operator, info, '01-acquired');

    const withdrawal = 'CMP_UI_TRANSFER_WITHDRAW';
    const no1 = await create(operator, info, withdrawal);
    await details(operator, info, withdrawal, zh.status.APPLYING, '02-source-pending');
    await record(operator, info, withdrawal)
      .getByRole('button', { name: zh.transfer.withdraw, exact: true })
      .click();
    await expect(modal(operator, info)).toContainText(no1);
    await expect(modal(operator, info).getByRole('textbox')).toHaveCount(0);
    await capture(operator, info, '03-withdraw-confirm');
    await modal(operator, info)
      .getByRole('button', { name: zh.common.cancel, exact: true })
      .click();
    expect(facts(info).current.filter((r) => r.status === 'APPLYING')).toHaveLength(51);
    await record(operator, info, withdrawal)
      .getByRole('button', { name: zh.transfer.withdraw, exact: true })
      .click();
    await confirm(operator, info);
    await expect(record(operator, info, withdrawal)).toContainText(zh.transferStatus.WITHDRAWN);
    await expect
      .poll(
        () =>
          facts(info).current.filter(
            (r) => r.status === 'TO_PRINT' && r.hasBatch === 1 && r.factory === 'CMP-A',
          ).length,
      )
      .toBe(51);
    await capture(operator, info, '04-withdrawn');

    const rejection = 'CMP_UI_TRANSFER_REJECT';
    await create(operator, info, rejection, true);
    await records(page);
    await expect(
      record(page, info, rejection).getByRole('button', {
        name: zh.transfer.withdraw,
        exact: true,
      }),
    ).toHaveCount(0);
    await record(page, info, rejection)
      .getByRole('button', { name: zh.transfer.reject, exact: true })
      .click();
    const input = modal(page, info).getByRole('textbox');
    await expect(input).toHaveCount(1);
    expect(await input.evaluate((e) => e.tagName)).toBe('INPUT');
    await input.fill('CMP_UI_REJECT_NOTE');
    await capture(page, info, '05-reject-confirm');
    await modal(page, info).getByRole('button', { name: zh.common.cancel, exact: true }).click();
    expect(facts(info).current.filter((r) => r.status === 'APPLYING')).toHaveLength(1);
    await record(page, info, rejection)
      .getByRole('button', { name: zh.transfer.reject, exact: true })
      .click();
    await modal(page, info).getByRole('textbox').fill('CMP_UI_REJECT_NOTE');
    await confirm(page, info);
    await expect(record(page, info, rejection)).toContainText(zh.transferStatus.REJECTED);
    await expect
      .poll(
        () =>
          facts(info).current.filter(
            (r) => r.status === 'TO_PRINT' && r.hasBatch === 1 && r.factory === 'CMP-A',
          ).length,
      )
      .toBe(51);
    await capture(page, info, '06-rejected');

    const approval = 'CMP_UI_TRANSFER_APPROVE';
    await create(operator, info, approval);
    await records(page);
    await details(page, info, approval, zh.status.APPLYING, '07-admin-pending');
    await record(page, info, approval)
      .getByRole('button', { name: zh.transfer.approve, exact: true })
      .click();
    await expect(modal(page, info)).toContainText('51');
    await modal(page, info).getByRole('textbox').fill('CMP_UI_APPROVE_NOTE');
    await capture(page, info, '08-approve-confirm');
    await confirm(page, info);
    await expect(record(page, info, approval)).toContainText(zh.transferStatus.APPROVED);
    await expect
      .poll(
        () =>
          facts(info).current.filter(
            (r) =>
              r.status === 'TO_ACQUIRE' &&
              r.hasBatch === 0 &&
              r.hasPrint === 0 &&
              r.factory === 'CMP-B',
          ).length,
      )
      .toBe(51);
    complete = true;
    await capture(page, info, '09-approved-admin');
    await details(page, info, approval, zh.status.TO_ACQUIRE, '10-admin-approved');
    await records(operator);
    await expect(record(operator, info, approval)).toContainText(zh.transferStatus.APPROVED);
    await details(operator, info, approval, zh.status.TRANSFERRED_OUT, '11-source-approved');
    const decisions = facts(info).decisions;
    expect(decisions.map((r) => [r.status, r.qty, r.actor, r.decider, r.note])).toEqual([
      ['WITHDRAWN', 51, 'cmp_operator', 'cmp_operator', ''],
      ['REJECTED', 1, 'cmp_operator', 'admin', 'CMP_UI_REJECT_NOTE'],
      ['APPROVED', 51, 'cmp_operator', 'admin', 'CMP_UI_APPROVE_NOTE'],
    ]);
    complete = true;
    await filters(page, info);
    expect(errors).toEqual([]);
    writeFileSync(info.outputPath('page-errors.json'), JSON.stringify(errors));
    complete = true;
  } catch (error) {
    if (!page.url().includes('/login'))
      await page
        .screenshot({ path: info.outputPath('diagnostic-admin.png'), fullPage: true })
        .catch(() => {});
    if (!operator.url().includes('/login'))
      await operator
        .screenshot({ path: info.outputPath('diagnostic-operator.png'), fullPage: true })
        .catch(() => {});
    throw error;
  } finally {
    writeFileSync(info.outputPath('page-errors.json'), JSON.stringify(errors));
    await context.close();
    // 未完成决策时保留现场；不能通过删除待审批记录来掩盖失败。
    if (complete) await clean(page, info);
  }
});
