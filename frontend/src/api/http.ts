import axios from "axios";
import { ElMessage, ElMessageBox } from "element-plus";
import type { ApiErrorBody } from "@/types/api";
import { currentLocale, t, translateApiError } from "@/i18n";
import { newRequestId } from "@/requestId";

const httpTimeout = Number(import.meta.env.VITE_HTTP_TIMEOUT);
export const TOKEN_KEY = "sn_token";
export const USER_KEY = "sn_user";

/**
 * 登录态只存在本标签页（sessionStorage），不跨标签页共享：新开的页面要自己登录，
 * 同一台机器可在不同标签页同时登录不同账户。同一账户只在一个页面有效，后登录的覆盖先登录的
 * （后端令牌校验 + 本浏览器内 stores/auth 的页面广播）。
 */
function read(key: string): string | null {
  try {
    return sessionStorage.getItem(key);
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
    if (token !== null) sessionStorage.setItem(TOKEN_KEY, token);
    sessionStorage.setItem(USER_KEY, JSON.stringify(user));
  } catch {
    /* 存储不可用时仅保留内存中的登录态 */
  }
}

export function clearSession(): void {
  try {
    sessionStorage.removeItem(TOKEN_KEY);
    sessionStorage.removeItem(USER_KEY);
  } catch {
    /* ignore */
  }
}

/** 整页跳到登录页（清掉内存状态）；reason 为错误码时登录页显示原因，并记下当前页供登录后返回。 */
export function goLogin(reason?: string): void {
  const { pathname, search } = window.location;
  if (pathname.endsWith("/login")) return;
  const q = new URLSearchParams();
  if (reason) q.set("reason", reason);
  if (pathname !== "/" || search) q.set("redirect", pathname + search);
  const qs = q.toString();
  window.location.href = "/login" + (qs ? "?" + qs : "");
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

/** 用弹窗（须点确定）而不是消息条提示的错误：前缀冲突（防重，F-08）。 */
const ALERT_CODES = new Set(["RULE_PREFIX_CONFLICT"]);

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
      goLogin(code === "SESSION_REPLACED" || code === "PASSWORD_CHANGED" ? code : undefined);
    } else if (!err.config?.silent) {
      if (code && ALERT_CODES.has(code)) ElMessageBox.alert(message, { type: "error" }).catch(() => undefined);
      else ElMessage.error({ message, duration: 5000, showClose: true });
    }
    return Promise.reject(new ApiError(message, status, code, body?.params));
  },
);
