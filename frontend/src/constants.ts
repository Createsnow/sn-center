import { t } from "@/i18n";

/** SN 状态 → Element Plus 标签类型。 */
export const STATUS_TYPE: Record<string, "" | "info" | "primary" | "success" | "warning" | "danger"> = {
  PENDING_ALLOC: "info",
  TO_ACQUIRE: "primary",
  TO_PRINT: "warning",
  PRINTED: "success",
  APPLYING: "danger",
  TRANSFERRED_OUT: "info",
};
export const STATUS_CODES = ["PENDING_ALLOC", "TO_ACQUIRE", "TO_PRINT", "PRINTED", "APPLYING"];
export const ROLE_CODES = ["admin", "factory_operator", "query"];
export const TRANSFER_STATUS_TYPE: Record<string, "" | "info" | "primary" | "success" | "warning" | "danger"> = {
  PENDING: "warning",
  APPROVED: "success",
  REJECTED: "danger",
  WITHDRAWN: "info",
};
export const AUDIT_ACTIONS = [
  "SN_GENERATE", "SN_REVOKE", "SN_ALLOCATE", "SN_ACQUIRE", "SN_PRINT", "SN_CALLBACK",
  "TRANSFER_APPLY", "TRANSFER_WITHDRAW", "TRANSFER_APPROVE", "TRANSFER_REJECT", "TRANSFER_DIRECT",
  "USER_CREATE", "USER_UPDATE", "USER_DISABLE", "USER_ENABLE", "USER_RESET_PWD", "PASSWORD_CHANGE",
  "RULE_CREATE", "RULE_UPDATE", "RULE_VERSION", "RULE_RENAME", "RULE_REBIND", "RULE_PI_BIND", "RULE_PI_UNBIND",
  "ORDER_SYNC", "ORDER_SYNC_SCHEDULE", "FACTORY_SYNC", "FACTORY_CREATE", "AUDIT_ARCHIVE", "OTHER",
];
/** 生成页单次默认生成数量的上限（可在抽屉里改）。后台不设上限。 */
export const DEFAULT_PAGE_LIMIT = 10000;

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
