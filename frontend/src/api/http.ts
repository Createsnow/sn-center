import axios from "axios";
import { ElMessage } from "element-plus";
import type { ApiErrorBody } from "@/types/api";
import { currentLocale, t, translateApiError } from "@/i18n";
import { newRequestId } from "@/requestId";

const httpTimeout = Number(import.meta.env.VITE_HTTP_TIMEOUT);
export const TOKEN_KEY = "sn_token";
export const USER_KEY = "sn_user";

/**
 * 登录态按浏览器标签页隔离（sessionStorage），同一台机器可在不同标签页同时登录不同账户，互不覆盖。
 * localStorage 只记「最近一次登录」，供新开的空白标签页沿用；之后各标签页各自独立。
 */
function read(key: string): string | null {
  try {
    const own = sessionStorage.getItem(key);
    if (own !== null || sessionStorage.getItem(TOKEN_KEY) !== null) return own;
    const last = localStorage.getItem(key);
    if (last !== null) sessionStorage.setItem(key, last);
    return last;
  } catch {
    return null;
  }
}

export function getToken(): string {
  return read(TOKEN_KEY) || "";
}

export function getSavedUser<T>(): T | null {
  try {
    return JSON.parse(read(USER_KEY) || "null") as T | null;
  } catch {
    return null;
  }
}

export function saveSession(token: string | null, user: unknown): void {
  try {
    if (token !== null) {
      sessionStorage.setItem(TOKEN_KEY, token);
      localStorage.setItem(TOKEN_KEY, token);
    }
    const json = JSON.stringify(user);
    sessionStorage.setItem(USER_KEY, json);
    // 只有本页仍是「最近一次登录」时才同步资料，避免把别的标签页的账户信息改掉
    if (localStorage.getItem(TOKEN_KEY) === sessionStorage.getItem(TOKEN_KEY)) localStorage.setItem(USER_KEY, json);
  } catch {
    /* 存储不可用时仅保留内存中的登录态 */
  }
}

export function clearSession(): void {
  try {
    const mine = sessionStorage.getItem(TOKEN_KEY);
    // 本页退出：sessionStorage 留一个空令牌，表示「本页已退出」，不再沿用最近一次登录
    sessionStorage.setItem(TOKEN_KEY, "");
    sessionStorage.removeItem(USER_KEY);
    if (mine && localStorage.getItem(TOKEN_KEY) === mine) {
      localStorage.removeItem(TOKEN_KEY);
      localStorage.removeItem(USER_KEY);
    }
  } catch {
    /* ignore */
  }
}

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || "/api",
  timeout: Number.isFinite(httpTimeout) && httpTimeout > 0 ? httpTimeout : 120_000,
});

http.interceptors.request.use((config) => {
  const token = getToken();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  config.headers["X-Request-ID"] = newRequestId();
  config.headers["Accept-Language"] = currentLocale.value;
  return config;
});

/** 业务错误：带后端错误码，页面可按 code 做分支。 */
export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
    public code?: string,
    public params?: Record<string, unknown>,
  ) {
    super(message);
  }
}

/** 调用方传 { silent: true } 时自己处理错误提示。 */
declare module "axios" {
  interface AxiosRequestConfig {
    silent?: boolean;
  }
}

http.interceptors.response.use(
  (res) => res.data,
  async (err) => {
    const status = (err.response?.status as number | undefined) ?? 0;
    const body = err.response?.data as ApiErrorBody | undefined;
    const message = translateApiError(body, err.message || t("common.requestFailed"));
    const code = body?.code;
    // 登录请求本身的 401（工号或密码错误、账户已停用）要提示，不能当成「登录已失效」处理
    const isLogin = /\/auth\/login$/.test(err.config?.url || "");
    if (status === 401 && !isLogin) {
      const { useAuthStore } = await import("@/stores/auth");
      useAuthStore().clear();
      if (!window.location.pathname.endsWith("/login")) {
        window.location.href = "/login";
      }
    } else if (code === "PASSWORD_CHANGE_REQUIRED") {
      if (!window.location.pathname.endsWith("/password")) {
        window.location.href = "/password";
      }
    } else if (!err.config?.silent) {
      ElMessage.error({ message, duration: 5000, showClose: true });
    }
    return Promise.reject(new ApiError(message, status, code, body?.params));
  },
);
