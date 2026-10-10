import { describe, expect, it } from 'vitest';
import {
  cursorGuard,
  isAcquireItem,
  isAcquireResult,
  isAcquireWork,
  isPrintRecord,
  isCurrentBatchItem,
} from './snAcquire';
const id = '10000000-0000-4000-8000-000000000001';
describe('S5固定历史、精度与文件状态契约', () => {
  it('当前批次页允许未知流水和空物料，拒绝丢精度数字和缺少成员身份', () => {
    const value = {
      id,
      sn: 'SN',
      pi: 'P',
      seqPi: null,
      seqDec: null,
      seqText: null,
      customer: '',
      material: '',
      factoryId: '9007199254740993',
      status: 'PRINTED',
      currentBatchId: id,
    };
    expect(isCurrentBatchItem(value)).toBe(true);
    expect(isCurrentBatchItem({ ...value, seqDec: '9007199254740993' })).toBe(true);
    expect(isCurrentBatchItem({ ...value, seqDec: Number('9007199254740993') })).toBe(false);
    expect(isCurrentBatchItem({ ...value, id: undefined })).toBe(false);
  });
  it('未知流水与当前批次失效仍保留历史快照，拒绝数字型流水', () => {
    const value = {
      ordinal: '1',
      snapshot: {
        sn: '=SN甲',
        seqText: null,
        seqDec: null,
        seqPi: null,
        pi: 'P',
        factoryId: '9007199254740993',
        factoryCode: 'A',
        factoryName: '甲',
        customer: '',
        material: '',
        ruleVersionId: null,
        sourceSystem: null,
        sourceAccount: null,
        sourceBillId: null,
        sourceEntryId: null,
        billNo: '',
        sourcePi: '',
        sourceOrgId: null,
      },
      currentStatus: 'PRINTED',
      currentFactoryId: '9007199254740994',
      currentPi: 'NEW',
      currentBatchId: id,
      currentMember: false,
    };
    expect(isAcquireItem(value)).toBe(true);
    expect(
      isAcquireItem({
        ...value,
        snapshot: { ...value.snapshot, seqDec: '9007199254740993', seqText: 'ABCDEFG' },
      }),
    ).toBe(true);
    expect(
      isAcquireItem({
        ...value,
        snapshot: { ...value.snapshot, seqDec: Number('9007199254740993') },
      }),
    ).toBe(false);
    expect(isAcquireItem({ ...value, currentStatus: 'COMPLETE' })).toBe(false);
  });
  it('零可领成功没有批次；阻止分类不能丢失', () => {
    const value = {
      taskId: id,
      operationId: id,
      factoryId: '1',
      kind: 'TAKE',
      origin: 'API',
      pi: 'P',
      state: 'SUCCESS',
      quantity: '13',
      eligible: '0',
      blocked: '13',
      updatedQty: '0',
      alreadyQty: '0',
      batchId: null,
      batchNo: null,
      errorCode: null,
      createdAt: '2026-09-30T00:00:00Z',
      finishedAt: '2026-09-30T00:00:01Z',
    };
    expect(isAcquireWork(value)).toBe(true);
    expect(isAcquireWork({ ...value, blocked: undefined })).toBe(false);
    expect(isAcquireWork({ ...value, batchId: '' })).toBe(false);
  });
  it('完整游标结果必须显式给出结束标记和有效分类', () => {
    const guard = cursorGuard(isAcquireResult);
    expect(
      guard({ items: [{ ordinal: '1', sn: 'A', outcome: 'BATCH_CHANGED' }], nextCursor: null }),
    ).toBe(true);
    expect(guard({ items: [], nextCursor: undefined })).toBe(false);
    expect(
      guard({ items: [{ ordinal: '1', sn: 'A', outcome: 'IGNORED' }], nextCursor: null }),
    ).toBe(false);
  });
  it('两份文件状态独立，恢复或失败不抹掉打印记录', () => {
    const value = {
      id,
      operationId: id,
      csvTaskId: id,
      factoryId: '1',
      factoryCode: 'A',
      factoryName: '甲',
      pi: 'P',
      quantity: '13',
      actorId: '9007199254740993',
      printNo: 'P202610082300000001',
      actorName: 'admin',
      createdAt: '2026-09-30T00:00:00Z',
      xlsxState: 'RECOVERING',
      csvState: 'FAILED',
    };
    expect(isPrintRecord(value)).toBe(true);
    expect(isPrintRecord({ ...value, xlsxState: undefined })).toBe(false);
  });
});
