import { computed } from "vue";
import { createI18n } from "vue-i18n";
import elZhCn from "element-plus/es/locale/lang/zh-cn";
import elEn from "element-plus/es/locale/lang/en";
import elVi from "element-plus/es/locale/lang/vi";
import zhCN from "@/locales/zh-CN.json";
import en from "@/locales/en.json";
import vi from "@/locales/vi.json";
import errZhCN from "@/locales/errors.zh-CN.json";
import errEn from "@/locales/errors.en.json";
import errVi from "@/locales/errors.vi.json";

export type Locale = "zh-CN" | "en" | "vi";
export const LOCALES: { value: Locale; label: string }[] = [
  { value: "zh-CN", label: "简体中文" },
  { value: "en", label: "English" },
  { value: "vi", label: "Tiếng Việt" },
];
const STORAGE_KEY = "sn_lang";
const EL_LOCALES = { "zh-CN": elZhCn, en: elEn, vi: elVi } as const;

export function normalizeLocale(v: unknown): Locale | null {
  const s = String(v ?? "").trim();
  if (!s) return null;
  if (/^zh/i.test(s)) return "zh-CN";
  if (/^en/i.test(s)) return "en";
  if (/^vi/i.test(s)) return "vi";
  return null;
}

/** 优先级：用户偏好（登录后由 store 写入）→ 本地记忆 → 浏览器语言 → zh-CN。 */
export function detectLocale(): Locale {
  const saved = normalizeLocale(localStorage.getItem(STORAGE_KEY));
  if (saved) return saved;
  for (const l of navigator.languages ?? [navigator.language]) {
    const n = normalizeLocale(l);
    if (n) return n;
  }
  return "zh-CN";
}

export const i18n = createI18n({
  legacy: false,
  locale: detectLocale(),
  fallbackLocale: "zh-CN",
  missingWarn: false,
  fallbackWarn: false,
  messages: {
    "zh-CN": { ...zhCN, errors: errZhCN },
    en: { ...en, errors: errEn },
    vi: { ...vi, errors: errVi },
  },
});

export const currentLocale = computed(() => i18n.global.locale.value as Locale);
export const elementLocale = computed(() => EL_LOCALES[currentLocale.value]);

export function setLocale(locale: Locale, remember = true) {
  i18n.global.locale.value = locale;
  document.documentElement.lang = locale;
  document.title = i18n.global.t("app.title");
  if (remember) localStorage.setItem(STORAGE_KEY, locale);
}

/** 组件外使用（axios 拦截器、工具函数）。 */
export function t(key: string, params?: Record<string, unknown>): string {
  return params ? i18n.global.t(key, params) : i18n.global.t(key);
}

/** 后端业务错误 {code, message, params} → 本地化文案；无对应词条时回退到 message。 */
export function translateApiError(
  body: { code?: string; message?: string; params?: Record<string, unknown> } | undefined,
  fallback: string,
): string {
  const code = body?.code;
  if (code) {
    const key = `errors.${code}`;
    if (i18n.global.te(key)) return i18n.global.t(key, body?.params ?? {});
  }
  return body?.message || fallback;
}
