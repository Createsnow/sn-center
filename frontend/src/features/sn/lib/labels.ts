import i18n from '../../../i18n';
import { ApiError } from '../../../api/client';

export type TagColor = 'default' | 'processing' | 'success' | 'warning' | 'error' | 'blue';

/** 生成、分配详情中单据超过这么多张时，打开 PI 默认全部收起。 */
export const BILL_FOLD_OVER = 5;

export const STATUS_COLOR: Record<string, TagColor> = {
  PENDING_ALLOC: 'default',
  TO_PUSH: 'warning',
  PUSHED: 'success',
  ALLOCATED: 'blue',
};
export const STATUS_CODES = ['PENDING_ALLOC', 'TO_PUSH', 'PUSHED', 'ALLOCATED'];
export const BATCH_RESULT_COLOR: Record<string, TagColor> = {
  UNCONFIRMED: 'warning',
  SUCCESS: 'success',
  FAILED: 'error',
  VOIDED: 'default',
};
export const BATCH_RESULTS = ['UNCONFIRMED', 'SUCCESS', 'FAILED', 'VOIDED'];
export const BATCH_TYPES = ['DELIVERY', 'NEW', 'SAME_ORG_TRANSFER', 'TRANSFER_IN', 'REMAP', 'TRANSFER_OUT'];
export const RECEIPT_RESULT_COLOR: Record<string, TagColor> = { ACCEPTED: 'success' };
export const CHARSETS: Record<number, string> = {
  10: '0123456789',
  16: '0123456789ABCDEF',
  32: '0123456789ABCDEFGHJKMNPQRSTVWXYZ',
  36: '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ',
};
export const BASES = Object.keys(CHARSETS).map(Number);
export const ROLE_CODES = ['operator', 'auditor'];
export const AUDIT_ACTIONS = [
  'SN_GENERATE', 'SN_GEN_CANCEL', 'SN_REVOKE', 'SN_IMPORT', 'SN_IMPORT_DELETE', 'SN_ALLOCATE', 'SN_BATCH_CREATE',
  'SN_PUSH', 'SN_PUSH_RESUME', 'SN_PULL', 'SN_RECEIPT', 'SN_BATCH_MANUAL', 'SN_BATCH_VOID', 'SN_ALERT_HANDLE',
  'SN_ACQUIRE', 'SN_READ', 'TRANSFER_BILL', 'TRANSFER_DIRECT', 'ORDER_SYNC', 'ORDER_ATTACH_FILL',
  'ORDER_LENGTH_FILL', 'ORDER_UNIT_FILL', 'ORDER_ALERT', 'ORDER_ALERT_HANDLE', 'ORDER_SUSPEND', 'ORDER_RESUME',
  'RULE_CREATE', 'RULE_UPDATE', 'RULE_VERSION', 'RULE_RENAME', 'RULE_CATEGORY', 'RULE_PI_BIND', 'RULE_PI_UNBIND',
  'RULE_LINE_SELECT', 'LINE_CONFIG_REVOKE', 'RULE_PREFIX_VERSION', 'RULE_DELETE', 'LOGIN', 'PASSWORD_CHANGE',
  'MES_AUTH', 'MES_AUTH_LOCK', 'MES_AUTH_UNLOCK', 'USER_CREATE', 'USER_UPDATE', 'USER_DISABLE', 'USER_ENABLE',
  'USER_RESET_PWD', 'USER_RESET_SECRET', 'USER_VIEW_SECRET', 'FACTORY_SYNC', 'FACTORY_MES_CONFIG',
  'FACTORY_MES_TOGGLE', 'FACTORY_MES_TEST', 'K3_CONFIG', 'K3_CONFIG_TEST', 'SELF_CHECK', 'AUDIT_ARCHIVE',
  'AUDIT_ARCHIVE_PURGE', 'OTHER',
];

/** sn 功能命名空间的文案（组件外使用）。 */
export function st(key: string, params?: Record<string, unknown>): string {
  return i18n.t(key, { ns: 'sn', ...params });
}

function label(prefix: string, code: unknown): string {
  if (code === null || code === undefined || code === '') return '';
  const key = `${prefix}.${String(code)}`;
  return i18n.exists(key, { ns: 'sn' }) ? st(key) : String(code);
}

export const statusLabel = (c: unknown) => label('status', c);
export const roleLabel = (c: unknown) => label('roles', c);
export const actionLabel = (c: unknown) => label('actions', c);
export const scopeLabel = (c: unknown) => label('scope', c);
export const receiptResultLabel = (c: unknown) => label('receiptResult', c);
export const receiptOutcomeLabel = (c: unknown) => label('receiptOutcome', c);
export const receiptVerdictLabel = (c: unknown) => label('receiptVerdict', c);
export const pushTypeLabel = (c: unknown) => label('pushType', c);
export const suspendLabel = (c: unknown) => label('suspend', c);
export const batchResultLabel = (c: unknown) => label('batchResult', c);
export const batchSourceLabel = (c: unknown) => label('batchSource', c);
export const batchTypeLabel = (c: unknown) => label('batchType', c);
export const confirmMethodLabel = (c: unknown) => label('confirmMethod', c);
export const pushStateLabel = (c: unknown) => label('pushState', c);
export const taskStateLabel = (c: unknown) => label('taskState', c);
export const cfgStatusLabel = (c: unknown) => label('cfgStatus', c);
export const leaveStateLabel = (c: unknown) => label('leaveState', c);

/** 后端错误 → 本地化文案（错误翻译在共享字典 error.*）。 */
export function errorText(e: unknown): string {
  if (e instanceof ApiError) {
    const text = i18n.t(e.key, { ...e.args });
    return e.key.startsWith('error.common.internalError') && e.traceId ? `${text}（${e.traceId}）` : text;
  }
  return e instanceof Error ? e.message : String(e);
}

export function errorKey(e: unknown): string {
  return e instanceof ApiError ? e.key : '';
}
