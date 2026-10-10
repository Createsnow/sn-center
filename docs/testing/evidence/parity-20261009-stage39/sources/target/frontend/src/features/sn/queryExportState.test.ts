import { describe, expect, it } from 'vitest';
import { ApiError } from '../../api/contract';
import type { Task } from '../../api/tasks';
import { queryFileRecoverable, rejectedQueryAbsent } from './queryExportState';

describe('query export recovery boundaries', () => {
  const absent = new ApiError(1000000005, 404);
  it('releases a rejected submission only after an authoritative absent operation', () => {
    for (const error of [
      new ApiError(1000000001, 400),
      new ApiError(1003000004, 403),
      new ApiError(1004000011, 409),
    ]) {
      expect(rejectedQueryAbsent(error, absent)).toBe(true);
      expect(rejectedQueryAbsent(error, null)).toBe(false);
      expect(rejectedQueryAbsent(error, new ApiError(1003000004, 403))).toBe(false);
    }
  });
  it('retains unknown submissions and idempotency conflicts even when lookup is absent', () => {
    for (const error of [
      new ApiError(1000000006, 0),
      new ApiError(1000000002, 500),
      new ApiError(1003000007, 409),
      new Error('network'),
    ])
      expect(rejectedQueryAbsent(error, absent)).toBe(false);
  });
  it('recovers a committed fixed file without making business failure recoverable', () => {
    const task = { operationState: 'SUCCEEDED', state: 'SUCCESS', fileState: 'RECOVERING' } as Task;
    expect(queryFileRecoverable(task)).toBe(true);
    expect(queryFileRecoverable({ ...task, state: 'FAILED', fileState: 'FAILED' })).toBe(true);
    for (const changed of [
      { ...task, operationState: 'FAILED' as const },
      { ...task, state: 'QUEUED' as const },
      { ...task, fileState: 'READY' as const },
    ])
      expect(queryFileRecoverable(changed)).toBe(false);
  });
});
