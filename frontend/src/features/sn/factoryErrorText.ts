import type { TFunction } from 'i18next';
import { ApiError } from '../../api/contract';
import { syncErrorText } from './syncErrorText';

/** 工厂受控原因使用原型三语；不把外部系统或异常原文显示给页面。 */
export function factoryErrorText(error: unknown, t: TFunction): string {
  if (error instanceof ApiError) {
    if (error.code === 1004000033) return t('prototypeErrors:FACTORY_CODE_INVALID');
    if (error.code === 1004000003)
      return t('prototypeErrors:FACTORY_EXISTS', { factory: error.params.factory ?? '' });
  }
  return syncErrorText(error, t);
}
