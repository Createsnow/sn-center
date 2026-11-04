import { describe, it, expect } from 'vitest';
import { isEquipment, isEquipmentPage } from './equipment';
const row = {
  id: '9007199254740993',
  code: 'EQ-1',
  name: '设备',
  enabled: true,
  version: '9007199254740995',
  deptId: '1',
  createBy: '2',
  createTime: '2026-10-04T00:00:00Z',
  updateTime: '2026-10-04T00:00:00Z',
};
describe('equipment contract', () => {
  it('preserves exact IDs and versions and rejects lossy numeric responses', () => {
    expect(isEquipment(row)).toBe(true);
    for (const field of ['id', 'version', 'deptId', 'createBy'])
      expect(isEquipment({ ...row, [field]: Number(row.id) })).toBe(false);
    expect(isEquipment({ ...row, updateTime: 'invalid' })).toBe(false);
  });
  it('checks both pagination metadata and every row', () => {
    const page = { items: [row], page: 1, size: 20, total: '1' };
    expect(isEquipmentPage(page)).toBe(true);
    expect(isEquipmentPage({ ...page, total: 1 })).toBe(false);
    expect(isEquipmentPage({ ...page, items: [{ ...row, enabled: 'true' }] })).toBe(false);
    expect(isEquipmentPage({ ...page, page: 0 })).toBe(false);
  });
});
