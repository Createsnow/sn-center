import axios, { type AxiosResponse } from 'axios';

// 统一 API 客户端：所有功能的 src/api/{feature}.ts 通过本文件访问 /api/v1，不直接使用 axios。
// 响应先按 Result 信封做运行时校验，再交给调用方的 parse 校验 data；失败统一抛出 ApiError（翻译键 + 参数 + traceId）。
// CSRF：axios 对同源请求自动读取 XSRF-TOKEN Cookie 并发送 X-XSRF-TOKEN 头，与后端 SPA 模式 CSRF 一致。

export type ApiArgs = Readonly<Record<string, string>>;

/** 解析成功响应的 data；入参是未经信任的 unknown，校验失败须抛错。 */
export type DataParser<T> = (data: unknown) => T;

interface ApiErrorInit {
  status: number;
  code: number;
  key: string;
  args: ApiArgs;
  traceId: string | null;
}

/** 失败响应或客户端错误；页面用 t(error.key, error.args) 呈现，并在内部错误时展示 traceId。 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: number;
  readonly key: string;
  readonly args: ApiArgs;
  readonly traceId: string | null;

  constructor(init: ApiErrorInit) {
    super(init.key);
    this.name = 'ApiError';
    this.status = init.status;
    this.code = init.code;
    this.key = init.key;
    this.args = init.args;
    this.traceId = init.traceId;
  }
}

/** 客户端侧错误（无法连接、响应不符合信封）；code 为负数以区别于服务端错误码。 */
export const clientErrors = {
  network: { code: -1, key: 'error.client.network' },
  invalidResponse: { code: -2, key: 'error.client.invalidResponse' },
} as const;

const http = axios.create({
  baseURL: '/api/v1',
  timeout: 30_000,
  headers: { Accept: 'application/json' },
  // 状态码由信封与 HTTP 状态共同判断，不让 axios 对 4xx/5xx 抛出未校验的错误。
  validateStatus: () => true,
});

export function apiGet<T>(
  path: string,
  parse: DataParser<T>,
  params?: Readonly<Record<string, string | number>>,
): Promise<T> {
  return send(() => http.get<unknown>(path, { params }), parse);
}

export function apiPost<T>(path: string, body: unknown, parse: DataParser<T>): Promise<T> {
  return send(() => http.post<unknown>(path, body), parse);
}

/** 写命令成功时 data 为 null。 */
export const parseNothing: DataParser<null> = (data) => {
  if (data !== null) {
    throw new TypeError('Expected no data');
  }
  return null;
};

/** 业务 ID、计数与版本以十进制字符串传输，前端不转换为 number。 */
export function isDecimalString(value: unknown): value is string {
  return typeof value === 'string' && /^(0|[1-9][0-9]*)$/.test(value);
}

async function send<T>(
  request: () => Promise<AxiosResponse<unknown>>,
  parse: DataParser<T>,
): Promise<T> {
  let response: AxiosResponse<unknown>;
  try {
    response = await request();
  } catch {
    throw new ApiError({ status: 0, ...clientErrors.network, args: {}, traceId: null });
  }
  return unwrap(response.status, response.data, parse);
}

/** 校验信封并返回 data；导出以便单元测试。 */
export function unwrap<T>(status: number, body: unknown, parse: DataParser<T>): T {
  if (!isEnvelope(body)) {
    throw new ApiError({ status, ...clientErrors.invalidResponse, args: {}, traceId: null });
  }
  const { code, key, args, traceId } = body;
  if (code !== 0 || status < 200 || status >= 300) {
    throw new ApiError({ status, code, key, args, traceId });
  }
  try {
    return parse(body.data);
  } catch {
    throw new ApiError({ status, ...clientErrors.invalidResponse, args: {}, traceId });
  }
}

interface Envelope {
  code: number;
  key: string;
  args: ApiArgs;
  data: unknown;
  traceId: string | null;
}

function isEnvelope(value: unknown): value is Envelope {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const record = value as Record<string, unknown>;
  return (
    Number.isInteger(record.code) &&
    typeof record.key === 'string' &&
    isArgs(record.args) &&
    'data' in record &&
    (record.traceId === null || typeof record.traceId === 'string')
  );
}

function isArgs(value: unknown): value is ApiArgs {
  return (
    typeof value === 'object' &&
    value !== null &&
    !Array.isArray(value) &&
    Object.values(value).every((item) => typeof item === 'string')
  );
}
