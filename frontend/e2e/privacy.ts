import { test, type TestInfoError } from '@playwright/test';

// 关闭通用页面快照；1.63 的 locator matcher 仍会带独立 ARIA 快照，需在报告写入前另行移除。
process.env.PLAYWRIGHT_NO_COPY_PROMPT = '1';
const secrets = new Set<string>();
export function protectTestSecrets(...values: string[]) {
  values.filter((value) => value.length > 0).forEach((value) => secrets.add(value));
}
function redact(value: string | undefined) {
  if (value === undefined) {
    return undefined;
  }
  for (const secret of secrets) {
    value = value.replaceAll(secret, '[REDACTED]');
  }
  return value;
}
function sanitize(error: TestInfoError) {
  // 只移除可能包含输入值的附加快照，保留错误、堆栈、失败状态及用例数量。
  delete error.errorContext;
  error.message = redact(error.message);
  error.stack = redact(error.stack);
  if (error.cause) {
    sanitize(error.cause);
  }
}
test.afterEach(async ({ page }, info) => {
  info.errors.forEach(sanitize);
  if (info.errors.length && !page.isClosed()) {
    await page.locator('input[type="password"]').evaluateAll((inputs) => {
      for (const input of inputs) {
        if (input instanceof HTMLInputElement) {
          input.value = '';
        }
      }
    });
  }
});
