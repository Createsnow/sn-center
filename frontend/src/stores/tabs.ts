import { defineStore } from "pinia";
import { ref, watch } from "vue";
import * as list from "@/tabList.js";

export type Tab = list.Tab;

/** 本浏览器标签页内保存（F5 刷新后恢复；关掉浏览器标签页即清空），按账户区分。 */
const KEY = "sn_tabs";

function readSaved(): unknown {
  try {
    return JSON.parse(sessionStorage.getItem(KEY) || "null");
  } catch {
    return null;
  }
}

/** 顶栏页签：左侧菜单每打开一个功能就加一个页签，用户手动关闭前一直保留。纯逻辑见 tabList.js。 */
export const useTabsStore = defineStore("tabs", () => {
  const tabs = ref<Tab[]>(list.withHome([]));
  let uid: number | null = null;

  watch(tabs, (v) => {
    if (uid == null) return;
    try {
      sessionStorage.setItem(KEY, JSON.stringify({ uid, tabs: v }));
    } catch {
      /* 存不了只是刷新后不恢复 */
    }
  });

  /** 进入主框架时读回本账户保存的页签；allowed 过滤掉当前账户无权访问的页面。 */
  function restore(userId: number | null | undefined, allowed: (path: string) => boolean) {
    uid = userId ?? null;
    tabs.value = list.restoreTabs(readSaved(), uid, allowed);
  }

  /** 退出登录：清空页签。 */
  function reset() {
    uid = null;
    tabs.value = list.withHome([]);
    try {
      sessionStorage.removeItem(KEY);
    } catch {
      /* ignore */
    }
  }

  const find = (path: string) => tabs.value.find((t) => t.path === path);
  const visit = (to: { path: string; fullPath: string; hasQuery: boolean }, fromPath: string | null) => {
    tabs.value = list.visit(tabs.value, to, fromPath);
  };
  /** 关闭一个页签，返回需要切换到的页签（关的不是当前页时为 null）。 */
  function close(path: string, activePath: string) {
    const r = list.closeTab(tabs.value, path, activePath);
    tabs.value = r.tabs;
    return r.next;
  }
  const closeOthers = (path: string) => (tabs.value = list.closeOthers(tabs.value, path));
  const closeRight = (path: string) => (tabs.value = list.closeRight(tabs.value, path));
  const closeAll = () => (tabs.value = list.closeAll(tabs.value));
  const refresh = (path: string) => (tabs.value = list.refreshTab(tabs.value, path));
  const move = (from: number, to: number) => (tabs.value = list.moveTab(tabs.value, from, to));

  return { tabs, restore, reset, find, visit, close, closeOthers, closeRight, closeAll, refresh, move };
});
