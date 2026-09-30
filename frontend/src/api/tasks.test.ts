import { describe, expect, it } from 'vitest';
import { isTask, isOperationId } from './tasks';
describe('durable task contract', () => {
  const value = {
    taskId: '11111111-1111-4111-8111-111111111111',
    operationId: '22222222-2222-4222-8222-222222222222',
    operationState: 'SUCCEEDED',
    state: 'SUCCESS',
    attempt: '1',
    executionEpoch: '9007199254741111',
    processed: '100',
    phase: 'PUBLISHED',
    fileState: 'READY',
    fileId: '11111111-1111-4111-8111-111111111111',
    byteSize: '4096',
    rowCount: '100',
    errorCode: null,
    resultRef: '11111111-1111-4111-8111-111111111111',
    createdAt: '2026-09-29T00:00:00Z',
  };
  it('preserves exact quantities and rejects unknown states or missing recovery facts', () => {
    expect(isTask(value)).toBe(true);
    expect(isTask({ ...value, executionEpoch: Number('9007199254741111') })).toBe(false);
    expect(isTask({ ...value, state: 'COMPLETE' })).toBe(false);
    expect(isTask({ ...value, fileState: undefined })).toBe(false);
    expect(isTask({ ...value, processed: '-1' })).toBe(false);
  });
  it('recovery identifiers are UUIDs without paths or principal claims', () => {
    expect(isOperationId(value.operationId)).toBe(true);
    expect(isOperationId('../file')).toBe(false);
    expect(isOperationId('1')).toBe(false);
  });
});
