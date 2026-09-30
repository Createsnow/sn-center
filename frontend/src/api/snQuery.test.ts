import { describe, expect, it } from 'vitest';
import { isSnItem, isSnExport, isDashboard } from './snQuery';
const id = '00000000-0000-4000-8000-000000000001';
const item = {
  genMonth: '2026-09-01',
  id,
  sn: '=甲9007199254740993',
  pi: 'PI1',
  seqPi: 'OLD',
  seqDec: '9007199254740993',
  seqText: 'ABC',
  ruleVersionId: '9007199254740994',
  customer: 'C',
  material: '',
  factoryId: null,
  factoryCode: null,
  factoryName: null,
  status: 'PENDING_ALLOC',
  source: 'GEN',
  billNo: 'MO1',
  sourceSystem: 'K3',
  sourceAccount: 'A',
  sourceBillId: 'B',
  sourceEntryId: 'E',
  currentBatchId: null,
  currentPrintId: null,
  currentTransferId: null,
  createdAt: '2026-09-30T01:00:00Z',
  acquiredAt: null,
  printedAt: null,
};
describe('SN query contracts', () => {
  it('preserves exact text and permits unassigned global rows', () => {
    expect(isSnItem(item)).toBe(true);
    expect(isSnItem({ ...item, seqDec: 9007199254740992 })).toBe(false);
    expect(isSnItem({ ...item, factoryId: undefined })).toBe(false);
  });
  it('rejects missing current pointer or unrecognized state', () => {
    expect(isSnItem({ ...item, currentTransferId: undefined })).toBe(false);
    expect(isSnItem({ ...item, status: 'SUCCESS' })).toBe(false);
  });
  it('treats export file state separately from completed business snapshot', () => {
    const value = {
      taskId: id,
      format: 'XLSX',
      quantity: '10001',
      fileState: 'RECOVERING',
      createdAt: item.createdAt,
    };
    expect(isSnExport(value)).toBe(true);
    expect(isSnExport({ ...value, fileState: 'SUCCESS' })).toBe(false);
  });
  it('requires suppressed global statistics to be explicit null', () => {
    const value = {
      states: [{ name: 'TO_ACQUIRE', quantity: '13' }],
      today: [],
      recentJobs: [],
      pendingTransfers: null,
      totalGenerated: null,
      totalImported: null,
      date: '2026-09-30',
      zone: 'Asia/Shanghai',
      allFactories: false,
    };
    expect(isDashboard(value)).toBe(true);
    expect(isDashboard({ ...value, totalGenerated: undefined })).toBe(false);
    expect(isDashboard({ ...value, states: [{ name: 'TO_ACQUIRE', quantity: 13 }] })).toBe(false);
  });
});
