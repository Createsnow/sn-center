import { test, expect, type Locator } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { protectTestSecrets } from '../privacy';
import { paceComparisonLogin } from './login-budget';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import errors from '../../src/i18n/prototype/errors.zh-CN.json' with { type: 'json' };

const PI = 'CMP-UI-IMPORT-CAPACITY';
const runtime = resolve('../tools/.runtime/comparison-20261008');

/** 大文件留在文本框时只轮询目标短文本，避免失败诊断反复收集整页巨型文本。 */
async function expectShortText(locator: Locator, text: string, timeout: number) {
  await expect
    .poll(() => locator.evaluateAll((nodes) => nodes.map((node) => node.textContent?.trim())), {
      timeout,
      intervals: [1_000, 2_000, 5_000],
    })
    .toEqual([text]);
}

for (const mode of ['limit', 'maximum'] as const)
  test(`P08 capacity ${mode}: ${mode === 'limit' ? 'rejects 500001' : 'imports 500000 full-width Unicode SN'} through file upload`, async ({
    page,
  }, info) => {
    test.setTimeout(1_200_000);
    page.setDefaultTimeout(120_000);
    const prototype = info.project.name === 'prototype';
    const kind = prototype ? 'python' : 'java';
    const fixture = (action: 'begin' | 'observe' | 'clean') =>
      execFileSync(
        resolve('../../sn-center-python/.venv/Scripts/python.exe'),
        [resolve('../tools/testing/parity-import-capacity-fixture.py'), kind, action],
        { windowsHide: true, encoding: 'utf8', maxBuffer: 4 * 1024 * 1024 },
      );
    const pageErrors: string[] = [];
    page.on('pageerror', (error) => {
      pageErrors.push(error.message);
      writeFileSync(info.outputPath('page-errors.json'), JSON.stringify(pageErrors, null, 2));
    });
    fixture('begin');
    try {
      await page.goto('/login');
      await paceComparisonLogin(page);
      const password = readFileSync(
        resolve(`../deploy/.secrets/comparison-20261008/${kind}-admin-password`),
        'utf8',
      ).trim();
      protectTestSecrets(password);
      await page.locator('input[autocomplete="username"]').fill('admin');
      await page.locator('input[autocomplete="current-password"]').fill(password);
      await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
      await page.getByRole('menuitem', { name: zh.nav.piInit, exact: true }).click();
      await page.getByPlaceholder(zh.piInit.piPlaceholder, { exact: true }).fill(PI);
      await page.getByRole('button', { name: zh.common.search, exact: true }).click();
      await expect(
        page.locator(prototype ? '.el-descriptions b' : '.ant-descriptions b'),
      ).toHaveText(PI);
      const form = page.locator(prototype ? '.field-stack' : '.sn-init-form');
      const start = prototype
        ? page
            .locator('.el-form-item')
            .filter({ has: page.locator('label').filter({ hasText: zh.generate.start }) })
            .locator('input')
        : page.getByRole('textbox', { name: zh.generate.start, exact: true });
      // 原型使用原生label + input；按已查询表单的第一个输入定位起号。
      const startInput = prototype ? form.locator('input').first() : start;
      await startInput.fill('10');
      const upload = page.locator('input[type="file"]');
      const submit = page.locator(
        prototype
          ? '.surface:has(.field-stack) > button'
          : '.sn-surface:has(.sn-init-form) > button',
      );
      const parsedCount = page.locator(
        prototype ? '.file-row > .muted' : '.sn-init-file-row > .sn-muted',
      );
      const locked = page.locator(prototype ? '.el-alert__title' : '.ant-alert-title');
      const dialog = page.locator(prototype ? '.el-message-box' : '.ant-modal-confirm');
      const confirm = async (n: number) => {
        console.log('capacity: confirming', mode, n);
        await expect(submit).toHaveText(zh.piInit.submit);
        await submit.click();
        await expect(dialog).toContainText(
          zh.piInit.confirm
            .replace('{{pi}}', PI)
            .replace('{{start}}', '10')
            .replace('{{n}}', String(n)),
        );
        await dialog.getByRole('button', { name: /^(确定|确认)$/ }).click();
      };
      if (mode === 'limit') {
        console.log('capacity: uploading over-limit file');
        await upload.setInputFiles(resolve(runtime, 'capacity-over.txt'));
        await expectShortText(parsedCount, zh.piInit.parsed.replace('{{n}}', '500001'), 180_000);
        const feedbackSelector = prototype
          ? '.el-message--error .el-message__content'
          : '.sn-prototype-error-message';
        // 在浏览器绘制帧内等待可见短提示，避免大文件期间跨进程读取错过五秒提示。
        const expectedFeedback = errors.PI_IMPORT_TOO_LARGE.replace('{{max}}', '500000');
        const observed = page.waitForFunction(
          ({ selector, expected }) => {
            const node = [...document.querySelectorAll(selector)].at(-1) as HTMLElement | undefined;
            return (
              !!node &&
              node.textContent?.trim() === expected &&
              node.getBoundingClientRect().height > 0
            );
          },
          { selector: feedbackSelector, expected: expectedFeedback },
          { polling: 'raf', timeout: 180_000 },
        );
        await Promise.all([confirm(500001), observed]);
        writeFileSync(
          info.outputPath('feedback-observations.json'),
          JSON.stringify({ text: expectedFeedback, browserFrameObserved: true }, null, 2),
        );
        await page.screenshot({ path: info.outputPath('over-limit.png'), fullPage: true });
        const rejected = JSON.parse(fixture('observe'));
        expect(rejected.sn_item.count).toBe(0);
        expect(rejected.history).toEqual([]);
        writeFileSync(info.outputPath('over-limit-facts.json'), JSON.stringify(rejected, null, 2));
        await expect(startInput).toHaveValue('10');
        expect(pageErrors).toEqual([]);
        return;
      }
      console.log('capacity: uploading maximum file');
      await upload.setInputFiles(resolve(runtime, 'capacity-valid.txt'));
      await expectShortText(parsedCount, zh.piInit.parsed.replace('{{n}}', '500000'), 180_000);
      console.log('capacity: maximum file parsed');
      const begun = Date.now();
      await confirm(500000);
      await expectShortText(locked, zh.piInit.locked.replace('{{start}}', '10'), 900_000);
      const submitToLockedMs = Date.now() - begun;
      console.log('capacity: import committed');
      await expect(submit).toHaveCount(0);
      const table = page.locator(prototype ? '.el-table__row' : '.sn-init-history .ant-table-row');
      await expect(table).toHaveCount(1);
      await expect(table.locator('td').nth(1)).toHaveText('500000');
      await expect(table.locator('td').nth(4)).toHaveText('capacity-valid.txt');
      await page.screenshot({ path: info.outputPath('capacity-complete.png'), fullPage: true });
      const complete = JSON.parse(fixture('observe'));
      expect(complete.sn_item.count).toBe(500000);
      expect(complete.sn_key).toEqual(complete.sn_item);
      writeFileSync(
        info.outputPath('capacity-complete-facts.json'),
        JSON.stringify(complete, null, 2),
      );
      writeFileSync(
        info.outputPath('duration.json'),
        JSON.stringify({ submitToLockedMs, submitToFactsMs: Date.now() - begun }, null, 2),
      );
      await page.reload();
      await page.getByPlaceholder(zh.piInit.piPlaceholder, { exact: true }).fill(PI);
      await page.getByRole('button', { name: zh.common.search, exact: true }).click();
      await expect(locked).toHaveText(zh.piInit.locked.replace('{{start}}', '10'));
      await expect(table).toHaveCount(1);
      await page.screenshot({ path: info.outputPath('capacity-reloaded.png'), fullPage: true });
      expect(pageErrors).toEqual([]);
    } finally {
      fixture('clean');
      writeFileSync(
        info.outputPath('baseline-preserved.json'),
        readFileSync(resolve(runtime, `capacity-clean-${kind}.json`)),
      );
    }
  });
