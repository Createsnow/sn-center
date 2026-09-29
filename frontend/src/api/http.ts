import axios from "axios";
import { ElMessage } from "element-plus";
import type { ApiErrorBody } from "@/types/api";
import { currentLocale, t, translateApiError } from "@/i18n";
import { newRequestId } from "@/requestId";

const httpTimeout = Number(import.meta.env.VITE_HTTP_TIMEOUT);
export const TOKEN_KEY = "sn_token";
export const USER_KEY = "sn_user";

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || "/api",
  timeout: Number.isFinite(httpTimeout) && httpTimeout > 0 ? httpTimeout : 120_000,
});

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY);
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
