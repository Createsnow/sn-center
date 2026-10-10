import type { TFunction } from 'i18next';
import { ApiError } from '../../api/contract';
import { syncErrorText } from './syncErrorText';

const reasons: Record<string, { code: number; keys: string[] }> = {
  PI_INIT_EMPTY: { code: 1000000001, keys: [] },
  PI_IMPORT_SN_INVALID: { code: 1000000001, keys: ['line'] },
  PI_IMPORT_DUP_IN_LIST: { code: 1004000015, keys: ['sn'] },
  PI_IMPORT_DUP_EXISTING: { code: 1004000015, keys: ['sn', 'pi'] },
  PI_IMPORT_SEQ_DUP: { code: 1004000015, keys: ['seq', 'pi'] },
  PI_IMPORT_TOO_LARGE: { code: 1004000011, keys: ['max'] },
  PI_INIT_LOCKED: { code: 1004000013, keys: ['pi'] },
  GEN_START_INVALID: { code: 1004000002, keys: ['max'] },
};

/** 仅将受控初始化原因映射为原型文案；不显示异常原文，精确容量不经过数值转换。 */
export function initializationErrorText(error: unknown, t: TFunction): string {
  if (error instanceof ApiError) {
    const reason = error.params.reason ?? '';
    const definition = reasons[reason];
    if (
      definition?.code === error.code &&
      definition.keys.every((key) => {
        const value = error.params[key];
        return (
          typeof value === 'string' &&
          value.length <= 128 &&
          (!['line', 'max'].includes(key) || /^[1-9][0-9]{0,18}$/.test(value)) &&
          (key !== 'seq' || /^(0|[1-9][0-9]{0,18})$/.test(value))
        );
      })
    )
      return t(`prototypeErrors:${reason}`, { ...error.params });
  }
  return syncErrorText(error, t);
}
