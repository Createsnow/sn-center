import { describe, it, expect } from 'vitest';
import { isGenerationPreview, isNextSequence, isPiStatus, isSnWork } from './snWork';
const id = '10000000-0000-4000-8000-000000000001';
describe('S4响应精度与固定状态边界', () => {
  it('预演流水仅接受精确字符串', () => {
    const value = {
      id,
      quantity: '1',
      baseLastSeq: '9007199254740992',
      startSeq: '9007199254740993',
      endSeq: '9007199254740993',
      firstSn: 'A',
      lastSn: 'A',
      expiresAt: '2026-09-30T00:00:00Z',
      nextStart: {
        ownLastSeq: '9007199254740992',
        startSeq: '9007199254740993',
        startSn: 'A',
        adjusted: false,
        bySn: null,
        bySeq: null,
      },
      segments: [
        {
          sourceEntryId: '9007199254740993',
          ordinal: '1',
          material: '',
          quantity: '1',
          startSeq: '9007199254740993',
          endSeq: '9007199254740993',
          firstSn: 'A',
          lastSn: 'A',
        },
      ],
    };
    expect(isGenerationPreview(value)).toBe(true);
    expect(isGenerationPreview({ ...value, startSeq: 9007199254740992 })).toBe(false);
  });
  it('起号建议支持超过long的已耗尽值，实际SN为空，仍拒绝浮点数', () => {
    const value = {
      ownLastSeq: '0',
      startSeq: '9223372036854775808',
      startSn: null,
      adjusted: true,
      bySeq: '9223372036854775807',
      bySn: 'GZZZZZZZZZZZZ',
    };
    expect(isNextSequence(value)).toBe(true);
    expect(isNextSequence({ ...value, startSeq: 9223372036854775808 })).toBe(false);
    expect(isNextSequence({ ...value, bySeq: undefined })).toBe(false);
  });
  it('PI空规则和空流水有明确语义，拒绝缺失锁定状态', () => {
    const value = {
      pi: 'P',
      customer: '',
      inSnapshot: false,
      rule: null,
      ruleCode: '',
      ruleScope: '',
      ruleBinding: '',
      counter: {
        pi: 'P',
        lastSeq: '0',
        generatedQty: '0',
        importedQty: '0',
        startSeq: null,
        startLocked: false,
        lockedBy: null,
        lockedAt: null,
        activeTaskId: null,
      },
    };
    expect(isPiStatus(value)).toBe(true);
    expect(isPiStatus({ ...value, counter: { ...value.counter, startLocked: undefined } })).toBe(
      false,
    );
  });
  it('历史只能显示已知业务状态，未解析最大流水为空', () => {
    const value = {
      taskId: id,
      kind: 'PI_INIT',
      pi: 'P',
      billId: null,
      factoryId: null,
      factoryCode: '',
      actorId: '9007199254740993',
      actorName: null,
      generationNumber: null,
      processed: '1',
      state: 'SUCCESS',
      quantity: '1',
      startSeq: null,
      endSeq: null,
      decodedQty: '0',
      maxSeq: null,
      lastSeq: '0',
      firstSn: 'UNKNOWN',
      lastSn: 'UNKNOWN',
      fileName: 'a.csv',
      errorCode: null,
      errorParams: {},
      createdAt: '2026-09-30T00:00:00Z',
      finishedAt: '2026-09-30T00:00:01Z',
    };
    expect(isSnWork(value)).toBe(true);
    expect(isSnWork({ ...value, state: 'PRINTED' })).toBe(false);
    expect(isSnWork({ ...value, quantity: 1 })).toBe(false);
    expect(isSnWork({ ...value, actorName: undefined })).toBe(false);
    expect(isSnWork({ ...value, generationNumber: '9007199254740993' })).toBe(true);
    expect(isSnWork({ ...value, generationNumber: 9007199254740992 })).toBe(false);
    expect(isSnWork({ ...value, processed: 1 })).toBe(false);
    expect(
      isSnWork({
        ...value,
        errorParams: { reason: 'GEN_PREVIEW_STALE', actual: '9007199254740993' },
      }),
    ).toBe(true);
    expect(isSnWork({ ...value, errorParams: { actual: 9007199254740992 } })).toBe(false);
    expect(isSnWork({ ...value, errorParams: undefined })).toBe(false);
  });
});
