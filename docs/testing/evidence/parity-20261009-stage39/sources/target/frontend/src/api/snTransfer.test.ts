import { describe, expect, it } from 'vitest';
import {
  isTransfer,
  isTransferCandidate,
  isTransferDetail,
  isTransferTarget,
  isTransferSnapshot,
} from './snTransfer';
const id = '00000000-0000-4000-8000-000000000001';
const transfer = {
  id,
  mode: 'APPLY',
  status: 'PENDING',
  version: '1',
  fromFactoryId: '9007199254740993',
  toFactoryId: '9007199254740994',
  fromFactoryCode: 'A',
  fromFactoryName: '甲',
  fromPi: 'P1',
  scope: 'MATERIAL',
  material: '',
  sn: null,
  toFactoryCode: 'B',
  toFactoryName: '乙',
  toPi: 'P2',
  toCustomer: '',
  toMaterial: '',
  targetSource: 'MANUAL',
  reason: '交接',
  quantity: '10001',
  actorId: '1',
  decidedBy: null,
  note: '',
  createdAt: '2026-09-30T01:00:00Z',
  decidedAt: null,
  direction: 'OUT',
  transferNo: 'T202610090200001234',
  actorName: 'admin',
  decidedName: null,
};
describe('transfer wire boundaries', () => {
  it('accepts scoped transferred-out details without losing precise sequences or nullable current rows', () => {
    const detail = {
      ordinal: '1',
      sn: 'A17A00001',
      seqText: '00001',
      seqDec: '9007199254740993',
      seqPi: 'P1',
      fromStatus: 'TO_PRINT',
      fromBatchNo: 'B202610090200001234',
      currentStatus: 'TRANSFERRED_OUT',
      currentFactoryId: '9007199254740994',
      currentFactoryCode: 'B',
      currentFactoryName: '乙',
      currentPi: 'P2',
    };
    expect(isTransferDetail(detail)).toBe(true);
    expect(isTransferDetail({ ...detail, seqDec: 9007199254740992 })).toBe(false);
    expect(isTransferDetail({ ...detail, currentStatus: 'APPROVED' })).toBe(false);
    expect(
      isTransferDetail({
        ...detail,
        currentStatus: null,
        currentFactoryId: null,
        currentFactoryCode: null,
        currentFactoryName: null,
        currentPi: null,
      }),
    ).toBe(true);
    expect(isTransferDetail({ ...detail, fromBatchNo: undefined })).toBe(false);
  });
  it('keeps exact IDs and explicitly empty material distinct from unknown values', () => {
    expect(isTransfer(transfer)).toBe(true);
    expect(isTransfer({ ...transfer, fromFactoryId: 9007199254740992 })).toBe(false);
    expect(isTransfer({ ...transfer, material: undefined })).toBe(false);
  });
  it('does not confuse task state with transfer state or accept guessed direction', () => {
    expect(isTransfer({ ...transfer, status: 'SUCCESS' })).toBe(false);
    expect(isTransfer({ ...transfer, direction: 'ADMIN' })).toBe(false);
  });
  it('requires every source state count as precise text', () => {
    const v = {
      total: '13',
      transferable: '12',
      toAcquire: '2',
      toPrint: '4',
      printed: '6',
      applying: '1',
      pendingAllocation: '0',
      materials: ['', 'M1'],
      duplicateCount: '0',
      duplicateSamples: [],
    };
    expect(isTransferCandidate(v)).toBe(true);
    expect(isTransferCandidate({ ...v, total: 13 })).toBe(false);
    expect(isTransferCandidate({ ...v, applying: undefined })).toBe(false);
    expect(isTransferCandidate({ ...v, materials: [null] })).toBe(false);
    expect(isTransferCandidate({ ...v, duplicateCount: 1 })).toBe(false);
    expect(isTransferCandidate({ ...v, duplicateSamples: Array(6).fill('SN') })).toBe(false);
  });
  it('allows a wholly unmapped snapshot but rejects incomplete or imprecise factory identities', () => {
    const row = {
      factoryId: null,
      factoryCode: null,
      factoryName: null,
      orgName: '组织',
      pi: 'PI',
      customer: 'C',
      material: '',
      materialName: '物料',
    };
    expect(isTransferSnapshot(row)).toBe(true);
    expect(
      isTransferSnapshot({
        ...row,
        factoryId: '9007199254740993',
        factoryCode: 'F',
        factoryName: '厂',
      }),
    ).toBe(true);
    expect(isTransferSnapshot({ ...row, factoryId: 9007199254740992 })).toBe(false);
    expect(isTransferSnapshot({ ...row, factoryId: '1' })).toBe(false);
    expect(isTransferSnapshot({ ...row, factoryCode: 'F' })).toBe(false);
    expect(isTransferSnapshot({ ...row, materialName: undefined })).toBe(false);
  });
  it('checks the complete minimal handoff target contract', () => {
    const v = {
      factoryId: '9007199254740993',
      factoryCode: 'A',
      factoryName: '甲',
      pi: 'P1',
      customer: 'C',
      material: '',
    };
    expect(isTransferTarget(v)).toBe(true);
    expect(isTransferTarget({ ...v, factoryId: null })).toBe(false);
  });
});
