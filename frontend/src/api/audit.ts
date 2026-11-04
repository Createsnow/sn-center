import type { components } from './generated/schema';
import { query } from './client';
import { isRecord } from './contract';
export type AuditLog = components['schemas']['AuditLogVO'];
export type AuditDetail = components['schemas']['AuditDetailVO'];
export type AuditOptions = components['schemas']['AuditOptionsVO'];
export type AuditPage = Required<components['schemas']['PageResultAuditLogVO']>;
export type AuditFilter = {
  from?: string;
  to?: string;
  action?: string;
  module?: string;
  actorUsername?: string;
  userId?: string;
  traceId?: string;
  success?: boolean;
};
const id = (value: unknown): value is string =>
  typeof value === 'string' && /^[1-9][0-9]*$/.test(value);
const textOrNull = (value: unknown) => value === null || typeof value === 'string';
export function isAuditLog(value: unknown): value is AuditLog {
  return (
    isRecord(value) &&
    id(value.id) &&
    (value.userId === null || id(value.userId)) &&
    (value.actorDeptId === null || id(value.actorDeptId)) &&
    textOrNull(value.actorUsername) &&
    typeof value.moduleName === 'string' &&
    typeof value.businessType === 'string' &&
    typeof value.action === 'string' &&
    textOrNull(value.objectId) &&
    typeof value.success === 'boolean' &&
    textOrNull(value.traceId) &&
    typeof value.createTime === 'string' &&
    Number.isFinite(Date.parse(value.createTime)) &&
    textOrNull(value.requestMethod) &&
    textOrNull(value.requestRoute) &&
    (value.httpStatus === null ||
      (typeof value.httpStatus === 'number' &&
        Number.isInteger(value.httpStatus) &&
        value.httpStatus >= 400 &&
        value.httpStatus <= 599)) &&
    (value.errorCode === null ||
      (typeof value.errorCode === 'number' &&
        Number.isInteger(value.errorCode) &&
        value.errorCode > 0 &&
        value.errorCode <= 2147483647))
  );
}
export function isAuditDetail(value: unknown): value is AuditDetail {
  return (
    isRecord(value) &&
    isAuditLog(value.event) &&
    Array.isArray(value.changes) &&
    value.changes.every(
      (item: unknown) =>
        isRecord(item) &&
        typeof item.field === 'string' &&
        textOrNull(item.before) &&
        textOrNull(item.after),
    ) &&
    textOrNull(value.reason) &&
    textOrNull(value.failureCode) &&
    typeof value.redacted === 'boolean'
  );
}
const isPage = (value: unknown): value is AuditPage =>
  isRecord(value) &&
  Array.isArray(value.items) &&
  value.items.every(isAuditLog) &&
  typeof value.total === 'string' &&
  /^(0|[1-9][0-9]*)$/.test(value.total) &&
  Number.isInteger(value.page) &&
  Number.isInteger(value.size);
const isOptions = (value: unknown): value is AuditOptions =>
  isRecord(value) &&
  Array.isArray(value.actions) &&
  value.actions.every((item: unknown) => typeof item === 'string') &&
  Array.isArray(value.modules) &&
  value.modules.every((item: unknown) => typeof item === 'string');
function parameters(filter: AuditFilter) {
  const params = new URLSearchParams();
  for (const [name, value] of Object.entries(filter)) {
    if (value !== undefined && value !== '') {
      params.set(name, String(value));
    }
  }
  return params;
}
export const auditApi = {
  list(filter: AuditFilter, page: number, size: number) {
    const params = parameters(filter);
    params.set('page', String(page));
    params.set('size', String(size));
    return query('/system/audits?' + params.toString(), isPage);
  },
  options(filter: AuditFilter) {
    return query(
      '/system/audits/options?' + parameters({ from: filter.from, to: filter.to }).toString(),
      isOptions,
    );
  },
  detail(id: string) {
    return query('/system/audits/' + encodeURIComponent(id), isAuditDetail);
  },
};
