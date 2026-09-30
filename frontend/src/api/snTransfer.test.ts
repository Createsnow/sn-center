import { describe, expect, it } from 'vitest';
import { isTransfer, isTransferCandidate, isTransferTarget } from './snTransfer';
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
};
describe('transfer wire boundaries', () => {
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
    };
    expect(isTransferCandidate(v)).toBe(true);
    expect(isTransferCandidate({ ...v, total: 13 })).toBe(false);
    expect(isTransferCandidate({ ...v, applying: undefined })).toBe(false);
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
