import { test, expect, type Locator } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, renameSync } from 'node:fs';
import { resolve } from 'node:path';
import { protectTestSecrets } from '../privacy';
import { createMesProbe } from './mes-probe';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import ui from '../../src/i18n/locales/zh-CN.json' with { type: 'json' };

// 仅准备隔离账号和合成ERP输入；规则、SN、分配、打印与转厂均由真实页面创建。
test(
  process.env.ADTEK_BUSINESS_LOOP_MES === '1'
    ? 'P16 MES lifecycle with UI generation, callback state, transfer protection and audit'
    : 'P15 complete UI business loop from order sync and rule creation to transferred reprint and audit',
  async ({ page }, info) => {
    test.setTimeout(240_000);
    page.setDefaultTimeout(20_000);
    const source = info.project.name === 'prototype';
    const mesMode = process.env.ADTEK_BUSINESS_LOOP_MES === '1';
    const kind = source ? 'python' : 'java';
    const run = process.env.ADTEK_BUSINESS_LOOP_RUN!;
    expect(run).toMatch(/^[0-9]{8,14}$/);
    const username = 'audit' + run;
    execFileSync(
      resolve('../../sn-center-python/.venv/Scripts/python.exe'),
      [
        '-X',
        'utf8',
        resolve('../tools/testing/parity-order-audit-fixture.py'),
        kind,
        'prepare',
        run,
      ],
      { windowsHide: true },
    );
    const password = readFileSync(
      resolve('../deploy/.secrets/comparison-20261008/' + kind + '-admin-password'),
      'utf8',
    ).trim();
    protectTestSecrets(password);
    const origin = source ? 'http://127.0.0.1:8001' : 'http://127.0.0.1:15174';
    const pi = 'CMP-LOOP-' + run,
      bill = 'CMP-LOOP-MO-' + run,
      prefix = 'L' + run;
    const sns = [1, 2, 3].map((i) => prefix + String(i).padStart(5, '0'));
    const extraPath = resolve(
      '../tools/.runtime/comparison-20261008/order-audits/extra-orders.json',
    );
    const original = readFileSync(extraPath, 'utf8');
    expect(JSON.parse(original)).toEqual([]);
    const writeExtra = (value: string) => {
      writeFileSync(extraPath + '.next', value);
      renameSync(extraPath + '.next', extraPath);
    };
    const errors: string[] = [],
      facts: unknown[] = [],
      files: { name: string; sns: string[] }[] = [];
    const mesObservations: unknown[] = [];
    let mes: Awaited<ReturnType<typeof createMesProbe>> | undefined;
    let firstMesBatch:
      Awaited<ReturnType<Awaited<ReturnType<typeof createMesProbe>>['take']>> | undefined;
    page.on('pageerror', (e) => errors.push(e.message));
    const rows = () => page.locator(source ? '.el-table__row' : '.ant-table-row');
    const panel = () => page.getByRole('tabpanel').filter({ visible: true });
    const item = (root: Locator, label: string) =>
      root
        .locator(source ? '.el-form-item' : '.ant-form-item')
        .filter({ has: page.locator('label', { hasText: new RegExp('^\\s*' + label + '\\s*$') }) });
    const field = (root: Locator, label: string) =>
      item(root, label).locator('input,textarea').first();
    const go = (name: string) => page.getByRole('menuitem', { name, exact: true }).click();
    const capture = async (name: string) => {
      await page.screenshot({ path: info.outputPath(name + '.png'), animations: 'disabled' });
    };
    const confirm = async () => {
      const modal = page.locator(source ? '.el-message-box:visible' : '.ant-modal-confirm:visible');
      await expect(modal).toBeVisible();
      await modal.getByRole('button', { name: /^(确定|确认)$/ }).click();
      await expect(modal).toBeHidden();
    };
    const sync = async () => {
      await go(zh.nav.orders);
      const response = page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' && new URL(r.url()).pathname.endsWith('/orders/sync'),
      );
      await page.getByRole('button', { name: zh.orders.syncAll, exact: true }).click();
      expect((await response).status()).toBe(200);
      await expect(
        page.getByRole('button', { name: zh.orders.syncAll, exact: true }),
      ).toBeEnabled();
    };
    const stat = async (label: string, expected: number) => {
      await expect(
        page
          .locator(source ? '.stats > div' : '.sn-work-stats > div')
          .filter({ has: page.getByText(label, { exact: true }) })
          .locator('b'),
      ).toHaveText(String(expected));
    };
    const query = async (stage: string, status: string | string[], factory: string | null) => {
      await go(zh.nav.query);
      await expect(page.getByRole('heading', { name: zh.query.title, exact: true })).toBeVisible();
      await expect(page.locator('.el-loading-mask:visible,.ant-spin-spinning:visible')).toHaveCount(
        0,
      );
      await page.getByPlaceholder('PI', { exact: true }).fill(pi);
      const response = page.waitForResponse(
        (r) =>
          r.request().method() === 'GET' &&
          new URL(r.url()).pathname === (source ? '/api/sn' : '/api/v1/sn/items/page') &&
          new URL(r.url()).searchParams.get('pi') === pi,
      );
      await page.getByRole('button', { name: zh.common.search, exact: true }).click();
      const raw = await response;
      expect(raw.status()).toBe(200);
      const body = await raw.json();
      const values = (source ? body.items : body.data.items) as Record<string, unknown>[];
      const observed = values.map((v) => ({
        sn: v.sn,
        status: v.status,
        pi: v.pi_no ?? v.pi,
        factory: v.factory_code ?? v.factoryCode ?? null,
      }));
      expect(observed.map((v) => v.sn).sort()).toEqual(sns);
      expect(observed.map((v) => v.status)).toEqual(
        Array.isArray(status) ? status : [status, status, status],
      );
      if (factory !== null)
        expect(observed.map((v) => v.factory)).toEqual([factory, factory, factory]);
      await expect(rows()).toHaveCount(3);
      facts.push({ stage, observed });
      await capture(stage);
    };
    const takePrint = async (factory: string, name: string) => {
      await go(zh.nav.acquire);
      await expect(
        page.getByRole('heading', { name: zh.acquire.title, exact: true }),
      ).toBeVisible();
      await page.getByPlaceholder('PI', { exact: true }).fill(pi);
      await page.getByPlaceholder('PI', { exact: true }).press('Enter');
      const summary = () =>
        panel()
          .locator(source ? '.el-table__row' : '.ant-table-row')
          .filter({ hasText: pi })
          .filter({ hasText: factory });
      await page.getByRole('tab', { name: /^待领取/ }).click();
      await summary().getByRole('button', { name: zh.acquire.take, exact: true }).click();
      await confirm();
      await expect(summary()).toHaveCount(0);
      await page.getByRole('tab', { name: /^待打印/ }).click();
      await summary().getByRole('button', { name: zh.acquire.print, exact: true }).click();
      const download = page.waitForEvent('download');
      await confirm();
      const file = await download;
      expect(file.suggestedFilename()).toMatch(/^print_P[0-9]{18}\.xlsx$/);
      await file.saveAs(info.outputPath(name + '.xlsx'));
      files.push({ name: name + '.xlsx', sns });
      await expect(summary()).toHaveCount(0);
      await page.getByRole('tab', { name: zh.acquire.prints, exact: true }).click();
      await capture(name);
    };
    let loggedIn = false;
    try {
      await page.goto(origin + '/login');
      await page.locator('input[autocomplete=username]').fill(username);
      await page.locator('input[autocomplete=current-password]').fill(password);
      await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
      await expect(page.getByRole('menuitem', { name: zh.nav.orders, exact: true })).toBeVisible();
      loggedIn = true;
      writeExtra(
        JSON.stringify([
          {
            'F_ora_kh.FNumber': 'CMP-LOOP-CUSTOMER',
            FBillNo: bill,
            F_HX_PO: 'CMP-LOOP-PO',
            F_HX_PI: pi,
            'FMaterialId.FNumber': 'CMP-LOOP-MAT',
            'FMaterialId.FName': '全流程验收物料',
            FQty: '3',
            FStatus: '1',
            FSaleOrderNo: 'CMP-LOOP-SO',
            'FPrdOrgId.FName': '比较测试甲厂',
            FID: String(9007199254900000n + BigInt(run.slice(-6))),
            FTreeEntity_FEntryId: '5401',
            FPrdOrgId: '101',
            'FPrdOrgId.FNumber': 'CMP-A',
          },
        ]),
      );
      await sync();
      await expect(page.getByText(bill, { exact: true })).toBeVisible();
      await capture('01-order-synced');
      await go(zh.nav.rules);
      await page.getByRole('button', { name: zh.rules.create, exact: true }).click();
      const editor = page.getByRole('dialog');
      await field(editor, zh.rules.code).fill('LOOP_' + run);
      await field(editor, zh.rules.name).fill('完整界面闭环 ' + run);
      await editor
        .locator('label')
        .filter({ has: page.getByRole('radio', { name: zh.rules.scopePI, exact: true }) })
        .click();
      await field(editor, 'PI').fill(pi);
      await field(editor, zh.rules.prefix).fill(prefix);
      await editor.getByRole('button', { name: zh.common.save, exact: true }).click();
      await expect(editor).toBeHidden();
      await expect(rows().filter({ hasText: 'LOOP_' + run })).toHaveCount(1);
      await capture('02-rule-created');
      await go(zh.nav.orders);
      await rows().filter({ hasText: bill }).click();
      await page.getByRole('button', { name: zh.orders.toGenerate, exact: true }).click();
      await stat(zh.orders.quota, 3);
      await field(page.locator('main'), zh.generate.qty).fill('3');
      await field(page.locator('main'), zh.generate.qty).blur();
      await page.getByRole('button', { name: zh.generate.preview, exact: true }).click();
      await expect(
        page.getByRole('button', { name: zh.generate.generate, exact: true }),
      ).toBeEnabled();
      await expect(page.getByText(new RegExp(sns[0] + '\\s*~\\s*' + sns[2])).first()).toBeVisible();
      await capture('03-preview');
      await page.getByRole('button', { name: zh.generate.generate, exact: true }).click();
      await confirm();
      await stat(zh.orders.generated, 3);
      await stat(zh.orders.quota, 0);
      await stat(zh.generate.pendingAlloc, 3);
      await capture('04-generated');
      await field(page.locator('main'), zh.generate.allocQty).fill('3');
      await field(page.locator('main'), zh.generate.allocQty).blur();
      await page.getByRole('button', { name: zh.generate.allocate, exact: true }).click();
      await confirm();
      await stat(zh.generate.allocated, 3);
      await stat(zh.generate.pendingAlloc, 0);
      await query('05-allocated', 'TO_ACQUIRE', 'CMP-A');
      if (mesMode) {
        mes = await createMesProbe(source, username, password, pi, sns, mesObservations);
        await mes.read('CMP-A', ['TO_ACQUIRE', 'TO_ACQUIRE', 'TO_ACQUIRE']);
        firstMesBatch = await mes.take('CMP-A');
        await query('MES-06-taken', 'TO_PRINT', 'CMP-A');
        await mes.rejectInvalid(firstMesBatch);
        const partial = { sns: [sns[0]!, sns[0]!, 'CMP-NOT-IN-THIS-BATCH'] };
        await mes.callback(firstMesBatch, partial, 'partial', {
          updated: 1,
          already: 0,
          blocked: 1,
        });
        await query('MES-06-partial', ['PRINTED', 'TO_PRINT', 'TO_PRINT'], 'CMP-A');
        await mes.callback(firstMesBatch, partial, 'already-printed', {
          updated: 0,
          already: 1,
          blocked: 1,
        });
        await mes.callback(
          firstMesBatch,
          { ranges: [{ startSn: sns[2]!, endSn: sns[1]! }] },
          'reverse-range',
          { updated: 2, already: 0, blocked: 0 },
        );
        await mes.read('CMP-A', ['PRINTED', 'PRINTED', 'PRINTED']);
      } else await takePrint('比较测试甲厂', '06-first-print');
      await query('07-printed', 'PRINTED', 'CMP-A');
      await go(zh.nav.transfer);
      await page.getByRole('tab', { name: zh.transfer.directTab, exact: true }).click();
      if (source) {
        for (const [label, name] of [
          [zh.transfer.fromFactory, '比较测试甲厂（CMP-A）'],
          [zh.transfer.toFactory, '比较测试乙厂（CMP-B）'],
        ]) {
          const combo = item(panel(), label!).getByRole('combobox');
          await combo.click();
          const id = await combo.getAttribute('aria-controls');
          await page.locator(`[id="${id}"]`).getByRole('option', { name, exact: true }).click();
        }
        await field(panel(), 'PI').fill(pi);
        await field(panel(), zh.transfer.toPi).fill(pi);
        await field(panel(), zh.transfer.reason).fill('完整界面闭环 ' + run);
      } else {
        await panel()
          .getByRole('combobox', { name: ui.snPages.common.factory, exact: true })
          .click();
        await page.getByRole('option', { name: '比较测试甲厂（CMP-A）', exact: true }).click();
        await page
          .getByRole('textbox', { name: ui.snTransfer.source + ' PI', exact: true })
          .fill(pi);
        const target = page.getByRole('combobox', { name: ui.snTransfer.toFactory, exact: true });
        await target.click();
        const id = await target.getAttribute('aria-controls');
        await page
          .locator('.ant-select-dropdown')
          .filter({ has: page.locator(`[id="${id}"]`) })
          .locator('.ant-select-item-option-content')
          .getByText('比较测试乙厂（CMP-B）', { exact: true })
          .click();
        await page.getByRole('textbox', { name: ui.snTransfer.toPi, exact: true }).fill(pi);
        await page
          .getByRole('textbox', { name: ui.snTransfer.reason, exact: true })
          .fill('完整界面闭环 ' + run);
      }
      await expect(panel()).toContainText('可转移 3 枚（范围内共 3 枚）');
      await page.getByRole('button', { name: zh.transfer.directSubmit, exact: true }).click();
      await confirm();
      await expect(
        page.getByRole('tab', { name: zh.transfer.records, exact: true }),
      ).toHaveAttribute('aria-selected', 'true');
      await query('08-transferred', 'TO_ACQUIRE', 'CMP-B');
      if (mes) {
        await mes.callback(firstMesBatch!, { sns }, 'old-batch-after-transfer', {
          updated: 0,
          already: 0,
          blocked: 3,
        });
        await query('MES-08-old-batch-protected', 'TO_ACQUIRE', 'CMP-B');
        const secondBatch = await mes.take('CMP-B');
        await mes.callback(secondBatch, { sns: [...sns, sns[0]!] }, 'new-batch-at-target', {
          updated: 3,
          already: 0,
          blocked: 0,
        });
        await mes.read('CMP-B', ['PRINTED', 'PRINTED', 'PRINTED']);
      } else await takePrint('比较测试乙厂', '09-second-print');
      await query('10-reprinted', 'PRINTED', 'CMP-B');
      await go(zh.nav.audit);
      await expect(page.getByRole('heading', { name: zh.audit.title, exact: true })).toBeVisible();
      await page.getByPlaceholder('PI', { exact: true }).fill(pi);
      const response = page.waitForResponse(
        (r) =>
          r.request().method() === 'GET' &&
          new URL(r.url()).pathname === (source ? '/api/audits' : '/api/v1/sn/audits/page') &&
          new URL(r.url()).searchParams.get('pi') === pi,
      );
      await page.getByRole('button', { name: zh.common.search, exact: true }).click();
      const raw = await response;
      expect(raw.status()).toBe(200);
      const body = await raw.json();
      const audits = (source ? body.items : body.data.items) as Record<string, unknown>[];
      facts.push({ stage: 'audit', audits });
      const actions: Record<string, string> = {
        'sn:generation:execute': 'SN_GENERATE',
        'sn:allocation:execute': 'SN_ALLOCATE',
        'sn:acquire:take': 'SN_ACQUIRE',
        'sn:print:execute': 'SN_PRINT',
        'sn:acquire:callback': 'SN_CALLBACK',
        'sn:transfer:direct': 'TRANSFER_DIRECT',
      };
      for (const action of [
        'SN_GENERATE',
        'SN_ALLOCATE',
        'SN_ACQUIRE',
        mesMode ? 'SN_CALLBACK' : 'SN_PRINT',
        'TRANSFER_DIRECT',
      ]) {
        expect(audits.some((r) => (actions[String(r.action)] ?? r.action) === action)).toBe(true);
      }
      await capture('11-audit');
      expect(errors).toEqual([]);
    } finally {
      writeExtra(original);
      try {
        if (loggedIn) {
          await page.goto(origin + '/orders');
          await sync();
        }
      } finally {
        await mes?.close();
        writeFileSync(
          info.outputPath('observations.json'),
          JSON.stringify({ pi, bill, sns, files, facts, mesObservations, errors }, null, 2),
        );
      }
    }
  },
);
