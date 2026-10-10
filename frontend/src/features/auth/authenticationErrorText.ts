import type { TFunction } from 'i18next';
import { ApiError } from '../../api/contract';
import { printDownloadErrorText } from '../sn/printDownloadErrorText';

const errors: Record<number, string> = {
  1002000001: 'LOGIN_FAILED',
  1002000002: 'USER_PASSWORD_WEAK',
  1002000003: 'USER_PASSWORD_WRONG',
  1002000004: 'USER_PASSWORD_SAME',
  1002000017: 'LOGIN_LOCKED',
  1002000018: 'ACCOUNT_DISABLED',
};

/** 认证页遵循原型文案；历史平台错误目录保持其原始版本。 */
export function authenticationErrorText(error: unknown, t: TFunction): string {
  if (error instanceof ApiError && errors[error.code])
    return t('prototypeErrors:' + errors[error.code], error.params);
  return printDownloadErrorText(error, t);
}
