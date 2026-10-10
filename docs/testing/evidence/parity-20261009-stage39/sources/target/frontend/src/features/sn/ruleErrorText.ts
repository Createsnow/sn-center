import type { TFunction } from 'i18next';
import { ApiError } from '../../api/contract';
import type { RuleCommand } from '../../api/sn';
import { syncErrorText } from './syncErrorText';

const reasons = new Set([
  'RULE_CODE_INVALID',
  'RULE_CODE_EXISTS',
  'RULE_NAME_REQUIRED',
  'RULE_SCOPE_INVALID',
  'RULE_BIND_REQUIRED',
  'RULE_BIND_EXISTS',
  'RULE_BASE_INVALID',
  'RULE_SEQ_LEN_INVALID',
  'RULE_CHARSET_LENGTH',
  'RULE_CHARSET_DUPLICATE',
  'RULE_SN_TOO_LONG',
]);

/** 仅翻译规则用例的已知原因，其他服务错误继续走统一错误边界。 */
export function ruleErrorText(error: unknown, t: TFunction): string {
  if (
    error instanceof ApiError &&
    [1004000001, 1004000006].includes(error.code) &&
    reasons.has(error.params.reason ?? '')
  )
    return t(`prototypeErrors:${error.params.reason}`, { ...error.params });
  return syncErrorText(error, t);
}

/** 表单与原型一样修剪外围空白；服务端仍负责约束、唯一性和乐观锁检查。 */
export function normalizeRuleForm(value: RuleCommand): RuleCommand {
  const command = {
    ...value,
    code: value.code.trim(),
    name: value.name.trim(),
    bindValue: value.bindScope === 'GENERAL' ? '' : (value.bindValue ?? '').trim(),
    prefix: (value.prefix ?? '').trim(),
    suffix: (value.suffix ?? '').trim(),
    alphabet: (value.alphabet ?? '').trim(),
  };
  const reject = (reason: string): never => {
    throw new ApiError(1004000001, 400, '', { reason });
  };
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(command.code)) reject('RULE_CODE_INVALID');
  if (!command.name || [...command.name].length > 128) reject('RULE_NAME_REQUIRED');
  if (!['GENERAL', 'CUSTOMER', 'PI'].includes(command.bindScope)) reject('RULE_SCOPE_INVALID');
  if (command.bindScope !== 'GENERAL' && !command.bindValue) reject('RULE_BIND_REQUIRED');
  return command;
}
