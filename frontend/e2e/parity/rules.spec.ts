import { expect, test, type Page, type TestInfo, type Locator } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';
import { protectTestSecrets } from '../privacy';
import { paceComparisonLogin } from './login-budget';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import en from '../../src/i18n/prototype/en.json' with { type: 'json' };
import vi from '../../src/i18n/prototype/vi.json' with { type: 'json' };
import errors from '../../src/i18n/prototype/errors.zh-CN.json' with { type: 'json' };

const prototype = (info: TestInfo) => info.project.name === 'prototype';
const kind = (info: TestInfo) => (prototype(info) ? 'python' : 'java');
const pageErrors = new WeakMap<Page, string[]>();
test.beforeEach(async ({ page }) => {
  page.setDefaultTimeout(10_000);
  const errors: string[] = [];
  pageErrors.set(page, errors);
  page.on('pageerror', (error) => errors.push(error.message));
});
test.afterEach(async ({ page }) => {
  expect(pageErrors.get(page)).toEqual([]);
});

function rows(page: Page, info: TestInfo) {
  return page.locator(prototype(info) ? '.el-table__row' : '.sn-rules-page .ant-table-row');
}
async function login(page: Page, info: TestInfo) {
  const password = readFileSync(
    resolve(`../deploy/.secrets/comparison-20261008/${kind(info)}-admin-password`),
    'utf8',
  ).trim();
  protectTestSecrets(password);
  await page.goto('/login');
  await paceComparisonLogin(page);
  await page.locator('input[autocomplete="username"]').fill('admin');
  await page.locator('input[autocomplete="current-password"]').fill(password);
  await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
  await page.getByRole('menuitem', { name: zh.nav.rules, exact: true }).click();
  await expect(rows(page, info)).toHaveCount(3);
}
function field(dialog: Locator, info: TestInfo, label: string) {
  return dialog
    .locator(prototype(info) ? '.el-form-item' : '.ant-form-item')
    .filter({ has: dialog.page().locator('label', { hasText: new RegExp(`^\\s*${label}\\s*$`) }) })
    .locator('input')
    .first();
}
async function create(page: Page) {
  await page.getByRole('button', { name: zh.rules.create, exact: true }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  return dialog;
}
async function cancel(dialog: Locator) {
  await dialog.getByRole('button', { name: /^(取消|Cancel|Hủy)$/ }).click();
  await expect(dialog).not.toBeVisible();
}
async function capture(page: Page, info: TestInfo, name: string) {
  const dialog = page.getByRole('dialog');
  if (await dialog.isVisible()) {
    await dialog.getByRole('button').first().hover();
    await expect.poll(() => dialog.evaluate((el) => getComputedStyle(el).opacity)).toBe('1');
    await page.mouse.move(0, 0);
  }
  await page.screenshot({ path: info.outputPath(name + '.png'), animations: 'disabled' });
  const geometry = await page
    .locator(
      prototype(info)
        ? '.el-dialog,.el-form-item,.el-drawer,.el-table'
        : '.sn-rule-modal,.sn-rule-modal .ant-form-item,.ant-drawer-section,.sn-rules-page .ant-table',
    )
    .evaluateAll((els) =>
      els.map((el) => ({
        class: el.className,
        rect: el.getBoundingClientRect().toJSON(),
        font: getComputedStyle(el).fontSize,
      })),
    );
  writeFileSync(info.outputPath(name + '.json'), JSON.stringify(geometry, null, 2));
}
function fixture(
  info: TestInfo,
  action: 'begin' | 'clean' | 'seed-catalog' | 'begin-used' | 'clean-used',
) {
  execFileSync(
    resolve('../../sn-center-python/.venv/Scripts/python.exe'),
    [resolve('../tools/testing/parity-rule-fixture.py'), kind(info), action],
    { windowsHide: true },
  );
}
async function save(dialog: Locator, page: Page, expected: string) {
  await dialog.getByRole('button', { name: zh.common.save, exact: true }).click();
  await expect(page.getByText(expected, { exact: true }).last()).toBeVisible();
  await expect(dialog).not.toBeVisible();
}

test('P06 baseline filters, used editing and version identity', async ({ page }, info) => {
  await login(page, info);
  await expect(rows(page, info).locator('td').first()).toHaveText('CMP_MAIN');
  await expect(page.locator('.el-pagination,.ant-pagination')).toHaveCount(0);
  await capture(page, info, 'rules-list');
  const search = page.getByPlaceholder(zh.rules.search, { exact: true });
  await search.fill('FRACTION');
  await search.press('Enter');
  await expect(rows(page, info)).toHaveCount(1);
  await expect(rows(page, info)).toContainText('CMP_FRACTION');
  await search.fill('');
  await search.press('Enter');
  await expect(rows(page, info)).toHaveCount(3);
  const main = rows(page, info).filter({ hasText: 'CMP_MAIN' });
  await main.getByRole('button', { name: zh.common.edit, exact: true }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByText(zh.rules.editUsed, { exact: true })).toBeVisible();
  await expect(field(dialog, info, zh.rules.code)).toHaveCount(0);
  await expect(dialog.getByText(zh.rules.bind, { exact: true })).toHaveCount(0);
  await expect(field(dialog, info, zh.rules.name)).not.toHaveValue('');
  await expect(dialog.getByText('CMP00001', { exact: true })).toBeVisible();
  await capture(page, info, 'rules-used-edit');
  await cancel(dialog);
  await main.getByRole('button', { name: zh.rules.versions, exact: true }).click();
  const drawer = page.locator(prototype(info) ? '.el-drawer' : '.ant-drawer-section');
  await expect(drawer).toContainText('admin');
  await expect(drawer).toContainText('v1');
  await expect(drawer).toContainText(zh.rules.used);
  await expect(drawer).toContainText(/2026-\d\d-\d\d \d\d:\d\d:\d\d/);
  await capture(page, info, 'rules-versions');
});

test('P06 preview bases, Unicode and inline validation recovery', async ({ page }, info) => {
  await login(page, info);
  const dialog = await create(page);
  await expect(field(dialog, info, zh.rules.seqLen)).toHaveValue('5');
  await expect(dialog.getByText('00001', { exact: true })).toBeVisible();
  for (const [base, last, max] of [
    [16, 'FFFFF', '1,048,575'],
    [32, 'VVVVV', '33,554,431'],
    [36, 'ZZZZZ', '60,466,175'],
    [10, '99999', '99,999'],
  ] as const) {
    await dialog
      .locator('label')
      .filter({ has: page.getByRole('radio', { name: String(base), exact: true }) })
      .click();
    await expect(field(dialog, info, zh.rules.charset)).toHaveValue('');
    await expect(dialog.getByText(last, { exact: true })).toBeVisible();
    await expect(dialog.getByText(`${zh.rules.maxSeq} ${max}`, { exact: true })).toBeVisible();
  }
  await field(dialog, info, zh.rules.prefix).fill(' 测试😀 ');
  await field(dialog, info, zh.rules.suffix).fill(' 尾 ');
  await field(dialog, info, zh.rules.seqLen).fill('2');
  await field(dialog, info, zh.rules.charset).fill('零一二三四五六七八九');
  await expect(dialog.getByText('测试😀零一尾', { exact: true })).toBeVisible();
  await field(dialog, info, zh.rules.charset).fill('123');
  await expect(dialog.getByText(errors.RULE_CHARSET_LENGTH, { exact: true })).toBeVisible();
  await expect(page.locator('.el-message--error,.sn-prototype-error-message')).toHaveCount(0);
  await field(dialog, info, zh.rules.charset).fill('0000000000');
  await expect(dialog.getByText(errors.RULE_CHARSET_DUPLICATE, { exact: true })).toBeVisible();
  await field(dialog, info, zh.rules.charset).fill('');
  await field(dialog, info, zh.rules.prefix).fill('X'.repeat(64));
  await expect(dialog.getByText(errors.RULE_SN_TOO_LONG, { exact: true })).toBeVisible();
  await capture(page, info, 'rules-preview-invalid');
  await field(dialog, info, zh.rules.prefix).fill('UI');
  await expect(dialog.getByText('UI01尾', { exact: true })).toBeVisible();
  await capture(page, info, 'rules-preview-valid');
  await cancel(dialog);
  await expect(rows(page, info)).toHaveCount(3);
});

test('P06 create scopes, duplicate errors and unused version updates', async ({ page }, info) => {
  test.setTimeout(100_000);
  await login(page, info);
  fixture(info, 'begin');
  try {
    let dialog = await create(page);
    for (const [code, name, expected] of [
      ['', '', errors.RULE_CODE_INVALID],
      ['CMP_UI_R_GENERAL', '', errors.RULE_NAME_REQUIRED],
    ] as const) {
      await field(dialog, info, zh.rules.code).fill(code);
      await field(dialog, info, zh.rules.name).fill(name);
      await dialog.getByRole('button', { name: zh.common.save, exact: true }).click();
      const message = page.locator(
        prototype(info) ? '.el-message--error' : '.sn-prototype-error-message',
      );
      await expect(message).toContainText(expected);
      await message.locator(prototype(info) ? '.el-message__closeBtn' : 'button').click();
      await expect(message).toHaveCount(0);
    }
    await field(dialog, info, zh.rules.name).fill(' 通用界面测试 ');
    await save(dialog, page, zh.common.saved);
    for (const scope of ['CUSTOMER', 'PI'] as const) {
      dialog = await create(page);
      await field(dialog, info, zh.rules.code).fill(`CMP_UI_R_${scope}`);
      await field(dialog, info, zh.rules.name).fill(`界面测试${scope}`);
      await dialog
        .locator('label')
        .filter({ has: page.getByRole('radio', { name: zh.rules[`scope${scope}`], exact: true }) })
        .click();
      await dialog.getByRole('button', { name: zh.common.save, exact: true }).click();
      const message = page.locator(
        prototype(info) ? '.el-message--error' : '.sn-prototype-error-message',
      );
      await expect(message).toContainText(errors.RULE_BIND_REQUIRED);
      await message.locator(prototype(info) ? '.el-message__closeBtn' : 'button').click();
      await expect(message).toHaveCount(0);
      const input = field(dialog, info, scope === 'PI' ? 'PI' : zh.common.customer);
      await input.fill('CMP_UI_R_BIND');
      if (scope === 'CUSTOMER') {
        await page.getByRole('option', { name: 'CMP_UI_R_BIND', exact: true }).click();
      }
      await save(dialog, page, zh.common.saved);
    }
    await expect(rows(page, info)).toHaveCount(6);
    dialog = await create(page);
    await field(dialog, info, zh.rules.code).fill('CMP_UI_R_PI');
    await field(dialog, info, zh.rules.name).fill('重复');
    await dialog.getByRole('button', { name: zh.common.save, exact: true }).click();
    await expect(page.getByText('规则编码已存在：CMP_UI_R_PI', { exact: true })).toBeVisible();
    await field(dialog, info, zh.rules.code).fill('CMP_UI_R_DUP');
    await dialog.getByRole('button', { name: zh.common.save, exact: true }).click();
    await expect(
      page.getByText('该绑定对象已有规则：CMP_UI_R_GENERAL', { exact: true }),
    ).toBeVisible();
    await cancel(dialog);
    const row = rows(page, info).filter({ hasText: 'CMP_UI_R_PI' });
    for (const action of ['UPDATED', 'RENAMED', 'UNCHANGED'] as const) {
      await row.getByRole('button', { name: zh.common.edit, exact: true }).click();
      dialog = page.getByRole('dialog');
      await expect(dialog.getByText(zh.rules.editUnused, { exact: true })).toBeVisible();
      if (action === 'UPDATED') await field(dialog, info, zh.rules.prefix).fill('UI');
      if (action === 'RENAMED') await field(dialog, info, zh.rules.name).fill('界面改名');
      await save(dialog, page, zh.rules[`result${action}`]);
      await expect(row).toContainText('v1');
    }
    await capture(page, info, 'rules-created');
  } finally {
    fixture(info, 'clean');
  }
});

test('P06 three languages and narrow rule dialog', async ({ page }, info) => {
  test.setTimeout(100_000);
  await login(page, info);
  try {
    for (const [label, catalog] of [
      ['English', en],
      ['Tiếng Việt', vi],
      ['简体中文', zh],
    ] as const) {
      await page
        .locator(prototype(info) ? '.header .lang' : '.prototype-language .ant-select')
        .click();
      const saved = page.waitForResponse(
        (r) =>
          r.request().method() === (prototype(info) ? 'PUT' : 'POST') &&
          r.url().endsWith(prototype(info) ? '/auth/lang' : '/system/auth/language'),
      );
      await page.getByRole('option', { name: label, exact: true }).click();
      expect((await saved).ok()).toBe(true);
      await expect(
        page.getByRole('heading', { name: catalog.rules.title, exact: true }),
      ).toBeVisible();
      await page.getByRole('button', { name: catalog.rules.create, exact: true }).click();
      const dialog = page.getByRole('dialog');
      await expect(dialog.getByText('00001', { exact: true })).toBeVisible();
      await expect(
        dialog.getByText(`${catalog.rules.maxSeq} 99,999`, { exact: true }),
      ).toBeVisible();
      await capture(
        page,
        info,
        `rules-create-${label === 'English' ? 'en' : label === 'Tiếng Việt' ? 'vi' : 'zh'}`,
      );
      await cancel(dialog);
    }
    await page.setViewportSize({ width: 900, height: 900 });
    const dialog = await create(page);
    await expect(dialog.getByText('00001', { exact: true })).toBeVisible();
    await capture(page, info, 'rules-narrow');
    await cancel(dialog);
  } finally {
    const dialog = page.getByRole('dialog');
    if (await dialog.isVisible()) await cancel(dialog);
    const select = page.locator(
      prototype(info) ? '.header .lang' : '.prototype-language .ant-select',
    );
    if (!(await select.innerText()).includes('简体中文')) {
      await select.click();
      await page.getByRole('option', { name: '简体中文', exact: true }).click();
      await page.waitForLoadState('networkidle');
    }
  }
});

test('P06 complete catalog beyond 100 rows and literal wildcard filters', async ({
  page,
}, info) => {
  test.setTimeout(120_000);
  await login(page, info);
  fixture(info, 'begin');
  try {
    // 批量准备也会登录一次，必须计入与界面相同的正式限流预算。
    await paceComparisonLogin(page);
    fixture(info, 'seed-catalog');
    // 相同空筛选按 Enter 也应刷新服务端数据，而不是只更新本地筛选状态。
    const search = page.getByPlaceholder(zh.rules.search, { exact: true });
    await search.press('Enter');
    await expect(rows(page, info)).toHaveCount(104);
    await expect(page.locator('.el-pagination,.ant-pagination')).toHaveCount(0);
    await expect(rows(page, info).last()).toContainText('CMP_UI_R_BULK_100');
    await search.fill('CMP_UI_R_BULK_%');
    await search.press('Enter');
    await expect(rows(page, info)).toHaveCount(101);
    const selector = page.locator(
      prototype(info) ? '.toolbar .el-select' : '.sn-toolbar .ant-select',
    );
    await selector.click();
    await page.getByRole('option', { name: zh.rules.scopeCUSTOMER, exact: true }).click();
    await expect(rows(page, info)).toHaveCount(0);
    await selector.click();
    await page.getByRole('option', { name: zh.rules.scopePI, exact: true }).click();
    await expect(rows(page, info)).toHaveCount(101);
    await search.fill('cmp_ui_r_bulk');
    await search.press('Enter');
    await expect(rows(page, info)).toHaveCount(0);
    await search.fill('CMP_UI_R_BULK_%');
    await search.press('Enter');
    await expect(rows(page, info)).toHaveCount(101);
    await capture(page, info, 'rules-catalog-101');
  } finally {
    fixture(info, 'clean');
  }
});

test('P06 used rule rename keeps v1 and encoding change creates immutable history', async ({
  page,
}, info) => {
  await login(page, info);
  fixture(info, 'begin-used');
  try {
    const row = rows(page, info).filter({ hasText: 'CMP_MAIN' });
    await row.getByRole('button', { name: zh.common.edit, exact: true }).click();
    let dialog = page.getByRole('dialog');
    await field(dialog, info, zh.rules.name).fill('CMP_UI_R_RENAMED');
    await save(dialog, page, zh.rules.resultRENAMED);
    await expect(row).toContainText('v1');
    await expect(row).toContainText(zh.common.yes);
    await row.getByRole('button', { name: zh.common.edit, exact: true }).click();
    dialog = page.getByRole('dialog');
    await field(dialog, info, zh.rules.prefix).fill('CMP_UI_R_USED');
    await save(dialog, page, zh.rules.resultNEW_VERSION);
    await expect(row).toContainText('v2');
    await expect(row).toContainText(zh.common.no);
    await row.getByRole('button', { name: zh.rules.versions, exact: true }).click();
    const drawer = page.locator(prototype(info) ? '.el-drawer' : '.ant-drawer-section');
    const entries = drawer.locator(prototype(info) ? '.el-timeline-item' : '.ant-timeline-item');
    await expect(entries).toHaveCount(2);
    await expect(entries.first()).toContainText('v2');
    await expect(entries.first()).toContainText('CMP_UI_R_USED#####');
    await expect(entries.first()).toContainText('admin');
    await expect(entries.last()).toContainText('v1');
    await expect(entries.last()).toContainText('CMP#####');
    await expect(entries.last()).toContainText(zh.rules.used);
    await capture(page, info, 'rules-used-new-version');
  } finally {
    fixture(info, 'clean-used');
  }
});
