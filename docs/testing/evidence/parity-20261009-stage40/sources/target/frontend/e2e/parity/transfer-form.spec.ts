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
      'small',
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
          FQty: line === 0 ? '2' : '1',
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
test('P10 transfer form: automatic candidates, material scope, single SN and cancelled confirmation', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  await setup(page, info);
  try {
    const before = fixture(info, 'observe');
    await page.getByRole('menuitem', { name: zh.nav.transfer, exact: true }).click();
    const submit = page.getByRole('button', { name: zh.transfer.directSubmit, exact: true });
    await expect(submit).toBeDisabled();
    await select(page, info, zh.transfer.fromFactory, '比较测试甲厂（CMP-A）');
    await field(page, info, 'PI').getByRole('textbox').fill(pi);
    await field(page, info, 'PI').getByRole('textbox').blur();
    await expect(active(page)).toContainText('可转移 3 枚（范围内共 3 枚）');
    await capture(page, info, '01-pi-candidates');
    const materialResponse = source(info)
      ? page.waitForResponse(
          (r) => r.url().includes('/transfers/candidates') && r.url().includes('scope=MATERIAL'),
        )
      : undefined;
    await scope(page, info, 'MATERIAL');
    if (source(info)) {
      // 来源缺陷单独留证，不能将此分支通过称为物料功能双端一致。
      const response = await materialResponse!;
      expect(response.status()).toBe(400);
      await expect(page.getByText('按物料转移时物料编码必填', { exact: true })).toBeVisible();
      await capture(page, info, '02-material-source-defect');
      writeFileSync(
        info.outputPath('material-known-difference.json'),
        JSON.stringify({
          status: response.status(),
          body: await response.json(),
          sourceMaterialUiBlocked: true,
        }),
      );
    } else {
      await field(page, info, zh.common.material).getByRole('combobox').click();
      const options = page.locator('.ant-select-dropdown:visible .ant-select-item-option-content');
      await expect(options.getByText('CMP-ACQ-MAT-0', { exact: true })).toBeVisible();
      await expect(options.getByText('CMP-ACQ-MAT-1', { exact: true })).toBeVisible();
      await options.getByText('CMP-ACQ-MAT-1', { exact: true }).click();
      await expect(active(page)).toContainText('可转移 1 枚（范围内共 1 枚）');
      await capture(page, info, '02-material-candidates');
      writeFileSync(
        info.outputPath('material-known-difference.json'),
        JSON.stringify({
          materials: ['CMP-ACQ-MAT-0', 'CMP-ACQ-MAT-1'],
          selected: 'CMP-ACQ-MAT-1',
          quantity: 1,
          sourceMaterialUiBlocked: false,
        }),
      );
    }
    await scope(page, info, 'SINGLE');
    await field(page, info, zh.common.sn).getByRole('textbox').fill('ABSENT');
    await field(page, info, zh.common.sn).getByRole('textbox').blur();
    await expect(active(page)).toContainText('可转移 0 枚（范围内共 0 枚）');
    await expect(submit).toBeDisabled();
    await field(page, info, zh.common.sn).getByRole('textbox').fill('A17A00003');
    await field(page, info, zh.common.sn).getByRole('textbox').blur();
    await expect(active(page)).toContainText('可转移 1 枚（范围内共 1 枚）');
    await select(page, info, zh.transfer.toFactory, '比较测试乙厂（CMP-B）');
    await field(page, info, zh.transfer.toPi).getByRole('textbox').fill(pi);
    await field(page, info, zh.transfer.reason).getByRole('textbox').fill('CMP_UI_CANCEL_ONLY');
    await expect(submit).toBeEnabled();
    await capture(page, info, '03-single-ready');
    await submit.click();
    const modal = page.locator(source(info) ? '.el-message-box' : '.ant-modal-confirm');
    await expect(modal).toContainText('直接把 1 枚号转到 比较测试乙厂 · ' + pi);
    await capture(page, info, '04-confirm-factory-name');
    await modal.getByRole('button', { name: zh.common.cancel, exact: true }).click();
    await expect(modal).toBeHidden();
    expect(fixture(info, 'observe')).toBe(before);
    await field(page, info, 'PI').getByRole('textbox').fill('');
    await field(page, info, 'PI').getByRole('textbox').blur();
    await expect(active(page)).not.toContainText('可转移');
    await expect(submit).toBeDisabled();
    await page.setViewportSize({ width: 900, height: 960 });
    await capture(page, info, '05-empty-narrow');
  } finally {
    await clean(page, info);
  }
});

test('P10 transfer roles: bound factory and query accounts preserve inventory', async ({
  page,
  browser,
}, info) => {
  test.setTimeout(180_000);
  await setup(page, info);
  try {
    const before = fixture(info, 'observe');
    for (const user of ['cmp_operator', 'cmp_query_all', 'cmp_query_factory']) {
      const context = await browser.newContext({
        baseURL: info.project.use.baseURL,
        viewport: { width: 1440, height: 960 },
      });
      const rolePage = await context.newPage();
      try {
        await login(rolePage, info, user);
        await rolePage.getByRole('menuitem', { name: zh.nav.transfer, exact: true }).click();
        if (user === 'cmp_operator') {
          const from = field(rolePage, info, zh.transfer.fromFactory).getByRole('textbox');
          await expect(from).toBeDisabled();
          await expect(from).toHaveValue('比较测试甲厂');
          await field(rolePage, info, 'PI').getByRole('textbox').fill(pi);
          await field(rolePage, info, 'PI').getByRole('textbox').blur();
          await expect(active(rolePage)).toContainText('可转移 3 枚（范围内共 3 枚）');
          await expect(
            rolePage.getByRole('button', { name: zh.transfer.applySubmit, exact: true }),
          ).toBeDisabled();
          await expect(
            rolePage.getByRole('button', { name: zh.transfer.directSubmit, exact: true }),
          ).toHaveCount(0);
        } else {
          await expect(
            rolePage.getByRole('tab', { name: zh.transfer.records, exact: true }),
          ).toHaveAttribute('aria-selected', 'true');
          await expect(
            rolePage.getByRole('tab', { name: zh.transfer.applyTab, exact: true }),
          ).toHaveCount(0);
          await expect(
            rolePage.getByRole('tab', { name: zh.transfer.directTab, exact: true }),
          ).toHaveCount(0);
        }
        await capture(rolePage, info, 'role-' + user);
      } finally {
        await context.close();
      }
    }
    expect(fixture(info, 'observe')).toBe(before);
  } finally {
    await clean(page, info);
  }
});
