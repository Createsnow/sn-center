import test from "node:test";
import assert from "node:assert/strict";
import { HOME, closeAll, closeOthers, closeRight, closeTab, moveTab, refreshTab, restoreTabs, visit, withHome } from "./tabList.js";

const tab = (path, ver = 1, fullPath = path) => ({ path, fullPath, ver });
const paths = (tabs) => tabs.map((t) => t.path);
const go = (path, fullPath = path) => ({ path, fullPath, hasQuery: fullPath.includes("?") });

test("visit appends new pages at the end and reuses open ones", () => {
  let tabs = withHome([]);
  tabs = visit(tabs, go("/generate"), HOME);
  tabs = visit(tabs, go("/query"), "/generate");
  assert.deepEqual(paths(tabs), [HOME, "/generate", "/query"]);
  const again = visit(tabs, go("/generate"), "/query");
  assert.equal(again, tabs, "same address: nothing changes");
  assert.ok(new Set(tabs.map((t) => t.ver)).size === 3, "each tab gets its own version");
});

test("visit keeps the page when its own query changes, reopens it on a cross-page jump with new params", () => {
  let tabs = [tab(HOME, 1), tab("/allocate", 2, "/allocate?tab=TO_ALLOCATE"), tab("/query", 3)];
  // 页内切换参数（如分配页切页签）：同一实例，只记地址
  tabs = visit(tabs, go("/allocate", "/allocate?tab=ALLOCATED"), "/allocate");
  assert.equal(tabs[1].ver, 2);
  assert.equal(tabs[1].fullPath, "/allocate?tab=ALLOCATED");
  // 点页签或菜单回到上次的地址：同一实例
  tabs = visit(tabs, go("/allocate", "/allocate?tab=ALLOCATED"), "/query");
  assert.equal(tabs[1].ver, 2);
  // 首页带参数跳来：按新参数重新打开
  tabs = visit(tabs, go("/query", "/query?tab=alerts"), HOME);
  assert.equal(tabs[2].ver, 4);
  // 不带参数跳来（如菜单回到无参地址）：同一实例
  tabs = visit(tabs, go("/query"), HOME);
  assert.equal(tabs[2].ver, 4);
  // F5 后首次进入：不重开
  const first = visit([tab(HOME), tab("/query", 5, "/query?tab=sn")], go("/query", "/query?tab=alerts"), null);
  assert.equal(first[1].ver, 5);
});

test("closeTab activates the right neighbour, then the left one; home cannot be closed", () => {
  const tabs = [tab(HOME, 1), tab("/a", 2), tab("/b", 3), tab("/c", 4)];
  let r = closeTab(tabs, "/b", "/b");
  assert.deepEqual(paths(r.tabs), [HOME, "/a", "/c"]);
  assert.equal(r.next.path, "/c");
  r = closeTab(tabs, "/c", "/c");
  assert.equal(r.next.path, "/b");
  r = closeTab(tabs, "/a", "/c");
  assert.equal(r.next, null, "closing a background tab keeps the current page");
  r = closeTab(tabs, HOME, HOME);
  assert.equal(r.tabs, tabs);
});

test("bulk close keeps home", () => {
  const tabs = [tab(HOME, 1), tab("/a", 2), tab("/b", 3), tab("/c", 4)];
  assert.deepEqual(paths(closeOthers(tabs, "/b")), [HOME, "/b"]);
  assert.deepEqual(paths(closeRight(tabs, "/a")), [HOME, "/a"]);
  assert.deepEqual(paths(closeRight(tabs, HOME)), [HOME]);
  assert.deepEqual(paths(closeAll(tabs)), [HOME]);
});

test("refreshTab gives a new version only to that tab", () => {
  const tabs = refreshTab([tab(HOME, 1), tab("/a", 2), tab("/b", 3)], "/a");
  assert.deepEqual(tabs.map((t) => t.ver), [1, 4, 3]);
});

test("moveTab never moves home or puts a tab before it", () => {
  const tabs = [tab(HOME), tab("/a"), tab("/b"), tab("/c")];
  assert.deepEqual(paths(moveTab(tabs, 3, 1)), [HOME, "/c", "/a", "/b"]);
  assert.deepEqual(paths(moveTab(tabs, 1, 3)), [HOME, "/b", "/c", "/a"]);
  assert.equal(moveTab(tabs, 0, 2), tabs);
  assert.equal(moveTab(tabs, 2, 0), tabs);
});

test("restoreTabs only trusts the same account and allowed pages", () => {
  const saved = { uid: 7, tabs: [tab("/a", 3), tab("/x", 4), tab("/a", 5), { path: 1 }, tab(HOME, 2, "/?k=1")] };
  const allowed = (p) => p !== "/x";
  const tabs = restoreTabs(saved, 7, allowed);
  assert.deepEqual(paths(tabs), [HOME, "/a"]);
  assert.equal(tabs[0].fullPath, "/?k=1");
  assert.deepEqual(paths(restoreTabs(saved, 8, allowed)), [HOME], "another account starts fresh");
  assert.deepEqual(paths(restoreTabs(null, 7, allowed)), [HOME]);
  assert.deepEqual(paths(restoreTabs({ uid: 7, tabs: "bad" }, 7, allowed)), [HOME]);
});
