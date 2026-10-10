import type { components } from './generated/schema';
import { query, submit } from './client';
import { isRecord } from './contract';
import { isTask, isOperationId } from './tasks';
import { plannedQuantity, isOrder, isRule, isVersion, pageGuard } from './sn';

export type PiStatus = components['schemas']['PiInitializationVO'];
export type SnWork = components['schemas']['SnWorkVO'];
export type GenerateContext = components['schemas']['GenerationContextVO'];
export type GenerationPreview = components['schemas']['GenerationPreviewVO'];
export type NextSequence = components['schemas']['NextSequenceVO'];
export type InitCommand = components['schemas']['PiInitializationDTO'];
export type GenerateCommand = components['schemas']['GenerationDTO'];
export type AllocateCommand = components['schemas']['AllocationDTO'];
export type WorkKind =
  | 'PI_INIT'
  | 'GENERATE'
  | 'ALLOCATE'
  | 'TAKE'
  | 'PRINT'
  | 'TRANSFER_APPLY'
  | 'TRANSFER_DIRECT'
  | 'TRANSFER_APPROVE'
  | 'TRANSFER_REJECT'
  | 'TRANSFER_WITHDRAW'
  | 'QUERY_CSV'
  | 'QUERY_XLSX'
  | 'AUDIT_CSV'
  | 'AUDIT_XLSX';
const text = (v: unknown): v is string => typeof v === 'string';
const decimal = (v: unknown): v is string => text(v) && /^(0|[1-9]\d*)$/.test(v);
const date = (v: unknown) => text(v) && Number.isFinite(Date.parse(v));
const nullable = (v: unknown, guard: (x: unknown) => boolean) => v === null || guard(v);
const fields = (v: Record<string, unknown>, keys: string[], guard: (x: unknown) => boolean) =>
  keys.every((k) => guard(v[k]));
const counter = (v: unknown): v is PiStatus['counter'] =>
  isRecord(v) &&
  text(v.pi) &&
  fields(v, ['lastSeq', 'generatedQty', 'importedQty'], decimal) &&
  typeof v.startLocked === 'boolean' &&
  nullable(v.startSeq, decimal) &&
  nullable(v.lockedBy, decimal) &&
  nullable(v.lockedAt, date) &&
  nullable(v.activeTaskId, isOperationId);
export const isPiStatus = (v: unknown): v is PiStatus =>
  isRecord(v) &&
  text(v.pi) &&
  text(v.customer) &&
  fields(v, ['ruleCode', 'ruleScope', 'ruleBinding'], text) &&
  typeof v.inSnapshot === 'boolean' &&
  counter(v.counter) &&
  nullable(v.rule, isVersion);
export const isSnWork = (v: unknown): v is SnWork =>
  isRecord(v) &&
  isOperationId(v.taskId) &&
  text(v.kind) &&
  ['PI_INIT', 'GENERATE', 'ALLOCATE'].includes(v.kind) &&
  text(v.state) &&
  ['PENDING', 'SUCCESS', 'FAILED', 'CANCELLED'].includes(v.state) &&
  fields(v, ['pi', 'fileName', 'factoryCode'], text) &&
  fields(v, ['actorId', 'quantity'], decimal) &&
  decimal(v.processed) &&
  nullable(v.actorName, text) &&
  nullable(v.generationNumber, decimal) &&
  fields(v, ['billId', 'factoryId', 'startSeq', 'endSeq', 'decodedQty', 'maxSeq', 'lastSeq'], (x) =>
    nullable(x, decimal),
  ) &&
  fields(v, ['firstSn', 'lastSn'], (x) => nullable(x, text)) &&
  date(v.createdAt) &&
  nullable(v.finishedAt, date) &&
  isRecord(v.errorParams) &&
  Object.values(v.errorParams).every(text) &&
  nullable(
    v.errorCode,
    (x) => typeof x === 'number' && Number.isInteger(x) && x > 0 && x <= 2147483647,
  );
export const isNextSequence = (v: unknown): v is NextSequence =>
  isRecord(v) &&
  fields(v, ['ownLastSeq', 'startSeq'], decimal) &&
  typeof v.adjusted === 'boolean' &&
  nullable(v.startSn, text) &&
  nullable(v.bySeq, decimal) &&
  nullable(v.bySn, text);
export const isGenerationPreview = (v: unknown): v is GenerationPreview =>
  isRecord(v) &&
  isOperationId(v.id) &&
  fields(v, ['quantity', 'baseLastSeq', 'startSeq', 'endSeq'], decimal) &&
  fields(v, ['firstSn', 'lastSn'], text) &&
  date(v.expiresAt) &&
  isNextSequence(v.nextStart) &&
  Array.isArray(v.segments) &&
  v.segments.every(
    (s: unknown) =>
      isRecord(s) &&
      fields(s, ['sourceEntryId', 'material', 'firstSn', 'lastSn'], text) &&
      fields(s, ['ordinal', 'quantity', 'startSeq', 'endSeq'], decimal),
  );
export const isGenerateContext = (v: unknown): v is GenerateContext =>
  isRecord(v) &&
  isOrder(v.bill) &&
  text(v.factoryName) &&
  fields(v, ['ruleCode', 'ruleScope', 'ruleBinding'], text) &&
  counter(v.counter) &&
  nullable(v.rule, isVersion) &&
  typeof v.ruleChoiceRequired === 'boolean' &&
  nullable(v.nextStart, isNextSequence) &&
  text(v.ruleSource) &&
  ['NONE', 'PI', 'CUSTOMER', 'CHOSEN'].includes(v.ruleSource) &&
  decimal(v.pendingQuantity) &&
  decimal(v.maxBatchQuantity) &&
  Array.isArray(v.issues) &&
  v.issues.every(text) &&
  Array.isArray(v.jobs) &&
  v.jobs.every(isSnWork) &&
  Array.isArray(v.allocations) &&
  v.allocations.every(isSnWork) &&
  Array.isArray(v.piSummary) &&
  v.piSummary.every(
    (s: unknown) =>
      isRecord(s) &&
      plannedQuantity(s.totalQty) &&
      fields(s, ['billId', 'generatedQty', 'allocatedQty', 'quota'], decimal) &&
      text(s.billNo) &&
      typeof s.inSnapshot === 'boolean',
  );
export const snWorkApi = {
  history: (billId: string, kind: 'GENERATE' | 'ALLOCATE', page: number) =>
    query(
      '/sn/generation/history?' +
        new URLSearchParams({ billId, kind, page: String(page), size: '20' }),
      pageGuard(isSnWork),
    ),
  status: (pi: string) =>
    query('/sn/pi-initializations?' + new URLSearchParams({ pi }), isPiStatus),
  imports: (pi: string, page: number) =>
    query(
      '/sn/pi-initializations/history?' +
        new URLSearchParams({ pi, page: String(page), size: '20' }),
      pageGuard(isSnWork),
    ),
  initialize: (input: InitCommand) => submit('/sn/pi-initializations', input, isTask, 60_000),
  context: (billId: string) =>
    query('/sn/generation/context?' + new URLSearchParams({ billId }), isGenerateContext),
  ruleOptions: (q: string, page: number) =>
    query(
      '/sn/generation/rules?' + new URLSearchParams({ q, page: String(page), size: '50' }),
      pageGuard(isRule),
    ),
  preview: (input: components['schemas']['GenerationPreviewDTO']) =>
    submit('/sn/generation/previews', input, isGenerationPreview),
  generate: (input: GenerateCommand) => submit('/sn/generation', input, isTask),
  allocate: (input: AllocateCommand) => submit('/sn/allocations', input, isTask, 60_000),
  operation: (kind: WorkKind, id: string) =>
    query(
      kind.startsWith('AUDIT_')
        ? `/sn/audits/exports/operations/${kind.slice(6)}/${encodeURIComponent(id)}`
        : kind.startsWith('QUERY_')
          ? `/sn/exports/operations/${kind.slice(6)}/${encodeURIComponent(id)}`
          : kind.startsWith('TRANSFER_')
            ? `/sn/transfers/operations/${kind.slice(9)}/${encodeURIComponent(id)}`
            : kind === 'TAKE'
              ? `/sn/acquisitions/operations/TAKE/${encodeURIComponent(id)}`
              : kind === 'PRINT'
                ? `/sn/prints/operations/${encodeURIComponent(id)}`
                : `/sn/operations/${kind}/${encodeURIComponent(id)}`,
      isTask,
    ),
};
