import { describe, it, expect } from 'vitest';
import { isFactory, isOrder, isLine, isPreview, isVersion, pageGuard, paginationTotal } from './sn';
describe('SN response boundary', () => {
  const large = '9007199254740993';
  const factory = {
    id: large,
    version: '1',
    code: 'F',
    name: '工厂',
    source: 'MANUAL',
    enabled: true,
    createdAt: '2026-09-29T00:00:00Z',
    updatedAt: '2026-09-29T00:00:00Z',
  };
  it('keeps factory identifiers exact and rejects numeric IDs', () => {
    expect(isFactory(factory)).toBe(true);
    expect(isFactory({ ...factory, id: Number(large) })).toBe(false);
    expect(isFactory({ ...factory, source: 'UNKNOWN' })).toBe(false);
    expect(isFactory({ ...factory, source: ['MANUAL'] })).toBe(false);
  });
  it('keeps unmapped orders and large quotas while rejecting float quantities', () => {
    const order = {
      id: large,
      snapshotVersion: '1',
      sourceSystem: 'K3',
      sourceAccount: 'ISOLATED',
      sourceBillId: large,
      billNo: 'MO',
      customer: 'C',
      po: 'P',
      pi: 'PI',
      orgId: '1',
      orgCode: 'F',
      orgName: '厂',
      factoryId: null,
      factoryCode: null,
      totalQty: large,
      quantityReady: true,
      lineCount: '2',
      generatedQty: '0',
      allocatedQty: '0',
      quota: large,
      statuses: '1,2',
      syncedAt: '2026-09-29T00:00:00Z',
    };
    expect(isOrder(order)).toBe(true);
    expect(isOrder({ ...order, totalQty: 1.5 })).toBe(false);
    expect(
      isOrder({ ...order, totalQty: '9007199254740993.1254', quantityReady: false, quota: '0' }),
    ).toBe(true);
    for (const totalQty of ['1.00001', '-0.1', '1e3', 'NaN'])
      expect(isOrder({ ...order, totalQty })).toBe(false);
    expect(isOrder({ ...order, totalQty: '1.5', quota: '1.5' })).toBe(false);
    expect(isOrder({ ...order, quota: '-1' })).toBe(false);
  });
  it('preserves empty materials and separate stable entry identity', () => {
    const line = {
      id: large,
      sourceEntryId: '12',
      ordinal: '1',
      snapshotVersion: '2',
      material: '',
      materialName: '',
      demandBillNo: '',
      qty: '0',
      generatedQty: '5',
      allocatedQty: '2',
      quota: '0',
      status: '1',
    };
    expect(isLine(line)).toBe(true);
    expect(isLine({ ...line, qty: '0.1254' })).toBe(true);
    expect(isLine({ ...line, qty: 0.1254 })).toBe(false);
    expect(isLine({ ...line, sourceEntryId: 12 })).toBe(false);
    expect(isLine({ ...line, status: 1 })).toBe(false);
  });
  it('keeps exact rule capacity and validates historical encoding metadata', () => {
    expect(
      isPreview({
        maxSequence: '4738381338321616895',
        samples: [{ seqDecimal: large, seqText: 'A', sn: 'SNA' }],
      }),
    ).toBe(true);
    expect(isPreview({ maxSequence: Number('4738381338321616895'), samples: [] })).toBe(false);
    expect(
      isVersion({
        id: '1',
        ruleId: '1',
        version: '1',
        createdBy: large,
        prefix: '',
        suffix: '',
        alphabet: '0123456789',
        base: 8,
        length: 4,
        used: false,
        contentDigest: 'a'.repeat(64),
        createdAt: '2026-09-29T00:00:00Z',
      }),
    ).toBe(false);
  });
  it('checks page elements and bounds only the pagination widget', () => {
    expect(pageGuard(isFactory)({ items: [factory], total: large, page: 1, size: 20 })).toBe(true);
    expect(pageGuard(isFactory)({ items: [factory], total: large, page: 1, size: 101 })).toBe(
      false,
    );
    expect(paginationTotal(large)).toBe(Number.MAX_SAFE_INTEGER);
  });
  it('preserves an unknown historical creator without accepting missing or numeric identities', () => {
    const version = {
      id: '1',
      ruleId: '1',
      version: '1',
      createdBy: null,
      prefix: '',
      suffix: '',
      alphabet: '0123456789',
      base: 10,
      length: 4,
      used: true,
      contentDigest: 'a'.repeat(64),
      createdAt: '2026-09-01T00:00:00Z',
    };
    expect(isVersion(version)).toBe(true);
    expect(isVersion({ ...version, createdBy: large })).toBe(true);
    expect(isVersion({ ...version, createdBy: undefined })).toBe(false);
    expect(isVersion({ ...version, createdBy: Number(large) })).toBe(false);
    expect(isVersion({ ...version, createdBy: '0' })).toBe(false);
  });
});
