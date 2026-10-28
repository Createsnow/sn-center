<template>
  <div class="page">
    <PageHead :title="title" :desc="t('soon.desc')" />
    <div class="surface soon">
      <el-icon :size="40" class="icon"><component :is="icon" /></el-icon>
      <div class="body">
        <div class="head">
          <b>{{ title }}</b>
          <el-tag size="small" type="info" effect="plain">{{ t("nav.soon") }}</el-tag>
        </div>
        <p class="plan">{{ t(`soon.plan.${key}`) }}</p>
        <p class="muted">{{ t("soon.note") }}</p>
        <div v-if="store.canAct"><el-button type="primary" link @click="router.push('/generate')">{{ t("soon.toSn") }}</el-button></div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/** 建设中的模块（箱号管理、托号管理）占位页：说明规划功能，不调任何接口。 */
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import { useAuthStore } from "@/stores/auth";
import PageHead from "@/components/PageHead.vue";

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const store = useAuthStore();

/** /box/generate → boxGenerate */
const key = computed(() => route.path.slice(1).replace(/\/(\w)/g, (_, c: string) => c.toUpperCase()));
const title = computed(() => t(route.meta.title as string));
const icon = computed(() => (route.path.startsWith("/pallet") ? "Van" : "Box"));
</script>

<style scoped>
.soon { display: flex; gap: 20px; align-items: flex-start; padding: 28px; }
.icon { color: var(--app-muted); flex: none; }
.body { display: flex; flex-direction: column; gap: 8px; min-width: 0; }
.head { display: flex; align-items: center; gap: 8px; font-size: 16px; }
.plan { margin: 0; line-height: 1.7; }
.muted { margin: 0; }
</style>
