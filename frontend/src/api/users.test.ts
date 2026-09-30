import { describe, expect, it } from 'vitest';
import { isManagedUser } from './users';

describe('user management API boundary', () => {
  const user = {
    id: '9007199254741001',
    username: 'it_user',
    displayName: 'Test',
    deptId: null,
    deptName: null,
    enabled: true,
    mustChangePassword: true,
    version: '1',
    roleIds: ['9007199254741002'],
    factoryIds: ['9007199254741003'],
  };
  it('keeps identifiers beyond JavaScript precision and nullable legacy department intact', () => {
    expect(isManagedUser(user)).toBe(true);
    expect(isManagedUser({ ...user, id: Number(user.id) })).toBe(false);
    expect(isManagedUser({ ...user, roleIds: [Number(user.roleIds[0])] })).toBe(false);
  });
  it('rejects missing version and string status instead of permitting an unsafe edit', () => {
    expect(isManagedUser({ ...user, version: undefined })).toBe(false);
    expect(isManagedUser({ ...user, enabled: 'false' })).toBe(false);
  });
});
