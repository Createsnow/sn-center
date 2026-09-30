import { defineConfig } from '@playwright/test';
import base from './playwright.config';

// 只放行明确的本机独立容器HTTPS端口；不把忽略测试自签证书扩展到外部地址。
const url = process.env.ADTEK_CONTAINER_URL ?? 'https://127.0.0.1:18443';
if (!/^https:\/\/127\.0\.0\.1:(18443|18444)$/.test(url)) {
  throw new Error('Container browser tests require an isolated loopback HTTPS endpoint.');
}
export default defineConfig({
  ...base,
  testMatch: 'container.spec.ts',
  timeout: 120_000,
  use: { ...base.use, baseURL: url, ignoreHTTPSErrors: true, actionTimeout: 15_000 },
  outputDir: 'test-results/container-browser',
  reporter: [['list'], ['json', { outputFile: 'test-results/container-browser.json' }]],
});
