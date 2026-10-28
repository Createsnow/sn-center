import { defineStore } from "pinia";
import { computed, ref } from "vue";
import { api } from "@/api";
import { clearSession, getSavedUser, getToken, goLogin, saveSession } from "@/api/http";
import { normalizeLocale, setLocale, type Locale } from "@/i18n";
import type { UserView } from "@/types/api";

/** 本浏览器内的页面广播：某页登录（或带登录态打开 / 刷新）某账户时，其他页面上的同一账户退出。 */
const PAGE_ID = Math.random().toString(36).slice(2) + Date.now().toString(36);
let channel: BroadcastChannel | null = null;
try {
  channel = new BroadcastChannel("sn_session");
} catch {
  /* 不支持时只靠后端令牌校验 */
}

export const useAuthStore = defineStore("auth", () => {
  const token = ref<string>(getToken());
  const profile = ref<UserView | null>(token.value ? getSavedUser<UserView>() : null);

  const role = computed(() => profile.value?.role || "");
  /** 系统管理员（唯一）：全部生产组织、全部功能。 */
  const isAdmin = computed(() => role.value === "admin");
  /** 系统管理员账户：唯一能管理账户的人。 */
  const isSuperAdmin = computed(() => !!profile.value?.super_admin);
  /** 普通操作员：授权组织内的订单、生成、分配、规则、转移、交付处理。 */
  const isOperator = computed(() => role.value === "operator");
  /** 审计员：授权组织内只读（SN 查询与导出、操作痕迹）。 */
  const isAuditor = computed(() => role.value === "auditor");
  /** 授权的生产组织：普通操作员、审计员 = 授权的一个或多个组织；系统管理员为空 = 全部。 */
  const scopeFactories = computed<string[]>(() => profile.value?.factory_codes ?? []);
  /** 只授权了一个生产组织时的那个组织（写操作可不选组织）。 */
  const boundFactory = computed(() => (scopeFactories.value.length === 1 ? scopeFactories.value[0] : ""));
  const scopeNames = computed(() => (profile.value?.factory_names ?? []).join("、"));
  /** 数据范围受限（系统管理员以外）。 */
  const bound = computed(() => !isAdmin.value);
  /** 页面上要不要「生产组织」筛选：系统管理员，或授权了多个组织的账户。 */
  const multiScope = computed(() => isAdmin.value || scopeFactories.value.length > 1);
  /** 能做业务操作（生成、分配、转移、交付处理等）：系统管理员、普通操作员。 */
  const canAct = computed(() => isAdmin.value || isOperator.value);
  /** 能对这些生产组织做写操作：系统管理员不限；普通操作员须全部在授权范围内（空值不计）。与后端 check_write 一致。 */
  function canWrite(...codes: (string | null | undefined)[]) {
    return isAdmin.value || (isOperator.value && codes.every((c) => !c || scopeFactories.value.includes(c)));
  }

  function persist(t: string, user: UserView) {
    token.value = t;
    profile.value = user;
    saveSession(t, user);
    announce();
  }

  /** 宣告本页正在使用该账户（复制标签页、刷新也算），同浏览器其他页上的该账户随即退出。 */
  function announce() {
    if (token.value && profile.value) channel?.postMessage({ uid: profile.value.id, page: PAGE_ID });
  }

  channel?.addEventListener("message", (e: MessageEvent) => {
    const msg = e.data as { uid?: number; page?: string } | null;
    if (!token.value || !profile.value || msg?.page === PAGE_ID || msg?.uid !== profile.value.id) return;
    clear();
    goLogin("SESSION_REPLACED");
  });
  announce();

  async function login(empNo: string, password: string) {
    const res = await api.login(empNo, password);
    persist(res.token, res.user);
    const pref = normalizeLocale(res.user?.lang);
    if (pref) setLocale(pref);
  }

  /** 改密成功后不发新令牌：本账户所有页面（含本页）退出，用新密码重新登录。 */
  async function changePassword(oldPwd: string, newPwd: string) {
    await api.changePassword(oldPwd, newPwd);
    clear();
  }

  async function refresh() {
    if (!token.value) return;
    const me = await api.me();
    profile.value = me;
    saveSession(null, me);
  }

  async function changeLocale(locale: Locale) {
    setLocale(locale);
    if (!token.value) return;
    try {
      const me = await api.setLang(locale);
      profile.value = me;
      saveSession(null, me);
    } catch {
      /* 保存偏好失败不影响本地切换 */
    }
  }

  function clear() {
    token.value = "";
    profile.value = null;
    clearSession();
  }

  return {
    token, profile, role, isAdmin, isSuperAdmin, isOperator, isAuditor, boundFactory, scopeFactories, scopeNames, bound,
    multiScope, canAct, canWrite,
    login, changePassword, refresh, changeLocale, clear,
  };
});
