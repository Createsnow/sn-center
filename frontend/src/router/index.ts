import { createRouter, createWebHistory } from "vue-router";
import { useAuthStore } from "@/stores/auth";

const ADMIN = ["admin"];
const ALL = ["admin", "factory_operator", "query"];

export const MENU = [
  { group: "nav.groupWork", items: [
    { path: "/", title: "nav.dashboard", icon: "Odometer", roles: ALL },
  ] },
  { group: "nav.groupHq", items: [
    { path: "/orders", title: "nav.orders", icon: "Tickets", roles: ["admin", "query"] },
    { path: "/generate", title: "nav.generate", icon: "Finished", roles: ADMIN },
    { path: "/allocate", title: "nav.allocate", icon: "Promotion", roles: ADMIN },
    { path: "/rules", title: "nav.rules", icon: "SetUp", roles: ADMIN },
  ] },
  { group: "nav.groupFactory", items: [
    { path: "/export", title: "nav.export", icon: "Download", roles: ALL },
    { path: "/transfer", title: "nav.transfer", icon: "Switch", roles: ALL },
  ] },
  { group: "nav.groupQuery", items: [
    { path: "/query", title: "nav.query", icon: "Search", roles: ALL },
    { path: "/audit", title: "nav.audit", icon: "Notebook", roles: ALL },
  ] },
  { group: "nav.groupSystem", items: [
    { path: "/users", title: "nav.users", icon: "User", roles: ADMIN, superAdmin: true },
    { path: "/factories", title: "nav.factories", icon: "OfficeBuilding", roles: ADMIN },
  ] },
];

const views: Record<string, () => Promise<unknown>> = {
  "/": () => import("@/views/Dashboard.vue"),
  "/orders": () => import("@/views/Orders.vue"),
  "/generate": () => import("@/views/Generate.vue"),
  "/allocate": () => import("@/views/Allocate.vue"),
  "/rules": () => import("@/views/Rules.vue"),
  "/export": () => import("@/views/SnExport.vue"),
  "/transfer": () => import("@/views/Transfer.vue"),
  "/query": () => import("@/views/Query.vue"),
  "/audit": () => import("@/views/Audit.vue"),
  "/users": () => import("@/views/Users.vue"),
  "/factories": () => import("@/views/Factories.vue"),
};

const children = MENU.flatMap((g) => g.items).map((m) => ({
  path: m.path === "/" ? "" : m.path.slice(1),
  component: views[m.path],
  meta: { roles: m.roles, title: m.title, superAdmin: "superAdmin" in m && m.superAdmin },
}));

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/login", name: "login", component: () => import("@/views/Login.vue"), meta: { public: true } },
    { path: "/password", name: "password", component: () => import("@/views/ChangePassword.vue") },
    { path: "/", component: () => import("@/layouts/AppLayout.vue"), children },
    // 原「页面领取」页已改为「SN 导出」，旧书签跳到新页
    { path: "/acquire", redirect: (to) => ({ path: "/export", query: to.query }) },
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
