import { createRouter, createWebHistory } from "vue-router";
import { useAuthStore } from "@/stores/auth";

/** 系统管理员（唯一）、普通操作员、审计员。 */
const ADMIN = ["admin"];
const OPS = ["admin", "operator"];
const AUDIT = ["admin", "auditor"];
const ALL = ["admin", "operator", "auditor"];

/**
 * 菜单按业务模块分组（工单、SN、箱号、托号、日志、账户、组织）；soon = 建设中的占位页（菜单标「建设中」）。
 * 新模块在这里加一组，页面放 views/<模块>/；已有页面的路径不变（页面间链接、书签照常可用）。
 */
export type MenuItem = { path: string; title: string; icon: string; roles: string[]; superAdmin?: boolean; soon?: boolean };
export type MenuGroup = { group: string; icon?: string; items: MenuItem[] };

export const MENU: MenuGroup[] = [
  { group: "nav.groupWork", items: [
    { path: "/", title: "nav.dashboard", icon: "Odometer", roles: ALL },
  ] },
  { group: "nav.groupOrder", icon: "Tickets", items: [
    { path: "/orders", title: "nav.orders", icon: "Tickets", roles: OPS },
  ] },
  { group: "nav.groupSn", icon: "Stamp", items: [
    { path: "/generate", title: "nav.generate", icon: "Finished", roles: OPS },
    { path: "/allocate", title: "nav.allocate", icon: "Promotion", roles: OPS },
    { path: "/transfer", title: "nav.transfer", icon: "Switch", roles: OPS },
    { path: "/query", title: "nav.query", icon: "Search", roles: ALL },
    { path: "/rules", title: "nav.rules", icon: "SetUp", roles: OPS },
  ] },
  { group: "nav.groupBox", icon: "Box", items: [
    { path: "/box/generate", title: "nav.boxGenerate", icon: "Finished", roles: OPS, soon: true },
    { path: "/box/transfer", title: "nav.boxTransfer", icon: "Switch", roles: OPS, soon: true },
    { path: "/box/query", title: "nav.boxQuery", icon: "Search", roles: ALL, soon: true },
    { path: "/box/rules", title: "nav.boxRules", icon: "SetUp", roles: OPS, soon: true },
  ] },
  { group: "nav.groupPallet", icon: "Van", items: [
    { path: "/pallet/generate", title: "nav.palletGenerate", icon: "Finished", roles: OPS, soon: true },
    { path: "/pallet/transfer", title: "nav.palletTransfer", icon: "Switch", roles: OPS, soon: true },
    { path: "/pallet/query", title: "nav.palletQuery", icon: "Search", roles: ALL, soon: true },
  ] },
  { group: "nav.groupLog", icon: "Notebook", items: [
    { path: "/audit", title: "nav.audit", icon: "Notebook", roles: AUDIT },
  ] },
  { group: "nav.groupAccount", icon: "User", items: [
    { path: "/users", title: "nav.users", icon: "User", roles: ADMIN, superAdmin: true },
    { path: "/user-groups", title: "nav.userGroups", icon: "UserFilled", roles: ADMIN, superAdmin: true },
  ] },
  { group: "nav.groupOrg", icon: "OfficeBuilding", items: [
    { path: "/factories", title: "nav.factories", icon: "OfficeBuilding", roles: ADMIN },
    { path: "/departments", title: "nav.departments", icon: "School", roles: OPS },
  ] },
];

const placeholder = () => import("@/views/Placeholder.vue");

const views: Record<string, () => Promise<unknown>> = {
  "/": () => import("@/views/Dashboard.vue"),
  "/orders": () => import("@/views/Orders.vue"),
  "/generate": () => import("@/views/Generate.vue"),
  "/allocate": () => import("@/views/Allocate.vue"),
  "/rules": () => import("@/views/Rules.vue"),
  "/transfer": () => import("@/views/Transfer.vue"),
  "/query": () => import("@/views/Query.vue"),
  "/audit": () => import("@/views/Audit.vue"),
  "/users": () => import("@/views/Users.vue"),
  "/user-groups": () => import("@/views/UserGroups.vue"),
  "/factories": () => import("@/views/Factories.vue"),
  "/departments": () => import("@/views/Departments.vue"),
};

const children = MENU.flatMap((g) => g.items.map((m) => ({ ...m, group: g.group }))).map((m) => ({
  path: m.path === "/" ? "" : m.path.slice(1),
  component: views[m.path] ?? placeholder,
  meta: { roles: m.roles, title: m.title, group: m.group, superAdmin: !!m.superAdmin, soon: !!m.soon },
}));

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/login", name: "login", component: () => import("@/views/Login.vue"), meta: { public: true } },
    { path: "/password", name: "password", component: () => import("@/views/ChangePassword.vue") },
    { path: "/", component: () => import("@/layouts/AppLayout.vue"), children },
    // SN 导出已并入 SN 分配（「已分配单据」页签）；旧链接、书签照常可用。审计员没有分配页，在 SN 查询导出
    { path: "/export", redirect: (to) => ({ path: useAuthStore().canAct ? "/allocate" : "/query", query: to.query.pi ? { pi: to.query.pi } : {} }) },
    { path: "/:pathMatch(.*)*", redirect: "/" },
  ],
});

router.beforeEach((to) => {
  const store = useAuthStore();
  if (to.meta.public) return true;
  if (!store.token) return { name: "login", query: { redirect: to.fullPath } };
  const roles = to.meta.roles as string[] | undefined;
  if (roles && !roles.includes(store.role)) return { path: "/" };
  if (to.meta.superAdmin && !store.isSuperAdmin) return { path: "/" };
  return true;
});

export default router;
