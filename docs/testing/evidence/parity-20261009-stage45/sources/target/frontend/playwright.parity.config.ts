import { defineConfig } from '@playwright/test';

// 比较专用服务和账号；仅操作合成数据，禁止录制含凭据的网络 trace。
process.env.PLAYWRIGHT_NO_COPY_PROMPT = '1';
export default defineConfig({
  testDir: './e2e/parity',
  outputDir: 'test-results/parity',
  workers: 1,
  fullyParallel: false,
  retries: 0,
  timeout: 60_000,
  expect: { timeout: 15_000 },
  reporter: [['list'], ['json', { outputFile: 'test-results/parity/results.json' }]],
  use: {
    channel: 'msedge',
    viewport: { width: 1440, height: 960 },
    locale: 'zh-CN',
    trace: 'off',
    screenshot: 'off',
    video: 'off',
  },
  projects: [
    {
      name: 'prototype',
      // 原型同步流式导出，没有持久任务或磁盘恢复协议；该验证仅适用于新版设施。
      testIgnore: '**/query-recovery.spec.ts',
      use: { baseURL: 'http://127.0.0.1:8000' },
    },
    {
      name: 'migration',
      use: {
        baseURL:
          process.env.ADTEK_PARITY_HTTPS === '1'
            ? 'https://127.0.0.1:18443'
            : 'http://127.0.0.1:15173',
        ignoreHTTPSErrors: process.env.ADTEK_PARITY_HTTPS === '1',
      },
    },
  ],
});
