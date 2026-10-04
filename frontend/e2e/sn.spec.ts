import { test, expect, type Page } from '@playwright/test';
import { protectTestSecrets } from './privacy';
const password = process.env.ADTEK_E2E_INITIAL_PASSWORD;
if (!password) throw new Error('Use the isolated SN browser harness.');
protectTestSecrets(password);

async function login(page: Page, user: string) {
  await page.goto('/login');
  await page.getByLabel('工号', { exact: true }).fill(user);
  await page.getByLabel('密码', { exact: true }).fill(password!);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('menuitem', { name: '工厂', exact: true })).toBeVisible();
}

test('SN工厂页保留手工建档和同步反馈，并显式关联稳定组织', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.name));
  await login(page, 'browser_sn6');
  await page.getByRole('menuitem', { name: '工厂', exact: true }).click();
  await page.getByRole('button', { name: '手工新增', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('工厂编码', { exact: true }).fill('MANUAL-A');
  await dialog.getByLabel('工厂名称', { exact: true }).fill('甲工厂');
  await dialog.getByRole('button', { name: /^保\s*存$/ }).click();
  await expect(dialog).toBeHidden();
  await page.getByRole('button', { name: '从金蝶同步', exact: true }).click();
  await expect(page.getByText('金蝶组织 2 个：新增 1，更新 0', { exact: true })).toBeVisible();
  await expect(page.getByText(/以下组织与已有工厂冲突/)).toBeVisible();
  await page
    .getByRole('row')
    .filter({ hasText: 'MANUAL-A' })
    .getByRole('button', { name: '关联生产组织', exact: true })
    .click();
  await dialog.getByRole('row').filter({ hasText: 'ERP-A' }).getByRole('radio').check();
  await dialog.getByRole('button', { name: /^保\s*存$/ }).click();
  await expect(dialog).toBeHidden();
  await page.reload();
  await expect(page.getByRole('row').filter({ hasText: 'MANUAL-A' })).toContainText('甲工厂');
  await page.screenshot({
    path: 'test-results/sn-factories-zh.png',
    fullPage: true,
    animations: 'disabled',
  });
  expect(errors).toEqual([]);
});

// 顺序声明保持S3工厂/订单/规则准备先执行；S4用独立账号和PI，不能修改开发管理员数据。

test('SN订单页保留全量和单据同步、左单据右分录、过滤及三语布局', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.name));
  await login(page, 'browser_sn7');
  await page.getByRole('menuitem', { name: '生产订单快照', exact: true }).click();
  // 真实后端完成后暂缓传回响应，验证等待态；不伪造同步结果或业务API。
  let release: () => void = () => {};
  const responseGate = new Promise<void>((resolve) => {
    release = resolve;
  });
  let submits = 0;
  await page.route('**/api/v1/sn/orders/sync', async (route) => {
    submits++;
    const response = await route.fetch();
    await responseGate;
    await route.fulfill({ response });
  });
  const fullSync = page.getByRole('button', { name: '全量同步', exact: true });
  await fullSync.click();
  try {
    await expect(fullSync).toBeDisabled();
    await expect(page.getByRole('button', { name: '按单据同步', exact: true })).toBeDisabled();
    await expect(
      page.getByText('正在从金蝶同步生产订单，完成后将更新列表，请稍候。', { exact: true }),
    ).toBeVisible();
    expect(submits).toBe(1);
    await page.screenshot({
      path: 'test-results/sn-orders-sync-running-zh.png',
      fullPage: true,
      animations: 'disabled',
    });
  } finally {
    release();
  }
  await expect(page.getByText('已同步 3 张单据，5 行', { exact: true })).toBeVisible();
  await page.unroute('**/api/v1/sn/orders/sync');
  // 已收到后端明确拒绝时，不同时显示旧成功提示或“结果未知”。原快照仍在。
  await page.getByLabel('单据编号', { exact: true }).fill('X'.repeat(65));
  await page.getByRole('button', { name: '按单据同步', exact: true }).click();
  await expect(page.getByText('输入内容不符合要求，请检查后重试。', { exact: true })).toBeVisible();
  await expect(page.getByText('已同步 3 张单据，5 行', { exact: true })).toHaveCount(0);
  await expect(
    page.getByText('请先查看同步记录确认结果；请求中断不代表同步已取消。', { exact: true }),
  ).toHaveCount(0);
  await page.screenshot({
    path: 'test-results/sn-orders-sync-rejected-zh.png',
    fullPage: true,
    animations: 'disabled',
  });
  await page.reload();
  await expect(page.getByText('小数数量待确认', { exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: '定时全量同步', exact: true })).toBeVisible();
  await page.getByRole('switch', { name: '启用定时同步', exact: true }).check();
  await page.getByRole('spinbutton', { name: '间隔（分钟）', exact: true }).fill('20');
  const saved = page.waitForResponse(
    (r) => r.url().endsWith('/orders/sync-schedule') && r.request().method() === 'PUT',
  );
  await page.getByRole('button', { name: '保存定时设置', exact: true }).click();
  expect((await saved).status()).toBe(200);
  await page.reload();
  await expect(page.getByRole('switch', { name: '启用定时同步', exact: true })).toBeChecked();
  await expect(page.getByRole('spinbutton', { name: '间隔（分钟）', exact: true })).toHaveValue(
    '20',
  );
  await page.getByRole('switch', { name: '启用定时同步', exact: true }).uncheck();
  const disabled = page.waitForResponse(
    (r) => r.url().endsWith('/orders/sync-schedule') && r.request().method() === 'PUT',
  );
  await page.getByRole('button', { name: '保存定时设置', exact: true }).click();
  expect((await disabled).status()).toBe(200);
  await page.reload();
  await expect(page.getByRole('switch', { name: '启用定时同步', exact: true })).not.toBeChecked();
  await page
    .getByTestId('orders-table')
    .getByRole('button', { name: 'MO-FRACTION', exact: true })
    .click();
  await expect(page.getByTestId('order-lines')).toContainText('0.1254');
  await page.getByRole('button', { name: '去生成', exact: true }).click();
  await expect(
    page.getByText('订单含小数计划量，原值已保留；单位口径确认前不能生成SN', { exact: true }),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: /^预\s*演$/ })).toBeDisabled();
  await page.screenshot({
    path: 'test-results/sn-fractional-plan-zh.png',
    fullPage: true,
    animations: 'disabled',
  });
  await page.goto('/orders');
  await page.getByTestId('orders-table').getByRole('button', { name: 'MO-1', exact: true }).click();
  await expect(
    page.getByTestId('order-lines').getByRole('row').filter({ hasText: 'M1' }),
  ).toHaveCount(2);
  await expect(page.getByTestId('order-lines')).toContainText('（无物料）');
  await expect(page.getByRole('button', { name: '去生成', exact: true })).toBeEnabled();
  await page.screenshot({
    path: 'test-results/sn-orders-zh.png',
    fullPage: true,
    animations: 'disabled',
  });
  await page.getByLabel('搜索单号 / PI / PO / 物料 / 客户', { exact: true }).fill('M2');
  await page.getByRole('button', { name: /^查\s*询$/ }).click();
  await expect(
    page.getByTestId('orders-table').getByRole('button', { name: 'MO-1', exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByTestId('orders-table').getByRole('button', { name: 'MO-2', exact: true }),
  ).toBeVisible();
  await page.getByLabel('单据编号', { exact: true }).fill('MO-1');
  await page.getByRole('button', { name: '按单据同步', exact: true }).click();
  await expect(page.getByText('已同步 1 张单据，3 行', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '同步记录', exact: true }).click();
  await expect(page.getByRole('dialog')).toContainText('已发布');
  await page.getByRole('dialog').locator('.ant-drawer-close').click();
  await page.goto('/profile');
  await page.getByRole('combobox').click();
  await page.getByText('English', { exact: true }).click();
  await expect(page.getByRole('heading', { name: 'My settings', exact: true })).toBeVisible();
  await page.goto('/orders');
  await expect(
    page.getByRole('heading', { name: 'Production order snapshot', exact: true }),
  ).toBeVisible();
  await page.getByTestId('orders-table').getByRole('button', { name: 'MO-1', exact: true }).click();
  await expect(
    page.getByTestId('order-lines').getByRole('row').filter({ hasText: 'M1' }),
  ).toHaveCount(2);
  // 横向查看原有数量/累计/额度列，截图等待真实分录响应而非加载状态。
  await page
    .getByTestId('orders-table')
    .locator('.ant-table-body')
    .evaluate((element) => {
      element.scrollLeft = element.scrollWidth;
    });
  await page.screenshot({
    path: 'test-results/sn-orders-en.png',
    fullPage: true,
    animations: 'disabled',
  });
  await page.goto('/profile');
  await page.getByRole('combobox').click();
  await page.getByText('Tiếng Việt', { exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Cài đặt cá nhân', exact: true })).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/orders');
  await expect(
    page.getByTestId('orders-table').getByRole('button', { name: 'MO-1', exact: true }),
  ).toBeVisible();
  await page.getByTestId('orders-table').getByRole('button', { name: 'MO-1', exact: true }).click();
  await expect(
    page.getByTestId('order-lines').getByRole('row').filter({ hasText: 'M1' }),
  ).toHaveCount(2);
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
    .toBe(true);
  await page.screenshot({
    path: 'test-results/sn-orders-vi-mobile.png',
    fullPage: true,
    animations: 'disabled',
  });
  expect(errors).toEqual([]);
});

test('SN规则页保留精确容量预览、未用原位修改和已用新版本历史', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.name));
  await login(page, 'browser_sn8');
  await page.getByRole('menuitem', { name: '规则模板', exact: true }).click();
  await page.getByRole('button', { name: '新建规则', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('规则编码', { exact: true }).fill('BROWSER_RULE');
  await dialog.getByLabel('名称', { exact: true }).fill('浏览器规则');
  await dialog.getByText('按 PI', { exact: true }).click();
  await dialog.getByLabel('PI', { exact: true }).fill('BROWSER-PI');
  await dialog.getByLabel('前缀', { exact: true }).fill('SN');
  await dialog.getByText('36', { exact: true }).click();
  await dialog.getByLabel('流水位数', { exact: true }).fill('12');
  await expect(dialog.getByText('最大流水 4738381338321616895', { exact: true })).toBeVisible();
  await dialog.getByRole('button', { name: /^保\s*存$/ }).click();
  await expect(dialog).toBeHidden();
  const row = page.getByRole('row').filter({ hasText: 'BROWSER_RULE' });
  await row.getByRole('button', { name: '修改', exact: true }).click();
  await dialog.getByLabel('名称', { exact: true }).fill('已改名规则');
  await dialog.getByRole('button', { name: /^保\s*存$/ }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByText('已改名', { exact: true })).toBeVisible();
  await row.getByRole('button', { name: '修改', exact: true }).click();
  await dialog.getByLabel('前缀', { exact: true }).fill('NEW');
  await dialog.getByRole('button', { name: /^保\s*存$/ }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByText('已就地修改当前版', { exact: true })).toBeVisible();
  const used = page.getByRole('row').filter({ hasText: 'USED_RULE' });
  await used.getByRole('button', { name: '修改', exact: true }).click();
  await expect(dialog).toContainText('修改编码参数将另出一版');
  await dialog.getByLabel('前缀', { exact: true }).fill('V');
  await dialog.getByRole('button', { name: /^保\s*存$/ }).click();
  await expect(dialog).toBeHidden();
  await expect(used).toContainText('v2');
  await used.getByRole('button', { name: '版本', exact: true }).click();
  await expect(dialog).toContainText('v1');
  await expect(dialog).toContainText('v2');
  await expect(dialog).toContainText('U####');
  await expect(dialog).toContainText('V####');
  await expect(dialog).toContainText('创建者未记录');
  await page.screenshot({
    path: 'test-results/sn-rules-versions-zh.png',
    fullPage: true,
    animations: 'disabled',
  });
  await dialog.locator('.ant-drawer-close').click();
  await page.screenshot({
    path: 'test-results/sn-rules-zh.png',
    fullPage: true,
    animations: 'disabled',
  });
  expect(errors).toEqual([]);
});

test('SN起号页保留CSV首列导入、重复提示与大整数，刷新恢复同一持久操作', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.name));
  await login(page, 'browser_sn9');
  await page.goto('/pi-init?pi=BROWSER-PI');
  await expect(page.getByTestId('sn-watermark')).toHaveText('0');
  await page.getByLabel('起始号（十进制，可选）', { exact: true }).fill('9007199254740993');
  await page.getByLabel('历史 SN 清单（可选）', { exact: true }).fill('SN\nDUP\nDUP');
  await expect(page.getByText('清单内重复 1 个', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '执行初始化', exact: true })).toBeDisabled();
  await page.getByLabel('上传文件', { exact: true }).setInputFiles({
    name: 'history.csv',
    mimeType: 'text/csv',
    buffer: Buffer.from('SN,备注\nNEW000000000001,已发\nLEGACY-X,未知流水', 'utf8'),
  });
  await expect(page.getByText('已识别 2 个 SN', { exact: true })).toBeVisible();
  await page.screenshot({
    path: 'test-results/sn-pi-init-form-zh.png',
    fullPage: true,
    animations: 'disabled',
  });
  let submissions = 0;
  page.on('request', (r) => {
    if (r.method() === 'POST' && new URL(r.url()).pathname === '/api/v1/sn/pi-initializations')
      submissions++;
  });
  await page.getByRole('button', { name: '执行初始化', exact: true }).click();
  await page.getByRole('button', { name: /^确\s*认$/ }).click();
  await expect(page.getByTestId('sn-operation')).toBeVisible();
  const operation = await page.getByTestId('sn-operation').innerText();
  await page.reload();
  await expect(page.getByTestId('sn-operation')).toHaveText(operation);
  await expect(page.getByTestId('sn-task-state')).toHaveText('已完成', { timeout: 30000 });
  await expect(page.getByTestId('sn-watermark')).toHaveText('9007199254740992');
  await expect(
    page.getByText('该 PI 已指定起始号或已生成过（起始号：9007199254740993），不能再初始化。', {
      exact: true,
    }),
  ).toBeVisible();
  await page.screenshot({
    path: 'test-results/sn-pi-init-result-zh.png',
    fullPage: true,
    animations: 'disabled',
  });
  expect(submissions).toBe(1);
  expect(errors).toEqual([]);
});

test('SN生成页保持预演和整单分配两步，固定物料段、任务恢复与三语移动布局', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.name));
  await login(page, 'browser_sn10');
  await page.goto('/rules');
  await page.getByRole('button', { name: '新建规则', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('规则编码', { exact: true }).fill('GENERATE_RULE');
  await dialog.getByLabel('名称', { exact: true }).fill('生成规则');
  await dialog.getByLabel('前缀', { exact: true }).fill('G');
  await dialog.getByLabel('流水位数', { exact: true }).fill('4');
  await dialog.getByRole('button', { name: /^保\s*存$/ }).click();
  await expect(dialog).toBeHidden();
  await page.goto('/orders');
  await page.getByTestId('orders-table').getByRole('button', { name: 'MO-1', exact: true }).click();
  await page.getByRole('button', { name: '去生成', exact: true }).click();
  await expect(page.getByRole('heading', { name: '生成与分配', exact: true })).toBeVisible();
  await expect(page.getByTestId('sn-watermark')).toHaveText('0');
  await expect(page.getByLabel('仅待生成或待分配', { exact: true })).toBeChecked();
  await page.getByLabel('选择客户', { exact: true }).click();
  await page
    .locator('.ant-select-dropdown:visible .ant-select-item-option-content')
    .filter({ hasText: /^C1$/ })
    .click();
  await page.getByLabel('选择 PI', { exact: true }).click();
  await page
    .locator('.ant-select-dropdown:visible .ant-select-item-option-content')
    .filter({ hasText: /PI-1 · 1 单/ })
    .click();
  await page.getByLabel('选择生产订单（单据 / PI）', { exact: true }).click();
  await page
    .locator('.ant-select-dropdown:visible .ant-select-item-option-content')
    .filter({ hasText: /^MO-1 · PI-1 · 甲工厂$/ })
    .click();
  await expect(page.getByLabel('生成数量', { exact: true })).toHaveValue('13');
  await expect(page.getByRole('button', { name: /^预\s*演$/ })).toBeDisabled();
  await page.getByLabel('选择生成规则', { exact: true }).click();
  await page
    .locator('.ant-select-dropdown:visible .ant-select-item-option-content')
    .filter({ hasText: /^GENERATE_RULE · 生成规则 · v1$/ })
    .click();
  await expect(page.getByRole('button', { name: /^生\s*成$/ })).toBeDisabled();
  await page.getByRole('button', { name: /^预\s*演$/ }).click();
  await expect(page.getByText('G0013', { exact: true }).first()).toBeVisible();
  // 通过地址导航回到同一组件时，旧订单的规则选择和预演都不能继续使用。
  await page.getByLabel('选择客户', { exact: true }).click();
  await page
    .locator('.ant-select-dropdown:visible .ant-select-item-option-content')
    .filter({ hasText: /^C2$/ })
    .click();
  await expect(page.getByLabel('生成数量', { exact: true })).toHaveCount(0);
  await page.goBack();
  await expect(page.getByLabel('生成数量', { exact: true })).toHaveValue('13');
  await expect(
    page.locator('.ant-select').filter({
      has: page.getByRole('combobox', { name: '选择客户', exact: true }),
    }),
  ).toContainText('C1');
  await expect(
    page.locator('.ant-select').filter({
      has: page.getByRole('combobox', { name: '选择 PI', exact: true }),
    }),
  ).toContainText('PI-1');
  await expect(page.getByRole('button', { name: /^预\s*演$/ })).toBeDisabled();
  await expect(page.getByRole('button', { name: /^生\s*成$/ })).toBeDisabled();
  await page.getByLabel('选择生成规则', { exact: true }).click();
  await page
    .locator('.ant-select-dropdown:visible .ant-select-item-option-content')
    .filter({ hasText: /^GENERATE_RULE · 生成规则 · v1$/ })
    .click();
  await page.getByLabel('生成数量', { exact: true }).fill('12');
  await expect(page.getByRole('button', { name: /^生\s*成$/ })).toBeDisabled();
  await page.getByLabel('生成数量', { exact: true }).fill('13');
  await page.getByRole('button', { name: /^预\s*演$/ }).click();
  await expect(page.getByText('G0013', { exact: true }).first()).toBeVisible();
  await page.screenshot({
    path: 'test-results/sn-generation-preview-zh.png',
    fullPage: true,
    animations: 'disabled',
  });
  await page.getByRole('button', { name: /^生\s*成$/ }).click();
  await page.getByRole('button', { name: /^确\s*认$/ }).click();
  await expect(page.getByTestId('sn-operation')).toBeVisible();
  const operation = await page.getByTestId('sn-operation').innerText();
  await page.reload();
  await expect(page.getByTestId('sn-operation')).toHaveText(operation);
  await expect(page.getByTestId('sn-task-state')).toHaveText('已完成', { timeout: 30000 });
  await expect(page.getByTestId('sn-watermark')).toHaveText('13');
  await expect(page.getByText('沿用首次生成选择', { exact: true })).toBeVisible();
  await expect(page.getByLabel('选择生成规则', { exact: true })).toHaveCount(0);
  await expect(page.getByLabel('分配数量', { exact: true })).toHaveValue('13');
  await expect(page.getByLabel('分配数量', { exact: true })).not.toBeEditable();
  await page.getByRole('button', { name: '确认分配', exact: true }).click();
  await page.getByRole('button', { name: /^确\s*认$/ }).click();
  await expect(page.getByTestId('sn-task-state')).toHaveCount(2);
  await expect(page.getByTestId('sn-task-state').last()).toHaveText('已完成', { timeout: 30000 });
  await expect(page.getByLabel('分配数量', { exact: true })).toHaveValue('0');
  await page.getByRole('tab', { name: '分配记录', exact: true }).click();
  await expect(page.getByRole('tabpanel')).toContainText('G0001 ~ G0013');
  await page.screenshot({
    path: 'test-results/sn-generation-allocated-zh.png',
    fullPage: true,
    animations: 'disabled',
  });
  const url = page.url();
  await page.goto('/profile');
  await page.getByRole('combobox').click();
  await page.getByText('English', { exact: true }).click();
  await expect(page.getByRole('heading', { name: 'My settings', exact: true })).toBeVisible();
  await page.goto(url);
  await expect(
    page.getByRole('heading', { name: 'Generate & allocate', exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: 'test-results/sn-generation-en.png',
    fullPage: true,
    animations: 'disabled',
  });
  await page.goto('/profile');
  await page.getByRole('combobox').click();
  await page.getByText('Tiếng Việt', { exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Cài đặt cá nhân', exact: true })).toBeVisible();
  await page.setViewportSize({ width: 430, height: 932 });
  await page.goto(url);
  await expect(page.getByTestId('sn-watermark')).toHaveText('13');
  await page.screenshot({
    path: 'test-results/sn-generation-vi-mobile.png',
    fullPage: true,
    animations: 'disabled',
  });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
  expect(errors).toEqual([]);
});

test('SN领取页保持整PI领取、固定批次与双格式打印，刷新不重复提交', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.name));
  await login(page, 'browser_sn11');
  await page.getByRole('menuitem', { name: '领取 / 打印', exact: true }).click();
  await expect(page.getByTestId('acquire-summary')).toContainText('13');
  await page.getByLabel('物料范围', { exact: true }).click();
  await page.getByText('无物料', { exact: true }).click();
  await expect(page.getByTestId('acquire-segments')).toContainText('G0012 ~ G0013');
  await page.screenshot({
    path: 'test-results/sn-acquire-before-zh.png',
    fullPage: true,
    animations: 'disabled',
  });
  let takeRequests = 0,
    printRequests = 0;
  const sentPaths: string[] = [];
  page.on('request', (r) => {
    sentPaths.push(r.method() + ' ' + new URL(r.url()).pathname);
    if (r.method() === 'POST') {
      const path = new URL(r.url()).pathname;
      if (path === '/api/v1/sn/acquisitions') takeRequests++;
      if (path === '/api/v1/sn/prints') printRequests++;
    }
  });
  await page.getByRole('button', { name: '一键领取', exact: true }).click();
  const takeSent = page.waitForRequest(
    (r) => r.method() === 'POST' && new URL(r.url()).pathname === '/api/v1/sn/acquisitions',
  );
  await page.getByRole('button', { name: /^确\s*认$/ }).click();
  await takeSent.catch(() => {
    throw new Error('Observed request paths: ' + sentPaths.join(', '));
  });
  await expect(page.getByTestId('sn-operation')).toBeVisible();
  const take = await page.getByTestId('sn-operation').innerText();
  await page.reload();
  await expect(page.getByTestId('sn-operation')).toHaveText(take);
  await expect(page.getByTestId('sn-task-state')).toHaveText('已完成', { timeout: 30000 });
  await expect(page.getByText('领取完成：可领 13 枚，阻止 0 枚', { exact: true })).toBeVisible();
  await page
    .getByTestId('acquire-batches')
    .getByRole('button', { name: '明细', exact: true })
    .click();
  const drawer = page.getByRole('dialog');
  await expect(drawer.getByRole('row')).toHaveCount(14);
  await expect(drawer).toContainText('G0001');
  await expect(drawer).toContainText('G0013');
  await page.screenshot({
    path: 'test-results/sn-acquire-batch-zh.png',
    fullPage: true,
    animations: 'disabled',
  });
  await drawer.locator('.ant-drawer-close').click();
  await page.getByRole('button', { name: '打印整 PI', exact: true }).click();
  const printSent = page.waitForRequest(
    (r) => r.method() === 'POST' && new URL(r.url()).pathname === '/api/v1/sn/prints',
  );
  await page.getByRole('button', { name: /^确\s*认$/ }).click();
  await printSent.catch(() => {
    throw new Error('Observed request paths: ' + sentPaths.join(', '));
  });
  await expect(page.getByTestId('sn-operation')).toHaveCount(2);
  const print = await page.getByTestId('sn-operation').last().innerText();
  await page.reload();
  await expect(page.getByTestId('sn-operation').last()).toHaveText(print);
  await expect(page.getByTestId('sn-task-state').last()).toHaveText('已完成', { timeout: 30000 });
  await page.getByRole('tab', { name: '打印记录', exact: true }).click();
  const history = page.getByTestId('acquire-prints');
  await expect(history.getByRole('button', { name: /^(XLSX|CSV) 下载$/ })).toHaveCount(2);
  for (let i = 0; i < 2; i++) {
    const button = history.getByRole('button', {
      name: (i === 0 ? 'XLSX' : 'CSV') + ' 下载',
      exact: true,
    });
    await expect(button).toBeEnabled({ timeout: 30000 });
    const waiting = page.waitForEvent('download');
    await button.click();
    const file = await waiting;
    expect(file.suggestedFilename()).toMatch(/^print-[0-9a-f-]{36}\.(xlsx|csv)$/);
    await file.saveAs('test-results/sn-print.' + (i === 0 ? 'xlsx' : 'csv'));
    await expect(button).not.toHaveClass(/ant-btn-loading/);
  }
  await page.screenshot({
    path: 'test-results/sn-print-history-zh.png',
    fullPage: true,
    animations: 'disabled',
  });
  await page.goto('/profile');
  await page.getByRole('combobox').click();
  await page.getByText('English', { exact: true }).click();
  await expect(page.getByRole('heading', { name: 'My settings', exact: true })).toBeVisible();
  await page.goto('/acquire');
  await expect(page.getByRole('heading', { name: 'Acquire / Print', exact: true })).toBeVisible();
  await page.getByRole('tab', { name: 'Print history', exact: true }).click();
  await page.screenshot({
    path: 'test-results/sn-acquire-en.png',
    fullPage: true,
    animations: 'disabled',
  });
  await page.goto('/profile');
  await page.getByRole('combobox').click();
  await page.getByText('Tiếng Việt', { exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Cài đặt cá nhân', exact: true })).toBeVisible();
  await page.setViewportSize({ width: 430, height: 932 });
  await page.goto('/acquire');
  await expect(page.getByRole('heading', { name: 'Nhận / In', exact: true })).toBeVisible();
  await page.getByRole('tab', { name: 'Lịch sử in', exact: true }).click();
  await page.screenshot({
    path: 'test-results/sn-acquire-vi-mobile.png',
    fullPage: true,
    animations: 'disabled',
  });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
  expect(takeRequests).toBe(1);
  expect(printRequests).toBe(1);
  expect(errors).toEqual([]);
});

async function languageEvidence(
  page: Page,
  path: string,
  prefix: string,
  enTitle: string,
  viTitle: string,
) {
  await page.goto('/profile');
  await page.getByRole('combobox').click();
  await page.getByText('English', { exact: true }).click();
  await expect(page.getByRole('heading', { name: 'My settings', exact: true })).toBeVisible();
  await page.goto(path);
  await expect(page.getByRole('heading', { name: enTitle, exact: true })).toBeVisible();
  await page.screenshot({
    path: `test-results/${prefix}-en.png`,
    fullPage: true,
    animations: 'disabled',
  });
  await page.goto('/profile');
  await page.getByRole('combobox').click();
  await page.getByText('Tiếng Việt', { exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Cài đặt cá nhân', exact: true })).toBeVisible();
  await page.setViewportSize({ width: 430, height: 932 });
  await page.goto(path);
  await expect(page.getByRole('heading', { name: viTitle, exact: true })).toBeVisible();
  await page.screenshot({
    path: `test-results/${prefix}-vi-mobile.png`,
    fullPage: true,
    animations: 'disabled',
  });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
}
test('SN转厂页保留空物料申请、驳回恢复、整PI批准和固定明细', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.name));
  await login(page, 'browser_sn12');
  await page.goto('/transfer');
  await page.getByRole('combobox', { name: '工厂', exact: true }).click();
  await page.getByText('MANUAL-A · 甲工厂', { exact: true }).click();
  await page.getByLabel('来源 PI', { exact: true }).fill('PI-1');
  await page.getByLabel('转移范围', { exact: true }).click();
  await page.getByText('PI + 物料', { exact: true }).click();
  await page.getByRole('button', { name: '查询可转移数量', exact: true }).click();
  await expect(page.getByText('可转移 2 枚（范围内共 2 枚）', { exact: true })).toBeVisible();
  await page.getByRole('combobox', { name: '生产组织（工厂）', exact: true }).click();
  await page
    .locator('.ant-select-dropdown:visible')
    .getByText('ERP-B · 乙工厂', { exact: true })
    .click();
  await page.getByLabel('转入 PI', { exact: true }).fill('PI-2');
  await page.getByLabel('转厂原因', { exact: true }).fill('空物料转厂申请');
  await page.getByRole('combobox', { name: '转厂方式', exact: true }).click();
  await page.getByText('申请', { exact: true }).click();
  await page.screenshot({
    path: 'test-results/sn-transfer-form-zh.png',
    fullPage: true,
    animations: 'disabled',
  });
  await page.getByRole('button', { name: '提交申请', exact: true }).click();
  await expect(page.getByRole('dialog').getByTestId('transfer-impact')).toContainText('G0001');
  await expect(page.getByRole('dialog').getByTestId('transfer-impact')).toContainText('G0014');
  await page.screenshot({
    path: 'test-results/sn-transfer-impact-zh.png',
    fullPage: true,
    animations: 'disabled',
  });
  await page
    .getByRole('dialog')
    .getByRole('button', { name: /^确\s*认$/ })
    .click();
  await expect(page.getByTestId('sn-task-state')).toHaveText('已完成', { timeout: 30000 });
  await page.getByRole('tab', { name: '转厂记录', exact: true }).click();
  const history = page.getByTestId('transfer-history');
  await expect(history).toContainText('待审批');
  await history.getByRole('button', { name: '驳回', exact: true }).click();
  await page
    .getByRole('dialog')
    .getByRole('button', { name: /^确\s*认$/ })
    .click();
  await expect(history).toContainText('已驳回', { timeout: 30000 });
  await page.getByRole('tab', { name: '直接转移', exact: true }).click();
  await page.getByLabel('转移范围', { exact: true }).click();
  await page.getByText('整张 PI', { exact: true }).click();
  await page.getByRole('button', { name: '查询可转移数量', exact: true }).click();
  await expect(page.getByText('可转移 13 枚（范围内共 13 枚）', { exact: true })).toBeVisible();
  await page.getByLabel('转厂原因', { exact: true }).fill('整PI交接');
  await page.getByRole('button', { name: '提交申请', exact: true }).click();
  await page
    .getByRole('dialog')
    .getByRole('button', { name: /^确\s*认$/ })
    .click();
  await expect(page.getByTestId('sn-task-state').first()).toHaveText('已完成', { timeout: 30000 });
  await page.getByRole('tab', { name: '转厂记录', exact: true }).click();
  const pending = history.getByRole('row').filter({ hasText: '待审批' });
  await expect(pending).toHaveCount(1);
  await pending.getByRole('button', { name: '确认', exact: true }).click();
  await expect(page.getByRole('dialog').getByTestId('transfer-impact')).toContainText('G0014');
  await page
    .getByRole('dialog')
    .getByLabel('处理意见（可选）', { exact: true })
    .fill('现场标签由工厂处理');
  await page
    .getByRole('dialog')
    .getByRole('button', { name: /^确\s*认$/ })
    .click();
  await expect(history).toContainText('已批准', { timeout: 30000 });
  const approved = history.getByRole('row').filter({ hasText: '已批准' });
  await approved.getByRole('button', { name: '明细', exact: true }).click();
  const drawer = page.getByRole('dialog');
  await expect(drawer.getByTestId('transfer-members').getByRole('row')).toHaveCount(14);
  await expect(drawer).toContainText('G0001');
  await expect(drawer).toContainText('已打印');
  await expect(drawer).toContainText('待领取');
  await page.screenshot({
    path: 'test-results/sn-transfer-detail-zh.png',
    fullPage: true,
    animations: 'disabled',
  });
  await drawer.locator('.ant-drawer-close').click();
  await page.reload();
  await page.getByRole('tab', { name: '转厂记录', exact: true }).click();
  await expect(history).toContainText('已批准');
  await languageEvidence(page, '/transfer', 'sn-transfer', 'Factory transfer', 'Chuyển nhà máy');
  expect(errors).toEqual([]);
});
test('SN查询保留全部筛选、精确流水和固定CSV/XLSX下载', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.name));
  await login(page, 'browser_sn13');
  await page.goto('/query');
  await page.getByRole('textbox', { name: 'PI', exact: true }).fill('PI-2');
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.getByText('共 13 枚', { exact: true })).toBeVisible();
  await expect(page.getByTestId('sn-query-items')).toContainText('G0001');
  await page.getByLabel('物料范围', { exact: true }).click();
  await page.getByText('无物料', { exact: true }).click();
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.getByText('共 2 枚', { exact: true })).toBeVisible();
  await page.getByLabel('物料范围', { exact: true }).click();
  await page.getByText('全部物料', { exact: true }).click();
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.getByText('共 13 枚', { exact: true })).toBeVisible();
  await page.screenshot({
    path: 'test-results/sn-query-zh.png',
    fullPage: true,
    animations: 'disabled',
  });
  for (const format of ['CSV', 'XLSX']) {
    await page.getByRole('button', { name: '导出 ' + format, exact: true }).click();
    await page.getByRole('button', { name: /^确\s*认$/ }).click();
    await expect(page.getByTestId('sn-task-state').last()).toHaveText('已完成', { timeout: 30000 });
  }
  await page.getByRole('tab', { name: '导出记录', exact: true }).click();
  const history = page.getByTestId('sn-query-exports');
  for (const format of ['CSV', 'XLSX']) {
    const button = history.getByRole('button', { name: format + ' 下载', exact: true });
    await expect(button).toBeEnabled({ timeout: 30000 });
    const pending = page.waitForEvent('download');
    await button.click();
    const file = await pending;
    expect(file.suggestedFilename()).toMatch(/^sn-export-[0-9a-f-]{36}\.(csv|xlsx)$/);
    await file.saveAs('test-results/sn-query.' + format.toLowerCase());
    await expect(button).not.toHaveClass(/ant-btn-loading/);
  }
  await page.screenshot({
    path: 'test-results/sn-query-exports-zh.png',
    fullPage: true,
    animations: 'disabled',
  });
  await languageEvidence(page, '/query', 'sn-query', 'SN Query', 'Tra cứu SN');
  expect(errors).toEqual([]);
});
test('SN首页保持在途、今日作业、快捷入口与最近任务并支持三语手机', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.name));
  await login(page, 'browser_sn14');
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.getByRole('heading', { name: '工作台', exact: true })).toBeVisible();
  await expect(page.getByTestId('dashboard-today')).toContainText('页面打印');
  await expect(page.getByTestId('dashboard-today')).toContainText('转入确认');
  await expect(page.getByTestId('dashboard-jobs')).toContainText('PI-1');
  await page.screenshot({
    path: 'test-results/sn-dashboard-zh.png',
    fullPage: true,
    animations: 'disabled',
  });
  await languageEvidence(page, '/dashboard', 'sn-dashboard', 'Dashboard', 'Bảng điều khiển');
  expect(errors).toEqual([]);
});

test('SN操作痕迹保留状态、归档筛选和双格式快照并支持三语手机', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.name));
  await login(page, 'browser_sn15');
  await page.goto('/audit');
  await expect(page.getByRole('heading', { name: '操作痕迹', exact: true })).toBeVisible();
  await page.getByLabel('操作人工号', { exact: true }).fill('browser_sn12');
  await page.getByRole('textbox', { name: 'PI', exact: true }).fill('PI-1');
  await page.getByRole('combobox', { name: '动作', exact: true }).click();
  await page.locator('.ant-select-dropdown:visible').getByText('批准转厂', { exact: true }).click();
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.getByText('共 1 条', { exact: true })).toBeVisible();
  const rows = page.getByTestId('sn-audit-items');
  await expect(rows).toContainText('G0001 ~ G0013');
  await expect(rows).toContainText('申请中 → 待领取');
  await rows.getByRole('button', { name: '明细', exact: true }).click();
  const modal = page.getByRole('dialog');
  await expect(modal).toContainText('现场标签由工厂处理');
  await expect(modal).toContainText('PI-2');
  await page.screenshot({
    path: 'test-results/sn-audit-detail-zh.png',
    fullPage: true,
    animations: 'disabled',
  });
  await modal.press('Escape');
  await expect(modal).not.toBeVisible();
  await page.getByRole('combobox', { name: '记录范围', exact: true }).click();
  await page
    .locator('.ant-select-dropdown:visible .ant-select-item-option-content')
    .getByText('归档记录', { exact: true })
    .click();
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.getByText('共 0 条', { exact: true })).toBeVisible();
  await page.getByRole('combobox', { name: '记录范围', exact: true }).click();
  await page
    .locator('.ant-select-dropdown:visible')
    .getByText('在线与归档', { exact: true })
    .click();
  await page.getByRole('button', { name: '查询', exact: true }).click();
  await expect(page.getByText('共 1 条', { exact: true })).toBeVisible();
  await page.screenshot({
    path: 'test-results/sn-audit-zh.png',
    fullPage: true,
    animations: 'disabled',
  });
  for (const format of ['CSV', 'XLSX']) {
    await page.getByRole('button', { name: '导出 ' + format, exact: true }).click();
    await page.getByRole('button', { name: /^确\s*认$/ }).click();
    await expect(page.getByTestId('sn-task-state').last()).toHaveText('已完成', { timeout: 30000 });
  }
  await page.getByRole('tab', { name: '导出记录', exact: true }).click();
  const history = page.getByTestId('sn-audit-exports');
  for (const format of ['CSV', 'XLSX']) {
    const button = history.getByRole('button', { name: format + ' 下载', exact: true });
    await expect(button).toBeEnabled({ timeout: 30000 });
    const wait = page.waitForEvent('download');
    await button.click();
    const file = await wait;
    expect(file.suggestedFilename()).toMatch(/^sn-audit-[0-9a-f-]{36}\.(csv|xlsx)$/);
    await file.saveAs('test-results/sn-audit.' + format.toLowerCase());
    await expect(button).not.toHaveClass(/ant-btn-loading/);
  }
  await languageEvidence(page, '/audit', 'sn-audit', 'Audit trail', 'Nhật ký thao tác');
  expect(errors).toEqual([]);
});
