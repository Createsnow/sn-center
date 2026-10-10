import type { TFunction } from 'i18next';
import { DownloadHttpError } from '../../api/contract';
import { syncErrorText } from './syncErrorText';

/** 原型下载对非 JSON HTTP 拒绝显示状态码；有效业务错误仍保留受控的本地化语义。 */
export function printDownloadErrorText(error: unknown, t: TFunction): string {
  if (error instanceof DownloadHttpError && error.status >= 400)
    return t('prototype:common.downloadFailed', { status: error.status });
  return syncErrorText(error, t);
}
