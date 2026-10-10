import { expect, test, type Locator, type Page, type TestInfo } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import { kind, source, login } from './query-support';

const python = resolve('../../sn-center-python/.venv/Scripts/python.exe');
const tag = process.env.ADTEK_AUDIT_RUN;
const usedTag = `${tag}77`;
const describe = (prefix: string) => `prefix=${prefix} suffix= base=10 len=5 charset=0123456789`;
const rowSelector = (info: TestInfo) => (source(info) ? '.el-table__row' : '.ant-table-row');
const fixture = (info: TestInfo, action: string) =>
  execFileSync(
    python,
    [
      '-X',
      'utf8',
      resolve('../tools/testing/parity-rule-fixture.py'),
      kind(info),
      action,
      '--used-prefix',
      `CMP_UI_R_USED_${usedTag}`,
      '--used-name',
      `CMP_UI_R_RENAMED_${usedTag}`,
    ],
    { windowsHide: true, encoding: 'utf8' },
  );
function field(dialog: Locator, info: TestInfo, label: string) {
  return dialog
    .locator(source(info) ? '.el-form-item' : '.ant-form-item')
    .filter({ has: dialog.page().locator('label', { hasText: new RegExp(`^\\s*${label}\\s*$`) }) })
    .locator('input')
    .first();
}
async function save(page: Page, result: string) {
  await page.getByRole('dialog').getByRole('button', { name: zh.common.save, exact: true }).click();
  await expect(page.getByText(result, { exact: true }).last()).toBeVisible();
  await expect(page.getByRole('dialog')).not.toBeVisible();
}
async function edit(page: Page, info: TestInfo, code: string, prefix?: string, name?: string) {
  await page
    .locator(rowSelector(info))
    .filter({ hasText: code })
    .getByRole('button', { name: zh.common.edit, exact: true })
    .click();
  const dialog = page.getByRole('dialog');
  if (prefix !== undefined) await field(dialog, info, zh.rules.prefix).fill(prefix);
  if (name !== undefined) await field(dialog, info, zh.rules.name).fill(name);
}
async function inspect(
  page: Page,
  info: TestInfo,
  keyword: string,
  expected: { action: string; detail: string; pi: string | null }[],
) {
  await page.getByRole('menuitem', { name: zh.nav.audit, exact: true }).click();
  await expect(page.getByRole('heading', { name: zh.audit.title, exact: true })).toBeVisible();
  await page.getByPlaceholder(zh.audit.keyword, { exact: true }).fill(keyword);
  const ready = page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      new URL(r.url()).pathname === (source(info) ? '/api/audits' : '/api/v1/sn/audits/page') &&
      new URL(r.url()).searchParams.get('keyword') === keyword,
  );
  await page.getByRole('button', { name: zh.common.search, exact: true }).click();
  const response = await ready;
  expect(response.ok()).toBe(true);
  const body = (await response.json()) as {
    data?: { items: Record<string, unknown>[] };
    items: Record<string, unknown>[];
  };
  const items = source(info) ? body.items : body.data!.items;
  writeFileSync(info.outputPath('actual-records.json'), JSON.stringify(items, null, 2));
  const rows = page.locator(rowSelector(info));
  await expect(rows).toHaveCount(expected.length);
  const action = (value: unknown) =>
    value === 'sn:rule:create' ? 'RULE_CREATE' : value === 'sn:rule:update' ? 'RULE_UPDATE' : value;
  expect(
    items.map((r) => ({
      action: action(r.action),
      detail: r.detail,
      pi: r[source(info) ? 'pi_no' : 'pi'],
    })),
  ).toEqual(expected);
  for (const item of items) {
    expect(item.operator).toBe('admin');
    expect(item.source).toBe('PAGE');
    expect(item.result).toBe(source(info) ? 'OK' : 'SUCCESS');
    expect(item[source(info) ? 'qty' : 'quantity']).toBeNull();
  }
  for (let index = 0; index < expected.length; index++) {
    await rows
      .nth(index)
      .locator(source(info) ? '.el-table__expand-icon' : '.sn-audit-expand')
      .click();
    await expect(page.getByText(expected[index]!.detail, { exact: true })).toBeVisible();
    await page.mouse.move(0, 0);
    await page.screenshot({ path: info.outputPath(`detail-${index}.png`), animations: 'disabled' });
    await rows
      .nth(index)
      .locator(source(info) ? '.el-table__expand-icon' : '.sn-audit-expand')
      .click();
  }
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: zh.query.exportXlsx, exact: true }).click();
  const file = await download;
  expect(file.suggestedFilename()).toBe('audit_export.xlsx');
  await file.saveAs(info.outputPath('rules.xlsx'));
  const parsed = JSON.parse(
    execFileSync(
      python,
      [
        '-X',
        'utf8',
        resolve('../tools/testing/parity-query-file.py'),
        info.outputPath('rules.xlsx'),
      ],
      { windowsHide: true, encoding: 'utf8' },
    ),
  ) as { raw: unknown[][]; types: string[][] };
  writeFileSync(info.outputPath('rules-file.json'), JSON.stringify(parsed, null, 2));
  expect(parsed.raw.slice(1).map((r) => ({ action: r[5], detail: r[23], pi: r[8] }))).toEqual(
    expected,
  );
  for (const row of parsed.raw.slice(1)) expect(row[14]).toBeNull();
}
for (const used of [false, true]) {
  test(`P12 real rule audits: ${used ? 'used version and rename' : 'create edit rename combined and no-op'}`, async ({
    page,
  }, info) => {
    test.setTimeout(150_000);
    page.setDefaultTimeout(15_000);
    expect(tag).toMatch(/^[0-9]{8,14}$/);
    fixture(info, used ? 'begin-used' : 'begin');
    try {
      await login(
        page,
        'admin',
        readFileSync(
          resolve(`../deploy/.secrets/comparison-20261008/${kind(info)}-admin-password`),
          'utf8',
        ).trim(),
      );
      await page.getByRole('menuitem', { name: zh.nav.rules, exact: true }).click();
      await expect(page.getByRole('heading', { name: zh.rules.title, exact: true })).toBeVisible();
      if (used) {
        const prefix = `CMP_UI_R_USED_${usedTag}`,
          name = `CMP_UI_R_RENAMED_${usedTag}`;
        await edit(page, info, 'CMP_MAIN', prefix, name);
        await save(page, zh.rules.resultNEW_VERSION);
        await edit(page, info, 'CMP_MAIN');
        await save(page, zh.rules.resultUNCHANGED);
        await inspect(page, info, usedTag, [
          { action: 'RULE_RENAME', detail: `CMP_MAIN CMP_MAIN -> ${name}`, pi: null },
          {
            action: 'RULE_VERSION',
            detail: `CMP_MAIN v2 ${describe(prefix)} (was ${describe('CMP')})`,
            pi: null,
          },
        ]);
      } else {
        const code = `CMP_UI_R_AUDIT_${tag}`;
        await page.getByRole('button', { name: zh.rules.create, exact: true }).click();
        const dialog = page.getByRole('dialog');
        await field(dialog, info, zh.rules.code).fill(code);
        await field(dialog, info, zh.rules.name).fill('原规则');
        await dialog.getByText(zh.rules.scopePI, { exact: true }).click();
        await field(dialog, info, 'PI').fill(code);
        await save(page, zh.common.saved);
        await edit(page, info, code, 'A');
        await save(page, zh.rules.resultUPDATED);
        await edit(page, info, code, undefined, '改名规则');
        await save(page, zh.rules.resultRENAMED);
        await edit(page, info, code, 'B', '组合修改');
        await save(page, zh.rules.resultUPDATED);
        await edit(page, info, code);
        await save(page, zh.rules.resultUNCHANGED);
        await inspect(page, info, code, [
          { action: 'RULE_RENAME', detail: `${code} 改名规则 -> 组合修改`, pi: null },
          {
            action: 'RULE_UPDATE',
            detail: `${code} v1 ${describe('B')} (was ${describe('A')})`,
            pi: null,
          },
          { action: 'RULE_RENAME', detail: `${code} 原规则 -> 改名规则`, pi: null },
          {
            action: 'RULE_UPDATE',
            detail: `${code} v1 ${describe('A')} (was ${describe('')})`,
            pi: null,
          },
          { action: 'RULE_CREATE', detail: `${code} v1 ${describe('')}`, pi: code },
        ]);
      }
    } finally {
      fixture(info, used ? 'clean-used' : 'clean');
    }
  });
}
