import { test, expect, type Page, type TestInfo } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { protectTestSecrets } from '../privacy';
import { paceComparisonLogin } from './login-budget';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import en from '../../src/i18n/prototype/en.json' with { type: 'json' };
import vi from '../../src/i18n/prototype/vi.json' with { type: 'json' };

const source = (info: TestInfo) => info.project.name === 'prototype';
async function capture(page: Page, info: TestInfo, name: string) {
  await page.mouse.move(0, 0);
  await page.evaluate(async () => {
    await document.fonts.ready;
    await Promise.all(
      document
        .getAnimations()
        .filter((animation) => animation.effect?.getTiming().iterations !== Infinity)
        .map((animation) => animation.finished.catch(() => {})),
    );
  });
  writeFileSync(
    info.outputPath(`${name}.json`),
    JSON.stringify(
      await page.evaluate((prototype) => {
        const table = document.querySelector(
          prototype ? '.el-table__body tbody' : '.ant-table-tbody',
        );
        return {
          styles: Object.fromEntries(
            Object.entries({
              tags: prototype
                ? '.el-table__body tr:first-child .el-tag'
                : '.ant-table-tbody tr:not(.ant-table-measure-row):first-of-type .ant-tag',
              firstTags: prototype ? '.el-table__body .el-tag' : '.ant-table-tbody .ant-tag',
              buttons: prototype
                ? '.el-table__body tr:first-child .el-button'
                : '.sn-users-actions .ant-btn',
              plus: prototype
                ? '.page__head .el-button .el-icon'
                : '.sn-page-actions .prototype-icon',
            }).map(([name, selector]) => [
              name,
              [...document.querySelectorAll(selector)].slice(0, 3).map((node) => {
                const css = getComputedStyle(node),
                  rect = node.getBoundingClientRect();
                return {
                  className: node.className,
                  width: rect.width,
                  height: rect.height,
                  color: css.color,
                  background: css.backgroundColor,
                  fontSize: css.fontSize,
                  lineHeight: css.lineHeight,
                  padding: css.padding,
                  margin: css.margin,
                  verticalAlign: css.verticalAlign,
                  display: css.display,
                };
              }),
            ]),
          ),
          rows: [...(table?.querySelectorAll('tr') ?? [])]
            .filter((row) => row.getBoundingClientRect().height > 0)
            .map((row) => [...row.querySelectorAll('td')].map((cell) => cell.textContent?.trim())),
          headings: [...document.querySelectorAll('thead th')]
            .filter((cell) => cell.getBoundingClientRect().width > 0)
            .map((cell) => cell.textContent?.trim()),
          geometry: Object.fromEntries(
            Object.entries({
              surface: prototype ? '.surface' : '.sn-surface',
              toolbar: prototype ? '.toolbar' : '.sn-toolbar',
              table: prototype ? '.el-table' : '.ant-table',
              pager: prototype ? '.pager' : '.sn-acquire-pager',
            }).map(([key, selector]) => {
              const element = document.querySelector(selector)!;
              const r = element.getBoundingClientRect();
              return [key, { x: r.x, y: r.y, width: r.width, height: r.height }];
            }),
          ),
        };
      }, source(info)),
      null,
      2,
    ),
  );
  await page.screenshot({
    path: info.outputPath(`${name}.png`),
    fullPage: true,
    animations: 'disabled',
  });
}

test('P13 account directory: prefix/name search, roles, factories, status, pagination and metadata', async ({
  page,
  browser,
}, info) => {
  test.setTimeout(180_000);
  page.setDefaultTimeout(15_000);
  const kind = source(info) ? 'python' : 'java';
  const password = readFileSync(
    resolve(`../deploy/.secrets/comparison-20261008/${kind}-admin-password`),
    'utf8',
  ).trim();
  protectTestSecrets(password);
  const fixture = JSON.parse(
    readFileSync(
      resolve(`../deploy/.secrets/comparison-20261008/users-directory-${kind}.json`),
      'utf8',
    ),
  ) as { password: string };
  protectTestSecrets(fixture.password);
  const memberContext = await browser.newContext({
    baseURL: String(info.project.use.baseURL),
    locale: 'zh-CN',
  });
  try {
    const member = await memberContext.newPage();
    await member.goto('/login');
    await paceComparisonLogin(member);
    await member.locator('input[autocomplete="username"]').fill('cmp_dir39_26');
    await member.locator('input[autocomplete="current-password"]').fill(fixture.password);
    await member.locator('button[type="submit"]').click();
    await expect(
      member.getByRole('heading', { name: zh.password.title, exact: true }),
    ).toBeVisible();
  } finally {
    await memberContext.close();
  }
  const failures: string[] = [];
  const responses: unknown[] = [];
  page.on('pageerror', (error) => failures.push(error.message));
  page.on('response', async (response) => {
    if (
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === (source(info) ? '/api/users' : '/api/v1/system/users')
    )
      responses.push({
        query: new URL(response.url()).search,
        status: response.status(),
        data: await response.json(),
      });
  });
  await page.goto('/login');
  await paceComparisonLogin(page);
  await page.locator('input[autocomplete="username"]').fill('admin');
  await page.locator('input[autocomplete="current-password"]').fill(password);
  await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
  await page.getByRole('menuitem', { name: zh.nav.users, exact: true }).click();
  await expect(page).toHaveURL(/\/users$/);
  const toolbar = page.locator(source(info) ? '.toolbar' : '.sn-users-page .sn-toolbar');
  const select = (index: number) =>
    toolbar.locator(source(info) ? '.el-select' : '.ant-select').nth(index);
  const rows = page.locator(
    source(info)
      ? '.el-table__body tbody tr'
      : '.ant-table-tbody > tr:not(.ant-table-measure-row):not(.ant-table-placeholder)',
  );
  const search = async (text: string) => {
    const input = toolbar.getByPlaceholder(zh.users.search);
    await input.fill(text);
    await input.press('Enter');
  };
  const choose = async (index: number, name: string) => {
    await select(index).click();
    await page.getByRole('option', { name, exact: true }).click();
  };
  const clear = async (index: number) => {
    await select(index).hover();
    await select(index)
      .locator(source(info) ? '.el-select__clear' : '.ant-select-clear')
      .click();
  };
  const names = () => rows.locator('td:first-child');
  const expected = (from: number, to: number) =>
    Array.from({ length: to - from }, (_, i) => `cmp_dir39_${String(i + from).padStart(2, '0')}`);
  try {
    await search('cmp_dir39_');
    await expect(names()).toHaveText(expected(0, 20));
    await expect(rows.first().locator('td')).toHaveCount(8);
    await expect(rows.first().locator('td').nth(6)).toContainText('admin · ');
    await expect(rows.first().locator('td').nth(5)).toHaveText('');
    await capture(page, info, '01-first-page');
    await page.locator(source(info) ? '.btn-next' : '.ant-pagination-next button').click();
    await expect(names()).toHaveText(expected(20, 27));
    await capture(page, info, '02-second-page');
    await choose(0, zh.roles.factory_operator);
    await expect(names()).toHaveText(expected(0, 20));
    await page.locator(source(info) ? '.btn-next' : '.ant-pagination-next button').click();
    await expect(names()).toHaveText(expected(20, 24));
    await capture(page, info, '03-operator-page-two');
    await choose(0, zh.roles.query);
    await expect(names()).toHaveText(expected(24, 26));
    await choose(1, zh.users.disabled);
    await expect(names()).toHaveText(['cmp_dir39_25']);
    await capture(page, info, '04-disabled-query');
    await choose(2, '比较测试甲厂（CMP-A）');
    await expect(rows).toHaveCount(0);
    await capture(page, info, '05-combined-empty');
    await clear(1);
    await expect(names()).toHaveText(['cmp_dir39_24']);
    await capture(page, info, '06-query-factory');
    await choose(0, zh.roles.admin);
    await expect(rows).toHaveCount(0);
    await clear(2);
    await expect(names()).toHaveText(['cmp_dir39_26']);
    await expect(rows.first().locator('td').nth(5)).toHaveText(
      /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/,
    );
    await capture(page, info, '07-admin');
    await clear(0);
    await search('成员2');
    await expect(names()).toHaveText(expected(20, 27));
    await capture(page, info, '08-name-substring');
    await search('dir39_');
    await expect(rows).toHaveCount(0);
    await capture(page, info, '09-username-suffix-rejected');
    await search('cmp_dir39_');
    for (const [locale, label, words] of [
      ['en', 'English', en],
      ['vi', 'Tiếng Việt', vi],
      ['zh-CN', '简体中文', zh],
    ] as const) {
      await page
        .locator(source(info) ? '.header .lang' : '.prototype-language .ant-select')
        .click();
      await page.getByRole('option', { name: label, exact: true }).click();
      await expect(page.getByRole('columnheader').first()).toHaveText(words.users.empNo);
      await capture(page, info, `10-${locale}`);
    }
    expect(failures).toEqual([]);
    writeFileSync(info.outputPath('list-responses.json'), JSON.stringify(responses, null, 2));
  } finally {
    const language = page.locator(
      source(info) ? '.header .lang' : '.prototype-language .ant-select',
    );
    if (!(await language.innerText()).includes('简体中文')) {
      await language.click();
      await page.getByRole('option', { name: '简体中文', exact: true }).click();
    }
  }
});
