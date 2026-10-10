import { expect, type Download, type Page, type TestInfo } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { renameSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { protectTestSecrets } from '../privacy';
import { paceComparisonLogin } from './login-budget';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };

const run = resolve('../tools/.runtime/comparison-20261008');
const python = resolve('../../sn-center-python/.venv/Scripts/python.exe');
const PIS = ['CMP-UI-QUERY-SCOPE-PI-A', 'CMP-UI-QUERY-SCOPE-PI-B'];
const BILLS = ['CMP-UI-QUERY-SCOPE-MO-A', 'CMP-UI-QUERY-SCOPE-MO-B'];
const customer = 'CMP-QUERY-SCOPE-CUSTOMER';
const source = (info: TestInfo) => info.project.name === 'prototype';
const kind = (info: TestInfo) => (source(info) ? 'python' : 'java');
const rows = (page: Page, info: TestInfo) =>
  page.locator(source(info) ? '.el-table__row' : '.ant-table-row');
const sns = (letter: string, ...seq: number[]) =>
  seq.map((n) => `Q29${letter}${String(n).padStart(5, '0')}`);

function fixture(info: TestInfo, action: string) {
  return execFileSync(
    python,
    [
      '-X',
      'utf8',
      resolve('../tools/testing/parity-acquire-fixture.py'),
      kind(info),
      action,
      '--profile',
      'query-scope',
    ],
    { windowsHide: true, encoding: 'utf8' },
  );
}

async function login(page: Page, user: string, password: string) {
  protectTestSecrets(password);
  await page.goto('/login');
  await paceComparisonLogin(page);
  await page.locator('input[autocomplete="username"]').fill(user);
  await page.locator('input[autocomplete="current-password"]').fill(password);
  await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
  await expect(page.getByRole('menuitem', { name: zh.nav.query, exact: true })).toBeVisible();
}

async function language(page: Page, info: TestInfo, label: string, exportTitle: string) {
  const picker = page.locator(source(info) ? '.header .lang' : '.prototype-language .ant-select');
  if (!(await picker.innerText()).includes(label)) {
    await picker.click();
    const saved = page.waitForResponse(
      (r) =>
        r.request().method() === (source(info) ? 'PUT' : 'POST') &&
        r.url().endsWith(source(info) ? '/auth/lang' : '/system/auth/language'),
    );
    await page.getByRole('option', { name: label, exact: true }).click();
    expect((await saved).ok()).toBe(true);
  }
  await expect(page.getByRole('button', { name: exportTitle, exact: true })).toBeVisible();
}

async function sync(page: Page, include: boolean) {
  const entries = include
    ? PIS.flatMap((pi, index) =>
        [0, 1].map((line) => ({
          'F_ora_kh.FNumber': customer,
          FBillNo: BILLS[index],
          F_HX_PO: 'CMP-SCOPE-PO',
          F_HX_PI: pi,
          'FMaterialId.FNumber': 'CMP-SCOPE-MAT-' + (line === 0 ? 'Z' : 'A'),
          'FMaterialId.FName': 'CMP-SCOPE-MAT-' + (line === 0 ? 'Z' : 'A'),
          FQty: String(line === 0 ? 2 : 1),
          FStatus: '1',
          FSaleOrderNo: 'CMP-SCOPE-SO',
          'FPrdOrgId.FName': index === 0 ? '比较测试甲厂' : '比较测试乙厂',
          FID: String(9007199254793001n + BigInt(index)),
          FTreeEntity_FEntryId: String(11 + line),
          FPrdOrgId: index === 0 ? '101' : '102',
          'FPrdOrgId.FNumber': index === 0 ? 'CMP-A' : 'CMP-B',
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

async function query(page: Page, info: TestInfo, status = 200) {
  const response = page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      new URL(r.url()).pathname === (source(info) ? '/api/sn' : '/api/v1/sn/items/page'),
  );
  await page.getByRole('button', { name: zh.common.search, exact: true }).click();
  const result = await response;
  expect(result.status()).toBe(status);
  return result;
}

async function expectRows(page: Page, info: TestInfo, expected: string[]) {
  await expect
    .poll(() =>
      rows(page, info).evaluateAll((elements) =>
        elements.map((el) => el.querySelector('td')?.textContent?.trim() ?? ''),
      ),
    )
    .toEqual(expected);
}

async function capture(page: Page, info: TestInfo, name: string) {
  await page.screenshot({ path: info.outputPath(name + '.png'), fullPage: true });
  const cells = await rows(page, info).evaluateAll((elements) =>
    elements.map((el) => [...el.querySelectorAll('td')].map((td) => td.textContent?.trim())),
  );
  writeFileSync(
    info.outputPath(name + '.json'),
    JSON.stringify({ cells, text: await page.locator('body').innerText() }, null, 2),
  );
}

async function download(
  page: Page,
  info: TestInfo,
  name: string,
  expected: string[],
  format = 'csv',
) {
  const ready = page.waitForEvent('download');
  await page
    .getByRole('button', { name: format === 'csv' ? 'CSV' : zh.query.exportXlsx, exact: true })
    .click();
  const result = await ready;
  return saveDownload(result, info, name, expected, format);
}

async function saveDownload(
  result: Download,
  info: TestInfo,
  name: string,
  expected: string[],
  format = 'csv',
) {
  expect(result.suggestedFilename()).toBe('sn_export.' + format);
  const path = info.outputPath(name + '.' + format);
  await result.saveAs(path);
  expect(await result.failure()).toBeNull();
  const parsed = JSON.parse(
    execFileSync(python, ['-X', 'utf8', resolve('../tools/testing/parity-query-file.py'), path], {
      windowsHide: true,
      encoding: 'utf8',
    }),
  ) as { rows: string[][] };
  writeFileSync(info.outputPath(name + '-' + format + '.json'), JSON.stringify(parsed, null, 2));
  expect(parsed.rows.slice(1).map((row) => row[1])).toEqual(expected);
  return parsed;
}

export {
  PIS,
  BILLS,
  customer,
  run,
  source,
  kind,
  rows,
  sns,
  fixture,
  login,
  language,
  sync,
  query,
  expectRows,
  capture,
  download,
  saveDownload,
};
