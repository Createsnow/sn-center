import type { components } from './generated/schema';
import { query, submit } from './client';
import { isRecord } from './contract';
import { isOperationId, isTask } from './tasks';
import { pageGuard, type SnFilter } from './sn';
interface AcquireFilter extends SnFilter {
  piAll?: boolean;
  materialMode?: string;
  material?: string;
  status?: string;
  cursor?: string;
}

export type AcquireSummary = components['schemas']['AcquireSummaryVO'];
export type AcquireSegment = components['schemas']['AcquireSegmentVO'];
export type AcquireBatch = components['schemas']['AcquireBatchVO'];
export type AcquireItem = components['schemas']['AcquireItemVO'];
export type AcquireWork = components['schemas']['AcquireWorkVO'];
export type AcquireResult = components['schemas']['AcquireResultVO'];
export type PrintRecord = components['schemas']['PrintVO'];
export type AcquisitionCommand = components['schemas']['AcquisitionDTO'];
const text = (value: unknown): value is string => typeof value === 'string';
const decimal = (value: unknown): value is string => text(value) && /^(0|[1-9]\d*)$/.test(value);
const date = (value: unknown) => text(value) && Number.isFinite(Date.parse(value));
const nullable = (value: unknown, guard: (x: unknown) => boolean) => value === null || guard(value);
const fields = (v: Record<string, unknown>, keys: string[], guard: (x: unknown) => boolean) =>
  keys.every((k) => guard(v[k]));
const state = (v: unknown) =>
  text(v) &&
  ['PENDING_ALLOC', 'TO_ACQUIRE', 'TO_PRINT', 'PRINTED', 'APPLYING', 'TRANSFERRED_OUT'].includes(v);
const fileState = (v: unknown) =>
  text(v) && ['PREPARING', 'READY', 'RECOVERING', 'FAILED'].includes(v);
export const isAcquireSummary = (v: unknown): v is AcquireSummary =>
  isRecord(v) &&
  fields(v, ['factoryId', 'toAcquire', 'toPrint', 'applying'], decimal) &&
  fields(v, ['factoryCode', 'factoryName', 'pi', 'customer'], text);
export const isAcquireSegment = (v: unknown): v is AcquireSegment =>
  isRecord(v) &&
  fields(v, ['factoryId', 'quantity'], decimal) &&
  fields(v, ['factoryCode', 'pi', 'material', 'startSn', 'endSn', 'groupId'], text) &&
  state(v.status) &&
  nullable(v.seqPi, text) &&
  fields(v, ['startSeq', 'endSeq'], (x) => nullable(x, decimal));
export const isAcquireBatch = (v: unknown): v is AcquireBatch =>
  isRecord(v) &&
  isOperationId(v.id) &&
  isOperationId(v.operationId) &&
  fields(v, ['factoryId', 'quantity', 'actorId'], decimal) &&
  fields(v, ['factoryCode', 'factoryName', 'pi'], text) &&
  ['PAGE', 'API'].includes(String(v.origin)) &&
  date(v.createdAt);
export const isAcquireItem = (v: unknown): v is AcquireItem =>
  isRecord(v) &&
  decimal(v.ordinal) &&
  isRecord(v.snapshot) &&
  fields(
    v.snapshot,
    ['sn', 'pi', 'factoryCode', 'factoryName', 'customer', 'material', 'billNo', 'sourcePi'],
    text,
  ) &&
  decimal(v.snapshot.factoryId) &&
  fields(v.snapshot, ['seqDec', 'ruleVersionId'], (x) => nullable(x, decimal)) &&
  fields(
    v.snapshot,
    [
      'seqPi',
      'seqText',
      'sourceSystem',
      'sourceAccount',
      'sourceBillId',
      'sourceEntryId',
      'sourceOrgId',
    ],
    (x) => nullable(x, text),
  ) &&
  nullable(v.currentStatus, state) &&
  nullable(v.currentFactoryId, decimal) &&
  nullable(v.currentPi, text) &&
  nullable(v.currentBatchId, isOperationId) &&
  typeof v.currentMember === 'boolean';
export const isAcquireWork = (v: unknown): v is AcquireWork =>
  isRecord(v) &&
  isOperationId(v.taskId) &&
  isOperationId(v.operationId) &&
  fields(
    v,
    ['factoryId', 'quantity', 'eligible', 'blocked', 'updatedQty', 'alreadyQty'],
    decimal,
  ) &&
  text(v.pi) &&
  ['TAKE', 'CALLBACK'].includes(String(v.kind)) &&
  ['PAGE', 'API'].includes(String(v.origin)) &&
  ['PENDING', 'SUCCESS', 'FAILED', 'CANCELLED'].includes(String(v.state)) &&
  nullable(v.batchId, isOperationId) &&
  nullable(v.errorCode, (x) => typeof x === 'number' && Number.isInteger(x) && x > 0) &&
  date(v.createdAt) &&
  nullable(v.finishedAt, date);
export const outcomes = [
  'ACQUIRED',
  'SOURCE_UNKNOWN',
  'SOURCE_MISSING',
  'SOURCE_INACTIVE',
  'PI_CHANGED',
  'ORG_CHANGED',
  'UPDATED',
  'ALREADY_PRINTED',
  'NOT_IN_BATCH',
  'MISSING',
  'TRANSFERRED',
  'APPLYING',
  'BATCH_CHANGED',
  'STATE_CHANGED',
] as const;
export const isAcquireResult = (v: unknown): v is AcquireResult =>
  isRecord(v) && decimal(v.ordinal) && text(v.sn) && outcomes.some((value) => value === v.outcome);
export const isPrintRecord = (v: unknown): v is PrintRecord =>
  isRecord(v) &&
  isOperationId(v.id) &&
  isOperationId(v.operationId) &&
  isOperationId(v.csvTaskId) &&
  fields(v, ['factoryId', 'quantity', 'actorId'], decimal) &&
  fields(v, ['factoryCode', 'factoryName', 'pi'], text) &&
  date(v.createdAt) &&
  fileState(v.xlsxState) &&
  fileState(v.csvState);
export const cursorGuard =
  <T>(guard: (v: unknown) => v is T) =>
  (v: unknown): v is { items: T[]; nextCursor: string | null } =>
    isRecord(v) && Array.isArray(v.items) && v.items.every(guard) && nullable(v.nextCursor, text);
const url = (path: string, filter: AcquireFilter) => {
  const p = new URLSearchParams();
  for (const [key, value] of Object.entries(filter))
    if (value !== undefined && value !== '') p.set(key, String(value));
  return path + '?' + p.toString();
};
export const acquireApi = {
  summaries: (filter: AcquireFilter) =>
    query(url('/sn/acquisitions/pis', filter), pageGuard(isAcquireSummary)),
  segments: (filter: AcquireFilter) =>
    query(url('/sn/acquisitions/segments', filter), pageGuard(isAcquireSegment)),
  take: (input: AcquisitionCommand) => submit('/sn/acquisitions', input, isTask, 60_000),
  print: (input: AcquisitionCommand) => submit('/sn/prints', input, isTask, 60_000),
  work: (id: string) => query('/sn/acquisitions/results/' + encodeURIComponent(id), isAcquireWork),
  results: (id: string, cursor?: string) =>
    query(
      url('/sn/acquisitions/results/' + encodeURIComponent(id) + '/items', { cursor, size: 50 }),
      cursorGuard(isAcquireResult),
    ),
  batches: (filter: SnFilter) =>
    query(url('/sn/acquire-batches', filter), pageGuard(isAcquireBatch)),
  items: (id: string, cursor?: string) =>
    query(
      url('/sn/acquire-batches/' + encodeURIComponent(id) + '/items', { cursor, size: 50 }),
      cursorGuard(isAcquireItem),
    ),
  prints: (filter: SnFilter) => query(url('/sn/prints', filter), pageGuard(isPrintRecord)),
  printRecord: (id: string) => query('/sn/prints/' + encodeURIComponent(id), isPrintRecord),
};
