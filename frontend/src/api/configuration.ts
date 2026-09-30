import type { components } from './generated/schema';
import { query, submit } from './client';
import { isRecord } from './contract';

export type Kind = 'roles' | 'departments' | 'menus' | 'dictionary-types' | 'dictionary-items';
export type Role = components['schemas']['RoleVO'];
export type Department = components['schemas']['DepartmentVO'];
export type ManagedMenu = components['schemas']['ManagedMenuVO'];
export type DictionaryType = components['schemas']['DictionaryTypeVO'];
export type DictionaryItem = components['schemas']['DictionaryItemVO'];
export type ConfigurationRow = Role | Department | ManagedMenu | DictionaryType | DictionaryItem;
type Change = components['schemas']['ConfigurationChangeVO'];
export type FormValues = {
  name: string;
  code: string;
  parentId?: string | null;
  enabled: boolean;
  dataScope: number;
  factoryScope: number;
  menuIds?: string[];
  deptIds?: string[];
  menuType: string;
  permission?: string | null;
  path?: string | null;
  component?: string | null;
  sortOrder: number;
  value: string;
  labelZh: string;
  labelEn: string;
  labelVi: string;
};
const id = (value: unknown): value is string =>
  typeof value === 'string' && /^[1-9][0-9]*$/.test(value);
const nullableId = (value: unknown) => value === null || id(value);
const nullableText = (value: unknown) => value === null || typeof value === 'string';
const strings = (value: unknown) => Array.isArray(value) && value.every(id);
const base = (value: unknown): value is Record<string, unknown> =>
  isRecord(value) && id(value.id) && id(value.version) && typeof value.enabled === 'boolean';
export const isRole = (value: unknown): value is Role =>
  base(value) &&
  typeof value.code === 'string' &&
  typeof value.name === 'string' &&
  typeof value.dataScope === 'number' &&
  [1, 2, 3, 4, 5].includes(value.dataScope) &&
  typeof value.factoryScope === 'number' &&
  [1, 2].includes(value.factoryScope) &&
  strings(value.menuIds) &&
  strings(value.deptIds);
export const isDepartment = (value: unknown): value is Department =>
  base(value) && typeof value.name === 'string' && nullableId(value.parentId);
export const isManagedMenu = (value: unknown): value is ManagedMenu =>
  base(value) &&
  typeof value.name === 'string' &&
  nullableId(value.parentId) &&
  typeof value.menuType === 'string' &&
  ['M', 'C', 'F'].includes(value.menuType) &&
  nullableText(value.permission) &&
  nullableText(value.path) &&
  nullableText(value.component) &&
  Number.isInteger(value.sortOrder);
export const isDictionaryType = (value: unknown): value is DictionaryType =>
  base(value) && typeof value.name === 'string' && typeof value.code === 'string';
export const isDictionaryItem = (value: unknown): value is DictionaryItem =>
  base(value) &&
  id(value.typeId) &&
  typeof value.value === 'string' &&
  typeof value.labelZh === 'string' &&
  typeof value.labelEn === 'string' &&
  typeof value.labelVi === 'string' &&
  Number.isInteger(value.sortOrder);
const isChange = (value: unknown): value is Change =>
  isRecord(value) && id(value.id) && typeof value.reauthenticate === 'boolean';
const listGuard =
  <T>(guard: (value: unknown) => value is T) =>
  (value: unknown): value is T[] =>
    Array.isArray(value) && value.every(guard);

export const configurationApi = {
  roles: () => query('/system/roles', listGuard(isRole)),
  departments: () => query('/system/departments', listGuard(isDepartment)),
  menus: () => query('/system/menus', listGuard(isManagedMenu)),
  types: () => query('/system/dictionary-types', listGuard(isDictionaryType)),
  list(kind: Kind, typeId?: string): Promise<ConfigurationRow[]> {
    switch (kind) {
      case 'roles':
        return this.roles();
      case 'departments':
        return this.departments();
      case 'menus':
        return this.menus();
      case 'dictionary-types':
        return this.types();
      case 'dictionary-items':
        return query(
          '/system/dictionary-items?typeId=' + encodeURIComponent(typeId ?? ''),
          listGuard(isDictionaryItem),
        );
    }
  },
  save(kind: Kind, row: ConfigurationRow | undefined, values: FormValues, typeId?: string) {
    const path = '/system/' + kind + (row ? '/' + row.id + '/update' : '');
    const shared = { enabled: values.enabled, ...(row ? { version: row.version } : {}) };
    switch (kind) {
      case 'roles': {
        const input: components['schemas']['RoleSaveDTO'] = {
          ...shared,
          code: values.code,
          name: values.name,
          dataScope: values.dataScope,
          factoryScope: values.factoryScope,
          menuIds: values.menuIds ?? [],
          deptIds: values.dataScope === 2 ? (values.deptIds ?? []) : [],
        };
        return submit(path, input, isChange);
      }
      case 'departments': {
        const input: components['schemas']['DepartmentSaveDTO'] = {
          ...shared,
          name: values.name,
          ...(values.parentId ? { parentId: values.parentId } : {}),
        };
        return submit(path, input, isChange);
      }
      case 'menus': {
        const input: components['schemas']['ManagedMenuSaveDTO'] = {
          ...shared,
          name: values.name,
          menuType: values.menuType,
          sortOrder: values.sortOrder,
          ...(values.parentId ? { parentId: values.parentId } : {}),
          ...(values.menuType !== 'M' && values.permission
            ? { permission: values.permission }
            : {}),
          ...(values.menuType === 'C' && values.path ? { path: values.path } : {}),
          ...(values.menuType === 'C' && values.component ? { component: values.component } : {}),
        };
        return submit(path, input, isChange);
      }
      case 'dictionary-types': {
        const input: components['schemas']['DictionaryTypeSaveDTO'] = {
          ...shared,
          code: values.code,
          name: values.name,
        };
        return submit(path, input, isChange);
      }
      case 'dictionary-items': {
        const input: components['schemas']['DictionaryItemSaveDTO'] = {
          ...shared,
          typeId: typeId ?? '',
          value: values.value,
          labelZh: values.labelZh,
          labelEn: values.labelEn,
          labelVi: values.labelVi,
          sortOrder: values.sortOrder,
        };
        return submit(path, input, isChange);
      }
    }
  },
};
