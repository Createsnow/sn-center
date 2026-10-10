import type { TFunction } from 'i18next';
import { ApiError } from '../../api/contract';
import { printDownloadErrorText } from './printDownloadErrorText';

/** 仅翻译明确的同 PI 范围拒绝；不将通用 403 推断为本厂无号。 */
export function queryErrorText(error: unknown, t: TFunction): string {
  if (
    error instanceof ApiError &&
    error.code === 1003000004 &&
    error.params.reason === 'QUERY_PI_NOT_OWNED' &&
    typeof error.params.pi === 'string' &&
    error.params.pi.length > 0 &&
    error.params.pi.length <= 128 &&
    !Array.from(error.params.pi).some(
      (char) => char.charCodeAt(0) < 32 || /[\u007f-\u009f]/.test(char),
    )
  )
    return t('prototypeErrors:QUERY_PI_NOT_OWNED', { pi: error.params.pi });
  return printDownloadErrorText(error, t);
}
