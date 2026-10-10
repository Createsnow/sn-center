import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, renameSync } from 'node:fs';
import { resolve } from 'node:path';
import { protectTestSecrets } from '../privacy';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import en from '../../src/i18n/prototype/en.json' with { type: 'json' };
import vi from '../../src/i18n/prototype/vi.json' with { type: 'json' };
import errorsZh from '../../src/i18n/prototype/errors.zh-CN.json' with { type: 'json' };
import errorsEn from '../../src/i18n/prototype/errors.en.json' with { type: 'json' };
import errorsVi from '../../src/i18n/prototype/errors.vi.json' with { type: 'json' };

type WireCase = {
  label: string;
  operation: 'login' | 'orders' | 'view';
  body: unknown;
  error?: string;
  removes?: boolean;
  encodeTwice?: boolean;
  laterPage?: boolean;
  httpStatus?: number;
};
const failure = (messages?: unknown[]) => ({
  Result: {
    ResponseStatus: {
      IsSuccess: false,
      ...(messages ? { Errors: messages.map((Message) => ({ Message })) } : {}),
    },
  },
});
const cases: WireCase[] = [
  { label: 'login-missing', operation: 'login', body: {}, error: 'LoginResultType != 1' },
  {
    label: 'login-boolean',
    operation: 'login',
    body: { LoginResultType: true },
    error: 'LoginResultType != 1',
  },
  {
    label: 'login-number-message',
    operation: 'login',
    body: { LoginResultType: 0, Message: 123 },
    error: '123',
  },
  { label: 'login-scalar', operation: 'login', body: '暂时不可用', error: 'LoginResultType != 1' },
  { label: 'query-object', operation: 'orders', body: {}, error: 'unexpected response' },
  { label: 'query-scalar', operation: 'orders', body: 'OK', error: 'unexpected response' },
  {
    label: 'query-message-values',
    operation: 'orders',
    body: failure([' \u0085组织权限不足\u0085 ', 123, false, null]),
    error: '组织权限不足; 123; false',
  },
  { label: 'query-empty-message', operation: 'orders', body: failure(['   ']), error: '' },
  {
    label: 'query-nested-encoded',
    operation: 'orders',
    body: [[failure(['查询权限不足'])]],
    encodeTwice: true,
    error: '查询权限不足',
  },
  { label: 'view-missing-status', operation: 'view', body: {}, error: 'View failed' },
  { label: 'view-fallback-message', operation: 'view', body: failure(), error: '查询失败' },
  { label: 'query-http-503', operation: 'orders', body: {}, httpStatus: 503, error: 'HTTP 503' },
  {
    label: 'query-later-page',
    operation: 'orders',
    body: [[failure(['后续页失败'])]],
    laterPage: true,
    error: '后续页失败',
  },
  { label: 'login-decimal', operation: 'login', body: { LoginResultType: 1.25 } },
  {
    label: 'login-empty-session',
    operation: 'login',
    body: { LoginResultType: 1, KDSVCSessionId: '   ' },
  },
  {
    label: 'view-decimal-missing',
    operation: 'view',
    body: { Result: { ResponseStatus: { IsSuccess: false, MsgCode: 10.25 } } },
    removes: true,
  },
  {
    label: 'view-empty-document',
    operation: 'view',
    body: { Result: { ResponseStatus: { IsSuccess: true }, Result: {} } },
    removes: true,
  },
  {
    label: 'view-empty-lines',
    operation: 'view',
    body: { Result: { ResponseStatus: { IsSuccess: true }, Result: { TreeEntity: [] } } },
    removes: true,
  },
];

// 仅覆盖专用a48环境的合成K3协议，业务请求通过页面完成；失败不修改订单或分录快照。
test('P05/P12 K3 response shapes, messages, later-page rejection and empty documents', async ({
  page,
  request,
}, info) => {
  test.setTimeout(480_000);
  page.setDefaultTimeout(15_000);
  const prototype = info.project.name === 'prototype';
  const kind = prototype ? 'python' : 'java';
  const tag = process.env.ADTEK_K3_SHAPES_RUN!;
  expect(tag).toMatch(/^[0-9]{8,14}$/);
  const username = 'audit' + tag;
  const helper = (action: string) =>
    JSON.parse(
      execFileSync(
        resolve('../../sn-center-python/.venv/Scripts/python.exe'),
        [
          '-X',
          'utf8',
          resolve('../tools/testing/parity-order-audit-fixture.py'),
          kind,
          action,
          tag,
        ],
        { windowsHide: true, encoding: 'utf8' },
      ),
    );
  helper('prepare');
  const password = readFileSync(
    resolve('../deploy/.secrets/comparison-20261008/' + kind + '-admin-password'),
    'utf8',
  ).trim();
  protectTestSecrets(password);
  const controlFile = resolve('../tools/.runtime/comparison-20261008/order-audits/k3-control.json');
  const control = (value: unknown) => {
    writeFileSync(controlFile + '.next', JSON.stringify(value));
    renameSync(controlFile + '.next', controlFile);
  };
  const observations: unknown[] = [];
  const pageErrors: string[] = [];
  page.on('pageerror', (error) => pageErrors.push(error.message));
  const messages = page.locator(prototype ? '.el-message' : '.ant-message-notice');
  const sync = async (
    label: string,
    text: typeof zh,
    expected: string,
    one: boolean,
    status: number,
  ) => {
    await expect(messages).toHaveCount(0);
    if (one)
      await page.getByPlaceholder(text.orders.billToSync, { exact: true }).fill('CMP-MO-001');
    const snapshot = helper('snapshot');
    const before = (await (await request.get('http://127.0.0.1:19091')).json()).wireSequence;
    const received = page.waitForResponse(
      (r) => r.request().method() === 'POST' && new URL(r.url()).pathname.endsWith('/orders/sync'),
    );
    await page
      .getByRole('button', { name: one ? text.orders.syncOne : text.orders.syncAll, exact: true })
      .click();
    const response = await received;
    await expect(messages).toHaveCount(1);
    const actual = await messages.innerText();
    expect.soft(response.status(), label).toBe(status);
    expect.soft(actual, label).toBe(expected.trimEnd());
    await expect(page.locator('.sn-page-actions [aria-busy="true"]')).toHaveCount(0);
    await expect(page.locator('.ant-spin-spinning:visible,.el-loading-mask:visible')).toHaveCount(
      0,
    );
    const after = helper('snapshot');
    if (status === 502) {
      expect.soft(after.orders, label + ' orders').toEqual(snapshot.orders);
      expect.soft(after.entries, label + ' entries').toEqual(snapshot.entries);
    }
    const traffic = (
      await (await request.get('http://127.0.0.1:19091')).json()
    ).wireRequests.filter((r: { sequence: number }) => r.sequence > before);
    const body = await response.json();
    observations.push({
      label,
      status: response.status(),
      message: actual,
      body,
      traffic,
      orders: after.orders,
      entries: after.entries,
    });
    await page.mouse.move(0, 0);
    await page.screenshot({ path: info.outputPath(label + '.png'), animations: 'disabled' });
    if (response.status() !== 200)
      await messages.locator(prototype ? '.el-message__closeBtn' : 'button').click();
    await expect(messages).toHaveCount(0);
    return { after, traffic };
  };
  try {
    control({});
    await page.goto((prototype ? 'http://127.0.0.1:8001' : 'http://127.0.0.1:15174') + '/login');
    await page.locator('input[autocomplete="username"]').fill(username);
    await page.locator('input[autocomplete="current-password"]').fill(password);
    await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
    await page.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
    await sync(
      'initial-snapshot',
      zh,
      zh.orders.syncedAll.replace('{{bills}}', '3').replace('{{rows}}', '4'),
      false,
      200,
    );
    for (const [locale, language, text, errors] of [
      ['zh-CN', '简体中文', zh, errorsZh],
      ['en', 'English', en, errorsEn],
      ['vi', 'Tiếng Việt', vi, errorsVi],
    ] as const) {
      const languageControl = page.locator(
        prototype ? '.header .lang' : '.prototype-language .ant-select',
      );
      if (!(await languageControl.innerText()).includes(language)) {
        const changed = page.waitForResponse(
          (r) =>
            r.request().method() !== 'GET' &&
            new URL(r.url()).pathname.endsWith(prototype ? '/auth/lang' : '/system/auth/language'),
        );
        await languageControl.click();
        await page.getByRole('option', { name: language, exact: true }).click();
        await changed;
      }
      for (const example of cases) {
        control({
          ...(example.laterPage ? { orderCount: 2000 } : {}),
          wire: {
            [example.operation]: {
              body: example.body,
              status: example.httpStatus ?? 200,
              encodeTwice: example.encodeTwice,
              fromStart: example.laterPage ? 2000 : 0,
            },
          },
        });
        const rejected = example.error !== undefined;
        const expected = rejected
          ? (example.operation === 'login'
              ? errors.K3_LOGIN_FAILED
              : errors.K3_QUERY_FAILED
            ).replace('{{message}}', example.error!)
          : example.removes
            ? text.orders.syncedRemoved.replace('{{bill}}', 'CMP-MO-001')
            : text.orders.syncedOne.replace('{{bill}}', 'CMP-MO-001').replace('{{rows}}', '2');
        const observed = await sync(
          locale + '-' + example.label,
          text,
          expected,
          example.operation !== 'orders',
          rejected ? 502 : 200,
        );
        if (example.laterPage)
          expect
            .soft(
              observed.traffic
                .filter((r: { operation: string }) => r.operation === 'orders')
                .map((r: { start: number }) => r.start),
            )
            .toEqual([0, 2000]);
        if (example.removes) {
          expect
            .soft(
              observed.after.orders.filter(
                (r: { bill_no: string; active?: number }) =>
                  r.bill_no === 'CMP-MO-001' && (prototype || r.active === 1),
              ),
            )
            .toHaveLength(0);
          const tableRows = page.locator(prototype ? '.el-table__row' : '.ant-table-row');
          await expect(tableRows.getByText('CMP-MO-001', { exact: true })).toHaveCount(0);
          control({});
          await sync(
            locale + '-' + example.label + '-restore',
            text,
            text.orders.syncedAll.replace('{{bills}}', '3').replace('{{rows}}', '4'),
            false,
            200,
          );
          await expect(tableRows.getByText('CMP-MO-001', { exact: true })).toBeVisible();
        }
      }
    }
    control({});
    const facts = helper('snapshot').audits.filter(
      (r: Record<string, unknown>) =>
        r.operator === username &&
        ['ORDER_SYNC', 'sn:order:sync'].includes(String(r.action)) &&
        ['FAIL', 'FAILED'].includes(String(r.result)),
    );
    writeFileSync(info.outputPath('failure-audits.json'), JSON.stringify(facts, null, 2));
    expect.soft(facts).toHaveLength(39);
    await page.getByRole('menuitem', { name: vi.nav.audit, exact: true }).click();
    await page.getByPlaceholder(vi.audit.operator, { exact: true }).fill(username);
    const filters = page.locator(prototype ? '.toolbar .el-select' : '.sn-toolbar .ant-select');
    await filters.nth(0).click();
    await page.getByRole('option', { name: vi.actions.ORDER_SYNC, exact: true }).click();
    await filters.nth(1).click();
    await page.getByRole('option', { name: vi.audit.fail, exact: true }).click();
    const auditReady = () =>
      page.waitForResponse(
        (r) =>
          r.request().method() === 'GET' &&
          new URL(r.url()).pathname === (prototype ? '/api/audits' : '/api/v1/sn/audits/page') &&
          new URL(r.url()).searchParams.get('operator') === username,
      );
    const searched = auditReady();
    await page.getByRole('button', { name: vi.common.search, exact: true }).click();
    await searched;
    await page
      .locator(
        prototype ? '.el-pagination__sizes .el-select' : '.ant-pagination-options .ant-select',
      )
      .click();
    const resized = auditReady();
    await page.getByRole('option', { name: /^100\s*\// }).click();
    const auditBody = await (await resized).json();
    const items = prototype ? auditBody.items : auditBody.data.items;
    expect(items).toHaveLength(39);
    const expectedMessages = cases
      .filter((example) => example.error !== undefined)
      .map((example) =>
        (example.operation === 'login'
          ? errorsZh.K3_LOGIN_FAILED
          : errorsZh.K3_QUERY_FAILED
        ).replace('{{message}}', example.error!),
      );
    expect(
      items.map((r: Record<string, unknown>) => r[prototype ? 'error_msg' : 'errorMessage']).sort(),
    ).toEqual([...expectedMessages, ...expectedMessages, ...expectedMessages].sort());
    const rows = page.locator(prototype ? '.el-table__row' : '.ant-table-row');
    await expect(rows).toHaveCount(39);
    const details: unknown[] = [];
    for (let i = 0; i < items.length; i++) {
      const item = items[i];
      expect(item.operator).toBe(username);
      expect(item.action).toBe(prototype ? 'ORDER_SYNC' : 'sn:order:sync');
      expect(item.result).toBe(prototype ? 'FAIL' : 'FAILED');
      expect(item.source).toBe('PAGE');
      expect(item.detail).toBe('POST /api/orders/sync');
      expect(item[prototype ? 'qty' : 'quantity']).toBeNull();
      const expand = rows.nth(i).locator(prototype ? '.el-table__expand-icon' : '.sn-audit-expand');
      await expand.click();
      const detail = page
        .locator(prototype ? '.el-descriptions' : '.sn-audit-details')
        .filter({ visible: true });
      await expect(
        detail.getByText(item[prototype ? 'error_msg' : 'errorMessage'], { exact: true }),
      ).toBeVisible();
      await expect(detail.getByText(item.detail, { exact: true })).toBeVisible();
      details.push({ item, visible: await detail.innerText() });
      await page.screenshot({
        path: info.outputPath('audit-detail-' + i + '.png'),
        animations: 'disabled',
      });
      await expand.click();
    }
    writeFileSync(info.outputPath('audit-visible-details.json'), JSON.stringify(details, null, 2));
    await expect(page.locator('.ant-spin-spinning:visible,.el-loading-mask:visible')).toHaveCount(
      0,
    );
    await page.screenshot({
      path: info.outputPath('audit-actual-actions.png'),
      animations: 'disabled',
    });
    expect(pageErrors).toEqual([]);
  } finally {
    control({});
    writeFileSync(
      info.outputPath('observations.json'),
      JSON.stringify({ observations, pageErrors }, null, 2),
    );
  }
});
