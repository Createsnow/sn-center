/** 顶栏页签的纯逻辑（与原系统 tabList.js 相同）：首页固定在最左；同一功能只有一个页签。 */
export interface Tab {
  path: string;
  fullPath: string;
  ver: number;
}

export const HOME = '/sn';

export function maxVer(tabs: Tab[]): number {
  return tabs.reduce((m, t) => Math.max(m, t.ver), 0);
}

export function withHome(tabs: Tab[]): Tab[] {
  const home = tabs.find((t) => t.path === HOME) ?? { path: HOME, fullPath: HOME, ver: maxVer(tabs) + 1 };
  return [home, ...tabs.filter((t) => t.path !== HOME)];
}

/** 打开或切到一个页签；从别的页面带参数进入已打开的页签时重新打开（版本加 1，页面重新加载）。 */
export function visit(
  tabs: Tab[],
  to: { path: string; fullPath: string; hasQuery: boolean },
  fromPath: string | null,
): Tab[] {
  const i = tabs.findIndex((t) => t.path === to.path);
  if (i < 0) return [...tabs, { path: to.path, fullPath: to.fullPath, ver: maxVer(tabs) + 1 }];
  const cur = tabs[i]!;
  if (cur.fullPath === to.fullPath) return tabs;
  const reopen = fromPath !== null && fromPath !== to.path && to.hasQuery;
  const next = { ...cur, fullPath: to.fullPath, ver: reopen ? maxVer(tabs) + 1 : cur.ver };
  return tabs.map((t, k) => (k === i ? next : t));
}

export function closeTab(tabs: Tab[], path: string, activePath: string): { tabs: Tab[]; next: Tab | null } {
  const i = tabs.findIndex((t) => t.path === path);
  if (path === HOME || i < 0) return { tabs, next: null };
  const rest = tabs.filter((_, k) => k !== i);
  const next = path === activePath ? (rest[i] ?? rest[i - 1] ?? null) : null;
  return { tabs: rest, next };
}

export const closeOthers = (tabs: Tab[], path: string) =>
  tabs.filter((t) => t.path === HOME || t.path === path);

export function closeRight(tabs: Tab[], path: string): Tab[] {
  const i = tabs.findIndex((t) => t.path === path);
  return i < 0 ? tabs : tabs.slice(0, i + 1);
}

export const closeAll = (tabs: Tab[]) => tabs.filter((t) => t.path === HOME);

export function refreshTab(tabs: Tab[], path: string): Tab[] {
  const ver = maxVer(tabs) + 1;
  return tabs.map((t) => (t.path === path ? { ...t, ver } : t));
}

export function moveTab(tabs: Tab[], from: number, to: number): Tab[] {
  if (from === to || from <= 0 || to <= 0 || from >= tabs.length || to >= tabs.length) return tabs;
  const out = [...tabs];
  const [t] = out.splice(from, 1);
  out.splice(to, 0, t!);
  return out;
}

export function restoreTabs(saved: unknown, uid: string | null, allowed: (path: string) => boolean): Tab[] {
  const s = (saved ?? {}) as { uid?: unknown; tabs?: unknown };
  if (uid === null || s.uid !== uid || !Array.isArray(s.tabs)) return withHome([]);
  const seen = new Set<string>();
  const tabs = (s.tabs as Tab[])
    .filter((t) => {
      const ok =
        t &&
        typeof t.path === 'string' &&
        typeof t.fullPath === 'string' &&
        Number.isFinite(t.ver) &&
        !seen.has(t.path) &&
        (t.path === HOME || allowed(t.path));
      if (ok) seen.add(t.path);
      return ok;
    })
    .map((t) => ({ path: t.path, fullPath: t.fullPath, ver: t.ver }));
  return withHome(tabs);
}
