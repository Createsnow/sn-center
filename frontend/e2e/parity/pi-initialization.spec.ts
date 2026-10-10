import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { readFileSync, writeFileSync, renameSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';
import { protectTestSecrets } from '../privacy';
import { paceComparisonLogin } from './login-budget';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import en from '../../src/i18n/prototype/en.json' with { type: 'json' };
import vi from '../../src/i18n/prototype/vi.json' with { type: 'json' };
import errorsZh from '../../src/i18n/prototype/errors.zh-CN.json' with { type: 'json' };
import errorsEn from '../../src/i18n/prototype/errors.en.json' with { type: 'json' };
import errorsVi from '../../src/i18n/prototype/errors.vi.json' with { type: 'json' };

const PIS = ['CMP-UI-INIT-PI', 'CMP-UI-INIT-NONE'] as const;
const languages = [
  ['简体中文', zh, errorsZh, 'zh'],
  ['English', en, errorsEn, 'en'],
  ['Tiếng Việt', vi, errorsVi, 'vi'],
] as const;
const extraFile = resolve('../tools/.runtime/comparison-20261008/extra-orders.json');
const prototype = (info: TestInfo) => info.project.name === 'prototype';
const kind = (info: TestInfo) => (prototype(info) ? 'python' : 'java');
type Facts = {
  items: {
    sn: string;
    pi: string;
    source_pi: string;
    seq_dec: number | null;
    customer: string | null;
    material: string | null;
    factory_code: string;
    status: string;
    source: string;
  }[];
  counters: {
    pi: string;
    last_seq: number;
    generated_qty: number;
    imported_qty: number;
    start_seq: number | null;
    start_locked: number;
  }[];
  history: {
    pi: string;
    start_seq: number | null;
    qty: number;
    max_seq: number | null;
    factory_code: string;
    file_name: string;
    operator: string;
  }[];
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

function fixture(
  info: TestInfo,
  action:
    | 'begin'
    | 'seed-rule'
    | 'seed-sn-conflict'
    | 'seed-sequence-conflict'
    | 'failures'
    | 'observe'
    | 'clean',
) {
  return execFileSync(
    resolve('../../sn-center-python/.venv/Scripts/python.exe'),
    [resolve('../tools/testing/parity-initialization-fixture.py'), kind(info), action],
    { windowsHide: true, encoding: 'utf8' },
  );
}
const facts = (info: TestInfo) => JSON.parse(fixture(info, 'observe')) as Facts;

async function rejectInLanguages(
  page: Page,
  info: TestInfo,
  reason:
    'PI_IMPORT_SN_INVALID' | 'GEN_START_INVALID' | 'PI_IMPORT_DUP_EXISTING' | 'PI_IMPORT_SEQ_DUP',
  params: Record<string, string>,
  name: string,
) {
  for (const [label, words, errors, key] of languages) {
    await language(page, info, label);
    const button = page.getByRole('button', { name: words.piInit.submit, exact: true });
    await button.click();
    await expect(dialog(page, info)).toBeVisible();
    await dialog(page, info)
      .getByRole('button', { name: key === 'zh' ? '确定' : 'OK', exact: true })
      .click();
    const message = errors[reason].replace(/\{\{(\w+)\}\}/g, (_, key: string) => params[key]!);
    await expect(page.getByText(message, { exact: true }).last()).toBeVisible();
    // 新错误须有自己的展示时长，不能因上一语言的提示到期而立即消失。
    await page.waitForTimeout(2_000);
    await expect(page.getByText(message, { exact: true }).last()).toBeVisible();
    await expect(button).not.toHaveClass(/is-loading|ant-btn-loading/);
    await expect(button).toBeEnabled();
    expect(facts(info).history).toEqual([]);
    expect(facts(info).items).toEqual([]);
    expect(facts(info).counters.every((row) => row.last_seq === 0 && !row.start_locked)).toBe(true);
    await capture(page, info, name + '-' + key, message);
  }
  await language(page, info, '简体中文');
}

for (const conflict of ['sn', 'sequence'] as const) {
  test(`P08 ${conflict} conflict in the second import chunk rolls back all rows and allows correction in three languages`, async ({
    page,
  }, info) => {
    test.setTimeout(180_000);
    await setup(page, info);
    try {
      fixture(info, conflict === 'sn' ? 'seed-sn-conflict' : 'seed-sequence-conflict');
      const pi = conflict === 'sn' ? PIS[1] : PIS[0];
      const values = Array.from(
        { length: 1000 },
        (_, i) => `CMP-INIT-CHUNK-${String(i).padStart(4, '0')}`,
      );
      const offending = conflict === 'sn' ? 'UNPARSED' : 'INI00005';
      const input = [...values, offending].join('\n');
      await lookup(page, info, pi);
      await start(page, info).fill('10');
      await sns(page, info).fill(input);
      await rejectInLanguages(
        page,
        info,
        conflict === 'sn' ? 'PI_IMPORT_DUP_EXISTING' : 'PI_IMPORT_SEQ_DUP',
        conflict === 'sn' ? { sn: offending, pi } : { seq: '5', pi },
        conflict + '-conflict',
      );
      await expect(sns(page, info)).toHaveValue(input);
      await expect(start(page, info)).toHaveValue('10');
      if (!prototype(info)) {
        const failed = JSON.parse(fixture(info, 'failures')) as {
          state: string;
          error_code: number;
          error_params: string;
          processed: number;
        }[];
        expect(failed).toHaveLength(3);
        for (const row of failed) {
          expect(row).toMatchObject({ state: 'FAILED', error_code: 1004000015, processed: 1000 });
          expect(JSON.parse(row.error_params)).toMatchObject({
            reason: conflict === 'sn' ? 'PI_IMPORT_DUP_EXISTING' : 'PI_IMPORT_SEQ_DUP',
            pi,
          });
        }
        writeFileSync(info.outputPath('failed-task-facts.json'), JSON.stringify(failed, null, 2));
      }
      await sns(page, info).fill(
        [...values, conflict === 'sn' ? 'EDITED-SN' : 'INI00008'].join('\n'),
      );
      await confirm(page, info, pi, '10', 1001);
      await done(page, info, pi, '10', 1001, conflict === 'sn' ? 0 : 1, 9, [
        '10',
        '1001',
        conflict === 'sn' ? '' : '8',
        '',
        '',
        'admin',
      ]);
      expect(facts(info).items).toHaveLength(1001);
      await capture(page, info, conflict + '-conflict-corrected');
    } finally {
      await clean(page, info);
    }
  });
}

test('P08 rejects a 65 character SN with its line in three languages and accepts the corrected 64 character value', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  await setup(page, info);
  try {
    await lookup(page, info, PIS[1]);
    await start(page, info).fill('10');
    await sns(page, info).fill('UNPARSED\n' + 'L'.repeat(65));
    await rejectInLanguages(page, info, 'PI_IMPORT_SN_INVALID', { line: '2' }, 'invalid-length');
    await expect(start(page, info)).toHaveValue('10');
    await expect(sns(page, info)).toHaveValue('UNPARSED\n' + 'L'.repeat(65));
    await sns(page, info).fill('UNPARSED\n' + 'L'.repeat(64));
    await confirm(page, info, PIS[1], '10', 2);
    await done(page, info, PIS[1], '10', 2, 0, 9, ['10', '2', '', '', '', 'admin']);
    expect(
      facts(info)
        .items.map((row) => row.sn)
        .sort(),
    ).toEqual(['L'.repeat(64), 'UNPARSED']);
    await capture(page, info, 'corrected-length');
  } finally {
    await clean(page, info);
  }
});

test('P08 rejects a start beyond rule capacity in three languages and permits a corrected initialization', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  await setup(page, info);
  try {
    await lookup(page, info, PIS[0]);
    await start(page, info).fill('100000');
    await rejectInLanguages(page, info, 'GEN_START_INVALID', { max: '99999' }, 'invalid-capacity');
    await expect(start(page, info)).toHaveValue('100000');
    await start(page, info).fill('99');
    await confirm(page, info, PIS[0], '99', 0);
    await done(page, info, PIS[0], '99', 0, 0, 98, ['99', '0', '', '', '', 'admin']);
    await capture(page, info, 'corrected-capacity');
  } finally {
    await clean(page, info);
  }
});

test('P08 stale page rejects an already initialized PI and can query the original successful history', async ({
  page,
  context,
}, info) => {
  test.setTimeout(180_000);
  await setup(page, info);
  const second = await context.newPage();
  const errors: string[] = [];
  second.on('pageerror', (error) => errors.push(error.message));
  try {
    await lookup(page, info, PIS[1]);
    await start(page, info).fill('10');
    await second.goto(page.url());
    await lookup(second, info, PIS[1]);
    await start(second, info).fill('21');
    await confirm(page, info, PIS[1], '10', 0);
    await done(page, info, PIS[1], '10', 0, 0, 9, ['10', '0', '', '', '', 'admin']);
    await confirm(second, info, PIS[1], '21', 0);
    await expect(
      second.getByText(errorsZh.PI_INIT_LOCKED.replace('{{pi}}', PIS[1]), { exact: true }).last(),
    ).toBeVisible();
    await expect(start(second, info)).toHaveValue('21');
    await expect(submit(second)).not.toHaveClass(/is-loading|ant-btn-loading/);
    expect(facts(info).history).toHaveLength(1);
    expect(facts(info).counters.find((row) => row.pi === PIS[1])).toMatchObject({
      start_seq: 10,
      last_seq: 9,
      start_locked: 1,
    });
    await capture(second, info, 'stale-locked-rejected');
    await lookup(second, info, PIS[1]);
    await expect(submit(second)).toHaveCount(0);
    await expect(
      second.getByText(zh.piInit.locked.replace('{{start}}', '10'), { exact: true }),
    ).toBeVisible();
    await capture(second, info, 'stale-query-recovered');
    expect(errors).toEqual([]);
  } finally {
    await second.close();
    await clean(page, info);
  }
});

async function setup(page: Page, info: TestInfo) {
  expect(JSON.parse(readFileSync(extraFile, 'utf8'))).toEqual([]);
  fixture(info, 'begin');
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
  await sync(page, true);
  await page.getByRole('menuitem', { name: zh.nav.piInit, exact: true }).click();
}
async function sync(page: Page, include: boolean) {
  const rows = include
    ? [
        {
          'F_ora_kh.FNumber': 'CMP-INIT-CUSTOMER',
          FBillNo: 'CMP-UI-INIT-MO',
          F_HX_PO: 'CMP-INIT-PO',
          F_HX_PI: PIS[0],
          'FMaterialId.FNumber': 'CMP-INIT-MAT',
          'FMaterialId.FName': 'CMP-INIT-MAT',
          FQty: '13',
          FStatus: '1',
          FSaleOrderNo: 'CMP-INIT-SO',
          'FPrdOrgId.FName': '比较测试甲厂',
          FID: '9007199254781001',
          FTreeEntity_FEntryId: '11',
          FPrdOrgId: '101',
          'FPrdOrgId.FNumber': 'CMP-A',
        },
      ]
    : [];
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
async function clean(page: Page, info: TestInfo) {
  await page.setViewportSize({ width: 1440, height: 960 });
  await language(page, info, '简体中文');
  await page.goto('/orders');
  await sync(page, false);
  fixture(info, 'clean');
  writeFileSync(
    info.outputPath('baseline-preserved.json'),
    readFileSync(
      resolve(`../tools/.runtime/comparison-20261008/initialization-clean-${kind(info)}.json`),
    ),
  );
}
function field(page: Page, info: TestInfo, label: string) {
  return page.locator(prototype(info) ? '.el-form-item' : '.ant-form-item').filter({
    has: page
      .locator('label')
      .filter({ hasText: new RegExp('^' + label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '$') }),
  });
}
const start = (page: Page, info: TestInfo) => field(page, info, zh.generate.start).locator('input');
const sns = (page: Page, info: TestInfo) => field(page, info, zh.piInit.snList).locator('textarea');
const submit = (page: Page) => page.getByRole('button', { name: zh.piInit.submit, exact: true });
const dialog = (page: Page, info: TestInfo) =>
  page.locator(prototype(info) ? '.el-message-box' : '.ant-modal-confirm');
async function lookup(page: Page, info: TestInfo, pi: string) {
  await page.getByPlaceholder(zh.piInit.piPlaceholder, { exact: true }).fill(pi);
  await page.getByRole('button', { name: zh.common.search, exact: true }).click();
  await expect(
    page.locator(prototype(info) ? '.el-descriptions b' : '.ant-descriptions b'),
  ).toHaveText(pi);
  await expect(page.getByRole('button', { name: zh.common.search, exact: true })).toBeEnabled();
}
async function factory(page: Page, info: TestInfo) {
  await field(page, info, zh.piInit.factory)
    .locator(prototype(info) ? '.el-select' : '.ant-select')
    .click();
  await page.getByRole('option', { name: '比较测试甲厂（CMP-A）', exact: true }).click();
}
async function confirm(page: Page, info: TestInfo, pi: string, initial: string, count: number) {
  await submit(page).click();
  await expect(dialog(page, info)).toContainText(
    zh.piInit.confirm
      .replace('{{pi}}', pi)
      .replace('{{start}}', initial)
      .replace('{{n}}', String(count)),
  );
  await dialog(page, info)
    .getByRole('button', { name: /^(确定|确认)$/ })
    .click();
  await expect(dialog(page, info)).toBeHidden();
}
async function done(
  page: Page,
  info: TestInfo,
  pi: string,
  initial: string,
  qty: number,
  decoded: number,
  last: number,
  history: string[],
) {
  await expect(
    page
      .getByText(
        zh.piInit.done
          .replace('{{n}}', String(qty))
          .replace('{{decoded}}', String(decoded))
          .replace('{{last}}', String(last)),
        { exact: true },
      )
      .last(),
  ).toBeVisible();
  await expect(
    page.getByText(zh.piInit.locked.replace('{{start}}', initial), { exact: true }),
  ).toBeVisible();
  await expect(submit(page)).toHaveCount(0);
  const rows = page.locator(prototype(info) ? '.el-table__row' : '.sn-init-history .ant-table-row');
  await expect(rows).toHaveCount(1);
  const cells = rows.first().locator('td');
  for (const [index, value] of history.entries()) await expect(cells.nth(index)).toHaveText(value);
  await expect(cells.nth(6)).toHaveText(/\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/);
  const state = facts(info);
  expect(state.counters.find((row) => row.pi === pi)).toMatchObject({
    last_seq: last,
    generated_qty: 0,
    imported_qty: qty,
    start_locked: 1,
  });
  expect(state.history.find((row) => row.pi === pi)).toMatchObject({ qty, operator: 'admin' });
}
async function language(page: Page, info: TestInfo, label: string) {
  const select = page.locator(
    prototype(info) ? '.header .lang' : '.prototype-language .ant-select',
  );
  await expect(select).toBeVisible();
  if ((await select.innerText()).includes(label)) return;
  await select.click();
  const saved = page.waitForResponse(
    (response) =>
      response.request().method() === (prototype(info) ? 'PUT' : 'POST') &&
      response.url().endsWith(prototype(info) ? '/auth/lang' : '/system/auth/language'),
  );
  await page.getByRole('option', { name: label, exact: true }).click();
  expect((await saved).ok()).toBe(true);
}
async function capture(page: Page, info: TestInfo, name: string, visibleMessage?: string) {
  // 原型在遮罩父节点做入场动画；等待整个文档的有限动画，避免保存透明弹窗。
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
  if (await dialog(page, info).isVisible()) {
    const motion = prototype(info) ? page.locator('.el-overlay-message-box') : dialog(page, info);
    await expect(motion).toHaveCSS('opacity', '1');
    await expect(motion).toHaveCSS('transform', 'none');
    await expect(motion).toHaveCSS('animation-name', 'none');
  }
  const form = page.locator(prototype(info) ? '.field-stack' : '.sn-init-form');
  if ((await form.isVisible()) && !(await dialog(page, info).isVisible())) {
    await form.locator('input').first().scrollIntoViewIfNeeded();
  }
  if (visibleMessage)
    await expect(page.getByText(visibleMessage, { exact: true }).last()).toBeVisible();
  await page.screenshot({
    path: info.outputPath(name + '.png'),
    fullPage: true,
    animations: 'allow',
  });
  if ((await form.isVisible()) && !(await dialog(page, info).isVisible())) {
    await page
      .getByRole('button', { name: /^(执行初始化|Initialize|Khởi tạo)$/ })
      .last()
      .scrollIntoViewIfNeeded();
    await page.screenshot({ path: info.outputPath(name + '-bottom.png'), animations: 'disabled' });
    await page.getByRole('heading').first().scrollIntoViewIfNeeded();
  }
  writeFileSync(info.outputPath(name + '-facts.json'), JSON.stringify(facts(info), null, 2));
}

test('P08 query retains displayed PI until lookup, three languages and reload resets form', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  await setup(page, info);
  try {
    await expect(submit(page)).toHaveCount(0);
    await capture(page, info, 'empty');
    await lookup(page, info, PIS[0]);
    await expect(page.getByText('CMP_UI_INIT_RULE v1 · INI00001', { exact: true })).toBeVisible();
    await expect(field(page, info, zh.piInit.customer).locator('input')).toHaveAttribute(
      'placeholder',
      'CMP-INIT-CUSTOMER',
    );
    await start(page, info).fill('10');
    await page.getByPlaceholder(zh.piInit.piPlaceholder).fill(PIS[1]);
    await expect(
      page.locator(prototype(info) ? '.el-descriptions b' : '.ant-descriptions b'),
    ).toHaveText(PIS[0]);
    await page.getByPlaceholder(zh.piInit.piPlaceholder).fill('');
    await page.getByRole('button', { name: zh.common.search, exact: true }).click();
    await expect(start(page, info)).toHaveValue('10');
    await lookup(page, info, PIS[1]);
    await expect(page.getByText(zh.piInit.noRule, { exact: true })).toBeVisible();
    await expect(start(page, info)).toHaveValue('10');
    for (const [label, words, key] of [
      ['English', en, 'en'],
      ['Tiếng Việt', vi, 'vi'],
      ['简体中文', zh, 'zh'],
    ] as const) {
      await language(page, info, label);
      await expect(
        page.getByRole('heading', { name: words.piInit.title, exact: true }),
      ).toBeVisible();
      await expect(page.getByText(words.piInit.noRule, { exact: true })).toBeVisible();
      await capture(page, info, 'form-' + key);
      await page.getByRole('button', { name: words.piInit.submit, exact: true }).click();
      await expect(dialog(page, info)).toBeVisible();
      await expect(
        dialog(page, info).getByRole('button', { name: key === 'zh' ? '确定' : 'OK', exact: true }),
      ).toBeVisible();
      await expect(dialog(page, info)).toContainText(
        words.piInit.confirm
          .replace('{{pi}}', PIS[1])
          .replace('{{start}}', '10')
          .replace('{{n}}', '0'),
      );
      await capture(page, info, 'confirmation-' + key);
      await dialog(page, info)
        .getByRole('button', { name: words.common.cancel, exact: true })
        .click();
    }
    await page.setViewportSize({ width: 900, height: 1000 });
    await capture(page, info, 'form-narrow');
    await page.reload();
    await expect(page.getByPlaceholder(zh.piInit.piPlaceholder)).toHaveValue('');
    await expect(submit(page)).toHaveCount(0);
    expect(facts(info).items).toEqual([]);
    expect(facts(info).history).toEqual([]);
  } finally {
    await clean(page, info);
  }
});

test('P08 pure start validates integers and duplicates, cancellation is read only, initialization locks once', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  await setup(page, info);
  try {
    await lookup(page, info, PIS[1]);
    await expect(submit(page)).toBeDisabled();
    for (const invalid of ['0', '1.5']) {
      await start(page, info).fill(invalid);
      await submit(page).click();
      await expect(page.getByText(zh.generate.startInvalid, { exact: true }).last()).toBeVisible();
      await expect(dialog(page, info)).toBeHidden();
    }
    await start(page, info).fill('');
    await sns(page, info).fill('UNPARSED\nINI00005\nUNPARSED');
    await expect(
      page.getByText(zh.piInit.parsed.replace('{{n}}', '2'), { exact: true }),
    ).toBeVisible();
    await expect(
      page.getByText(zh.piInit.dups.replace('{{n}}', '1'), { exact: true }),
    ).toBeVisible();
    await expect(submit(page)).toBeDisabled();
    await capture(page, info, 'duplicate-list');
    await sns(page, info).fill('');
    await start(page, info).fill('1e1');
    await submit(page).click();
    await expect(dialog(page, info)).toBeVisible();
    await expect(dialog(page, info)).toContainText('起始号 10');
    await capture(page, info, 'start-confirmation');
    await dialog(page, info).getByRole('button', { name: zh.common.cancel, exact: true }).click();
    expect(facts(info).history).toEqual([]);
    expect(facts(info).counters.every((row) => row.last_seq === 0 && !row.start_locked)).toBe(true);
    await confirm(page, info, PIS[1], '10', 0);
    await done(page, info, PIS[1], '10', 0, 0, 9, ['10', '0', '', '', '', 'admin']);
    await capture(page, info, 'pure-start-complete');
    await lookup(page, info, PIS[0]);
    await expect(start(page, info)).toHaveValue('');
    await expect(sns(page, info)).toHaveValue('');
    await page.reload();
    await lookup(page, info, PIS[1]);
    await expect(
      page.getByText(zh.piInit.locked.replace('{{start}}', '10'), { exact: true }),
    ).toBeVisible();
  } finally {
    await clean(page, info);
  }
});

test('P08 mixed history preserves zero and undecodable SN, start dominates decoded maximum and snapshot customer applies', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  await setup(page, info);
  try {
    await lookup(page, info, PIS[0]);
    await start(page, info).fill('20');
    await sns(page, info).fill('INI00005\nUNPARSED\nINI00000');
    await factory(page, info);
    await field(page, info, zh.piInit.material).locator('input').fill('CMP-INIT-MAT');
    await capture(page, info, 'mixed-before');
    await confirm(page, info, PIS[0], '20', 3);
    await done(page, info, PIS[0], '20', 3, 2, 19, ['20', '3', '5', 'CMP-A', '', 'admin']);
    expect(facts(info).items).toEqual(
      ['INI00000', 'INI00005', 'UNPARSED'].map((sn, index) => ({
        sn,
        pi: PIS[0],
        source_pi: PIS[0],
        seq_dec: [0, 5, null][index],
        customer: 'CMP-INIT-CUSTOMER',
        material: 'CMP-INIT-MAT',
        factory_code: 'CMP-A',
        status: 'PRINTED',
        source: 'IMPORT',
      })),
    );
    await capture(page, info, 'mixed-complete');
  } finally {
    await clean(page, info);
  }
});

test('P08 CSV keeps literal quotes and filename after textarea editing without a decoding rule', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  await setup(page, info);
  try {
    await lookup(page, info, PIS[1]);
    const text = `SN,备注\n'KEEP',x\n"OPEN,x\nTAIL",x\n"RAW-CSV",x`;
    await page
      .locator('input[type=file]')
      .setInputFiles({ name: 'history.csv', mimeType: 'text/csv', buffer: Buffer.from(text) });
    await expect(sns(page, info)).toHaveValue(text);
    await expect(
      page.getByText(zh.piInit.parsed.replace('{{n}}', '4'), { exact: true }),
    ).toBeVisible();
    await sns(page, info).fill(text + '\nEDITED-SN');
    await field(page, info, zh.piInit.customer).locator('input').fill('CMP-CSV');
    await field(page, info, zh.piInit.material).locator('input').fill('CMP-CSV-MAT');
    await confirm(page, info, PIS[1], '—', 5);
    await done(page, info, PIS[1], '—', 5, 0, 0, ['', '5', '', '', 'history.csv', 'admin']);
    const items = facts(info).items;
    expect(items.map((row) => row.sn).sort()).toEqual(
      ["'KEEP'", '"OPEN', 'TAIL"', 'RAW-CSV', 'EDITED-SN'].sort(),
    );
    for (const row of items)
      expect(row).toMatchObject({
        pi: PIS[1],
        source_pi: PIS[1],
        seq_dec: null,
        customer: 'CMP-CSV',
        material: 'CMP-CSV-MAT',
        factory_code: '',
        status: 'PRINTED',
        source: 'IMPORT',
      });
    await capture(page, info, 'csv-complete');
  } finally {
    await clean(page, info);
  }
});

test('P08 import only takes decoded maximum and keeps optional metadata for the next PI', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  await setup(page, info);
  try {
    await lookup(page, info, PIS[0]);
    await sns(page, info).fill('INI00008\nUNPARSED');
    await factory(page, info);
    await field(page, info, zh.piInit.customer).locator('input').fill('CMP-OVERRIDE');
    await field(page, info, zh.piInit.material).locator('input').fill('CMP-INIT-MAT');
    await confirm(page, info, PIS[0], '—', 2);
    await done(page, info, PIS[0], '—', 2, 1, 8, ['', '2', '8', 'CMP-A', '', 'admin']);
    await capture(page, info, 'import-only-complete');
    await lookup(page, info, PIS[1]);
    await expect(start(page, info)).toHaveValue('');
    await expect(sns(page, info)).toHaveValue('');
    await expect(field(page, info, zh.piInit.customer).locator('input')).toHaveValue(
      'CMP-OVERRIDE',
    );
    await expect(field(page, info, zh.piInit.material).locator('input')).toHaveValue(
      'CMP-INIT-MAT',
    );
    await expect(field(page, info, zh.piInit.factory)).toContainText('比较测试甲厂（CMP-A）');
    await start(page, info).fill('21');
    await confirm(page, info, PIS[1], '21', 0);
    await done(page, info, PIS[1], '21', 0, 0, 20, ['21', '0', '', 'CMP-A', '', 'admin']);
    expect(facts(info).history).toHaveLength(2);
    for (const row of facts(info).items)
      expect(row).toMatchObject({
        customer: 'CMP-OVERRIDE',
        material: 'CMP-INIT-MAT',
        factory_code: 'CMP-A',
      });
    await capture(page, info, 'second-pi-complete');
  } finally {
    await clean(page, info);
  }
});

test('P08 previously generated PI stays locked without fabricated initialization history', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  await setup(page, info);
  try {
    await lookup(page, info, 'CMP-PI-001');
    await expect(submit(page)).toHaveCount(0);
    await expect(page.getByText(zh.piInit.history, { exact: true })).toHaveCount(0);
    for (const [label, words, key] of [
      ['English', en, 'en'],
      ['Tiếng Việt', vi, 'vi'],
      ['简体中文', zh, 'zh'],
    ] as const) {
      await language(page, info, label);
      await expect(
        page.getByText(words.piInit.locked.replace('{{start}}', '—'), { exact: true }),
      ).toBeVisible();
      await capture(page, info, 'generated-locked-' + key);
    }
    expect(facts(info).items).toEqual([]);
  } finally {
    await clean(page, info);
  }
});

for (const failedRead of ['history', 'status'] as const) {
  test(`P08 committed initialization survives failed ${failedRead} reading and manual query without another submission`, async ({
    page,
  }, info) => {
    test.setTimeout(180_000);
    await setup(page, info);
    const path = prototype(info) ? '/api/pi-init' : '/api/v1/sn/pi-initializations';
    let denyResult = false,
      failures = 0,
      submissions = 0;
    await page.route('**' + path + '**', async (route) => {
      const request = route.request();
      const url = new URL(request.url());
      if (request.method() === 'POST' && url.pathname === path) submissions++;
      if (
        denyResult &&
        request.method() === 'GET' &&
        url.pathname === (prototype(info) || failedRead === 'status' ? path : path + '/history')
      ) {
        failures++;
        await route.abort('failed');
        return;
      }
      await route.continue();
    });
    try {
      await lookup(page, info, PIS[1]);
      await start(page, info).fill('10');
      denyResult = true;
      await confirm(page, info, PIS[1], '10', 0);
      await expect.poll(() => failures).toBeGreaterThan(0);
      await expect.poll(() => facts(info).history.length).toBe(1);
      await expect(
        page
          .getByText('Network Error', {
            exact: true,
          })
          .last(),
      ).toBeVisible();
      await expect(submit(page)).toBeDisabled();
      await capture(page, info, 'result-read-failed');
      denyResult = false;
      await page.getByRole('button', { name: zh.common.search, exact: true }).click();
      await expect(
        page.getByText(zh.piInit.locked.replace('{{start}}', '10'), { exact: true }),
      ).toBeVisible();
      await expect(page.getByText(zh.piInit.history, { exact: true })).toBeVisible();
      expect(facts(info).history).toHaveLength(1);
      expect(submissions).toBe(1);
      if (!prototype(info))
        expect(
          await page.evaluate(() =>
            Object.keys(localStorage).filter(
              (key) => key.startsWith('adtek-sn-operation:') && key.endsWith(':PI_INIT'),
            ),
          ),
        ).toEqual([]);
      await capture(page, info, 'result-query-recovered');
    } finally {
      denyResult = false;
      await page.unroute('**' + path + '**');
      await clean(page, info);
    }
  });
}

for (const recovery of ['query', 'reload'] as const) {
  test(`P08 committed initialization with a lost response recovers by ${recovery} without another submission`, async ({
    page,
  }, info) => {
    test.setTimeout(180_000);
    await setup(page, info);
    const path = prototype(info) ? '/api/pi-init' : '/api/v1/sn/pi-initializations';
    let submissions = 0,
      lost = 0;
    await page.route('**' + path, async (route) => {
      if (route.request().method() !== 'POST') return route.continue();
      submissions++;
      const response = await route.fetch();
      expect(response.ok()).toBe(true);
      // 真正写入比较库后才丢弃响应，不能用伪造成功替代事务检查。
      await expect.poll(() => facts(info).history.length).toBe(1);
      lost++;
      await route.abort('failed');
    });
    try {
      await lookup(page, info, PIS[1]);
      await start(page, info).fill('10');
      await sns(page, info).fill('UNPARSED');
      await confirm(page, info, PIS[1], '10', 1);
      await expect.poll(() => lost).toBe(1);
      await expect(page.getByText('Network Error', { exact: true }).last()).toBeVisible();
      if (recovery === 'reload') {
        await page.reload();
        await expect(page.getByPlaceholder(zh.piInit.piPlaceholder)).toHaveValue('');
        await expect(submit(page)).toHaveCount(0);
      }
      await lookup(page, info, PIS[1]);
      await expect(
        page.getByText(zh.piInit.locked.replace('{{start}}', '10'), { exact: true }),
      ).toBeVisible();
      await expect(page.getByText(zh.piInit.history, { exact: true })).toBeVisible();
      expect(facts(info).history).toHaveLength(1);
      expect(facts(info).items).toHaveLength(1);
      expect(submissions).toBe(1);
      if (!prototype(info))
        await expect
          .poll(() =>
            page.evaluate(() =>
              Object.keys(localStorage).filter(
                (key) => key.startsWith('adtek-sn-operation:') && key.endsWith(':PI_INIT'),
              ),
            ),
          )
          .toEqual([]);
      await capture(page, info, 'response-lost-' + recovery);
    } finally {
      await page.unroute('**' + path);
      await clean(page, info);
    }
  });
}

test('P08 request lost before delivery can be confirmed again with the same original payload', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  await setup(page, info);
  const path = prototype(info) ? '/api/pi-init' : '/api/v1/sn/pi-initializations';
  const attempts: unknown[] = [];
  let forwarded = 0;
  await page.route('**' + path, async (route) => {
    if (route.request().method() !== 'POST') return route.continue();
    attempts.push(route.request().postDataJSON());
    if (attempts.length === 1) return route.abort('failed');
    forwarded++;
    await route.continue();
  });
  try {
    await lookup(page, info, PIS[1]);
    await start(page, info).fill('10');
    await sns(page, info).fill('UNPARSED');
    await confirm(page, info, PIS[1], '10', 1);
    await expect(page.getByText('Network Error', { exact: true }).last()).toBeVisible();
    expect(facts(info).history).toEqual([]);
    expect(facts(info).items).toEqual([]);
    await expect(submit(page)).not.toHaveClass(/is-loading|ant-btn-loading/);
    await expect(submit(page)).toBeEnabled();
    await expect(start(page, info)).toHaveValue('10');
    await expect(sns(page, info)).toHaveValue('UNPARSED');
    await capture(page, info, 'delivery-failed');
    await confirm(page, info, PIS[1], '10', 1);
    await done(page, info, PIS[1], '10', 1, 0, 9, ['10', '1', '', '', '', 'admin']);
    expect(attempts).toHaveLength(2);
    expect(attempts[1]).toEqual(attempts[0]);
    expect(forwarded).toBe(1);
    expect(facts(info).items).toHaveLength(1);
    await capture(page, info, 'delivery-recovered');
  } finally {
    await page.unroute('**' + path);
    await clean(page, info);
  }
});

test('P08 failed lookup preserves the displayed PI and edited form until a successful query', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  await setup(page, info);
  const path = prototype(info) ? '/api/pi-init' : '/api/v1/sn/pi-initializations';
  let denyRead = false,
    failures = 0;
  await page.route('**' + path + '?**', async (route) => {
    if (denyRead && route.request().method() === 'GET') {
      failures++;
      return route.abort('failed');
    }
    await route.continue();
  });
  try {
    await lookup(page, info, PIS[0]);
    await start(page, info).fill('10');
    await sns(page, info).fill('UNPARSED');
    await field(page, info, zh.piInit.customer).locator('input').fill('CMP-KEPT');
    await page.getByPlaceholder(zh.piInit.piPlaceholder).fill(PIS[1]);
    denyRead = true;
    await page.getByRole('button', { name: zh.common.search, exact: true }).click();
    await expect.poll(() => failures).toBe(1);
    await expect(page.getByText('Network Error', { exact: true }).last()).toBeVisible();
    await expect(
      page.locator(prototype(info) ? '.el-descriptions b' : '.ant-descriptions b'),
    ).toHaveText(PIS[0]);
    await expect(start(page, info)).toHaveValue('10');
    await expect(sns(page, info)).toHaveValue('UNPARSED');
    await expect(field(page, info, zh.piInit.customer).locator('input')).toHaveValue('CMP-KEPT');
    await capture(page, info, 'lookup-failed');
    denyRead = false;
    await lookup(page, info, PIS[1]);
    await expect(start(page, info)).toHaveValue('10');
    await expect(sns(page, info)).toHaveValue('UNPARSED');
    expect(facts(info).items).toEqual([]);
    expect(facts(info).history).toEqual([]);
    await capture(page, info, 'lookup-recovered');
  } finally {
    denyRead = false;
    await page.unroute('**' + path + '?**');
    await clean(page, info);
  }
});
