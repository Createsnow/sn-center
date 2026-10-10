/** 原型动作目录及已有审计动作的显示映射；保留未识别的历史动作原文。 */
export const auditActions = {
  SN_GENERATE: 'sn:generation:execute',
  SN_ALLOCATE: 'sn:allocation:execute',
  SN_ACQUIRE: 'sn:acquire:take',
  SN_PRINT: 'sn:print:execute',
  SN_CALLBACK: 'sn:acquire:callback',
  TRANSFER_APPLY: 'sn:transfer:apply',
  TRANSFER_WITHDRAW: 'sn:transfer:withdraw',
  TRANSFER_APPROVE: 'sn:transfer:approve',
  TRANSFER_REJECT: 'sn:transfer:reject',
  TRANSFER_DIRECT: 'sn:transfer:direct',
  USER_CREATE: 'system:user:add',
  USER_UPDATE: 'system:user:update',
  USER_DISABLE: 'USER_DISABLE',
  USER_ENABLE: 'USER_ENABLE',
  USER_RESET_PWD: 'system:user:reset',
  PASSWORD_CHANGE: 'auth:PASSWORD_CHANGE',
  RULE_CREATE: 'sn:rule:create',
  RULE_UPDATE: 'sn:rule:update',
  RULE_VERSION: 'RULE_VERSION',
  RULE_RENAME: 'RULE_RENAME',
  PI_INIT: 'sn:pi-init:execute',
  ORDER_SYNC: 'sn:order:sync',
  ORDER_SYNC_SCHEDULE: 'sn:order:schedule',
  FACTORY_SYNC: 'sn:factory:sync',
  FACTORY_CREATE: 'sn:factory:create',
  AUDIT_ARCHIVE: 'AUDIT_ARCHIVE',
  OTHER: 'OTHER',
};

export function auditActionCode(action: string): string {
  return Object.entries(auditActions).find(([, value]) => value === action)?.[0] ?? action;
}
