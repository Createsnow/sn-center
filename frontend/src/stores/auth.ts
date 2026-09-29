import { defineStore } from "pinia";
import { computed, ref } from "vue";
import { api } from "@/api";
import { TOKEN_KEY, USER_KEY } from "@/api/http";
import { normalizeLocale, setLocale, type Locale } from "@/i18n";
import type { UserView } from "@/types/api";

export const useAuthStore = defineStore("auth", () => {
  const token = ref<string>(localStorage.getItem(TOKEN_KEY) || "");
  const profile = ref<UserView | null>(JSON.parse(localStorage.getItem(USER_KEY) || "null"));

  const role = computed(() => profile.value?.role || "");
  const isAdmin = computed(() => role.value === "admin");
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
    localStorage.setItem(TOKEN_KEY, t);
    localStorage.setItem(USER_KEY, JSON.stringify(user));
  }

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
    localStorage.setItem(USER_KEY, JSON.stringify(me));
  }

  async function changeLocale(locale: Locale) {
    setLocale(locale);
    if (!token.value || mustChangePwd.value) return;
    try {
      const me = await api.setLang(locale);
      profile.value = me;
      localStorage.setItem(USER_KEY, JSON.stringify(me));
    } catch {
      /* 保存偏好失败不影响本地切换 */
    }
  }

  function clear() {
    token.value = "";
    profile.value = null;
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
  }

  return {
    token, profile, role, isAdmin, isFactory, isQuery, boundFactory, mustChangePwd, canAct,
    login, changePassword, refresh, changeLocale, clear,
  };
});
