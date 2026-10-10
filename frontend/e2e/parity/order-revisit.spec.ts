import { test, expect, type Page } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, renameSync } from 'node:fs';
import { resolve } from 'node:path';
import { protectTestSecrets } from '../privacy';
import { settleOrderPage } from './settleOrderPage';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };

// 两个真实页面共享测试账号；另一页同步并改定时后，返回页必须重新读取一致快照。
test('P05 revisit: another page updates orders and schedule before returning from home', async ({
  page,
  context,
}, info) => {
  test.setTimeout(150_000);
  page.setDefaultTimeout(15_000);
  const prototype = info.project.name === 'prototype';
  const kind = prototype ? 'python' : 'java';
  const run = process.env.ADTEK_ORDER_REVISIT_RUN!;
  expect(run).toMatch(/^[0-9]{8,14}$/);
  const fixture = (action: string) =>
    JSON.parse(
      execFileSync(
        resolve('../../sn-center-python/.venv/Scripts/python.exe'),
        [
          '-X',
          'utf8',
          resolve('../tools/testing/parity-order-audit-fixture.py'),
          kind,
          action,
          run,
        ],
        { encoding: 'utf8', windowsHide: true },
      ),
    );
  fixture('prepare');
  expect(fixture('snapshot').schedule[0].interval_minutes).toBe(1440);
  const password = readFileSync(
    resolve('../deploy/.secrets/comparison-20261008/' + kind + '-admin-password'),
    'utf8',
  ).trim();
  protectTestSecrets(password);
  const origin = prototype ? 'http://127.0.0.1:8001' : 'http://127.0.0.1:15174';
  const prefix = prototype ? '/api/orders' : '/api/v1/sn/orders';
  const extrasPath = resolve(
    '../tools/.runtime/comparison-20261008/order-audits/extra-orders.json',
  );
  const original = readFileSync(extrasPath, 'utf8');
  expect(JSON.parse(original)).toEqual([]);
  const writeExtras = (value: string) => {
    writeFileSync(extrasPath + '.next', value);
    renameSync(extrasPath + '.next', extrasPath);
  };
  const observations: unknown[] = [],
    errors: string[] = [],
    requests: unknown[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('request', (r) => {
    if (new URL(r.url()).pathname.startsWith(prefix))
      requests.push({ path: new URL(r.url()).pathname, method: r.method(), at: Date.now() });
  });
  let peer: Page | undefined,
    changed = false;
  const messages = (target: Page) =>
    target.locator(prototype ? '.el-message' : '.ant-message-notice');
  const sync = async (target: Page) => {
    await expect(messages(target)).toHaveCount(0);
    const response = target.waitForResponse(
      (r) => r.request().method() === 'POST' && new URL(r.url()).pathname === prefix + '/sync',
    );
    await target.getByRole('button', { name: zh.orders.syncAll, exact: true }).click();
    expect((await response).status()).toBe(200);
    await expect(messages(target)).toHaveCount(1);
    await expect(messages(target)).toHaveCount(0);
  };
  const schedule = async (target: Page, minutes: number) => {
    await target.getByRole('button', { name: zh.orders.sched.settings, exact: true }).click();
    await expect(target.getByRole('dialog')).toBeVisible();
    await target
      .getByRole('dialog')
      .locator(prototype ? '.el-select' : '.ant-select')
      .click();
    await target.getByRole('option', { name: zh.orders.sched.unit.minute, exact: true }).click();
    await target.getByRole('spinbutton').fill(String(minutes));
    const response = target.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' && new URL(r.url()).pathname === prefix + '/sync-schedule',
    );
    await target
      .getByRole('dialog')
      .getByRole('button', { name: zh.common.save, exact: true })
      .click();
    expect((await response).status()).toBe(200);
    await expect(target.getByRole('dialog')).toBeHidden();
    await expect(messages(target)).toHaveCount(1);
    await expect(messages(target)).toHaveCount(0);
  };
  const capture = async (label: string) => {
    await settleOrderPage(page);
    const values = await page
      .locator(prototype ? '.meta > span > b' : '.sn-order-meta > span > b')
      .allTextContents();
    const amount = (await page.getByRole('spinbutton').count())
      ? await page.getByRole('spinbutton').inputValue()
      : null;
    const unit = (await page.getByRole('dialog').count())
      ? await page
          .getByRole('dialog')
          .locator(prototype ? '.el-select' : '.ant-select')
          .innerText()
      : null;
    observations.push({
      label,
      values,
      amount,
      unit,
      schedule: fixture('snapshot').schedule,
      at: Date.now(),
    });
    await page.screenshot({ path: info.outputPath(label + '.png'), animations: 'disabled' });
    return { values, amount, unit };
  };
  try {
    await page.goto(origin + '/login');
    await page.locator('input[autocomplete=username]').fill('audit' + run);
    await page.locator('input[autocomplete=current-password]').fill(password);
    await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
    await page.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
    await expect(page.getByText('CMP-MO-002', { exact: true })).toBeVisible();
    await expect(
      page.getByRole('button', { name: zh.orders.sched.settings, exact: true }),
    ).toBeVisible();
    const initial = await capture('initial');
    expect(initial.values.slice(1)).toEqual(['3', '4']);
    await page.getByRole('menuitem', { name: zh.nav.dashboard, exact: true }).click();
    peer = await context.newPage();
    peer.on('pageerror', (e) => errors.push(e.message));
    await peer.goto(origin + '/orders');
    await expect(peer.getByText('CMP-MO-002', { exact: true })).toBeVisible();
    const bill = 'CMP-REVISIT-' + run;
    changed = true;
    writeExtras(
      JSON.stringify([
        {
          'F_ora_kh.FNumber': 'CMP-CUSTOMER',
          FBillNo: bill,
          F_HX_PO: 'CMP-PO',
          F_HX_PI: 'CMP-PI-002',
          'FMaterialId.FNumber': 'CMP-MAT-REVISIT',
          'FMaterialId.FName': '返回页面刷新验证',
          FQty: '1',
          FStatus: '1',
          FSaleOrderNo: 'CMP-SO',
          'FPrdOrgId.FName': '比较测试乙厂',
          FID: '9007199254741053',
          FTreeEntity_FEntryId: '5301',
          FPrdOrgId: '102',
          'FPrdOrgId.FNumber': 'CMP-B',
        },
      ]),
    );
    await sync(peer);
    await expect(peer.getByText(bill, { exact: true })).toBeVisible();
    await schedule(peer, 120);
    const returnedAt = Date.now();
    await page.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
    await expect(page.getByText(bill, { exact: true })).toBeVisible();
    const returned = await capture('returned');
    await page.getByRole('button', { name: zh.orders.sched.settings, exact: true }).click();
    const editor = await capture('schedule-after-return');
    expect.soft(returned.values.slice(1)).toEqual(['4', '5']);
    expect.soft(editor.amount).toBe('2');
    expect.soft(editor.unit?.trim()).toBe(zh.orders.sched.unit.hour);
    expect(
      requests.some((r) => {
        const value = r as { at: number; path: string };
        return value.at >= returnedAt && value.path === prefix + '/meta';
      }),
    ).toBe(true);
    await page
      .getByRole('dialog')
      .getByRole('button', { name: zh.common.cancel, exact: true })
      .click();
    expect(errors).toEqual([]);
  } finally {
    writeExtras(original);
    try {
      if (peer && changed) {
        await sync(peer);
        await schedule(peer, 1440);
      }
    } finally {
      await peer?.close();
      writeFileSync(
        info.outputPath('observations.json'),
        JSON.stringify({ observations, requests, errors }, null, 2),
      );
    }
  }
});
