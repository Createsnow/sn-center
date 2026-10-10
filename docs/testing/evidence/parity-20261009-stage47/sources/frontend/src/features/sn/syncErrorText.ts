import type { TFunction } from 'i18next';
import { ApiError } from '../../api/contract';

/** 只将已脱敏的 K3 业务摘要映射到原型文案；未知错误仍使用统一受控错误码。 */
export function syncErrorText(error: unknown, t: TFunction): string {
  if (!(error instanceof ApiError)) return t('errors.unknown');
  // 原型未处理的本地事务异常返回固定HTTP文本；不显示SQL、载荷或原始异常摘要。
  if (error.code === 1000000002 && error.status === 500) return 'Internal Server Error';
  if (error.params.message && (error.code === 1005000002 || error.code === 1005000006)) {
    return t(
      `prototypeErrors:${error.code === 1005000002 ? 'K3_LOGIN_FAILED' : 'K3_QUERY_FAILED'}`,
      { message: error.params.message },
    );
  }
  return t(error.status === 0 ? 'errors.network' : `errors.${error.code}`, {
    ...error.params,
    defaultValue: t('errors.unknown'),
  });
}
