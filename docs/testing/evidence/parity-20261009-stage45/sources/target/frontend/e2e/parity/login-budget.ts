import type { Page } from '@playwright/test';
import { setTimeout } from 'node:timers/promises';

let lastLoginAt = 0;

/** 比较套件遵循正式登录限流（同账号每分钟 8 次），不关闭或清空服务端限制。 */
export async function paceComparisonLogin(page: Page) {
  if (!['15173', '18443'].includes(new URL(page.url()).port)) return;
  await setTimeout(Math.max(0, 8_100 - (Date.now() - lastLoginAt)));
  lastLoginAt = Date.now();
}
