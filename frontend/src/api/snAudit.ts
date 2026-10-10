import type { components } from './generated/schema';
import { query, submit } from './client';
import { isRecord } from './contract';
import { isOperationId, isTask } from './tasks';
import { pageGuard } from './sn';
import { cursorGuard } from './snAcquire';
import { isSnCount, isSnExport } from './snQuery';
export type SnAudit = components['schemas']['SnAuditVO'];
export type SnAuditFilter = components['schemas']['SnAuditQuery'];
export type SnAuditExport = components['schemas']['SnAuditExportDTO'];
export type SnAuditMeta = components['schemas']['SnAuditMetaVO'];
const text = (v: unknown): v is string => typeof v === 'string';
const decimal = (v: unknown): v is string => text(v) && /^(0|[1-9]\d*)$/.test(v);
const nullable = (v: unknown, guard: (v: unknown) => boolean) => v === null || guard(v);
export const isSnAudit = (v: unknown): v is SnAudit =>
  isRecord(v) &&
  isOperationId(v.id) &&
  text(v.createdAt) &&
  Number.isFinite(Date.parse(v.createdAt)) &&
  nullable(v.actorId, decimal) &&
  nullable(v.quantity, decimal) &&
  ['operator', 'operatorName', 'roles', 'source', 'action', 'detail'].every((k) => text(v[k])) &&
  ['SUCCESS', 'FAILED'].includes(String(v.result)) &&
  ['factoryId', 'toFactoryId'].every((k) => nullable(v[k], decimal)) &&
  [
    'factoryCode',
    'factoryName',
    'pi',
    'toFactoryCode',
    'toPi',
    'customer',
    'material',
    'billNo',
    'batchNo',
    'transferNo',
    'startSn',
    'endSn',
    'beforeStatus',
    'afterStatus',
    'reason',
    'errorCode',
    'errorMessage',
    'traceId',
  ].every((k) => nullable(v[k], text)) &&
  ['batchId', 'operationId', 'transferId'].every((k) => nullable(v[k], isOperationId));
export const isSnAuditMeta = (v: unknown): v is SnAuditMeta =>
  isRecord(v) &&
  typeof v.permanent === 'boolean' &&
  decimal(v.exportLimit) &&
  typeof v.defaultDays === 'number' &&
  Number.isInteger(v.defaultDays) &&
  typeof v.archivesQueryable === 'boolean';
const params = (filter: SnAuditFilter, cursor?: string, size?: number) => {
  const p = new URLSearchParams();
  Object.entries({ ...filter, cursor, size }).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') p.set(k, String(v));
  });
  return p.toString();
};
export const snAuditApi = {
  page: (filter: SnAuditFilter, page: number, size: number) =>
    query(
      '/sn/audits/page?' + params(filter, undefined, size) + '&page=' + page,
      pageGuard(isSnAudit),
    ),
  items: (filter: SnAuditFilter, cursor?: string, size = 50) =>
    query('/sn/audits?' + params(filter, cursor, size), cursorGuard(isSnAudit)),
  count: (filter: SnAuditFilter) => query('/sn/audits/count?' + params(filter), isSnCount),
  detail: (row: SnAudit) =>
    query(
      '/sn/audits/' +
        encodeURIComponent(row.id) +
        '?' +
        new URLSearchParams({ createdAt: row.createdAt }),
      isSnAudit,
    ),
  meta: () => query('/sn/audits/meta', isSnAuditMeta),
  export: (input: SnAuditExport) => submit('/sn/audits/exports', input, isTask, 60000),
  history: (page: number) =>
    query(`/sn/audits/exports?page=${page}&size=20`, pageGuard(isSnExport)),
};
