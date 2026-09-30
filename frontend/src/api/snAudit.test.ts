import { describe, expect, it } from 'vitest';
import { isSnAudit, isSnAuditMeta } from './snAudit';
const id = '00000000-0000-4000-8000-000000000001';
const item = {
  id,
  createdAt: '2026-09-30T01:00:00Z',
  actorId: '9007199254740993',
  operator: 'user',
  operatorName: '=2+2',
  roles: '["SN_HQ"]',
  source: 'PAGE',
  action: 'sn:transfer:approve',
  result: 'SUCCESS',
  quantity: '9007199254740993',
  detail: '{}',
  factoryId: '9007199254740994',
  toFactoryId: '9007199254740995',
  factoryCode: 'A',
  factoryName: '甲',
  pi: 'PI-A',
  toFactoryCode: 'B',
  toPi: 'PI-B',
  customer: null,
  material: '',
  billNo: null,
  startSn: '00001',
  endSn: '00002',
  beforeStatus: 'APPLYING',
  afterStatus: 'TO_ACQUIRE',
  batchId: null,
  operationId: id,
  transferId: id,
  reason: '交接',
  errorCode: null,
  traceId: id,
};
describe('SN historical audit contracts', () => {
  it('retains precise IDs and quantities and distinguishes blank material from mixed null', () => {
    expect(isSnAudit(item)).toBe(true);
    expect(isSnAudit({ ...item, actorId: 9007199254740992 })).toBe(false);
    expect(isSnAudit({ ...item, quantity: 13 })).toBe(false);
    expect(isSnAudit({ ...item, material: null })).toBe(true);
  });
  it('requires explicit nullable historical fields', () => {
    expect(isSnAudit({ ...item, toFactoryId: undefined })).toBe(false);
    expect(isSnAudit({ ...item, transferId: undefined })).toBe(false);
  });
  it('rejects invalid outcomes and dates without rewriting free text', () => {
    expect(isSnAudit({ ...item, result: 'PENDING' })).toBe(false);
    expect(isSnAudit({ ...item, createdAt: 'bad date' })).toBe(false);
    expect(item.operatorName).toBe('=2+2');
  });
  it('requires explicit archive and exact export-limit metadata', () => {
    const meta = {
      permanent: true,
      exportLimit: '100000',
      defaultDays: 30,
      archivesQueryable: true,
    };
    expect(isSnAuditMeta(meta)).toBe(true);
    expect(isSnAuditMeta({ ...meta, exportLimit: 100000 })).toBe(false);
    expect(isSnAuditMeta({ ...meta, archivesQueryable: undefined })).toBe(false);
  });
});
