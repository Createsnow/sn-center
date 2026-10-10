import type { ManagedUser } from '../../api/users';

export const snAccountRoles = ['admin', 'factory_operator', 'query'] as const;
export type SnAccountRole = (typeof snAccountRoles)[number];

/** 仅将完整、可逆的三角色配置交给简化表单，扩展授权继续使用通用编辑器。 */
export function canonicalAccountRole(user: ManagedUser): SnAccountRole | undefined {
  const roles = new Set(user.roleCodes);
  const exact = (...codes: string[]) =>
    roles.size === codes.length && codes.every((code) => roles.has(code));
  if (exact('PLATFORM_ADMIN', 'SN_HQ') && user.factoryIds.length === 0) return 'admin';
  if (exact('FACTORY_OPERATOR') && user.factoryIds.length === 1) return 'factory_operator';
  if (exact('SN_QUERY_ALL') && user.factoryIds.length === 0) return 'query';
  if (exact('SN_QUERY_FACTORY') && user.factoryIds.length === 1) return 'query';
  return undefined;
}
