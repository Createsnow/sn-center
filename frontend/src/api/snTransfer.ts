import type { components } from './generated/schema';
import { query, submit } from './client';
import { isRecord } from './contract';
import { isOperationId, isTask } from './tasks';
import { pageGuard } from './sn';
import { cursorGuard, isAcquireItem } from './snAcquire';

export type Transfer = components['schemas']['TransferVO'];
export type TransferItem = components['schemas']['TransferItemVO'];
export type TransferCandidate = components['schemas']['TransferCandidateVO'];
export type TransferTarget = components['schemas']['TransferTargetVO'];
export type TransferCreate = components['schemas']['TransferCreateDTO'];
export type TransferDecision = components['schemas']['TransferDecisionDTO'] & { id: string };
export type TransferAction = 'APPROVE' | 'REJECT' | 'WITHDRAW';
const text = (v: unknown): v is string => typeof v === 'string';
const decimal = (v: unknown): v is string => text(v) && /^(0|[1-9]\d*)$/.test(v);
const date = (v: unknown) => text(v) && Number.isFinite(Date.parse(v));
const nullable = (v: unknown, guard: (value: unknown) => boolean) => v === null || guard(v);
const fields = (v: Record<string, unknown>, keys: string[], guard: (v: unknown) => boolean) =>
  keys.every((k) => guard(v[k]));
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
  date(v.createdAt) &&
  nullable(v.decidedAt, date);
export const isTransferCandidate = (v: unknown): v is TransferCandidate =>
  isRecord(v) &&
  fields(
    v,
    ['total', 'transferable', 'toAcquire', 'toPrint', 'printed', 'applying', 'pendingAllocation'],
    decimal,
  );
export const isTransferTarget = (v: unknown): v is TransferTarget =>
  isRecord(v) &&
  decimal(v.factoryId) &&
  fields(v, ['factoryCode', 'factoryName', 'pi', 'customer', 'material'], text);
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
export const transferApi = {
  candidates: (input: {
    factoryId: string;
    pi: string;
    scope: string;
    material?: string;
    sn?: string;
  }) => query('/sn/transfers/candidates?' + params(input), isTransferCandidate),
  targets: (factoryOnly: boolean, search: string, page: number) =>
    query(
      '/sn/transfers/' +
        (factoryOnly ? 'factories' : 'targets') +
        '?' +
        params({ query: search, page, size: 20 }),
      pageGuard(isTransferTarget),
    ),
  list: (input: { factoryId?: string; pi?: string; status?: string; page: number }) =>
    query('/sn/transfers?' + params({ ...input, size: 20 }), pageGuard(isTransfer)),
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
