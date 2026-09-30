import { defineConfig, devices } from '@playwright/test';

// Playwright 的失败辅助快照包含 input 值；认证测试禁止生成这种 DOM 快照。
process.env.PLAYWRIGHT_NO_COPY_PROMPT = '1';

export default defineConfig({
  testDir: './e2e',
  testMatch: 'auth.spec.ts',
  outputDir: 'test-results/browser',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list'], ['json', { outputFile: 'test-results/browser.json' }]],
  use: {
    ...devices['Desktop Chrome'],
    channel: process.platform === 'win32' ? 'msedge' : undefined,
    baseURL: process.env.ADTEK_E2E_URL ?? 'http://127.0.0.1:15173',
    // 网络 trace/storageState 含认证秘密，禁止生成；只保存明确指定的无秘密页面截图。
    trace: 'off',
    screenshot: 'off',
    video: 'off',
    viewport: { width: 1440, height: 960 },
  },
});
