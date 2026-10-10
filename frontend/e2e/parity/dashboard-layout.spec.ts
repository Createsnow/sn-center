import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { protectTestSecrets } from '../privacy';
import { paceComparisonLogin } from './login-budget';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import en from '../../src/i18n/prototype/en.json' with { type: 'json' };
import vi from '../../src/i18n/prototype/vi.json' with { type: 'json' };

const source = (info: TestInfo) => info.project.name === 'prototype';

async function language(page: Page, info: TestInfo, label: string) {
  const picker = page.locator(source(info) ? '.header .lang' : '.prototype-language .ant-select');
  if ((await picker.innerText()).includes(label)) return;
  await picker.click();
  const response = page.waitForResponse(
    (r) =>
      r.request().method() === (source(info) ? 'PUT' : 'POST') &&
      r.url().endsWith(source(info) ? '/auth/lang' : '/system/auth/language'),
  );
  await page.getByRole('option', { name: label, exact: true }).click();
  expect((await response).ok()).toBe(true);
}

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
          header: '.header',
          left: '.header .left',
          right: '.header .right',
          fold: '.header .left > button',
          breadcrumb: '.el-breadcrumb',
          breadcrumbText: '.el-breadcrumb__inner',
          title: '.page__title',
          description: '.page__desc',
          factory: '.header .fac',
          factoryIcon: '.header .fac .el-icon',
          language: '.header .lang .el-select__wrapper',
          languageText: '.header .lang .el-select__placeholder',
          languageArrow: '.header .lang .el-select__caret',
          user: '.who',
          avatar: '.who .el-avatar',
          userName: '.who-name',
          userArrow: '.who > .el-icon',
          page: '.page',
          head: '.page__head',
          kpis: '.kpi',
          kpiValues: '.kpi .v',
          kpiLabels: '.kpi .l',
          surfaces: '.surface',
          titles: '.surface__title',
          shortcuts: '.shortcuts',
          buttons: '.shortcuts button',
          icons: '.shortcuts button .el-icon',
          buttonTexts: '.shortcuts button > span > span',
          todayBorders: '.surface:first-child .el-table__cell',
        }
      : {
          header: '.prototype-header',
          left: '.prototype-header-left',
          right: '.prototype-header-right',
          fold: '.prototype-header-left > button',
          breadcrumb: '.prototype-breadcrumb',
          breadcrumbText: '.prototype-breadcrumb-text',
          title: '.sn-page-head h2',
          description: '.sn-page-head p',
          factory: '.prototype-factory-tag',
          factoryIcon: '.prototype-factory-tag .prototype-icon',
          language: '.prototype-language .ant-select',
          languageText: '.prototype-language .ant-select-content',
          languageArrow: '.prototype-language .ant-select-suffix > *',
          user: '.prototype-user',
          avatar: '.prototype-avatar',
          userName: '.prototype-user-name',
          userArrow: '.prototype-user > .prototype-icon',
          page: '.sn-dashboard-page',
          head: '.sn-page-head',
          kpis: '.sn-dashboard-kpi',
          kpiValues: '.sn-dashboard-kpi .value',
          kpiLabels: '.sn-dashboard-kpi .label',
          surfaces: '.sn-dashboard-page .sn-surface',
          titles: '.sn-dashboard-page .sn-surface-title',
          shortcuts: '.sn-dashboard-shortcuts',
          buttons: '.sn-dashboard-shortcuts button',
          icons: '.sn-dashboard-shortcuts button .prototype-icon',
          buttonTexts: '.sn-dashboard-shortcuts button > span:not(.ant-btn-icon)',
          todayBorders: '[data-testid="dashboard-today"] .ant-table-cell',
        };
    return Object.fromEntries(
      Object.entries(selectors).map(([key, selector]) => [
        key,
        [...document.querySelectorAll(selector)].map((el) => {
          const r = el.getBoundingClientRect(),
            s = getComputedStyle(el);
          if (key === 'todayBorders') return { borderBottom: s.borderBottom };
          const characters: {
            text: string;
            x: number;
            y: number;
            width: number;
            height: number;
          }[] = [];
          if (['languageText', 'userName', 'avatar', 'buttonTexts'].includes(key)) {
            const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
            for (let node = walker.nextNode(); node; node = walker.nextNode()) {
              for (let index = 0; index < (node.textContent?.length ?? 0); index++) {
                const range = document.createRange();
                range.setStart(node, index);
                range.setEnd(node, index + 1);
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
            fontFamily: s.fontFamily,
            fontSize: s.fontSize,
            fontWeight: s.fontWeight,
            lineHeight: s.lineHeight,
            color: s.color,
            background: s.backgroundColor,
            borderRadius: s.borderRadius,
            border: s.border,
            padding: s.padding,
            characters,
            text: [
              'userName',
              'factory',
              'languageText',
              'titles',
              'kpiValues',
              'kpiLabels',
              'buttonTexts',
            ].includes(key)
              ? el.textContent?.trim()
              : null,
            paths: ['icons', 'factoryIcon', 'userArrow', 'languageArrow'].includes(key)
              ? [...el.querySelectorAll('path')].map((p) => p.getAttribute('d'))
              : [],
          };
        }),
      ]),
    );
  }, source(info));
  writeFileSync(info.outputPath(name + '.json'), JSON.stringify(data, null, 2));
  await page.screenshot({
    path: info.outputPath(name + '.png'),
    animations: 'disabled',
    fullPage: true,
  });
}

test('P01 P04 factory operator header and shortcuts: three languages, four widths and real navigation', async ({
  page,
}, info) => {
  test.setTimeout(180_000);
  page.setDefaultTimeout(15_000);
  const narrowNavigation: { language: string; namedDashboardItems: number }[] = [];
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  const account = JSON.parse(
    readFileSync(resolve('../deploy/.secrets/comparison-20261008/role-accounts.json'), 'utf8'),
  )[source(info) ? 'python' : 'java'].cmp_operator as { current: string; changed: boolean };
  expect(account.changed).toBe(true);
  protectTestSecrets(account.current);
  await page.goto('/login');
  await paceComparisonLogin(page);
  await page.locator('input[autocomplete="username"]').fill('cmp_operator');
  await page.locator('input[autocomplete="current-password"]').fill(account.current);
  const response = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === (source(info) ? '/api/dashboard' : '/api/v1/sn/dashboard') &&
      r.request().method() === 'GET',
  );
  await page.getByRole('button', { name: zh.login.submit, exact: true }).click();
  expect((await response).ok()).toBe(true);
  writeFileSync(
    info.outputPath('dashboard-response.json'),
    JSON.stringify(await (await response).json(), null, 2),
  );
  const short = page.locator(source(info) ? '.shortcuts' : '.sn-dashboard-shortcuts');
  const totals = JSON.parse(
    readFileSync(resolve('../tools/.runtime/comparison-20261008/stage38-statistics.json'), 'utf8'),
  ).systems[source(info) ? 'python' : 'java'].factoryA as Record<string, number>;
  await expect(short).toBeVisible();
  try {
    for (const [locale, label, words] of [
      ['zh-CN', '简体中文', zh],
      ['en', 'English', en],
      ['vi', 'Tiếng Việt', vi],
    ] as const) {
      await page.setViewportSize({ width: 1440, height: 960 });
      await language(page, info, label);
      await expect(short.getByRole('button')).toHaveText(
        ['acquire', 'transfer', 'query', 'audit'].map(
          (key) => words.nav[key as keyof typeof words.nav],
        ),
      );
      await page.reload();
      await expect(short.getByRole('button').first()).toHaveText(words.nav.acquire);
      const today = source(info)
        ? page.locator('.surface').first()
        : page.getByTestId('dashboard-today');
      for (const action of ['SN_ACQUIRE', 'SN_ALLOCATE', 'SN_GENERATE', 'SN_PRINT'] as const) {
        await expect(
          today
            .getByRole('row')
            .filter({ hasText: words.actions[action] })
            .getByRole('cell')
            .last(),
        ).toHaveText(BigInt(totals[action]!).toLocaleString());
      }
      for (const width of [1920, 1440, 900, 390]) {
        await page.setViewportSize({ width, height: 960 });
        await capture(page, info, `${locale}-${width}`);
      }
      const lastShortcut = short.getByRole('button', { name: words.nav.audit, exact: true });
      await lastShortcut.scrollIntoViewIfNeeded();
      await expect(lastShortcut).toBeInViewport();
      await capture(page, info, `${locale}-390-scrolled`);
      await lastShortcut.click();
      await expect(page).toHaveURL(/\/audit$/);
      narrowNavigation.push({
        language: locale,
        namedDashboardItems: await page
          .getByRole('menuitem', { name: words.nav.dashboard, exact: true })
          .count(),
      });
      writeFileSync(
        info.outputPath('narrow-navigation-probe.json'),
        JSON.stringify(narrowNavigation),
      );
      await page.goBack();
      await expect(page).toHaveURL(/\/$/);
      await expect(short).toBeVisible();
      // 折叠后的原型菜单只有图标；按已核对的顺序点击首个工作台入口。
      await short.getByRole('button', { name: words.nav.audit, exact: true }).click();
      await expect(page).toHaveURL(/\/audit$/);
      await page.getByRole('menubar').getByRole('menuitem').first().click();
      await expect(page).toHaveURL(/\/$/);
      await expect(short).toBeVisible();
      await page.setViewportSize({ width: 1440, height: 960 });
      for (const key of ['acquire', 'transfer', 'query', 'audit'] as const) {
        await short.getByRole('button', { name: words.nav[key], exact: true }).click();
        await expect(page).toHaveURL(new RegExp('/' + key + '$'));
        await page.getByRole('menuitem', { name: words.nav.dashboard, exact: true }).click();
        await expect(short).toBeVisible();
      }
    }
    expect(errors).toEqual([]);
    writeFileSync(
      info.outputPath('actions.json'),
      JSON.stringify({
        languageReloads: 3,
        shortcutNavigations: 12,
        narrowShortcutNavigations: 6,
        narrowMenuReturns: 3,
        pageErrors: errors,
      }),
    );
  } finally {
    await page.setViewportSize({ width: 1440, height: 960 });
    await language(page, info, '简体中文');
  }
});
