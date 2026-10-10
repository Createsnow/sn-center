import type { TFunction } from 'i18next';
import { ApiError } from '../../api/contract';
import { syncErrorText } from './syncErrorText';

const reasons = new Set([
  'USER_EMP_NO_INVALID',
  'USER_EMP_NO_EXISTS',
  'USER_NAME_REQUIRED',
  'USER_ROLE_INVALID',
  'USER_FACTORY_REQUIRED',
  'USER_ADMIN_NO_FACTORY',
  'USER_PASSWORD_WEAK',
  'FACTORY_NOT_FOUND',
]);

/** 只展示账号用例的已知拒绝原因，未知异常沿用统一错误处理。 */
export function accountErrorText(error: unknown, t: TFunction): string {
  if (
    error instanceof ApiError &&
    [1004000030, 1004000032].includes(error.code) &&
    reasons.has(error.params.reason ?? '')
  )
    return t(`prototypeErrors:${error.params.reason}`, { ...error.params });
  return syncErrorText(error, t);
}
