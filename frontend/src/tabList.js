/**
 * 顶栏页签的纯逻辑（不依赖 Vue，便于测试）。
 * 页签 = { path, fullPath, ver }：path 是菜单路径（一个功能一个页签），fullPath 是带参数的完整地址（切回时原样恢复），
 * ver 是页面实例版本：新开、刷新、按新参数重新打开时取新值，旧实例的缓存随之释放。首页固定在最左，不能关闭、不能拖动。
 * @typedef {{ path: string, fullPath: string, ver: number }} Tab
 */

export const HOME = "/";

/** @param {Tab[]} tabs */
export function maxVer(tabs) {
  return tabs.reduce((m, t) => Math.max(m, t.ver), 0);
}

/** 首页放在第一个；没有就补上。 @param {Tab[]} tabs @returns {Tab[]} */
export function withHome(tabs) {
  const home = tabs.find((t) => t.path === HOME) ?? { path: HOME, fullPath: HOME, ver: maxVer(tabs) + 1 };
  return [home, ...tabs.filter((t) => t.path !== HOME)];
}

/**
 * 进入某页（菜单、页签、页面内跳转、浏览器前进后退都走这里）。
 * 没打开过：追加在末尾。已打开：记下新地址；若是从别的页带参数跳来、且与页签上次的地址不同（如首页点「去看告警」），
 * 换新版本，页面按新参数重新打开——这些页面只在打开时读取地址参数。
 * @param {Tab[]} tabs
 * @param {{ path: string, fullPath: string, hasQuery: boolean }} to
 * @param {string | null} fromPath 上一页的 path；首次进入为 null
 * @returns {Tab[]}
 */
export function visit(tabs, to, fromPath) {
  const i = tabs.findIndex((t) => t.path === to.path);
  if (i < 0) return [...tabs, { path: to.path, fullPath: to.fullPath, ver: maxVer(tabs) + 1 }];
  const cur = tabs[i];
  if (cur.fullPath === to.fullPath) return tabs;
  const reopen = fromPath !== null && fromPath !== to.path && to.hasQuery;
  const next = { ...cur, fullPath: to.fullPath, ver: reopen ? maxVer(tabs) + 1 : cur.ver };
  return tabs.map((t, k) => (k === i ? next : t));
}

/**
 * 关闭一个页签。关的是当前页时，激活右边的；右边没有就激活左边的（与浏览器一致）。
 * @param {Tab[]} tabs @param {string} path @param {string} activePath
 * @returns {{ tabs: Tab[], next: Tab | null }} next = 需要切换到的页签
 */
export function closeTab(tabs, path, activePath) {
  const i = tabs.findIndex((t) => t.path === path);
  if (path === HOME || i < 0) return { tabs, next: null };
  const rest = tabs.filter((_, k) => k !== i);
  const next = path === activePath ? (rest[i] ?? rest[i - 1] ?? null) : null;
  return { tabs: rest, next };
}

/** 关闭其他：只留首页和该页签。 @param {Tab[]} tabs @param {string} path */
export function closeOthers(tabs, path) {
  return tabs.filter((t) => t.path === HOME || t.path === path);
}

/** 关闭右侧：该页签右边的全部关闭。 @param {Tab[]} tabs @param {string} path */
export function closeRight(tabs, path) {
  const i = tabs.findIndex((t) => t.path === path);
  return i < 0 ? tabs : tabs.slice(0, i + 1);
}

/** 关闭全部：只留首页。 @param {Tab[]} tabs */
export function closeAll(tabs) {
  return tabs.filter((t) => t.path === HOME);
}

/** 刷新：换新版本，页面重新加载。 @param {Tab[]} tabs @param {string} path */
export function refreshTab(tabs, path) {
  const ver = maxVer(tabs) + 1;
  return tabs.map((t) => (t.path === path ? { ...t, ver } : t));
}

/**
 * 拖动排序：把 from 位置的页签移到 to 位置；首页不动，也不能把别的页签放到首页前面。
 * @param {Tab[]} tabs @param {number} from @param {number} to
 */
export function moveTab(tabs, from, to) {
  if (from === to || from <= 0 || to <= 0 || from >= tabs.length || to >= tabs.length) return tabs;
  const out = [...tabs];
  const [t] = out.splice(from, 1);
  out.splice(to, 0, t);
  return out;
}

/**
 * 读回 F5 前保存的页签：只认同一账户保存的；去掉格式不对、重复、当前账户无权访问的页签。
 * @param {unknown} saved sessionStorage 里解析出来的对象
 * @param {number | string | null | undefined} uid
 * @param {(path: string) => boolean} allowed
 * @returns {Tab[]}
 */
export function restoreTabs(saved, uid, allowed) {
  const s = /** @type {{ uid?: unknown, tabs?: unknown }} */ (saved ?? {});
  if (uid == null || s.uid !== uid || !Array.isArray(s.tabs)) return withHome([]);
  const seen = new Set();
  const tabs = s.tabs.filter((t) => {
    const ok = t && typeof t.path === "string" && typeof t.fullPath === "string" && Number.isFinite(t.ver)
      && !seen.has(t.path) && (t.path === HOME || allowed(t.path));
    if (ok) seen.add(t.path);
    return ok;
  }).map((t) => ({ path: t.path, fullPath: t.fullPath, ver: t.ver }));
  return withHome(tabs);
}
