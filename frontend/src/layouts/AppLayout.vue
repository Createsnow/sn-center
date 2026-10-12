<template>
  <el-container class="shell">
    <el-aside :width="collapsed ? '64px' : '220px'" class="aside">
      <div class="brand" :class="{ mini: collapsed }">
        <el-icon :size="22"><Stamp /></el-icon>
        <div v-if="!collapsed" class="brand-text">
          <div class="brand-title">{{ t("app.short") }}</div>
          <div class="brand-sub">{{ t("app.subtitle") }}</div>
        </div>
      </div>
      <el-scrollbar>
        <el-menu :default-active="activePath" :collapse="collapsed" router class="menu">
          <template v-for="g in menus" :key="g.group">
            <div v-if="!collapsed" class="menu-group">{{ t(g.group) }}</div>
            <el-menu-item v-for="m in g.items" :key="m.path" :index="m.path">
              <el-icon><component :is="m.icon" /></el-icon>
              <template #title>{{ t(m.title) }}</template>
            </el-menu-item>
          </template>
        </el-menu>
      </el-scrollbar>
    </el-aside>
    <el-container direction="vertical" class="body">
      <el-header class="header">
        <div class="left">
          <el-button text @click="collapsed = !collapsed">
            <el-icon :size="18"><component :is="collapsed ? 'Expand' : 'Fold'" /></el-icon>
          </el-button>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item>{{ t("app.short") }}</el-breadcrumb-item>
            <el-breadcrumb-item>{{ pageTitle }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="right">
          <el-tooltip :content="scope.tip" placement="bottom">
            <span class="scope" :class="scope.kind">
              <el-icon><component :is="scope.icon" /></el-icon>
              <span class="scope-text">{{ scope.label }}</span>
              <span v-if="scope.readonly" class="scope-ro">{{ t("app.readonly") }}</span>
            </span>
          </el-tooltip>
          <el-dropdown trigger="click" @command="onLocale">
            <el-button text class="lang" :title="t('app.language')">
              <svg class="globe" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
                <circle cx="12" cy="12" r="9" /><path d="M3 12h18M12 3c2.5 2.6 3.8 5.6 3.8 9s-1.3 6.4-3.8 9c-2.5-2.6-3.8-5.6-3.8-9S9.5 5.6 12 3z" />
              </svg>
              <span class="lang-code">{{ localeShort }}</span>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-for="l in LOCALES" :key="l.value" :command="l.value" :class="{ 'is-current': l.value === locale }">
                  <span class="lang-item">{{ l.label }}<el-icon v-if="l.value === locale"><Check /></el-icon></span>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-dropdown trigger="click" placement="bottom-end" @command="onCommand">
            <span class="who">
              <el-avatar :size="30" class="avatar" :class="scope.kind">{{ initial }}</el-avatar>
              <span class="who-text">
                <span class="who-name">{{ store.profile?.name }}</span>
                <span class="who-role">{{ roleShort }}</span>
              </span>
              <el-icon class="who-caret"><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <div class="card">
                <el-avatar :size="40" class="avatar" :class="scope.kind">{{ initial }}</el-avatar>
                <div class="card-main">
                  <div class="card-name">{{ store.profile?.name }}</div>
                  <div class="card-line">{{ t("app.empNo") }} {{ store.profile?.emp_no }}</div>
                  <div class="card-tags">
                    <el-tag size="small" effect="plain">{{ roleShort }}</el-tag>
                    <el-tag size="small" effect="plain" :type="scope.kind === 'factory' ? 'warning' : 'info'">{{ scope.label }}</el-tag>
                    <el-tag v-if="scope.readonly" size="small" type="info">{{ t("app.readonly") }}</el-tag>
                  </div>
                </div>
              </div>
              <el-dropdown-menu>
                <el-dropdown-item command="password" divided><el-icon><Lock /></el-icon>{{ t("app.changePassword") }}</el-dropdown-item>
                <el-dropdown-item command="logout"><el-icon><SwitchButton /></el-icon>{{ t("app.logout") }}</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main class="main">
        <router-view v-slot="{ Component }">
          <transition name="page" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import { useAuthStore } from "@/stores/auth";
import { LOCALES, type Locale } from "@/i18n";
import { MENU } from "@/router";

const { t, locale } = useI18n();
const store = useAuthStore();
const route = useRoute();
const router = useRouter();
const collapsed = ref(false);

const menus = computed(() =>
  MENU.map((g) => ({ ...g, items: g.items.filter((m) => m.roles.includes(store.role) && (!("superAdmin" in m) || store.isSuperAdmin)) })).filter((g) => g.items.length),
);
const activePath = computed(() => route.path);
const pageTitle = computed(() => {
  const title = route.meta.title as string | undefined;
  return title ? t(title) : "";
});

/** 数据范围：总部 / 本厂 / 全部工厂（只读）/ 本厂（只读），角色与范围分开展示，不再重复。 */
const scope = computed(() => {
  const factory = store.profile?.factory_name || store.boundFactory;
  if (store.isAdmin) return { kind: "hq", icon: "OfficeBuilding", label: t("app.hq"), tip: t("app.scopeTip.hq"), readonly: false };
  if (store.isFactory) return { kind: "factory", icon: "School", label: factory, tip: t("app.scopeTip.factory"), readonly: false };
  if (store.boundFactory) return { kind: "factory", icon: "School", label: factory, tip: t("app.scopeTip.queryFactory"), readonly: true };
  return { kind: "all", icon: "Search", label: t("app.allFactories"), tip: t("app.scopeTip.queryAll"), readonly: true };
});
const roleShort = computed(() => {
  if (store.isSuperAdmin) return t("app.superAdmin");
  const key = `app.roleShort.${store.role}`;
  const s = t(key);
  return s === key ? store.role : s;
});
const initial = computed(() => (store.profile?.name || "?").trim().slice(0, 1).toUpperCase());
const localeShort = computed(() => ({ "zh-CN": "中", en: "EN", vi: "VI" })[locale.value as Locale] ?? locale.value);

function onLocale(v: Locale) {
  store.changeLocale(v);
}

function onCommand(cmd: string) {
  if (cmd === "password") router.push("/password");
  if (cmd === "logout") {
    store.clear();
    router.push("/login");
  }
}

function onResize() {
  collapsed.value = window.innerWidth < 1100;
}

onMounted(() => {
  onResize();
  window.addEventListener("resize", onResize);
  store.refresh().catch(() => undefined);
});
onUnmounted(() => window.removeEventListener("resize", onResize));
</script>

<style scoped>
.shell { height: 100vh; overflow: hidden; }
.aside { background: var(--app-ink); color: #fff; display: flex; flex-direction: column; transition: width 0.18s ease; overflow: hidden; }
.brand { display: flex; align-items: center; gap: 10px; padding: 16px; color: #fff; }
.brand.mini { justify-content: center; padding: 16px 0; }
.brand-title { font-weight: 700; font-size: 15px; white-space: nowrap; }
.brand-sub { font-size: 11px; color: #94a3b8; margin-top: 2px; white-space: nowrap; }
.menu { border-right: none; background: transparent; --el-menu-bg-color: transparent; --el-menu-text-color: #cbd5e1;
  --el-menu-hover-bg-color: #1e293b; --el-menu-active-color: #fff; --el-menu-item-height: 44px; }
.menu :deep(.el-menu-item.is-active) { background: #1e4a8a; }
.menu-group { color: #64748b; font-size: 11px; letter-spacing: 0.08em; padding: 14px 20px 6px; text-transform: uppercase; }
.body { min-width: 0; }
.header { height: var(--app-header); background: var(--app-surface); border-bottom: 1px solid var(--app-line);
  display: flex; align-items: center; justify-content: space-between; padding: 0 16px; }
.left, .right { display: flex; align-items: center; gap: 10px; min-width: 0; }
.right { gap: 6px; }
.scope { display: inline-flex; align-items: center; gap: 6px; height: 28px; padding: 0 10px; border-radius: 14px;
  font-size: 12px; font-weight: 500; border: 1px solid; cursor: default; max-width: 240px; }
.scope-text { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.scope.hq { color: #1e4a8a; background: #eef4fd; border-color: #c9dbf5; }
.scope.factory { color: #a15c07; background: #fff7eb; border-color: #f5dcb0; }
.scope.all { color: #475569; background: #f1f5f9; border-color: #dbe2ea; }
.scope-ro { font-size: 11px; padding: 0 6px; line-height: 18px; border-radius: 9px; background: rgba(100, 116, 139, 0.14); color: #475569; }
.lang { padding: 0 8px; height: 32px; color: var(--app-muted); }
.globe { width: 16px; height: 16px; }
.lang-code { margin-left: 4px; font-size: 12px; font-weight: 600; }
.lang-item { display: inline-flex; align-items: center; justify-content: space-between; gap: 16px; min-width: 110px; }
.is-current { color: var(--el-color-primary); font-weight: 600; }
.who { display: inline-flex; align-items: center; gap: 8px; cursor: pointer; color: var(--app-text);
  padding: 4px 8px 4px 4px; border-radius: 20px; transition: background 0.15s; }
.who:hover { background: var(--app-canvas); }
.avatar { flex: none; font-weight: 600; color: #fff; }
.avatar.hq { background: #1e4a8a; }
.avatar.factory { background: #c27410; }
.avatar.all { background: #64748b; }
.who-text { display: flex; flex-direction: column; line-height: 1.2; min-width: 0; }
.who-name { font-size: 13px; font-weight: 600; max-width: 140px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.who-role { font-size: 11px; color: var(--app-muted); white-space: nowrap; }
.who-caret { color: var(--app-muted); }
.card { display: flex; gap: 12px; padding: 14px 16px 10px; min-width: 240px; }
.card-name { font-weight: 600; font-size: 14px; color: var(--app-text); }
.card-line { font-size: 12px; color: var(--app-muted); margin-top: 2px; }
.card-tags { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 8px; }
.main { padding: 20px; background: var(--app-canvas); overflow: auto; }
@media (max-width: 760px) { .who-text, .scope-text, .globe { width: 16px; height: 16px; }
.lang-code { display: none; } .scope { padding: 0 8px; } }
</style>
