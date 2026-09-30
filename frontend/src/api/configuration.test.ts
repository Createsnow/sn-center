import { describe, expect, it } from 'vitest';
import { isRole, isDepartment, isManagedMenu, isDictionaryItem } from './configuration';

describe('configuration response contracts', () => {
  it('preserves IDs and versions beyond JavaScript integer precision', () => {
    const role = {
      id: '9007199254741001',
      version: '9007199254741003',
      code: 'READ',
      name: 'Reader',
      enabled: true,
      dataScope: 2,
      factoryScope: 1,
      menuIds: ['110'],
      deptIds: ['9007199254741005'],
    };
    expect(isRole(role)).toBe(true);
    expect(isRole({ ...role, id: Number(role.id) })).toBe(false);
    expect(isRole({ ...role, dataScope: 6 })).toBe(false);
    expect(isRole({ ...role, deptIds: [1] })).toBe(false);
    expect(
      isDepartment({ id: '1', version: '1', name: 'Root', parentId: null, enabled: true }),
    ).toBe(true);
  });
  it('rejects missing translations, malformed menu types and nonboolean states', () => {
    const item = {
      id: '1',
      version: '1',
      enabled: true,
      typeId: '2',
      value: 'READY',
      labelZh: '就绪',
      labelEn: 'Ready',
      labelVi: 'Sẵn sàng',
      sortOrder: 0,
    };
    expect(isDictionaryItem(item)).toBe(true);
    expect(isDictionaryItem({ ...item, labelVi: undefined })).toBe(false);
    expect(isDictionaryItem({ ...item, enabled: 1 })).toBe(false);
    expect(
      isManagedMenu({
        id: '1',
        version: '1',
        name: 'Directory',
        enabled: true,
        parentId: null,
        menuType: 'M',
        permission: null,
        component: null,
        path: null,
        sortOrder: 0,
      }),
    ).toBe(true);
    expect(
      isManagedMenu({
        id: '1',
        version: '1',
        name: 'Unsafe',
        enabled: true,
        parentId: null,
        menuType: 'JS',
        permission: null,
        component: null,
        path: null,
        sortOrder: 0,
      }),
    ).toBe(false);
  });
});
