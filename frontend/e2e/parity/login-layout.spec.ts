import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { writeFileSync } from 'node:fs';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import en from '../../src/i18n/prototype/en.json' with { type: 'json' };
import vi from '../../src/i18n/prototype/vi.json' with { type: 'json' };

const source = (info: TestInfo) => info.project.name === 'prototype';

async function capture(page: Page, info: TestInfo, name: string) {
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
          page: '.login',
          hero: '.hero',
          panel: '.panel',
          top: '.top',
          title: '.top h2',
          labels: '.el-form-item__label',
          fields: '.el-input__wrapper',
          inputs: '.el-input__inner',
          prefixes: '.el-input__prefix-inner .el-icon',
          eye: '.el-input__password',
          button: '.submit',
          note: '.hint',
          language: '.lang .el-select__wrapper',
          languageValue: '.lang .el-select__placeholder',
          languageArrow: '.lang .el-select__caret',
        }
      : {
          page: '.prototype-login',
          hero: '.prototype-login-hero',
          panel: '.prototype-login-panel',
          top: '.prototype-login-top',
          title: '.prototype-login-top h2',
          labels: '.ant-form-item-label > label',
          fields: '.ant-input-affix-wrapper',
          inputs: '.ant-input',
          prefixes: '.ant-input-prefix > *',
          eye: '.ant-input-password-icon',
          button: 'button[type="submit"]',
          note: '.prototype-login-note',
          language: '.ant-select',
          languageValue: '.ant-select-content',
          languageArrow: '.ant-select-suffix .prototype-icon',
        };
    const result: Record<string, unknown> = {};
    for (const [key, selector] of Object.entries(selectors)) {
      result[key] = [...document.querySelectorAll(selector)].map((el) => {
        const r = el.getBoundingClientRect(),
          s = getComputedStyle(el);
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
          letterSpacing: s.letterSpacing,
          padding: s.padding,
          background: s.backgroundColor,
          boxShadow: s.boxShadow,
          borderRadius: s.borderRadius,
          display: s.display,
          iconPaths: [...el.querySelectorAll('svg path')].map((path) => path.getAttribute('d')),
        };
      });
    }
    return result;
  }, source(info));
  writeFileSync(info.outputPath(name + '.json'), JSON.stringify(data, null, 2));
  await page.screenshot({
    path: info.outputPath(name + '.png'),
    animations: 'disabled',
    fullPage: true,
  });
}

test('P02 login layout: three languages, four widths, focus, reveal and empty submission', async ({
  page,
}, info) => {
  test.setTimeout(120_000);
  await page.goto('/login');
  const user = page.locator('input[autocomplete="username"]');
  const password = page.locator('input[autocomplete="current-password"]');
  await expect(user).toBeVisible();
  await page.mouse.move(0, 0);
  await capture(page, info, 'initial');
  await expect(user).not.toBeFocused();
  await expect(
    page.locator(source(info) ? '.el-input__password' : '.ant-input-password-icon'),
  ).toHaveCount(0);
  await user.fill('cmp_visual_36');
  await capture(page, info, 'username-focus');
  await password.fill('Visual-only-36');
  await expect(password).toHaveAttribute('type', 'password');
  await capture(page, info, 'password-focus');
  const eye = page.locator(source(info) ? '.el-input__password' : '.ant-input-password-icon');
  await eye.click();
  await expect(password).toHaveAttribute('type', 'text');
  await page.mouse.move(0, 0);
  await capture(page, info, 'password-visible');
  await eye.click();
  await expect(password).toHaveAttribute('type', 'password');
  await user.fill('');
  await password.fill('');
  await page.getByRole('heading', { name: zh.login.title, exact: true }).click();
  for (const [lang, label, messages] of [
    ['zh-CN', '简体中文', zh],
    ['en', 'English', en],
    ['vi', 'Tiếng Việt', vi],
  ] as const) {
    const picker = page.locator(source(info) ? '.lang' : '.prototype-login-top .ant-select');
    if (!(await picker.innerText()).includes(label)) {
      await picker.click();
      await page.getByRole('option', { name: label, exact: true }).click();
    }
    await expect(
      page.getByRole('heading', { name: messages.login.title, exact: true }),
    ).toBeVisible();
    for (const width of [1920, 1440, 900, 390]) {
      await page.setViewportSize({ width, height: 960 });
      await page.getByRole('heading', { name: messages.login.title, exact: true }).click();
      await page.mouse.move(0, 0);
      await capture(page, info, lang + '-' + width);
    }
  }
  await page.setViewportSize({ width: 1440, height: 960 });
  const submit = page.getByRole('button', { name: vi.login.submit, exact: true });
  await submit.hover();
  await capture(page, info, 'button-hover');
  let loginRequests = 0;
  page.on('request', (request) => {
    if (request.method() === 'POST' && request.url().endsWith('/auth/login')) loginRequests++;
  });
  await submit.click();
  await password.press('Enter');
  await page.mouse.move(0, 0);
  await capture(page, info, 'empty-submit');
  await expect(page.locator('.el-form-item__error,.ant-form-item-explain-error')).toHaveCount(0);
  expect(loginRequests).toBe(0);
  await expect(page).toHaveURL(/\/login$/);
});
