import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, renameSync } from 'node:fs';
import { resolve } from 'node:path';
import { protectTestSecrets } from '../privacy';
import { paceComparisonLogin } from './login-budget';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };

const source = (info: TestInfo) => info.project.name === 'prototype';
const kind = (info: TestInfo) => (source(info) ? 'python' : 'java');
const pi = 'CMP-UI-ACQ-PI-A';
const targetPi = 'CMP-UI-ACQ-PI-B';
const profile = (info: TestInfo) => (info.title.includes('duplicate') ? 'duplicates' : 'hint');
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
      profile(info),
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
  const id = await combo.getAttribute('aria-controls');
  expect(id).toBeTruthy();
  if (source(info)) {
    await page.locator(`[id="${id}"]`).getByRole('option', { name: value, exact: true }).click();
  } else
    await page
      .locator('.ant-select-dropdown')
      .filter({ has: page.locator(`[id="${id}"]`) })
      .locator('.ant-select-item-option-content')
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
async function sync(page: Page, info: TestInfo, include: boolean) {
  const entries = include
    ? ['A', 'B'].flatMap((letter, index) =>
        [0, 1].map((line) => ({
          'F_ora_kh.FNumber': 'CMP-ACQ-CUSTOMER',
          FBillNo: 'CMP-UI-ACQ-MO-' + letter,
          F_HX_PO: 'CMP-ACQ-PO',
          F_HX_PI: 'CMP-UI-ACQ-PI-' + letter,
          'FMaterialId.FNumber': 'CMP-ACQ-MAT-' + line,
          'FMaterialId.FName': 'CMP-ACQ-MAT-' + line,
          FQty: profile(info) === 'duplicates' ? '3' : line === 0 ? '2' : '1',
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
  await login(page, info);
  await sync(page, info, true);
  await paceComparisonLogin(page);
  fixture(info, 'seed');
}
async function clean(page: Page, info: TestInfo) {
  if (!source(info)) await expect.poll(() => Number(fixture(info, 'active'))).toBe(0);
  await page.goto('/orders');
  await sync(page, info, false);
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

type ImpactFacts = {
  transfers: { qty: number; status: string; from_pi: string; to_pi: string }[];
  crossPiItems: {
    sn: string;
    pi: string;
    seq_pi: string;
    seq_dec: number;
    factory: string;
    status: string;
  }[];
  counters: Record<string, number>;
};
const facts = (info: TestInfo): ImpactFacts => JSON.parse(fixture(info, 'observe')) as ImpactFacts;
const text = (pattern: string, values: Record<string, string | number>) =>
  Object.entries(values).reduce(
    (s, [key, value]) => s.replaceAll('{{' + key + '}}', String(value)),
    pattern,
  );
const jump = (unused: number) =>
  text(zh.transfer.jumpWarn, { pi: targetPi, before: 'A17A00001', after: 'A17A00004', unused });
async function prepare(page: Page, info: TestInfo) {
  await setup(page, info);
  await page.getByRole('menuitem', { name: zh.nav.transfer, exact: true }).click();
  await select(page, info, zh.transfer.fromFactory, '比较测试甲厂（CMP-A）');
  await field(page, info, 'PI').getByRole('textbox').fill(pi);
  await field(page, info, 'PI').getByRole('textbox').blur();
  await select(page, info, zh.transfer.toFactory, '比较测试乙厂（CMP-B）');
}
async function submit(
  page: Page,
  info: TestInfo,
  quantity: number,
  expectedJump?: string,
  cancel = false,
) {
  const before = facts(info).transfers.length;
  await page.getByRole('button', { name: zh.transfer.directSubmit, exact: true }).click();
  const modal = page.locator(
    source(info) ? '.el-message-box:visible' : '.ant-modal-confirm:visible',
  );
  await expect(modal).toContainText(
    text(zh.transfer.directConfirm, { n: quantity, factory: '比较测试乙厂', pi: targetPi }),
  );
  if (expectedJump) await expect(modal).toContainText(expectedJump);
  else await expect(modal).not.toContainText('下一次生成');
  await capture(page, info, `confirm-${before}-${cancel ? 'cancel' : 'submit'}`);
  await modal
    .getByRole('button', { name: cancel ? zh.common.cancel : /^(确定|确认)$/, exact: true })
    .click();
  await expect(modal).toBeHidden();
  if (cancel) expect(facts(info).transfers).toHaveLength(before);
  else {
    await expect.poll(() => facts(info).transfers.length).toBe(before + 1);
    await expect(page.getByRole('tab', { name: zh.transfer.records, exact: true })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    expect(facts(info).transfers.at(-1)).toMatchObject({
      qty: quantity,
      status: 'APPROVED',
      from_pi: pi,
      to_pi: targetPi,
    });
  }
}

test('P10 cross PI hints: wait for impact, zero skipped positions, gaps and actual transfer', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  fixture(info, 'begin');
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  let release: () => void = () => {};
  try {
    await prepare(page, info);
    const before = fixture(info, 'observe');
    await field(page, info, zh.transfer.reason).getByRole('textbox').fill('CMP_UI_XPI_REST');
    let started = false;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const endpoint = source(info) ? '**/transfers/candidates?**' : '**/transfers/target-hint';
    await page.route(endpoint, async (route) => {
      const matches = source(info)
        ? new URL(route.request().url()).searchParams.get('to_pi') === targetPi
        : (route.request().postDataJSON() as { toPi: string }).toPi === targetPi;
      if (matches) {
        started = true;
        await gate;
      }
      await route.continue();
    });
    await field(page, info, zh.transfer.toPi).getByRole('textbox').fill(targetPi);
    await field(page, info, zh.transfer.toPi).getByRole('textbox').blur();
    await expect.poll(() => started, { timeout: 15000 }).toBe(true);
    const send = page.getByRole('button', { name: zh.transfer.directSubmit, exact: true });
    await expect(send).toBeDisabled();
    await capture(page, info, '01-impact-pending-disabled');
    release();
    await expect(active(page).getByText(jump(0), { exact: true })).toBeVisible();
    await expect(send).toBeEnabled();
    await page.unroute(endpoint);
    await capture(page, info, '02-contiguous-zero-skipped');
    await submit(page, info, 3, jump(0), true);
    expect(fixture(info, 'observe')).toBe(before);
    await scope(page, info, 'SINGLE');
    await field(page, info, zh.common.sn).getByRole('textbox').fill('A17A00003');
    await field(page, info, zh.common.sn).getByRole('textbox').blur();
    await field(page, info, zh.transfer.reason).getByRole('textbox').fill('CMP_UI_XPI_SINGLE');
    await expect(active(page).getByText(jump(2), { exact: true })).toBeVisible();
    await expect(send).toBeEnabled();
    await capture(page, info, '03-single-gap-two');
    await submit(page, info, 1, jump(2));
    expect(facts(info).counters).toEqual({ [pi]: 3, [targetPi]: 0 });
    expect(facts(info).crossPiItems.find((r) => r.sn === 'A17A00003')).toMatchObject({
      pi: targetPi,
      seq_pi: pi,
      factory: 'CMP-B',
      status: 'TO_ACQUIRE',
    });
    await capture(page, info, '04-single-transferred');
    await page.getByRole('tab', { name: zh.transfer.directTab, exact: true }).click();
    await scope(page, info, 'PI');
    await field(page, info, zh.transfer.reason).getByRole('textbox').fill('CMP_UI_XPI_REST');
    await expect(active(page)).toContainText('可转移 2 枚（范围内共 2 枚）');
    await expect(send).toBeEnabled();
    await expect(active(page)).not.toContainText('下一次生成');
    await capture(page, info, '05-current-target-no-further-jump');
    await submit(page, info, 2);
    expect(facts(info).crossPiItems).toHaveLength(3);
    expect(
      facts(info).crossPiItems.every(
        (r) =>
          r.pi === targetPi &&
          r.seq_pi === pi &&
          r.factory === 'CMP-B' &&
          r.status === 'TO_ACQUIRE',
      ),
    ).toBe(true);
    expect(facts(info).counters).toEqual({ [pi]: 3, [targetPi]: 0 });
    await capture(page, info, '06-all-transferred-original-counter');
    expect(errors).toEqual([]);
  } catch (error) {
    await page.screenshot({ path: info.outputPath('diagnostic.png'), fullPage: true });
    throw error;
  } finally {
    release();
    await clean(page, info);
    writeFileSync(info.outputPath('page-errors.json'), JSON.stringify(errors));
  }
});

test('P10 cross PI duplicate: exact count and five samples, single SN and same PI reset', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  fixture(info, 'begin');
  try {
    await prepare(page, info);
    const before = fixture(info, 'observe');
    await field(page, info, zh.transfer.reason).getByRole('textbox').fill('CMP_UI_DUPLICATE_CHECK');
    await field(page, info, zh.transfer.toPi).getByRole('textbox').fill(targetPi);
    await field(page, info, zh.transfer.toPi).getByRole('textbox').blur();
    const warning = text(zh.transfer.dupWarn, {
      pi: targetPi,
      n: 6,
      sns: [1, 2, 3, 4, 5].map((n) => 'A17A0000' + n).join('、'),
    });
    await expect(active(page).getByText(warning, { exact: true })).toBeVisible();
    const send = page.getByRole('button', { name: zh.transfer.directSubmit, exact: true });
    await expect(send).toBeDisabled();
    await capture(page, info, '01-six-duplicates-five-samples');
    await scope(page, info, 'SINGLE');
    await field(page, info, zh.common.sn).getByRole('textbox').fill('A17A00006');
    await field(page, info, zh.common.sn).getByRole('textbox').blur();
    await expect(
      active(page).getByText(text(zh.transfer.dupWarn, { pi: targetPi, n: 1, sns: 'A17A00006' }), {
        exact: true,
      }),
    ).toBeVisible();
    await expect(send).toBeDisabled();
    await capture(page, info, '02-single-duplicate');
    await field(page, info, zh.transfer.toPi).getByRole('textbox').fill(pi);
    await field(page, info, zh.transfer.toPi).getByRole('textbox').blur();
    await expect(send).toBeEnabled();
    await expect(active(page)).not.toContainText('相同的完整 SN');
    await capture(page, info, '03-same-pi-no-duplicate');
    await field(page, info, zh.transfer.toPi).getByRole('textbox').fill(targetPi);
    await field(page, info, zh.transfer.toPi).getByRole('textbox').blur();
    await expect(send).toBeDisabled();
    await expect(active(page)).toContainText('A17A00006');
    expect(fixture(info, 'observe')).toBe(before);
  } finally {
    await clean(page, info);
  }
});
