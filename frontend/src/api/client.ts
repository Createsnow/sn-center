import axios from 'axios';
import { queryClient } from '../app/queryClient';
import { useSession } from '../stores/session';
import {
  ApiError,
  isAccessToken,
  isProfile,
  isRecord,
  unwrap,
  type AccessToken,
  type Language,
  type LoginCommand,
  type PasswordChange,
  type UserProfile,
} from './contract';

const transport = axios.create({
  baseURL: '/api/v1',
  timeout: 12_000,
  withCredentials: true,
  validateStatus: () => true,
});
const ended =
  typeof BroadcastChannel === 'undefined'
    ? undefined
    : new BroadcastChannel('adtek-sn-session-ended');
let sessionEpoch = 0;

export function clearSession(notifyTabs = false) {
  sessionEpoch++;
  useSession.getState().clear();
  queryClient.clear();
  if (notifyTabs) {
    ended?.postMessage('ended');
  }
}
if (ended) {
  ended.onmessage = () => clearSession();
}

function acceptSession(value: AccessToken) {
  const previous = queryClient.getQueryData<UserProfile>(['identity', 'me']);
  if (
    previous &&
    (previous.id !== value.profile.id ||
      previous.authorizationVersion !== value.profile.authorizationVersion)
  ) {
    queryClient.clear();
  }
  queryClient.setQueryData(['identity', 'me'], value.profile);
  useSession.getState().setAccess(value.accessToken, value.expiresAt);
}

/** 不向页面传播 Axios config（可能包含密码/Authorization），也不记录原始错误。 */
async function send<T>(
  method: 'GET' | 'POST',
  path: string,
  validate: (value: unknown) => value is T,
  body?: unknown,
  bearer?: string,
  csrf?: { headerName: string; token: string },
  timeout = 12_000,
) {
  let response;
  try {
    response = await transport.request<unknown>({
      method,
      url: path,
      data: body,
      timeout,
      headers: {
        ...(bearer ? { Authorization: `Bearer ${bearer}` } : {}),
        ...(csrf ? { [csrf.headerName]: csrf.token } : {}),
      },
    });
  } catch {
    throw new ApiError(1_000_000_006, 0);
  }
  try {
    return unwrap(response.data, response.status, validate);
  } catch (error) {
    if (response.status === 401 && bearer) {
      clearSession();
    }
    throw error;
  }
}

async function csrf() {
  return send(
    'GET',
    '/system/auth/csrf',
    (value): value is { headerName: string; token: string } =>
      isRecord(value) && value.headerName === 'X-XSRF-TOKEN' && typeof value.token === 'string',
  );
}

let refreshFlight: Promise<void> | undefined;
/** 同一标签页 single-flight；Web Locks 串行化同源多标签页对共享 Cookie 的轮换。 */
export function refreshSession(): Promise<void> {
  if (!refreshFlight) {
    const perform = async () => {
      const epoch = sessionEpoch;
      try {
        const token = await send(
          'POST',
          '/system/auth/refresh',
          isAccessToken,
          {},
          undefined,
          await csrf(),
        );
        if (epoch !== sessionEpoch) {
          throw new ApiError(1_003_000_001, 401);
        }
        acceptSession(token);
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
          clearSession();
        } else {
          useSession.getState().unavailable();
        }
        throw error;
      }
    };
    refreshFlight = (async () => {
      if (navigator.locks) {
        await navigator.locks.request('adtek-sn-refresh', perform);
      } else {
        await perform();
      }
    })().finally(() => {
      refreshFlight = undefined;
    });
  }
  return refreshFlight;
}

async function access() {
  const session = useSession.getState();
  if (!session.accessToken) {
    throw new ApiError(1_003_000_001, 401);
  }
  if (session.expiresAt < Date.now() + 30_000) {
    await refreshSession();
  }
  const token = useSession.getState().accessToken;
  if (!token) {
    throw new ApiError(1_003_000_001, 401);
  }
  return token;
}

async function command(path: string, body: unknown) {
  const token = await access();
  return send('POST', path, (value): value is null => value === null, body, token, await csrf());
}

/** 管理 API 共用会话、CSRF 和错误边界；页面不访问原始 Axios 响应。 */
export async function query<T>(
  path: string,
  validate: (value: unknown) => value is T,
  timeout = 12_000,
) {
  return send('GET', path, validate, undefined, await access(), undefined, timeout);
}
export async function submit<T>(
  path: string,
  body: unknown,
  validate: (value: unknown) => value is T,
  timeout = 12_000,
) {
  return send('POST', path, validate, body, await access(), await csrf(), timeout);
}
export { command };

/** 文件入口沿用会话/统一错误协议；在创建下载链接前完整接收并验证长度，不把JSON错误保存成CSV。 */
export async function downloadFile(path: string) {
  const bearer = await access();
  let response;
  try {
    response = await transport.get<Blob>(path, {
      headers: { Authorization: `Bearer ${bearer}` },
      responseType: 'blob',
      timeout: 60_000,
    });
  } catch {
    throw new ApiError(1_000_000_006, 0);
  }
  if (
    response.status !== 200 ||
    !['text/csv', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'].some(
      (type) => String(response.headers['content-type']).startsWith(type),
    )
  ) {
    if (response.status === 401) clearSession();
    let payload: unknown;
    try {
      payload = JSON.parse(await response.data.text());
    } catch {
      throw new ApiError(1_000_000_006, response.status);
    }
    unwrap(payload, response.status, (value): value is null => value === null);
    throw new ApiError(1_000_000_006, response.status);
  }
  const match = String(response.headers['content-disposition']).match(
    /filename="((?:audit-[0-9a-f-]{36}\.csv|(?:print|sn-export|sn-audit)-[0-9a-f-]{36}\.(?:csv|xlsx)))"/,
  );
  const length = Number(response.headers['content-length']);
  if (
    !match ||
    !Number.isSafeInteger(length) ||
    length < 1 ||
    length > 16777216 ||
    response.data.size !== length
  ) {
    throw new ApiError(1_000_000_006, response.status);
  }
  return { blob: response.data, filename: match[1]! };
}

export const authApi = {
  async login(input: LoginCommand) {
    const epoch = sessionEpoch;
    const token = await send(
      'POST',
      '/system/auth/login',
      isAccessToken,
      input,
      undefined,
      await csrf(),
    );
    if (epoch !== sessionEpoch) {
      throw new ApiError(1_003_000_001, 401);
    }
    queryClient.clear();
    acceptSession(token);
  },
  async me() {
    return send('GET', '/system/auth/me', isProfile, undefined, await access());
  },
  async logout() {
    await command('/system/auth/logout', {});
    clearSession(true);
  },
  async password(input: PasswordChange) {
    await command('/system/auth/password', input);
    clearSession(true);
  },
  async language(language: Language) {
    await command('/system/auth/language', { language });
    await queryClient.invalidateQueries({ queryKey: ['identity', 'me'] });
  },
};
