import { defineConfig } from '@playwright/test';
import base from './playwright.config';

/** 由隔离 MySQL/Redis + Spring 测试进程启动，不读取开发管理员密码、不修改开发数据库。 */
export default defineConfig({
  ...base,
  testMatch: [
    'auth.spec.ts',
    'users.spec.ts',
    'configuration.spec.ts',
    'audit.spec.ts',
    'export.spec.ts',
    'sn.spec.ts',
  ],
  timeout: 90_000,
  use: { ...base.use, actionTimeout: 15_000 },
  outputDir: 'test-results/browser-users',
  reporter: [['list'], ['json', { outputFile: 'test-results/browser-users.json' }]],
});
