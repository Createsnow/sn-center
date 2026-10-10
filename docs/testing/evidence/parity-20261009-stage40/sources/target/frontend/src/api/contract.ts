import type { components } from './generated/schema';

export type UserProfile = components['schemas']['UserProfileVO'];
export type AccessToken = components['schemas']['AccessTokenVO'];
export type LoginCommand = components['schemas']['LoginCommandDTO'];
export type PasswordChange = components['schemas']['PasswordChangeDTO'];
export type Language = 'zh-CN' | 'en' | 'vi';

export class ApiError extends Error {
  constructor(
    public readonly code: number,
    public readonly status: number,
    public readonly traceId = '',
    public readonly params: Readonly<Record<string, string>> = {},
  ) {
    super('API request rejected');
    this.name = 'ApiError';
  }
}

/** 下载响应不是 JSON 时保留 HTTP 状态，避免与有效的业务错误码混淆。 */
export class DownloadHttpError extends ApiError {
  constructor(status: number) {
    super(1_000_000_006, status);
    this.name = 'DownloadHttpError';
  }
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function isLanguage(value: unknown): value is Language {
  return value === 'zh-CN' || value === 'en' || value === 'vi';
}

export function isProfile(value: unknown): value is UserProfile {
  return (
    isRecord(value) &&
    typeof value.id === 'string' &&
    /^[1-9][0-9]*$/.test(value.id) &&
    typeof value.username === 'string' &&
    typeof value.authorizationVersion === 'string' &&
    /^[1-9][0-9]*$/.test(value.authorizationVersion) &&
    typeof value.displayName === 'string' &&
    (value.deptId === null ||
      (typeof value.deptId === 'string' && /^[1-9][0-9]*$/.test(value.deptId))) &&
    isLanguage(value.language) &&
    typeof value.mustChangePassword === 'boolean' &&
    Array.isArray(value.permissions) &&
    value.permissions.every((item: unknown) => typeof item === 'string')
  );
}

export function isAccessToken(value: unknown): value is AccessToken {
  return (
    isRecord(value) &&
    typeof value.accessToken === 'string' &&
    value.accessToken.length > 0 &&
    value.tokenType === 'Bearer' &&
    typeof value.expiresAt === 'string' &&
    Number.isFinite(Date.parse(value.expiresAt)) &&
    isProfile(value.profile)
  );
}

/** unknown 输入先检查统一信封和领域数据；精确 ID 不允许从 number 强转恢复。 */
export function unwrap<T>(
  value: unknown,
  status: number,
  validate: (value: unknown) => value is T,
): T {
  if (
    !isRecord(value) ||
    !Number.isInteger(value.code) ||
    typeof value.code !== 'number' ||
    typeof value.message !== 'string' ||
    typeof value.traceId !== 'string' ||
    !isRecord(value.params) ||
    !Object.values(value.params).every((item) => typeof item === 'string')
  ) {
    throw new ApiError(1_000_000_002, status);
  }
  const params: Record<string, string> = {};
  for (const [key, item] of Object.entries(value.params)) {
    if (typeof item === 'string') {
      params[key] = item;
    }
  }
  if (value.code !== 0) {
    throw new ApiError(value.code, status, value.traceId, params);
  }
  if (status < 200 || status >= 300 || !validate(value.data)) {
    throw new ApiError(1_000_000_002, status, value.traceId);
  }
  return value.data;
}
