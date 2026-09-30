import type { components } from './generated/schema';
import { query, submit, downloadFile } from './client';
import { isRecord } from './contract';
import type { AuditFilter } from './audit';
export type Task = components['schemas']['TaskVO'];
export type ExportCommand = AuditFilter & { operationId: string; from: string; to: string };
export const isOperationId = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value);
const quantity = (value: unknown) => typeof value === 'string' && /^(0|[1-9][0-9]*)$/.test(value);
export function isTask(value: unknown): value is Task {
  return (
    isRecord(value) &&
    isOperationId(value.taskId) &&
    isOperationId(value.operationId) &&
    typeof value.operationState === 'string' &&
    ['PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED'].includes(value.operationState) &&
    typeof value.state === 'string' &&
    ['QUEUED', 'RUNNING', 'RETRY_WAIT', 'SUCCESS', 'FAILED', 'CANCELLED'].includes(value.state) &&
    quantity(value.attempt) &&
    quantity(value.executionEpoch) &&
    quantity(value.processed) &&
    (value.phase === null || value.phase === 'WRITING' || value.phase === 'PUBLISHED') &&
    (value.fileState === null ||
      (typeof value.fileState === 'string' &&
        ['PREPARING', 'READY', 'RECOVERING', 'FAILED'].includes(value.fileState))) &&
    (value.fileId === null || isOperationId(value.fileId)) &&
    (value.resultRef === null || isOperationId(value.resultRef)) &&
    (value.byteSize === null || quantity(value.byteSize)) &&
    (value.rowCount === null || quantity(value.rowCount)) &&
    (value.errorCode === null ||
      (typeof value.errorCode === 'number' &&
        Number.isInteger(value.errorCode) &&
        value.errorCode > 0 &&
        value.errorCode <= 2147483647)) &&
    typeof value.createdAt === 'string' &&
    Number.isFinite(Date.parse(value.createdAt))
  );
}
export const tasksApi = {
  export: (input: ExportCommand) => submit('/system/audit-exports', input, isTask),
  operation: (id: string) =>
    query('/system/audit-exports/operations/' + encodeURIComponent(id), isTask),
  cancel: (id: string) => submit('/tasks/' + encodeURIComponent(id) + '/cancel', {}, isTask),
  recover: (id: string) => submit('/tasks/' + encodeURIComponent(id) + '/recover', {}, isTask),
  download: (id: string) => downloadFile('/files/' + encodeURIComponent(id)),
};
