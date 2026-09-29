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
    { path: "/pi-init", title: "nav.piInit", icon: "Upload", roles: ADMIN },
    { path: "/rules", title: "nav.rules", icon: "SetUp", roles: ADMIN },
  ] },
  { group: "nav.groupFactory", items: [
    { path: "/acquire", title: "nav.acquire", icon: "Printer", roles: ALL },
    { path: "/transfer", title: "nav.transfer", icon: "Switch", roles: ALL },
  ] },
  { group: "nav.groupQuery", items: [
    { path: "/query", title: "nav.query", icon: "Search", roles: ALL },
    { path: "/audit", title: "nav.audit", icon: "Notebook", roles: ALL },
  ] },
  { group: "nav.groupSystem", items: [
    { path: "/users", title: "nav.users", icon: "User", roles: ADMIN },
    { path: "/factories", title: "nav.factories", icon: "OfficeBuilding", roles: ADMIN },
  ] },
];

const views: Record<string, () => Promise<unknown>> = {
  "/": () => import("@/views/Dashboard.vue"),
  "/orders": () => import("@/views/Orders.vue"),
  "/generate": () => import("@/views/Generate.vue"),
  "/pi-init": () => import("@/views/PiInit.vue"),
  "/rules": () => import("@/views/Rules.vue"),
  "/acquire": () => import("@/views/Acquire.vue"),
  "/transfer": () => import("@/views/Transfer.vue"),
  "/query": () => import("@/views/Query.vue"),
  "/audit": () => import("@/views/Audit.vue"),
  "/users": () => import("@/views/Users.vue"),
  "/factories": () => import("@/views/Factories.vue"),
};

const children = MENU.flatMap((g) => g.items).map((m) => ({
  path: m.path === "/" ? "" : m.path.slice(1),
  component: views[m.path],
  meta: { roles: m.roles, title: m.title },
}));

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/login", name: "login", component: () => import("@/views/Login.vue"), meta: { public: true } },
    { path: "/password", name: "password", component: () => import("@/views/ChangePassword.vue") },
    { path: "/", component: () => import("@/layouts/AppLayout.vue"), children },
    { path: "/:pathMatch(.*)*", redirect: "/" },
  ],
});

router.beforeEach((to) => {
  const store = useAuthStore();
  if (to.meta.public) return true;
  if (!store.token) return { name: "login", query: { redirect: to.fullPath } };
  if (store.mustChangePwd && to.name !== "password") return { name: "password" };
  const roles = to.meta.roles as string[] | undefined;
  if (roles && !roles.includes(store.role)) return { path: "/" };
  return true;
});

export default router;
