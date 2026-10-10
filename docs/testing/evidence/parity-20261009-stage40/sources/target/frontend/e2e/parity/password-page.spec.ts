import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import en from '../../src/i18n/prototype/en.json' with { type: 'json' };
import vi from '../../src/i18n/prototype/vi.json' with { type: 'json' };
import { protectTestSecrets } from '../privacy';
import { paceComparisonLogin } from './login-budget';
import { kind, source } from './query-support';

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
    const selectors = prototype
      ? {
          wrap: '.wrap',
          panel: '.panel',
          title: '.panel h2',
          alert: '.el-alert',
          alertIcon: '.el-alert__icon',
          alertTitle: '.el-alert__title',
          form: '.form',
          labels: '.el-form-item__label',
          fields: '.el-input__wrapper',
          inputs: '.el-input__inner',
          eyes: '.el-input__password',
          policy: '.form .muted',
          errors: '.el-form-item__error',
          actions: '.actions',
          buttons: '.actions button',
        }
      : {
          wrap: '.prototype-password',
          panel: '.prototype-password-panel',
          title: '.prototype-password-panel h2',
          alert: '.prototype-password-panel .ant-alert',
          alertIcon: '.prototype-password-panel .ant-alert-icon',
          alertTitle: '.prototype-password-panel .ant-alert-title',
          form: '.prototype-password-panel form',
          labels: '.ant-form-item-label > label',
          fields: '.ant-input-affix-wrapper',
          inputs: '.ant-input',
          eyes: '.ant-input-password-icon',
          policy: '.ant-form-item-extra',
          errors: '.ant-form-item-explain-error',
          actions: '.prototype-password-actions',
          buttons: '.prototype-password-actions button',
        };
    const data: Record<string, unknown> = {};
    for (const [key, selector] of Object.entries(selectors))
      data[key] = [...document.querySelectorAll(selector)].map((el) => {
        const r = el.getBoundingClientRect(),
          s = getComputedStyle(el);
        const characters: { text: string; x: number; y: number; width: number; height: number }[] =
          [];
        if (key === 'alertTitle') {
          const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
          for (let node = walker.nextNode(); node; node = walker.nextNode()) {
            for (let i = 0; i < (node.textContent?.length ?? 0); i++) {
              const range = document.createRange();
              range.setStart(node, i);
              range.setEnd(node, i + 1);
              const rect = range.getBoundingClientRect();
              characters.push({
                text: range.toString(),
                x: rect.x - r.x,
                y: rect.y - r.y,
                width: rect.width,
                height: rect.height,
              });
            }
          }
        }
        return {
          x: r.x,
          y: r.y,
          width: r.width,
          height: r.height,
          color: s.color,
          fontFamily: s.fontFamily,
          fontSize: s.fontSize,
          fontWeight: s.fontWeight,
          lineHeight: s.lineHeight,
          padding: s.padding,
          background: s.backgroundColor,
          boxShadow: s.boxShadow,
          borderRadius: s.borderRadius,
          iconPaths: [...el.querySelectorAll('svg path')].map((p) => p.getAttribute('d')),
          characters,
          // Never serialize password inputs or form state; text is limited to public labels.
          text: ['title', 'alertTitle', 'labels', 'policy', 'errors', 'buttons'].includes(key)
            ? el.textContent
            : null,
        };
      });
    return data;
  }, source(info));
  writeFileSync(info.outputPath(name + '.json'), JSON.stringify(data, null, 2));
  await page.screenshot({
    path: info.outputPath(name + '.png'),
    animations: 'disabled',
    fullPage: true,
  });
}

for (const [index, language, messages] of [
  [1, 'zh-CN', zh],
  [2, 'en', en],
  [3, 'vi', vi],
] as const) {
  test(`P03 password ${language}: forced form, blur validation, logout, save and voluntary cancel`, async ({
    page,
  }, info) => {
    test.setTimeout(180_000);
    const baseTag = process.env.ADTEK_PASSWORD_RUN;
    expect(baseTag).toMatch(/^[0-9]{8,12}$/);
    const tag = baseTag! + index;
    const fixture = (action: string) =>
      execFileSync(
        resolve('../../sn-center-python/.venv/Scripts/python.exe'),
        [
          '-X',
          'utf8',
          resolve('../tools/testing/parity-auth-fixture.py'),
          kind(info),
          action,
          tag,
          '--language',
          language,
        ],
        { windowsHide: true, encoding: 'utf8' },
      );
    fixture('prepare');
    const account = (
      JSON.parse(
        readFileSync(
          resolve(`../deploy/.secrets/comparison-20261008/auth-fixture-${kind(info)}-${tag}.json`),
          'utf8',
        ),
      ) as {
        entries: { A: { username: string; initial: string; changed: string } };
      }
    ).entries.A;
    protectTestSecrets(account.initial, account.changed);
    let passwordRequests = 0;
    page.on('request', (request) => {
      if (request.method() === 'POST' && request.url().endsWith('/auth/password'))
        passwordRequests++;
    });
    const login = async (password: string) => {
      await page.goto('/login');
      await paceComparisonLogin(page);
      await page.locator('input[autocomplete="username"]').fill(account.username);
      await page.locator('input[autocomplete="current-password"]').fill(password);
      await page.locator('button[type="submit"]').click();
      await expect(
        page.getByRole('heading', { name: messages.password.title, exact: true }),
      ).toBeVisible();
    };
    try {
      await login(account.initial);
      const old = page.locator('input[autocomplete="current-password"]');
      const next = page.locator('input[autocomplete="new-password"]').nth(0);
      const confirm = page.locator('input[autocomplete="new-password"]').nth(1);
      const errors = page.locator(
        source(info) ? '.el-form-item__error' : '.ant-form-item-explain-error',
      );
      for (const width of [1440, 900, 390]) {
        await page.setViewportSize({ width, height: 960 });
        await capture(page, info, 'forced-' + width);
      }
      await page.setViewportSize({ width: 1440, height: 960 });
      await old.fill('Draft-only-37');
      await next.fill('abc');
      await capture(page, info, 'weak-typing');
      await expect(errors).toHaveCount(0);
      await next.blur();
      await expect(errors).toHaveText([messages.password.policy]);
      await capture(page, info, 'weak-blurred');
      await confirm.fill('other');
      await confirm.blur();
      await expect(errors).toHaveText([messages.password.policy, messages.password.mismatch]);
      await capture(page, info, 'mismatch');
      await next.fill('Valid-example-37');
      await next.blur();
      await confirm.fill('Valid-example-37');
      await expect(errors).toHaveText([messages.password.mismatch]);
      await confirm.blur();
      await expect(errors).toHaveCount(0);
      await capture(page, info, 'corrected');
      const eye = page
        .locator(source(info) ? '.el-input__password' : '.ant-input-password-icon')
        .first();
      await eye.click();
      await expect(old).toHaveAttribute('type', 'text');
      await capture(page, info, 'visible-demo-password');
      await eye.click();
      await expect(old).toHaveAttribute('type', 'password');
      await old.fill('');
      await next.fill('');
      await confirm.fill('');
      await page.getByRole('button', { name: messages.common.save, exact: true }).click();
      await expect(errors).toHaveText([messages.common.required, messages.common.required]);
      await capture(page, info, 'empty-submit');
      expect(passwordRequests).toBe(0);
      await page.getByRole('button', { name: messages.app.logout, exact: true }).click();
      await expect(page).toHaveURL(/\/login/);
      await login(account.initial);
      await old.fill(account.initial);
      await next.fill(account.changed);
      await confirm.fill(account.changed);
      const saved = page.waitForResponse(
        (r) => r.request().method() === 'POST' && r.url().endsWith('/auth/password'),
      );
      await page.getByRole('button', { name: messages.common.save, exact: true }).click();
      expect((await saved).ok()).toBe(true);
      await expect(
        page.getByRole('menuitem', { name: messages.nav.query, exact: true }),
      ).toBeVisible();
      await expect(page.getByText(messages.password.done, { exact: true }).last()).toBeVisible();
      const success = page.locator(
        source(info) ? '.el-message--success' : '.prototype-password-success',
      );
      const paint = () =>
        success.evaluate((el) => {
          const r = el.getBoundingClientRect(),
            s = getComputedStyle(el);
          let opacity = 1;
          for (let node: Element | null = el; node; node = node.parentElement)
            opacity *= Number(getComputedStyle(node).opacity);
          return {
            x: r.x,
            y: r.y,
            width: r.width,
            height: r.height,
            color: s.color,
            background: s.backgroundColor,
            text: el.textContent,
            opacity,
          };
        });
      await expect.poll(async () => (await paint()).opacity).toBe(1);
      await expect.poll(async () => (await paint()).y).toBeGreaterThanOrEqual(0);
      const before = await paint();
      await page.screenshot({ path: info.outputPath('success.png'), animations: 'disabled' });
      const after = await paint();
      expect(after.opacity).toBe(1);
      writeFileSync(info.outputPath('success.json'), JSON.stringify({ before, after }, null, 2));
      expect(passwordRequests).toBe(1);
      // Follow the actual account menu into voluntary password change, then go back.
      await page.locator(source(info) ? '.who' : '.prototype-user').click();
      await page.getByText(messages.app.changePassword, { exact: true }).click();
      await expect(
        page.getByRole('heading', { name: messages.password.title, exact: true }),
      ).toBeVisible();
      await expect(page.getByText(messages.password.forced, { exact: true })).toHaveCount(0);
      for (const width of [1440, 390]) {
        await page.setViewportSize({ width, height: 960 });
        await capture(page, info, 'voluntary-' + width);
      }
      await page.getByRole('button', { name: messages.common.cancel, exact: true }).click();
      await expect(
        page.getByRole('menuitem', { name: messages.nav.query, exact: true }),
      ).toBeVisible();
      expect(passwordRequests).toBe(1);
      writeFileSync(
        info.outputPath('actions.json'),
        JSON.stringify(
          {
            language,
            invalidFormsSentNoRequests: true,
            forcedLogoutWorked: true,
            passwordRequests: 1,
            changedPasswordSucceeded: true,
            voluntaryCancelWorked: true,
          },
          null,
          2,
        ),
      );
    } finally {
      expect.soft(() => fixture('clean'), 'Remove only owned test accounts').not.toThrow();
    }
  });
}
