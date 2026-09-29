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
          <el-tag v-if="store.boundFactory" type="warning" effect="plain" class="fac">
            <el-icon><OfficeBuilding /></el-icon> {{ store.profile?.factory_name || store.boundFactory }}
          </el-tag>
          <el-tag v-else-if="store.isAdmin" effect="plain">{{ t("app.hq") }}</el-tag>
          <el-select :model-value="locale" size="small" class="lang" @change="onLocale">
            <el-option v-for="l in LOCALES" :key="l.value" :label="l.label" :value="l.value" />
          </el-select>
          <el-dropdown trigger="click" @command="onCommand">
            <span class="who">
              <el-avatar :size="28">{{ (store.profile?.name || "?").slice(0, 1) }}</el-avatar>
              <span class="who-name">{{ store.profile?.name }} · {{ roleLabel(store.role) }}</span>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>{{ t("app.empNo") }}：{{ store.profile?.emp_no }}</el-dropdown-item>
                <el-dropdown-item command="password" divided>{{ t("app.changePassword") }}</el-dropdown-item>
                <el-dropdown-item command="logout">{{ t("app.logout") }}</el-dropdown-item>
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
import { roleLabel } from "@/constants";
import { LOCALES, type Locale } from "@/i18n";
import { MENU } from "@/router";

const { t, locale } = useI18n();
const store = useAuthStore();
const route = useRoute();
const router = useRouter();
const collapsed = ref(false);

const menus = computed(() =>
  MENU.map((g) => ({ ...g, items: g.items.filter((m) => m.roles.includes(store.role)) })).filter((g) => g.items.length),
);
const activePath = computed(() => route.path);
const pageTitle = computed(() => {
  const title = route.meta.title as string | undefined;
  return title ? t(title) : "";
});

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
.left, .right { display: flex; align-items: center; gap: 10px; }
.lang { width: 118px; }
.who { display: inline-flex; align-items: center; gap: 8px; cursor: pointer; color: var(--app-text); font-size: 13px; }
.who-name { max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.fac { display: inline-flex; align-items: center; gap: 4px; }
.main { padding: 20px; background: var(--app-canvas); overflow: auto; }
@media (max-width: 760px) { .who-name, .lang { display: none; } }
</style>
