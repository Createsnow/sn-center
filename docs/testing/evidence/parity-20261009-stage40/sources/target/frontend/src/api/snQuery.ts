import type { components } from './generated/schema';
import { query, submit } from './client';
import { isRecord } from './contract';
import { isOperationId, isTask } from './tasks';
import { isFactory, pageGuard } from './sn';
import { cursorGuard } from './snAcquire';
import { isSnWork } from './snWork';
export type SnItem = components['schemas']['SnQueryItemVO'];
export type SnFilter = components['schemas']['SnItemQuery'];
export type SnQueryPage = components['schemas']['SnQueryPageVO'];
export type SnExport = components['schemas']['SnExportVO'];
export type SnExportCommand = components['schemas']['SnExportDTO'];
export type Dashboard = components['schemas']['DashboardVO'];
export type DashboardJob = components['schemas']['DashboardJobVO'];
export type SnContext = components['schemas']['SnContextVO'];
export type SnCount = components['schemas']['SnCountVO'];
const text = (v: unknown): v is string => typeof v === 'string';
const decimal = (v: unknown): v is string => text(v) && /^(0|[1-9]\d*)$/.test(v);
const date = (v: unknown) => text(v) && Number.isFinite(Date.parse(v));
const nullable = (v: unknown, guard: (v: unknown) => boolean) => v === null || guard(v);
const fields = (v: Record<string, unknown>, keys: string[], guard: (v: unknown) => boolean) =>
  keys.every((k) => guard(v[k]));
export const isSnItem = (v: unknown): v is SnItem =>
  isRecord(v) &&
  isOperationId(v.id) &&
  fields(v, ['genMonth', 'sn', 'pi', 'customer', 'material', 'billNo'], text) &&
  fields(v, ['factoryId', 'seqDec', 'ruleVersionId'], (x) => nullable(x, decimal)) &&
  fields(
    v,
    [
      'factoryCode',
      'factoryName',
      'seqPi',
      'seqText',
      'sourceSystem',
      'sourceAccount',
      'sourceBillId',
      'sourceEntryId',
      'currentBatchNo',
    ],
    (x) => nullable(x, text),
  ) &&
  fields(v, ['currentBatchId', 'currentPrintId', 'currentTransferId'], (x) =>
    nullable(x, isOperationId),
  ) &&
  ['PENDING_ALLOC', 'TO_ACQUIRE', 'TO_PRINT', 'PRINTED', 'APPLYING'].includes(String(v.status)) &&
  ['GEN', 'IMPORT'].includes(String(v.source)) &&
  date(v.createdAt) &&
  nullable(v.acquiredAt, date) &&
  nullable(v.printedAt, date);
export const isSnCount = (v: unknown): v is SnCount =>
  isRecord(v) && text(v.name) && decimal(v.quantity);
export const isSnQueryPage = (v: unknown): v is SnQueryPage =>
  isRecord(v) &&
  Array.isArray(v.items) &&
  v.items.every(isSnItem) &&
  decimal(v.total) &&
  Number.isSafeInteger(v.page) &&
  Number(v.page) > 0 &&
  Number.isSafeInteger(v.size) &&
  Number(v.size) > 0 &&
  typeof v.totalCapped === 'boolean';
export const isSnContext = (v: unknown): v is SnContext =>
  isRecord(v) &&
  ['admin', 'factory_operator', 'query'].includes(String(v.role)) &&
  typeof v.allFactories === 'boolean' &&
  Array.isArray(v.factories) &&
  v.factories.every(isFactory);
export const isDashboardJob = (v: unknown): v is DashboardJob =>
  isRecord(v) &&
  decimal(v.id) &&
  v.id !== '0' &&
  isOperationId(v.taskId) &&
  fields(v, ['billNo', 'pi', 'factoryCode'], text) &&
  fields(v, ['quantity', 'processed'], decimal) &&
  ['PENDING', 'SUCCESS', 'FAILED'].includes(String(v.state)) &&
  date(v.createdAt);
export const isSnExport = (v: unknown): v is SnExport =>
  isRecord(v) &&
  isOperationId(v.taskId) &&
  ['CSV', 'XLSX'].includes(String(v.format)) &&
  decimal(v.quantity) &&
  date(v.createdAt) &&
  ['PREPARING', 'READY', 'RECOVERING', 'FAILED'].includes(String(v.fileState));
export const isDashboard = (v: unknown): v is Dashboard =>
  isRecord(v) &&
  Array.isArray(v.states) &&
  v.states.every(isSnCount) &&
  Array.isArray(v.today) &&
  v.today.every(isSnCount) &&
  Array.isArray(v.recentJobs) &&
  v.recentJobs.every(isSnWork) &&
  Array.isArray(v.jobs) &&
  v.jobs.every(isDashboardJob) &&
  fields(v, ['pendingTransfers', 'totalGenerated', 'totalImported'], (x) => nullable(x, decimal)) &&
  text(v.date) &&
  v.zone === 'Asia/Shanghai' &&
  typeof v.allFactories === 'boolean';
const params = (filters: SnFilter, cursor?: string, size?: number) => {
  const p = new URLSearchParams();
  Object.entries({ ...filters, cursor, size }).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') p.set(k, String(v));
  });
  return p.toString();
};
export const snQueryApi = {
  page: (filters: SnFilter, page: number, size: number) =>
    query('/sn/items/page?' + params(filters, undefined, size) + '&page=' + page, isSnQueryPage),
  items: (filters: SnFilter, cursor?: string, size = 50) =>
    query('/sn/items?' + params(filters, cursor, size), cursorGuard(isSnItem)),
  count: (filters: SnFilter) => query('/sn/items/count?' + params(filters), isSnCount),
  export: (input: SnExportCommand) => submit('/sn/exports', input, isTask, 60_000),
  history: (page: number) => query(`/sn/exports?page=${page}&size=20`, pageGuard(isSnExport)),
  dashboard: () => query('/sn/dashboard', isDashboard),
  context: () => query('/sn/context', isSnContext),
};
