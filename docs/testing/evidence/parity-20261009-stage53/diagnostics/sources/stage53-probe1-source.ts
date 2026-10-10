import { test, expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { protectTestSecrets } from '../privacy';
import { settleOrderPage } from './settleOrderPage';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };

// 仅专用a48库；延后首个真实GET的发送，不伪造响应或修改浏览器滚动位置。
test('P05 scroll: initial navigation and full sync with real request overlap', async ({
  page,
}, info) => {
  test.setTimeout(240_000);
  page.setDefaultTimeout(15_000);
  const prototype = info.project.name === 'prototype';
  const kind = prototype ? 'python' : 'java';
  const run = process.env.ADTEK_ORDER_SCROLL_RUN!;
  expect(run).toMatch(/^[0-9]{8,14}$/);
  execFileSync(
    resolve('../../sn-center-python/.venv/Scripts/python.exe'),
    ['-X', 'utf8', resolve('../tools/testing/parity-order-audit-fixture.py'), kind, 'prepare', run],
    { windowsHide: true },
  );
  const password = readFileSync(
    resolve('../deploy/.secrets/comparison-20261008/' + kind + '-admin-password'),
    'utf8',
  ).trim();
  protectTestSecrets(password);
  const origin = prototype ? 'http://127.0.0.1:8001' : 'http://127.0.0.1:15174';
  const prefix = prototype ? '/api/orders' : '/api/v1/sn/orders';
  const records: unknown[] = [],
    requests: unknown[] = [],
    errors: string[] = [];
  let phase = 'login';
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('request', (r) => {
    if (new URL(r.url()).pathname.startsWith(prefix))
      requests.push({
        phase,
        event: 'request',
        method: r.method(),
        path: new URL(r.url()).pathname,
        at: Date.now(),
      });
  });
  page.on('response', (r) => {
    if (new URL(r.url()).pathname.startsWith(prefix))
      requests.push({
        phase,
        event: 'response',
        method: r.request().method(),
        path: new URL(r.url()).pathname,
        status: r.status(),
        at: Date.now(),
      });
  });
  const beginObservation = async () => {
    await page.evaluate(() => {
      const frames: unknown[] = [];
      let running = true,
        previous = '';
      const sample = (event: string) => {
        const main = document.querySelector('main');
        const content = document.querySelector('.page,.sn-orders-page,.sn-dashboard-page');
        const rect = content?.getBoundingClientRect();
        const state = {
          scroll: main?.scrollTop,
          height: main?.clientHeight,
          scrollHeight: main?.scrollHeight,
          mainClass: main?.className,
          pageClass: content?.className,
          pageBox: rect ? [rect.x, rect.y, rect.width, rect.height] : null,
          focus: { tag: document.activeElement?.tagName, cls: document.activeElement?.className },
          loading: document.querySelectorAll('.ant-spin-spinning,.el-loading-mask').length,
        };
        const value = JSON.stringify(state);
        if (value !== previous || event !== 'frame') {
          frames.push({ event, time: performance.now(), ...state });
          previous = value;
        }
      };
      const scroll = () => sample('scroll'),
        focus = () => sample('focus');
      document.addEventListener('scroll', scroll, true);
      document.addEventListener('focusin', focus, true);
      const tick = () => {
        if (running) {
          sample('frame');
          requestAnimationFrame(tick);
        }
      };
      tick();
      Object.assign(window, {
        orderScrollObservation: {
          mark: (label: string) => sample(label),
          stop: () => {
            running = false;
            document.removeEventListener('scroll', scroll, true);
            document.removeEventListener('focusin', focus, true);
            sample('stop');
            return frames;
          },
        },
      });
    });
  };
  const mark = (label: string) =>
    page.evaluate((name) => {
      (
        window as unknown as { orderScrollObservation: { mark: (label: string) => void } }
      ).orderScrollObservation.mark(name);
    }, label);
  try {
    await page.goto(origin + '/login');
    await page.locator('input[autocomplete=username]').fill('audit' + run);
    await page.locator('input[autocomplete=current-password]').fill(password);
    await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
    await expect(page.getByRole('menuitem', { name: zh.nav.orders, exact: true })).toBeVisible();
    for (const mode of ['fast-1', 'settled', 'hold-meta', 'hold-orders', 'fast-2', 'fast-3']) {
      phase = mode;
      await page.getByRole('menuitem', { name: zh.nav.dashboard, exact: true }).click();
      await settleOrderPage(page);
      let release = () => {},
        held = false,
        announce = () => {};
      const gate = new Promise<void>((resolve) => {
        release = resolve;
      });
      const heldRequest = new Promise<void>((resolve) => {
        announce = resolve;
      });
      const gatedPath = mode === 'hold-meta' ? prefix + '/meta' : prefix;
      const pattern = (url: URL) => url.pathname === gatedPath;
      if (mode.startsWith('hold')) {
        await page.route(pattern, async (route) => {
          if (route.request().method() === 'GET' && !held) {
            held = true;
            requests.push({ phase, event: 'held', path: gatedPath, at: Date.now() });
            announce();
            await gate;
          }
          await route.continue();
        });
      }
      await beginObservation();
      try {
        await page.getByRole('menuitem', { name: zh.nav.orders, exact: true }).click();
        if (mode.startsWith('hold')) await heldRequest;
        else await expect(page.getByText('CMP-MO-002', { exact: true })).toBeVisible();
        if (mode === 'settled') {
          await expect(
            page.getByRole('button', { name: zh.orders.sched.settings, exact: true }),
          ).toBeVisible();
          await expect(
            page.locator('.ant-spin-spinning:visible,.el-loading-mask:visible'),
          ).toHaveCount(0);
          await settleOrderPage(page);
        }
        await mark('before-sync');
        const synced = page.waitForResponse(
          (r) => r.request().method() === 'POST' && new URL(r.url()).pathname === prefix + '/sync',
        );
        await page.getByRole('button', { name: zh.orders.syncAll, exact: true }).click();
        expect((await synced).status()).toBe(200);
        await mark('sync-response');
        release();
        await expect(page.getByText('CMP-MO-002', { exact: true })).toBeVisible();
        await expect(
          page.getByRole('button', { name: zh.orders.syncAll, exact: true }),
        ).toBeEnabled();
        await expect(
          page.locator('.ant-spin-spinning:visible,.el-loading-mask:visible'),
        ).toHaveCount(0);
        await expect(page.locator(prototype ? '.el-message' : '.ant-message-notice')).toHaveCount(
          0,
        );
        await settleOrderPage(page);
        await mark('settled');
        await page.screenshot({ path: info.outputPath(mode + '.png'), animations: 'disabled' });
      } finally {
        release();
        const frames = await page.evaluate(() =>
          (
            window as unknown as { orderScrollObservation: { stop: () => unknown[] } }
          ).orderScrollObservation.stop(),
        );
        records.push({ mode, frames });
        if (mode.startsWith('hold')) await page.unroute(pattern);
      }
    }
    expect(errors).toEqual([]);
  } finally {
    writeFileSync(
      info.outputPath('observations.json'),
      JSON.stringify({ records, requests, errors }, null, 2),
    );
  }
});
