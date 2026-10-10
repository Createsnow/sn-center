import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { protectTestSecrets } from '../privacy';
import { paceComparisonLogin } from './login-budget';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import en from '../../src/i18n/prototype/en.json' with { type: 'json' };
import vi from '../../src/i18n/prototype/vi.json' with { type: 'json' };
import errors from '../../src/i18n/prototype/errors.zh-CN.json' with { type: 'json' };

test('P14 factories: full catalog, create validation, Unicode, cancel, sync conflicts and recovery', async ({
  page,
}, info) => {
  test.setTimeout(240_000);
  const prototype = info.project.name === 'prototype';
  const kind = prototype ? 'python' : 'java';
  const fixture = (action: string) =>
    execFileSync(
      resolve('../../sn-center-python/.venv/Scripts/python.exe'),
      ['-X', 'utf8', resolve('../tools/testing/parity-factory-fixture.py'), kind, action],
      { windowsHide: true, encoding: 'utf8' },
    );
  const control = resolve('../tools/.runtime/comparison-20261008/k3-control.json');
  const setControl = (value: unknown) => writeFileSync(control, JSON.stringify(value));
  fixture('prepare');
  const password = readFileSync(
    resolve(`../deploy/.secrets/comparison-20261008/${kind}-admin-password`),
    'utf8',
  ).trim();
  protectTestSecrets(password);
  const snapshots: unknown[] = [];
  const writes: unknown[] = [];
  const failures: string[] = [];
  page.on('pageerror', (error) => failures.push(error.message));
  const route = prototype ? '/api/factories' : '/api/v1/sn/factories';
  const rows = () =>
    page.locator(
      prototype
        ? '.el-table__body tbody tr'
        : '.ant-table-tbody > tr:not(.ant-table-measure-row):not(.ant-table-placeholder)',
    );
  const dialog = page.getByRole('dialog');
  const messages = () =>
    page.locator(prototype ? '.el-message:visible' : '.ant-message-notice:visible');
  const capture = async (name: string) => {
    await page.mouse.move(0, 0);
    if ((await dialog.isVisible()) || (await messages().count())) {
      // 弹窗挂载后的下一帧才开始缩放；先跨过进入动画，再测实际最终位置。
      await page.waitForTimeout(350);
    }
    if (await dialog.isVisible()) {
      await expect
        .poll(
          async () =>
            (
              await page
                .locator(prototype ? '.el-dialog' : '.sn-factory-modal .ant-modal-container')
                .boundingBox()
            )?.width,
        )
        .toBe(440);
    }
    await page.evaluate(async () => {
      await document.fonts.ready;
      await Promise.all(
        document
          .getAnimations()
          .filter((a) => a.effect?.getTiming().iterations !== Infinity)
          .map((a) => a.finished.catch(() => {})),
      );
    });
    const data = await page.evaluate((source) => {
      const rect = (node: Element) => {
        const b = node.getBoundingClientRect();
        const s = getComputedStyle(node);
        return {
          rect: [b.x, b.y, b.width, b.height],
          text: (node as HTMLElement).innerText,
          color: s.color,
          background: s.backgroundColor,
          fontSize: s.fontSize,
          lineHeight: s.lineHeight,
        };
      };
      const selectors = source
        ? [
            '.page__head',
            '.surface',
            '.el-table__header th',
            '.el-table__body tr:first-child td',
            '.el-dialog',
            '.el-dialog__header',
            '.el-dialog__body',
            '.el-form-item',
            '.el-form-item__label',
            '.el-input__wrapper',
            '.el-dialog .muted',
            '.el-dialog__footer',
            '.el-message',
            '.page__actions button',
            '.el-table__body tr:first-child .el-tag',
            '.el-message__content',
          ]
        : [
            '.sn-page-head',
            '.sn-factories-page .sn-surface',
            '.ant-table-thead th',
            '.ant-table-tbody > tr:not(.ant-table-measure-row):first-child td',
            '.sn-factory-modal .ant-modal-container',
            '.sn-factory-modal .ant-modal-header',
            '.sn-factory-modal .ant-modal-body',
            '.sn-factory-modal .ant-form-item',
            '.sn-factory-modal .ant-form-item-label',
            '.sn-factory-modal .ant-input',
            '.sn-factory-hint',
            '.sn-factory-modal .ant-modal-footer',
            '.ant-message-notice',
            '.sn-page-actions button',
            '.ant-table-tbody > tr:not(.ant-table-measure-row):first-child .ant-tag',
            '.ant-message-notice-title',
          ];
      return selectors.map((selector) =>
        Array.from(document.querySelectorAll(selector))
          .filter((e) => e.getClientRects().length && getComputedStyle(e).visibility !== 'hidden')
          .map(rect),
      );
    }, prototype);
    snapshots.push({
      name,
      data,
      records: JSON.parse(fixture('snapshot')),
      rows: await rows().evaluateAll((items) =>
        items.map((row) =>
          Array.from(row.querySelectorAll('td')).map((cell) => (cell as HTMLElement).innerText),
        ),
      ),
    });
    await page.screenshot({
      path: info.outputPath(name + '.png'),
      fullPage: true,
      animations: 'disabled',
    });
  };
  const clearMessages = async () => {
    for (const close of await page
      .locator(prototype ? '.el-message__closeBtn:visible' : '.sn-feedback-content button:visible')
      .all())
      await close.click();
    await expect(messages()).toHaveCount(0, { timeout: 6000 });
  };
  const inputs = () => dialog.locator('input');
  const fill = async (code: string, name: string) => {
    await inputs().nth(0).fill(code);
    await inputs().nth(1).fill(name);
  };
  const save = async (status: number, label: string, expectedText?: string) => {
    const count = await rows().count();
    const waiting = page.waitForResponse(
      (r) => r.request().method() === 'POST' && new URL(r.url()).pathname === route,
    );
    await dialog.getByRole('button', { name: zh.common.save, exact: true }).click();
    const response = await waiting;
    const body = await response.json();
    writes.push({ label, status: response.status(), body });
    expect(response.status()).toBe(status);
    if (status === 200) {
      await expect(dialog).toBeHidden();
      await expect(page.getByText(zh.common.saved, { exact: true }).last()).toBeVisible();
      await expect(rows()).toHaveCount(count + 1);
    } else {
      await expect(dialog).toBeVisible();
      if (expectedText) await expect(messages().last()).toContainText(expectedText);
    }
    await capture(label);
    await clearMessages();
    return prototype ? body : body.data;
  };
  const open = async () => {
    await page.getByRole('button', { name: zh.factories.create, exact: true }).click();
    await expect(dialog).toBeVisible();
  };
  const orgs = [
    {
      FOrgID: '944001',
      FNumber: 'CMP_F44_MANUAL',
      FName: '比较工厂44-手工改名',
      FForbidStatus: 'A',
    },
    { FOrgID: '944002', FNumber: 'CMP_F44_K3', FName: '比较工厂44-K3新增', FForbidStatus: 'A' },
    { FOrgID: '944003', FNumber: 'CMP_F44_CONFLICT', FName: '比较工厂44-00', FForbidStatus: 'A' },
  ];
  const sync = async (
    label: string,
    expected: { total: number; inserted: number; updated: number; conflicts: string[] } | null,
  ) => {
    const count = await rows().count();
    const pending = page.waitForResponse(
      (r) => r.request().method() === 'POST' && new URL(r.url()).pathname === route + '/sync',
    );
    await page.getByRole('button', { name: zh.factories.sync, exact: true }).click();
    const response = await pending;
    const body = await response.json();
    writes.push({ label, status: response.status(), body });
    if (expected) {
      expect(response.status()).toBe(200);
      const value = prototype ? body : body.data;
      expect({
        total: Number(value.total),
        inserted: Number(value.inserted),
        updated: Number(value.updated),
        conflicts: value.conflicts,
      }).toEqual(expected);
      await expect(messages().first()).toContainText(
        `金蝶组织 ${expected.total} 个：新增 ${expected.inserted}，更新 ${expected.updated}`,
      );
      if (expected.conflicts.length)
        await expect(messages().last()).toContainText(expected.conflicts.join('、'));
      await expect(rows()).toHaveCount(count + expected.inserted);
      if (expected.updated)
        await expect(
          rows()
            .filter({ has: page.getByRole('cell', { name: 'CMP_F44_MANUAL', exact: true }) })
            .locator('td')
            .nth(1),
        ).toHaveText('比较工厂44-手工改名');
    } else {
      expect(response.status()).toBeGreaterThanOrEqual(400);
      await expect(messages().last()).toBeVisible();
    }
    await capture(label);
    await expect(
      page.getByRole('button', { name: zh.factories.sync, exact: true }),
    ).not.toHaveClass(/loading/);
    await clearMessages();
  };
  try {
    await page.goto('/login');
    await paceComparisonLogin(page);
    await page.locator('input[autocomplete="username"]').fill('admin');
    await page.locator('input[autocomplete="current-password"]').fill(password);
    await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
    await page.getByRole('menuitem', { name: zh.nav.factories, exact: true }).click();
    await expect(rows()).toHaveCount(55);
    await expect(page.locator('.ant-pagination,.el-pagination')).toHaveCount(0);
    await expect(page.getByRole('columnheader')).toHaveText([
      zh.factories.code,
      zh.factories.name,
      zh.factories.source,
      zh.common.time,
    ]);
    const codes = await rows().locator('td:first-child').allTextContents();
    expect(codes).toEqual([...codes].sort());
    for (const row of await rows().filter({ hasText: 'CMP_F44_L_' }).all())
      await expect(row.locator('td').nth(3)).toHaveText('2026-10-09 10:03:04');
    await capture('01-catalog');
    await open();
    await capture('02-dialog-empty');
    await fill('draft44', '保留草稿');
    await dialog.getByRole('button', { name: zh.common.cancel, exact: true }).click();
    await open();
    await expect(inputs().nth(0)).toHaveValue('draft44');
    await expect(inputs().nth(1)).toHaveValue('保留草稿');
    await capture('03-cancel-retains');
    await fill('', '');
    await save(400, '04-empty-invalid', errors.FACTORY_CODE_INVALID);
    await fill('A'.repeat(65), '比较工厂44-过长');
    await save(400, '05-code-too-long', errors.FACTORY_CODE_INVALID);
    await fill('CMP_F44_MANUAL', '名'.repeat(129));
    await save(400, '06-name-too-long', errors.FACTORY_CODE_INVALID);
    await fill('\u00a0\u0085CMP_F44_MANUAL\u202f', '\u00a0比较工厂44-手工\u202f');
    const first = await save(200, '07-created');
    expect(first[prototype ? 'factory_code' : 'code']).toBe('CMP_F44_MANUAL');
    expect(first[prototype ? 'factory_name' : 'name']).toBe('比较工厂44-手工');
    expect(first.source).toBe('MANUAL');
    await expect(rows()).toHaveCount(56);
    await open();
    await expect(inputs().nth(0)).toHaveValue('');
    await expect(inputs().nth(1)).toHaveValue('');
    await fill('CMP_F44_MANUAL', '比较工厂44-重复编码');
    await save(
      409,
      '08-duplicate-code',
      '工厂编码或名称已存在：CMP_F44_MANUAL/比较工厂44-重复编码',
    );
    await fill('CMP_F44_CONFLICT', '比较工厂44-手工');
    await save(409, '09-duplicate-name', '工厂编码或名称已存在：CMP_F44_CONFLICT/比较工厂44-手工');
    for (const [code, name, label] of [
      ['cmp_f44_manual', '比较工厂44-小写', '10-case-distinct'],
      ['CMP_F44_é', '比较工厂44-é', '11-accent'],
      ['CMP_F44_e', '比较工厂44-e', '12-plain'],
      ['🏭'.repeat(64), '😀'.repeat(128), '13-codepoint-limit'],
    ]) {
      if (!(await dialog.isVisible())) await open();
      await fill(code!, name!);
      await save(200, label!);
    }
    await expect(rows()).toHaveCount(60);
    setControl({ organizations: orgs });
    await sync('14-sync-new-update-conflict', {
      total: 3,
      inserted: 1,
      updated: 1,
      conflicts: ['CMP_F44_CONFLICT/比较工厂44-00'],
    });
    await expect(rows()).toHaveCount(61);
    const manual = rows().filter({
      has: page.getByRole('cell', { name: 'CMP_F44_MANUAL', exact: true }),
    });
    await expect(manual.locator('td').nth(1)).toHaveText('比较工厂44-手工改名');
    await expect(manual.locator('td').nth(2)).toHaveText(zh.factories.srcMANUAL);
    const stable = JSON.parse(fixture('snapshot'));
    await sync('15-sync-unchanged', {
      total: 3,
      inserted: 0,
      updated: 0,
      conflicts: ['CMP_F44_CONFLICT/比较工厂44-00'],
    });
    expect(JSON.parse(fixture('snapshot'))).toEqual(stable);
    setControl({ organizations: [{ ...orgs[0], FName: '比较工厂44-00' }] });
    await sync('16-sync-rename-conflict', {
      total: 1,
      inserted: 0,
      updated: 0,
      conflicts: ['CMP_F44_MANUAL/比较工厂44-00'],
    });
    expect(JSON.parse(fixture('snapshot'))).toEqual(stable);
    setControl({ organizationFailure: true });
    await sync('17-sync-failure', null);
    expect(JSON.parse(fixture('snapshot'))).toEqual(stable);
    setControl({ organizations: [] });
    await sync('18-sync-empty-recovery', { total: 0, inserted: 0, updated: 0, conflicts: [] });
    expect(JSON.parse(fixture('snapshot'))).toEqual(stable);
    for (const [locale, label, words] of [
      ['en', 'English', en],
      ['vi', 'Tiếng Việt', vi],
      ['zh-CN', '简体中文', zh],
    ] as const) {
      await page.locator(prototype ? '.header .lang' : '.prototype-language .ant-select').click();
      await page.getByRole('option', { name: label, exact: true }).click();
      await page.setViewportSize({ width: 900, height: 960 });
      await capture(`19-${locale}-900-page`);
      await page.getByRole('button', { name: words.factories.create, exact: true }).click();
      await capture(`20-${locale}-900-dialog`);
      await page.setViewportSize({ width: 1440, height: 960 });
      await capture(`21-${locale}-1440-dialog`);
      await dialog.getByRole('button', { name: words.common.cancel, exact: true }).click();
    }
    expect(failures).toEqual([]);
  } finally {
    writeFileSync(info.outputPath('factory-states.json'), JSON.stringify(snapshots, null, 2));
    writeFileSync(info.outputPath('factory-writes.json'), JSON.stringify(writes, null, 2));
    writeFileSync(info.outputPath('page-errors.json'), JSON.stringify(failures, null, 2));
    if (!page.isClosed()) {
      if (await dialog.isVisible()) await page.keyboard.press('Escape');
      const language = page.locator(
        prototype ? '.header .lang' : '.prototype-language .ant-select',
      );
      if ((await language.isVisible()) && !(await language.innerText()).includes('简体中文')) {
        await language.click();
        await page.getByRole('option', { name: '简体中文', exact: true }).click();
      }
    }
    setControl({});
    fixture('clean');
  }
});
