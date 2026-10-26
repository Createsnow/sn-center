import { t } from "@/i18n";

type TagType = "" | "info" | "primary" | "success" | "warning" | "danger";

/** 生成、分配详情中单据超过这么多张时，打开 PI 默认全部收起。 */
export const BILL_FOLD_OVER = 5;

/** SN 状态 → Element Plus 标签类型。 */
export const STATUS_TYPE: Record<string, TagType> = {
  PENDING_ALLOC: "info",
  TO_PUSH: "warning",
  PUSHED: "success",
  ALLOCATED: "primary",
  ACQUIRED: "success",
  APPLYING: "danger",
};
export const STATUS_CODES = ["PENDING_ALLOC", "TO_PUSH", "PUSHED", "ALLOCATED", "ACQUIRED", "APPLYING"];
/** 交付批次结果：UNCONFIRMED 未确认 / SUCCESS / SUCCESS_CONFLICT 成功（有冲突）/ FAILED / VOIDED 已作废。 */
export const BATCH_RESULT_TYPE: Record<string, TagType> = {
  UNCONFIRMED: "warning",
  SUCCESS: "success",
  SUCCESS_CONFLICT: "warning",
  FAILED: "danger",
  VOIDED: "info",
};
export const BATCH_RESULTS = ["UNCONFIRMED", "SUCCESS", "SUCCESS_CONFLICT", "FAILED", "VOIDED"];
/** 批次类型筛选：DELIVERY = 全部交付类（后端按非转出筛选） */
export const BATCH_TYPES = ["DELIVERY", "NEW", "SAME_ORG_TRANSFER", "TRANSFER_IN", "REMAP", "TRANSFER_OUT"];
/** 逐枚回执结果 */
export const RECEIPT_RESULT_TYPE: Record<string, TagType> = {
  ACCEPTED: "success",
  DUPLICATE: "info",
  CONFLICT: "danger",
  STALE: "warning",
};
/** 规则进制 → 系统默认字符表（与后端 encoding.DEFAULT_CHARSETS 一致；32 进制去掉 I L O U）。 */
export const CHARSETS: Record<number, string> = {
  10: "0123456789",
  16: "0123456789ABCDEF",
  32: "0123456789ABCDEFGHJKMNPQRSTVWXYZ",
  36: "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ",
};
export const BASES = Object.keys(CHARSETS).map(Number);
export const ROLE_CODES = ["admin", "factory_operator", "query"];
export const TRANSFER_STATUS_TYPE: Record<string, TagType> = {
  PENDING: "warning",
  APPROVED: "success",
  REJECTED: "danger",
  WITHDRAWN: "info",
};
export const AUDIT_ACTIONS = [
  "SN_GENERATE", "SN_GEN_CANCEL", "SN_REVOKE", "SN_ALLOCATE", "SN_BATCH_CREATE", "SN_PUSH", "SN_PUSH_RESUME", "SN_PULL",
  "SN_RECEIPT", "SN_BATCH_MANUAL", "SN_BATCH_VOID", "SN_ALERT_HANDLE", "SN_ACQUIRE", "SN_READ",
  "TRANSFER_BILL", "TRANSFER_APPLY", "TRANSFER_WITHDRAW", "TRANSFER_APPROVE", "TRANSFER_REJECT", "TRANSFER_DIRECT",
  "ORDER_SYNC", "ORDER_ATTACH_FILL", "ORDER_LENGTH_FILL", "ORDER_ALERT", "ORDER_ALERT_HANDLE", "ORDER_SUSPEND", "ORDER_RESUME",
  "RULE_CREATE", "RULE_UPDATE", "RULE_VERSION", "RULE_RENAME", "RULE_CATEGORY", "RULE_PI_BIND", "RULE_PI_UNBIND",
  "RULE_LINE_SELECT", "RULE_PREFIX_VERSION", "RULE_CUSTOMER_BIND", "RULE_CUSTOMER_UNBIND", "RULE_DELETE",
  "LOGIN", "PASSWORD_CHANGE", "MES_AUTH", "MES_AUTH_LOCK", "MES_AUTH_UNLOCK",
  "USER_CREATE", "USER_UPDATE", "USER_DISABLE", "USER_ENABLE", "USER_RESET_PWD", "USER_RESET_SECRET", "USER_VIEW_SECRET",
  "FACTORY_SYNC", "FACTORY_MES_CONFIG", "FACTORY_MES_TOGGLE", "FACTORY_MES_TEST",
  "SELF_CHECK", "AUDIT_ARCHIVE", "AUDIT_ARCHIVE_PURGE", "OTHER",
];

function label(prefix: string, code: string | null | undefined): string {
  if (!code) return "";
  const key = `${prefix}.${code}`;
  const s = t(key);
  return s === key ? code : s;
}

export const statusLabel = (c?: string | null) => label("status", c);
export const roleLabel = (c?: string | null) => label("roles", c);
export const actionLabel = (c?: string | null) => label("actions", c);
export const transferStatusLabel = (c?: string | null) => label("transferStatus", c);
export const scopeLabel = (c?: string | null) => label("scope", c);
export const deliveryModeLabel = (c?: string | null) => label("deliveryMode", c);
export const receiptResultLabel = (c?: string | null) => label("receiptResult", c);
export const receiptOutcomeLabel = (c?: string | null) => label("receiptOutcome", c);
export const receiptVerdictLabel = (c?: string | null) => label("receiptVerdict", c);
export const pushTypeLabel = (c?: string | null) => label("pushType", c);
export const suspendLabel = (c?: string | null) => label("suspend", c);
export const batchResultLabel = (c?: string | null) => label("batchResult", c);
export const batchSourceLabel = (c?: string | null) => label("batchSource", c);
export const batchTypeLabel = (c?: string | null) => label("batchType", c);
export const confirmMethodLabel = (c?: string | null) => label("confirmMethod", c);
export const pushStateLabel = (c?: string | null) => label("pushState", c);
export const taskStateLabel = (c?: string | null) => label("taskState", c);
export const cfgStatusLabel = (c?: string | null) => label("cfgStatus", c);
export const leaveStateLabel = (c?: string | null) => label("leaveState", c);
