import type { components } from './generated/schema';
import { command, query, submit } from './client';
import { isRecord } from './contract';

export type ManagedUser = components['schemas']['ManagedUserVO'];
export type UserCreate = components['schemas']['UserCreateDTO'];
export type UserUpdate = components['schemas']['UserUpdateDTO'];
export type UserOptions = components['schemas']['UserOptionsVO'];
export type MenuEntry = components['schemas']['MenuVO'];
export type UserPage = Required<components['schemas']['PageResultManagedUserVO']>;
const id = (value: unknown): value is string =>
  typeof value === 'string' && /^[1-9][0-9]*$/.test(value);
export function isManagedUser(value: unknown): value is ManagedUser {
  return (
    isRecord(value) &&
    id(value.id) &&
    typeof value.username === 'string' &&
    typeof value.displayName === 'string' &&
    (value.deptId === null || id(value.deptId)) &&
    (value.deptName === null || typeof value.deptName === 'string') &&
    typeof value.enabled === 'boolean' &&
    typeof value.mustChangePassword === 'boolean' &&
    id(value.version) &&
    Array.isArray(value.roleIds) &&
    value.roleIds.every(id) &&
    Array.isArray(value.factoryIds) &&
    value.factoryIds.every(id)
  );
}
function isUserPage(value: unknown): value is UserPage {
  return (
    isRecord(value) &&
    Array.isArray(value.items) &&
    value.items.every(isManagedUser) &&
    typeof value.total === 'string' &&
    /^(0|[1-9][0-9]*)$/.test(value.total) &&
    Number.isInteger(value.page) &&
    Number.isInteger(value.size)
  );
}
function isOptions(value: unknown): value is UserOptions {
  return (
    isRecord(value) &&
    Array.isArray(value.departments) &&
    Array.isArray(value.roles) &&
    value.departments.every(
      (item: unknown) => isRecord(item) && id(item.id) && typeof item.name === 'string',
    ) &&
    value.roles.every(
      (item: unknown) =>
        isRecord(item) &&
        id(item.id) &&
        typeof item.name === 'string' &&
        typeof item.dataScope === 'number' &&
        [1, 2, 3, 4, 5].includes(item.dataScope),
    )
  );
}
function isMenus(value: unknown): value is MenuEntry[] {
  return (
    Array.isArray(value) &&
    value.every(
      (item: unknown) =>
        isRecord(item) &&
        id(item.id) &&
        typeof item.name === 'string' &&
        typeof item.path === 'string' &&
        typeof item.component === 'string' &&
        typeof item.permission === 'string',
    )
  );
}
export const usersApi = {
  list(input: { page: number; size: number; keyword: string; enabled?: boolean }) {
    const parameters = new URLSearchParams({
      page: String(input.page),
      size: String(input.size),
      keyword: input.keyword,
    });
    if (input.enabled !== undefined) {
      parameters.set('enabled', String(input.enabled));
    }
    return query(`/system/users?${parameters.toString()}`, isUserPage);
  },
  options(operation: 'add' | 'update') {
    return query(`/system/users/options?operation=${operation}`, isOptions);
  },
  menus() {
    return query('/system/menus/current', isMenus);
  },
  create(input: UserCreate) {
    return submit('/system/users', input, isManagedUser);
  },
  update(user: ManagedUser, input: UserUpdate) {
    return command(`/system/users/${user.id}/update`, input);
  },
  status(user: ManagedUser) {
    return command(`/system/users/${user.id}/status`, {
      enabled: !user.enabled,
      version: user.version,
    });
  },
  reset(user: ManagedUser, password: string) {
    return command(`/system/users/${user.id}/password`, { password, version: user.version });
  },
};
