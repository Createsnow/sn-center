import { message, Modal } from 'antd';
import { errorKey, errorText } from './labels';

/** 用弹窗（须点确定）而不是消息条提示的错误：前缀冲突（防重，F-08）。 */
const ALERT_KEYS = new Set(['error.sn.rulePrefixConflict']);

export function showError(e: unknown): void {
  const text = errorText(e);
  if (ALERT_KEYS.has(errorKey(e))) {
    Modal.error({ content: text });
  } else {
    void message.error({ content: text, duration: 5 });
  }
}

export function showSuccess(text: string): void {
  void message.success(text);
}

export function showInfo(text: string): void {
  void message.info(text);
}

export function showWarning(text: string): void {
  void message.warning(text);
}

/** 执行写操作：失败统一提示，返回是否成功。 */
export async function run<T>(fn: () => Promise<T>): Promise<T | undefined> {
  try {
    return await fn();
  } catch (e) {
    showError(e);
    return undefined;
  }
}
