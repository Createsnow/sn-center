import { test, expect, type Page, type TestInfo } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { protectTestSecrets } from '../privacy';
import { paceComparisonLogin } from './login-budget';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import en from '../../src/i18n/prototype/en.json' with { type: 'json' };
import vi from '../../src/i18n/prototype/vi.json' with { type: 'json' };
import errors from '../../src/i18n/prototype/errors.zh-CN.json' with { type: 'json' };

const source = (info: TestInfo) => info.project.name === 'prototype';
async function capture(page: Page, info: TestInfo, name: string) {
  await page.mouse.move(0, 0);
  await page.evaluate(async () => {
    await document.fonts.ready;
    await Promise.all(
      document
        .getAnimations()
        .filter((a) => a.effect?.getTiming().iterations !== Infinity)
        .map((a) => a.finished.catch(() => {})),
    );
  });
  const data = await page.evaluate((prototype) => {
    const dialog = document.querySelector(
      prototype ? '.el-dialog' : '.sn-user-modal .ant-modal-container',
    );
    if (!dialog) throw new Error('Account dialog not found');
    const rect = (node: Element) => {
      const r = node.getBoundingClientRect();
      return [r.x, r.y, r.width, r.height];
    };
    return {
      text: (dialog as HTMLElement).innerText,
      drawnLabels: [
        ...dialog.querySelectorAll(
          prototype ? '.el-form-item__label' : '.ant-form-item-label > label',
        ),
      ].map((label) => {
        const nodes = document.createTreeWalker(label, NodeFilter.SHOW_TEXT);
        const letters: unknown[] = [];
        while (nodes.nextNode()) {
          const node = nodes.currentNode;
          for (let i = 0; i < (node.textContent?.length ?? 0); i++) {
            const range = document.createRange();
            range.setStart(node, i);
            range.setEnd(node, i + 1);
            const r = range.getBoundingClientRect();
            letters.push([node.textContent?.[i], r.x, r.y, r.width, r.height]);
          }
        }
        return letters;
      }),
      factoryMarkup: dialog.querySelector(prototype ? '.factory-select' : '.sn-factory-picker')
        ?.outerHTML,
      styles: [
        ...dialog.querySelectorAll(
          prototype
            ? '.el-radio-button__inner, .muted'
            : '.ant-radio-button-wrapper, .sn-account-hint',
        ),
      ].map((node) => {
        const style = getComputedStyle(node);
        return {
          text: node.textContent,
          font: style.font,
          letterSpacing: style.letterSpacing,
          boxSizing: style.boxSizing,
          padding: style.padding,
          border: style.border,
          margin: style.margin,
          display: style.display,
          width: style.width,
        };
      }),
      geometry: Object.fromEntries(
        Object.entries({
          dialog: prototype ? '.el-dialog' : '.sn-user-modal .ant-modal-container',
          title: prototype ? '.el-dialog__title' : '.sn-user-modal .ant-modal-title',
          rows: prototype
            ? '.el-dialog .el-form-item'
            : '.sn-user-modal .ant-form > .ant-form-item',
          labels: prototype
            ? '.el-dialog .el-form-item__label'
            : '.sn-user-modal .ant-form-item-label',
          inputs: prototype
            ? '.el-dialog .el-input__wrapper'
            : '.sn-user-modal .ant-input:not(.ant-input-affix-wrapper > .ant-input), .sn-user-modal .ant-input-affix-wrapper',
          hints: prototype ? '.el-dialog .muted' : '.sn-user-modal .sn-account-hint',
          roles: prototype
            ? '.el-dialog .el-radio-button'
            : '.sn-user-modal .ant-radio-button-wrapper',
          footer: prototype ? '.el-dialog__footer' : '.sn-user-modal .ant-modal-footer',
        }).map(([k, s]) => [k, [...document.querySelectorAll(s)].map(rect)]),
      ),
    };
  }, source(info));
  writeFileSync(info.outputPath(name + '.json'), JSON.stringify(data, null, 2));
  expect(
    await page
      .locator('input[type="password"]')
      .evaluateAll((nodes) => nodes.every((n) => (n as HTMLInputElement).value === '')),
  ).toBe(true);
  await page.screenshot({
    path: info.outputPath(name + '.png'),
    fullPage: true,
  });
}

test('P13 account editor: real create/edit, ordered validation, roles, cancel and three languages', async ({
  page,
}, info) => {
  test.setTimeout(210_000);
  page.setDefaultTimeout(15_000);
  const kind = source(info) ? 'python' : 'java';
  const statePath = resolve(`../deploy/.secrets/comparison-20261008/account-editor-${kind}.json`);
  const state = JSON.parse(readFileSync(statePath, 'utf8')) as {
    password: string;
    names: [string, string, string];
    created: string[];
  };
  expect(state.created).toEqual([]);
  const password = readFileSync(
    resolve(`../deploy/.secrets/comparison-20261008/${kind}-admin-password`),
    'utf8',
  ).trim();
  protectTestSecrets(password, state.password);
  const failures: string[] = [];
  page.on('pageerror', (error) => failures.push(error.message));
  const writes: unknown[] = [];
  await page.goto('/login');
  await paceComparisonLogin(page);
  await page.locator('input[autocomplete="username"]').fill('admin');
  await page.locator('input[autocomplete="current-password"]').fill(password);
  await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
  await page.getByRole('menuitem', { name: zh.nav.users, exact: true }).click();
  const dialog = page.getByRole('dialog');
  const textInputs = () =>
    dialog.locator(
      source(info)
        ? '.el-form-item .el-input__inner:not([role="combobox"]):not([type="password"])'
        : '.ant-form-item input.ant-input:not([type="password"])',
    );
  const role = async (value: 'admin' | 'factory_operator' | 'query') => {
    await dialog
      .locator(source(info) ? '.el-radio-button' : '.ant-radio-button-wrapper')
      .filter({ hasText: zh.roles[value] })
      .click();
  };
  const factory = async () => {
    await dialog.locator(source(info) ? '.el-select' : '.ant-select').click();
    await page.getByRole('option', { name: '比较测试甲厂（CMP-A）', exact: true }).click();
  };
  const save = async (expected: number, reason?: string) => {
    const responsePromise = page.waitForResponse(
      (r) =>
        ['POST', 'PUT'].includes(r.request().method()) &&
        /\/(?:sn\/)?users(?:\/[^/]+(?:\/update)?)?$/.test(new URL(r.url()).pathname),
    );
    await dialog.getByRole('button', { name: zh.common.save, exact: true }).click();
    const response = await responsePromise;
    const value = await response.json();
    writes.push({
      route: new URL(response.url()).pathname,
      status: response.status(),
      data: value,
    });
    if (response.ok() && !/\/users\//.test(new URL(response.url()).pathname)) {
      const id = String(source(info) ? value.id : value.data.id);
      state.created.push(id);
      writeFileSync(statePath, JSON.stringify(state));
    }
    expect(response.status()).toBe(expected);
    if (reason) await expect(page.getByText(reason, { exact: true })).toBeVisible();
    if (expected === 200) await expect(dialog).toBeHidden();
  };
  const search = async (name: string) => {
    const box = page.getByPlaceholder(zh.users.search);
    await box.fill(name);
    await box.press('Enter');
  };
  const rows = () =>
    page.locator(
      source(info)
        ? '.el-table__body tbody tr'
        : '.ant-table-tbody > tr:not(.ant-table-measure-row):not(.ant-table-placeholder)',
    );
  try {
    await page.getByRole('button', { name: zh.users.create, exact: true }).click();
    await expect(
      dialog.getByRole('radio', { name: zh.roles.factory_operator, exact: true }),
    ).toBeChecked();
    await capture(page, info, '01-default');
    await save(400, errors.USER_EMP_NO_INVALID);
    await textInputs().nth(0).fill(state.names[0]);
    await save(400, errors.USER_NAME_REQUIRED);
    await textInputs().nth(1).fill('  表单测试成员  ');
    await save(400, errors.USER_FACTORY_REQUIRED);
    await factory();
    await save(400, errors.USER_PASSWORD_WEAK);
    await dialog.locator('input[type="password"]').fill(state.password);
    await save(200);
    await search(state.names[0]);
    await expect(rows()).toHaveCount(1);
    await expect(rows().first().locator('td').nth(1)).toHaveText('表单测试成员');
    await expect(rows().first()).toContainText(zh.users.mustChange);
    await page.getByRole('button', { name: zh.users.create, exact: true }).click();
    await textInputs().nth(0).fill(state.names[0]);
    await textInputs().nth(1).fill('duplicate');
    await role('query');
    await dialog.locator('input[type="password"]').fill(state.password);
    await save(409, errors.USER_EMP_NO_EXISTS.replace('{{emp_no}}', state.names[0]));
    await dialog.getByRole('button', { name: zh.common.cancel, exact: true }).click();
    for (const next of ['query', 'admin', 'factory_operator'] as const) {
      await rows().first().getByRole('button', { name: zh.common.edit, exact: true }).click();
      await expect(textInputs().nth(0)).toBeDisabled();
      await expect(dialog.locator('input[type="password"]')).toHaveCount(0);
      await role(next);
      if (next === 'query') {
        const picker = dialog.locator(source(info) ? '.el-select' : '.ant-select');
        await picker.hover();
        await picker.locator(source(info) ? '.el-select__clear' : '.ant-select-clear').click();
      }
      if (next === 'factory_operator') await factory();
      await capture(page, info, '02-edit-' + next);
      await save(200);
      await expect(rows().first().locator('td').nth(2)).toHaveText(zh.roles[next]);
      await expect(rows().first().locator('td').nth(3)).toHaveText(
        next === 'factory_operator' ? '比较测试甲厂（CMP-A）' : zh.users.noFactory,
      );
    }
    await rows().first().getByRole('button', { name: zh.common.edit, exact: true }).click();
    await textInputs().nth(1).fill('取消的改动');
    await dialog.getByRole('button', { name: zh.common.cancel, exact: true }).click();
    await expect(rows().first().locator('td').nth(1)).toHaveText('表单测试成员');
    for (const [index, next] of [
      [1, 'query'],
      [2, 'admin'],
    ] as const) {
      await page.getByRole('button', { name: zh.users.create, exact: true }).click();
      await textInputs().nth(0).fill(state.names[index]);
      await textInputs()
        .nth(1)
        .fill('表单' + next);
      await role(next);
      await dialog.locator('input[type="password"]').fill(state.password);
      await save(200);
      await search(state.names[index]);
      await expect(rows()).toHaveCount(1);
      await expect(rows().first().locator('td').nth(2)).toHaveText(zh.roles[next]);
      await expect(rows().first().locator('td').nth(3)).toHaveText(zh.users.noFactory);
    }
    for (const [locale, label, words] of [
      ['en', 'English', en],
      ['vi', 'Tiếng Việt', vi],
      ['zh-CN', '简体中文', zh],
    ] as const) {
      await page
        .locator(source(info) ? '.header .lang' : '.prototype-language .ant-select')
        .click();
      await page.getByRole('option', { name: label, exact: true }).click();
      await page.getByRole('button', { name: words.users.create, exact: true }).click();
      for (const value of ['factory_operator', 'query', 'admin'] as const) {
        await dialog
          .locator(source(info) ? '.el-radio-button' : '.ant-radio-button-wrapper')
          .filter({ hasText: words.roles[value] })
          .click();
        await expect(dialog).toContainText(words.users.roleHint[value]);
        await capture(page, info, `03-${locale}-${value}`);
      }
      await dialog.getByRole('button', { name: words.common.cancel, exact: true }).click();
    }
    expect(failures).toEqual([]);
  } finally {
    writeFileSync(info.outputPath('writes.json'), JSON.stringify(writes, null, 2));
    if (!page.isClosed()) {
      if (await dialog.isVisible()) await page.keyboard.press('Escape');
      await expect(dialog).toBeHidden();
      const language = page.locator(
        source(info) ? '.header .lang' : '.prototype-language .ant-select',
      );
      if (!(await language.innerText()).includes('简体中文')) {
        await language.click();
        await page.getByRole('option', { name: '简体中文', exact: true }).click();
      }
    }
  }
});
