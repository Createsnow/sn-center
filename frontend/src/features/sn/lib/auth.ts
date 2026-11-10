import { create } from 'zustand';
import { onUnauthorized, setAuthToken, type ApiError } from '../../../api/client';
import { snApi, type Row } from '../../../api/sn';
import i18n, { isLanguage } from '../../../i18n';
import { bool, num, str, strs } from './row';
import { DEFAULT_TZ_OFFSET_MIN, formatTime, tzLabel } from './time';

// 登录态只存在本标签页（sessionStorage），与原系统相同：同一浏览器的不同标签页可同时登录不同账户；
// 同一账户只在最后登录的页面有效（后端会话 + 本浏览器内的页面广播）。

const TOKEN_KEY = 'sn_token';
const USER_KEY = 'sn_user';
const PAGE_ID = Math.random().toString(36).slice(2) + Date.now().toString(36);

function read(key: string): string | null {
  try {
    return sessionStorage.getItem(key);
  } catch {
    return null;
  }
}

function savedUser(): Row | null {
  try {
    const v = JSON.parse(read(USER_KEY) ?? 'null') as unknown;
    return typeof v === 'object' && v !== null ? (v as Row) : null;
  } catch {
    return null;
  }
}

function save(token: string | null, user: Row | null) {
  try {
    if (token === null && user === null) {
      sessionStorage.removeItem(TOKEN_KEY);
      sessionStorage.removeItem(USER_KEY);
      return;
    }
    if (token !== null) {
      sessionStorage.setItem(TOKEN_KEY, token);
    }
    sessionStorage.setItem(USER_KEY, JSON.stringify(user));
  } catch {
    /* 存储不可用时仅保留内存中的登录态 */
  }
}

let channel: BroadcastChannel | null = null;
try {
  channel = new BroadcastChannel('sn_session');
} catch {
  channel = null;
}

export function normalizeLocale(v: unknown): 'zh-CN' | 'en' | 'vi' | null {
  const s = str(v).trim();
  if (/^zh/i.test(s)) return 'zh-CN';
  if (/^en/i.test(s)) return 'en';
  if (/^vi/i.test(s)) return 'vi';
  return null;
}

interface AuthState {
  token: string;
  profile: Row | null;
  login: (empNo: string, password: string) => Promise<void>;
  changePassword: (oldPwd: string, newPwd: string) => Promise<void>;
  refresh: () => Promise<void>;
  changeLocale: (locale: 'zh-CN' | 'en' | 'vi') => Promise<void>;
  clear: () => void;
}

const initialToken = read(TOKEN_KEY) ?? '';
setAuthToken(initialToken);

export const useAuth = create<AuthState>((set, get) => ({
  token: initialToken,
  profile: initialToken ? savedUser() : null,
  async login(empNo, password) {
    const res = await snApi.login(empNo, password);
    const token = str(res.token);
    const user = (res.user ?? {}) as Row;
    setAuthToken(token);
    save(token, user);
    set({ token, profile: user });
    announce();
    const pref = normalizeLocale(user.lang);
    if (pref) {
      void i18n.changeLanguage(pref);
    }
  },
  async changePassword(oldPwd, newPwd) {
    await snApi.changePassword(oldPwd, newPwd);
    get().clear();
  },
  async refresh() {
    if (!get().token) return;
    const me = await snApi.me();
    save(null, me);
    set({ profile: me });
  },
  async changeLocale(locale) {
    void i18n.changeLanguage(locale);
    if (!get().token) return;
    try {
      const me = await snApi.setLang(locale);
      save(null, me);
      set({ profile: me });
    } catch {
      /* 保存偏好失败不影响本地切换 */
    }
  },
  clear() {
    setAuthToken('');
    save(null, null);
    set({ token: '', profile: null });
  },
}));

function announce() {
  const { token, profile } = useAuth.getState();
  if (token && profile) {
    channel?.postMessage({ uid: str(profile.id), page: PAGE_ID });
  }
}

/** 整页跳到登录页（清掉内存状态）；reason 为错误翻译键时登录页显示原因，并记下当前页供登录后返回。 */
export function goLogin(reason?: string): void {
  const { pathname, search } = window.location;
  if (pathname.endsWith('/sn/login')) return;
  const q = new URLSearchParams();
  if (reason) q.set('reason', reason);
  if (pathname !== '/sn' || search) q.set('redirect', pathname + search);
  const qs = q.toString();
  window.location.href = '/sn/login' + (qs ? `?${qs}` : '');
}

channel?.addEventListener('message', (e: MessageEvent) => {
  const msg = e.data as { uid?: string; page?: string } | null;
  const { token, profile } = useAuth.getState();
  if (!token || !profile || msg?.page === PAGE_ID || msg?.uid !== str(profile.id)) return;
  useAuth.getState().clear();
  goLogin('error.auth.sessionReplaced');
});
announce();

onUnauthorized((error: ApiError) => {
  if (!error.key.startsWith('error.auth.') && error.key !== 'error.common.unauthenticated') {
    return;
  }
  useAuth.getState().clear();
  goLogin(
    error.key === 'error.auth.sessionReplaced' || error.key === 'error.auth.passwordChanged'
      ? error.key
      : undefined,
  );
});

/** 当前账户的派生信息（与原系统 stores/auth 相同的口径）。 */
export function useAccount() {
  const profile = useAuth((s) => s.profile);
  const role = str(profile?.role);
  const isAdmin = role === 'admin';
  const isOperator = role === 'operator';
  const isAuditor = role === 'auditor';
  const scopeFactories = strs(profile?.factoryCodes);
  const offset =
    profile && profile.tzOffsetMin !== undefined && profile.tzOffsetMin !== null
      ? num(profile.tzOffsetMin)
      : DEFAULT_TZ_OFFSET_MIN;
  return {
    profile,
    role,
    isAdmin,
    isSuperAdmin: bool(profile?.superAdmin),
    isOperator,
    isAuditor,
    scopeFactories,
    boundFactory: scopeFactories.length === 1 ? (scopeFactories[0] ?? '') : '',
    scopeNames: strs(profile?.factoryNames).join('、'),
    bound: !isAdmin,
    multiScope: isAdmin || scopeFactories.length > 1,
    canAct: isAdmin || isOperator,
    canWrite: (...codes: (string | null | undefined)[]) =>
      isAdmin || (isOperator && codes.every((c) => !c || scopeFactories.includes(c))),
    tz: tzLabel(offset),
    fmt: (iso: unknown, withSeconds = true) => formatTime(iso, offset, withSeconds),
  };
}

export { isLanguage };
