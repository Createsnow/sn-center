import type { components } from './generated/schema';
import { query, submit } from './client';
import { isRecord } from './contract';
export type Equipment = components['schemas']['EquipmentVO'];
export type EquipmentCreate = components['schemas']['EquipmentCreateDTO'];
export type EquipmentUpdate = components['schemas']['EquipmentUpdateDTO'];
type EquipmentPage = components['schemas']['PageResultEquipmentVO'];
export type EquipmentFilter = { page: number; size: number; keyword: string; enabled?: boolean };
const id = (value: unknown): value is string =>
  typeof value === 'string' && /^[1-9][0-9]{0,18}$/.test(value);
const time = (value: unknown) => typeof value === 'string' && Number.isFinite(Date.parse(value));
export const isEquipment = (value: unknown): value is Equipment =>
  isRecord(value) &&
  id(value.id) &&
  id(value.version) &&
  id(value.deptId) &&
  id(value.createBy) &&
  typeof value.code === 'string' &&
  typeof value.name === 'string' &&
  typeof value.enabled === 'boolean' &&
  time(value.createTime) &&
  time(value.updateTime);
export const isEquipmentPage = (value: unknown): value is EquipmentPage =>
  isRecord(value) &&
  Array.isArray(value.items) &&
  value.items.every(isEquipment) &&
  typeof value.total === 'string' &&
  /^(0|[1-9][0-9]*)$/.test(value.total) &&
  Number.isSafeInteger(value.page) &&
  Number(value.page) > 0 &&
  Number.isSafeInteger(value.size) &&
  Number(value.size) > 0 &&
  Number(value.size) <= 100;
export const equipmentApi = {
  list(filter: EquipmentFilter) {
    const params = new URLSearchParams({ page: String(filter.page), size: String(filter.size) });
    if (filter.keyword) params.set('keyword', filter.keyword);
    if (filter.enabled !== undefined) params.set('enabled', String(filter.enabled));
    return query('/equipment/assets?' + params.toString(), isEquipmentPage);
  },
  detail: (id: string) => query('/equipment/assets/' + encodeURIComponent(id), isEquipment),
  create: (command: EquipmentCreate) => submit('/equipment/assets', command, isEquipment),
  update: (id: string, command: EquipmentUpdate) =>
    submit('/equipment/assets/' + encodeURIComponent(id) + '/update', command, isEquipment),
};
