import type { components } from './generated/schema';
import { query, submit } from './client';
import { isRecord } from './contract';
import { isOperationId, isTask } from './tasks';
import { pageGuard } from './sn';
import { cursorGuard, isAcquireItem } from './snAcquire';
import { isNextSequence } from './snWork';

export type Transfer = components['schemas']['TransferVO'];
export type TransferItem = components['schemas']['TransferItemVO'];
export type TransferDetail = components['schemas']['TransferDetailVO'];
export type TransferCandidate = components['schemas']['TransferCandidateVO'];
export type TransferTarget = components['schemas']['TransferTargetVO'];
export type TransferSnapshot = components['schemas']['TransferSnapshotVO'];
export type TransferCreate = components['schemas']['TransferCreateDTO'];
export type TransferDecision = components['schemas']['TransferDecisionDTO'] & { id: string };
export type TransferAction = 'APPROVE' | 'REJECT' | 'WITHDRAW';
export type TransferHint = components['schemas']['TransferHintDTO'];
export type TransferImpact = components['schemas']['TransferImpactVO'];
const text = (v: unknown): v is string => typeof v === 'string';
const decimal = (v: unknown): v is string => text(v) && /^(0|[1-9]\d*)$/.test(v);
const date = (v: unknown) => text(v) && Number.isFinite(Date.parse(v));
const nullable = (v: unknown, guard: (value: unknown) => boolean) => v === null || guard(v);
const fields = (v: Record<string, unknown>, keys: string[], guard: (v: unknown) => boolean) =>
  keys.every((k) => guard(v[k]));
export const isTransferImpact = (v: unknown): v is TransferImpact =>
  isRecord(v) &&
  typeof v.available === 'boolean' &&
  fields(v, ['ruleCode', 'ruleSource'], text) &&
  nullable(v.before, isNextSequence) &&
  nullable(v.after, isNextSequence) &&
  nullable(v.unused, decimal);
export const isTransfer = (v: unknown): v is Transfer =>
  isRecord(v) &&
  isOperationId(v.id) &&
  fields(v, ['version', 'fromFactoryId', 'toFactoryId', 'quantity', 'actorId'], decimal) &&
  fields(
    v,
    [
      'fromFactoryCode',
      'fromFactoryName',
      'fromPi',
      'toFactoryCode',
      'toFactoryName',
      'toPi',
      'toCustomer',
      'toMaterial',
      'reason',
      'note',
    ],
    text,
  ) &&
  ['APPLY', 'DIRECT'].includes(String(v.mode)) &&
  ['PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'].includes(String(v.status)) &&
  ['PI', 'MATERIAL', 'SINGLE'].includes(String(v.scope)) &&
  ['MANUAL', 'SNAPSHOT'].includes(String(v.targetSource)) &&
  ['BOTH', 'OUT', 'IN'].includes(String(v.direction)) &&
  nullable(v.material, text) &&
  nullable(v.sn, text) &&
  nullable(v.decidedBy, decimal) &&
  nullable(v.transferNo, (value) => text(value) && /^T\d{18}$/.test(value)) &&
  nullable(v.actorName, text) &&
  nullable(v.decidedName, text) &&
  date(v.createdAt) &&
  nullable(v.decidedAt, date);
export const isTransferCandidate = (v: unknown): v is TransferCandidate =>
  isRecord(v) &&
  fields(
    v,
    ['total', 'transferable', 'toAcquire', 'toPrint', 'printed', 'applying', 'pendingAllocation'],
    decimal,
  ) &&
  Array.isArray(v.materials) &&
  v.materials.every(text) &&
  decimal(v.duplicateCount) &&
  Array.isArray(v.duplicateSamples) &&
  v.duplicateSamples.length <= 5 &&
  v.duplicateSamples.every(text);
export const isTransferTarget = (v: unknown): v is TransferTarget =>
  isRecord(v) &&
  decimal(v.factoryId) &&
  fields(v, ['factoryCode', 'factoryName', 'pi', 'customer', 'material'], text);
export const isTransferSnapshot = (v: unknown): v is TransferSnapshot =>
  isRecord(v) &&
  fields(v, ['orgName', 'pi', 'customer', 'material', 'materialName'], text) &&
  (v.factoryId === null
    ? v.factoryCode === null && v.factoryName === null
    : decimal(v.factoryId) && fields(v, ['factoryCode', 'factoryName'], text));
export const isTransferItem = (v: unknown): v is TransferItem =>
  isRecord(v) &&
  isAcquireItem({ ...v, currentMember: false }) &&
  ['TO_ACQUIRE', 'TO_PRINT', 'PRINTED'].includes(String(v.fromStatus)) &&
  nullable(v.fromBatchId, isOperationId) &&
  nullable(v.fromPrintId, isOperationId);
const params = (input: Record<string, string | number | undefined>) => {
  const result = new URLSearchParams();
  Object.entries(input).forEach(([key, value]) => {
    if (value !== undefined) result.set(key, String(value));
  });
  return result.toString();
};
export const isTransferDetail = (v: unknown): v is TransferDetail =>
  isRecord(v) &&
  decimal(v.ordinal) &&
  text(v.sn) &&
  fields(
    v,
    ['seqText', 'seqPi', 'fromBatchNo', 'currentFactoryCode', 'currentFactoryName', 'currentPi'],
    (x) => nullable(x, text),
  ) &&
  fields(v, ['seqDec', 'currentFactoryId'], (x) => nullable(x, decimal)) &&
  ['TO_ACQUIRE', 'TO_PRINT', 'PRINTED'].includes(String(v.fromStatus)) &&
  nullable(v.currentStatus, (x) =>
    ['PENDING_ALLOC', 'TO_ACQUIRE', 'TO_PRINT', 'PRINTED', 'APPLYING', 'TRANSFERRED_OUT'].includes(
      String(x),
    ),
  );
export const transferApi = {
  hint: (input: TransferHint) => submit('/sn/transfers/target-hint', input, isTransferImpact),
  pendingHint: (id: string) =>
    query(`/sn/transfers/${encodeURIComponent(id)}/target-hint`, isTransferImpact),
  candidates: (input: {
    factoryId: string;
    pi: string;
    scope: string;
    material?: string;
    sn?: string;
    toPi?: string;
  }) => query('/sn/transfers/candidates?' + params(input), isTransferCandidate),
  factories: (search: string, page: number) =>
    query(
      '/sn/transfers/factories?' + params({ query: search, page, size: 20 }),
      pageGuard(isTransferTarget),
    ),
  targets: (search: string) =>
    query(
      '/sn/transfers/targets?' + params({ query: search, page: 1, size: 50 }),
      pageGuard(isTransferSnapshot),
    ),
  list: (input: {
    factoryId?: string;
    pi?: string;
    status?: string;
    page: number;
    size?: number;
  }) => query('/sn/transfers?' + params({ size: 20, ...input }), pageGuard(isTransfer)),
  itemPage: (id: string, page: number) =>
    query(
      '/sn/transfers/' + encodeURIComponent(id) + '/items/page?' + params({ page, size: 50 }),
      pageGuard(isTransferDetail),
    ),
  get: (id: string) => query('/sn/transfers/' + encodeURIComponent(id), isTransfer),
  items: (id: string, cursor?: string) =>
    query(
      '/sn/transfers/' + encodeURIComponent(id) + '/items?' + params({ cursor, size: 100 }),
      cursorGuard(isTransferItem),
    ),
  apply: (input: TransferCreate) => submit('/sn/transfers', input, isTask),
  direct: (input: TransferCreate) => submit('/sn/transfers/direct', input, isTask),
  decision: (kind: TransferAction, input: TransferDecision) => {
    const { id, ...command } = input;
    return submit(`/sn/transfers/${encodeURIComponent(id)}/${kind.toLowerCase()}`, command, isTask);
  },
};
