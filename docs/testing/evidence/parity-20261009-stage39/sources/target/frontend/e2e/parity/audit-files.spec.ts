import { expect, test } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import { kind, source, login, run } from './query-support';

const python = resolve('../../sn-center-python/.venv/Scripts/python.exe');

test('P12 audit files: original insertion order, typed cells and empty workbook', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  page.setDefaultTimeout(20_000);
  const fixture = (action: string) =>
    execFileSync(
      python,
      [
        '-X',
        'utf8',
        resolve('../tools/testing/parity-audit-fixture.py'),
        kind(info),
        action,
        '--file-cases',
      ],
      { windowsHide: true, encoding: 'utf8' },
    );
  fixture('begin');
  try {
    await login(
      page,
      'admin',
      readFileSync(
        resolve(`../deploy/.secrets/comparison-20261008/${kind(info)}-admin-password`),
        'utf8',
      ).trim(),
    );
    await page.getByRole('menuitem', { name: zh.nav.audit, exact: true }).click();
    await expect(page.getByRole('heading', { name: zh.audit.title, exact: true })).toBeVisible();
    await page.getByPlaceholder('PI', { exact: true }).fill('CMP-UI-AUDIT-VIEW-PI');
    await page.getByPlaceholder(zh.audit.operator, { exact: true }).fill('CMP_AUDIT_VIEW');
    const query = async () => {
      const ready = page.waitForResponse(
        (r) =>
          r.request().method() === 'GET' &&
          new URL(r.url()).pathname === (source(info) ? '/api/audits' : '/api/v1/sn/audits/page'),
      );
      await page.getByRole('button', { name: zh.common.search, exact: true }).click();
      expect((await ready).ok()).toBe(true);
    };
    await query();
    const rows = page.locator(
      source(info) ? '.el-table__row' : '[data-testid="sn-audit-items"] .ant-table-row',
    );
    await expect
      .poll(() =>
        rows.evaluateAll((els) =>
          els.slice(0, 4).map((el) => el.querySelectorAll('td')[8]?.textContent?.trim()),
        ),
      )
      .toEqual(['AV00053', 'AV00052', 'AV00051', 'AV00050']);
    for (const [name, keyword, expected] of [
      ['01-all', '', Array.from({ length: 54 }, (_, i) => `AV${String(54 - i).padStart(5, '0')}`)],
      ['02-text', 'AV00010', ['AV00010']],
      ['03-empty', 'NO-SUCH-AUDIT-SN', []],
    ] as const) {
      await page.getByPlaceholder(zh.audit.keyword, { exact: true }).fill(keyword);
      const ready = page.waitForEvent('download');
      await page.getByRole('button', { name: zh.query.exportXlsx, exact: true }).click();
      const file = await ready;
      expect(file.suggestedFilename()).toBe('audit_export.xlsx');
      await file.saveAs(info.outputPath(name + '.xlsx'));
      const parsed = JSON.parse(
        execFileSync(
          python,
          [
            '-X',
            'utf8',
            resolve('../tools/testing/parity-query-file.py'),
            info.outputPath(name + '.xlsx'),
          ],
          { windowsHide: true, encoding: 'utf8' },
        ),
      ) as { sheet: string; raw: unknown[][]; types: string[][]; rows: string[][] };
      expect(parsed.sheet).toBe('audit');
      expect(parsed.raw[0]).toHaveLength(24);
      expect(parsed.rows.slice(1).map((row) => row[12])).toEqual(expected);
      expect(parsed.types.flat()).not.toContain('f');
      if (name === '01-all') {
        const row = (sn: string) => parsed.rows.findIndex((r) => r[12] === sn);
        expect(parsed.raw[row('AV00007')]?.[14]).toBe(999999999999999);
        expect(parsed.raw[row('AV00008')]?.[14]).toBe('9999999999999999');
        expect(parsed.types[row('AV00007')]?.[14]).toBe('n');
        expect(parsed.types[row('AV00008')]?.[14]).toBe('s');
        expect(parsed.raw[row('AV00009')]?.[14]).toBe(0);
        expect(parsed.raw[row('AV00010')]?.[2]).toBe('=2+2');
        expect(parsed.raw[row('AV00010')]?.[10]).toBe('000001');
        expect(parsed.raw[row('AV00010')]?.[20]).toBe('逗号,引号"与\n\n换行');
        expect(parsed.types[row('AV00012')]?.[20]).toBe('inlineStr');
        expect(parsed.types[row('AV00013')]?.[20]).toBe('n');
      }
      writeFileSync(
        info.outputPath(name + '.json'),
        JSON.stringify({ ...parsed, filename: file.suggestedFilename() }, null, 2),
      );
      await expect(rows).toHaveCount(20);
      await expect(
        page.getByRole('button', { name: zh.query.exportXlsx, exact: true }),
      ).toBeEnabled();
      await page.mouse.move(0, 0);
      await page.evaluate(async () => {
        await Promise.all(
          document
            .getAnimations()
            .filter((a) => a.effect?.getTiming().iterations !== Infinity)
            .map((a) => a.finished.catch(() => {})),
        );
      });
      await page.screenshot({ path: info.outputPath(name + '.png'), fullPage: true });
    }
  } finally {
    fixture('clean');
    writeFileSync(
      info.outputPath('baseline-preserved.json'),
      readFileSync(resolve(run, `audit-view-clean-${kind(info)}.json`)),
    );
  }
});
