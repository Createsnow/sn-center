import { describe, expect, it } from 'vitest';
import { ApiError, isProfile, unwrap } from './contract';

const profile = {
  id: '9007199254741001',
  username: 'test',
  displayName: 'Test',
  deptId: null,
  language: 'zh-CN',
  mustChangePassword: true,
  authorizationVersion: '1',
  permissions: ['system:user:list'],
};

describe('external API boundaries', () => {
  it('preserves IDs above the JavaScript safe integer range and rejects numeric IDs', () => {
    expect(isProfile(profile)).toBe(true);
    expect(isProfile({ ...profile, id: Number(profile.id) })).toBe(false);
    expect(isProfile({ ...profile, deptId: 1 })).toBe(false);
  });
  it('rejects unknown language and malformed permissions before caching a profile', () => {
    expect(isProfile({ ...profile, language: 'unknown' })).toBe(false);
    expect(isProfile({ ...profile, permissions: [1] })).toBe(false);
    expect(isProfile({ ...profile, mustChangePassword: 'false' })).toBe(false);
  });
  it('preserves retry and trace information without treating a failed response as success', () => {
    const value = {
      code: 1003000003,
      message: 'framework.rateLimited',
      data: null,
      params: { retryAfter: '30' },
      traceId: 'trace',
    };
    try {
      unwrap(value, 429, isProfile);
      throw new Error('Expected rejection');
    } catch (error) {
      expect(error).toBeInstanceOf(ApiError);
      expect(error).toMatchObject({
        status: 429,
        code: 1003000003,
        params: { retryAfter: '30' },
        traceId: 'trace',
      });
    }
  });
  it('rejects an invalid success envelope and accepts a valid typed payload', () => {
    expect(() => unwrap({ code: 0, data: profile }, 200, isProfile)).toThrow(ApiError);
    const result = {
      code: 0,
      message: 'common.success',
      data: profile,
      params: {},
      traceId: 'trace',
    };
    expect(unwrap(result, 200, isProfile).id).toBe(profile.id);
    expect(() => unwrap(result, 503, isProfile)).toThrow(ApiError);
  });
});
