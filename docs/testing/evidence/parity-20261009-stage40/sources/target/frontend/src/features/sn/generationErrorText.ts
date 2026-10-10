import type { TFunction } from 'i18next';
import { ApiError } from '../../api/contract';
import { syncErrorText } from './syncErrorText';

const reasons: Record<string, number> = {
  GEN_PREVIEW_REQUIRED: 1004000016,
  GEN_PREVIEW_USED: 1004000016,
  GEN_PREVIEW_EXPIRED: 1004000016,
  GEN_PREVIEW_CHANGED: 1004000016,
  GEN_PREVIEW_STALE: 1004000016,
  GEN_QUOTA_EMPTY: 1004000017,
  GEN_QUOTA_EXCEEDED: 1004000018,
  ALLOC_NOTHING: 1004000019,
  ALLOC_FACTORY_MISMATCH: 1004000019,
  ALLOC_QTY_EXCEEDED: 1004000029,
};

/** 受控生成原因使用原型文案；未知原因不作为翻译键或异常原文展示。 */
export function generationErrorText(error: unknown, t: TFunction): string {
  if (error instanceof ApiError && reasons[error.params.reason ?? ''] === error.code)
    return t(`prototypeErrors:${error.params.reason}`, { ...error.params });
  return syncErrorText(error, t);
}

export function generationFailureText(
  code: number | null,
  params: Record<string, string>,
  t: TFunction,
): string {
  return code === null ? '' : generationErrorText(new ApiError(code, 409, '', params), t);
}
