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
  const isAdmin = computed(() => role.value === "admin");
  /** 内置 admin 账户：唯一能管理账户的人。 */
  const isSuperAdmin = computed(() => !!profile.value?.super_admin);
  const isFactory = computed(() => role.value === "factory_operator");
  const isQuery = computed(() => role.value === "query");
  /** 绑厂账户（厂区操作员、绑厂查询员）只能看本厂。 */
  const boundFactory = computed(() => profile.value?.factory_code || "");
  const mustChangePwd = computed(() => !!profile.value?.must_change_pwd);
  /** 能做领取 / 打印（总部代任一厂、本厂操作员）。 */
  const canAct = computed(() => isAdmin.value || isFactory.value);

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

  async function changePassword(oldPwd: string, newPwd: string) {
    const res = await api.changePassword(oldPwd, newPwd);
    persist(res.token, res.user);
  }

  async function refresh() {
    if (!token.value) return;
    const me = await api.me();
    profile.value = me;
    saveSession(null, me);
  }

  async function changeLocale(locale: Locale) {
    setLocale(locale);
    if (!token.value || mustChangePwd.value) return;
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
    token, profile, role, isAdmin, isSuperAdmin, isFactory, isQuery, boundFactory, mustChangePwd, canAct,
    login, changePassword, refresh, changeLocale, clear,
  };
});
