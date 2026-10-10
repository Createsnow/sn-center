import { describe, expect, it } from 'vitest';
import { canonicalAccountRole } from './accountRoles';
import type { ManagedUser } from '../../api/users';

const account = (roleCodes: string[], factoryIds: string[]): ManagedUser => ({
  id: '1',
  username: 'test',
  displayName: 'Test',
  deptId: '1',
  deptName: 'HQ',
  enabled: true,
  mustChangePassword: true,
  version: '1',
  roleIds: [],
  roleCodes,
  factoryIds,
  factories: [],
  lastLoginAt: null,
  createdAt: null,
  createdByName: null,
});
describe('SN account editor selection', () => {
  it('accepts only the complete reversible three-role mapping', () => {
    expect(canonicalAccountRole(account(['SN_HQ', 'PLATFORM_ADMIN'], []))).toBe('admin');
    expect(canonicalAccountRole(account(['FACTORY_OPERATOR'], ['9']))).toBe('factory_operator');
    expect(canonicalAccountRole(account(['SN_QUERY_ALL'], []))).toBe('query');
    expect(canonicalAccountRole(account(['SN_QUERY_FACTORY'], ['9']))).toBe('query');
  });
  it('preserves extra grants and unusual bindings by using the generic editor', () => {
    for (const value of [
      account(['SN_HQ'], []),
      account(['SN_QUERY_ALL', 'CUSTOM'], []),
      account(['FACTORY_OPERATOR'], ['1', '2']),
      account(['SN_QUERY_FACTORY'], []),
      account(['SN_HQ', 'PLATFORM_ADMIN'], ['1']),
    ])
      expect(canonicalAccountRole(value)).toBeUndefined();
  });
});
