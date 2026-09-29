<template>
  <div class="page">
    <PageHead :title="t('dashboard.title', { name: store.profile?.name || '' })" :desc="scopeDesc" />
    <div class="kpi-grid">
      <div v-for="k in kpis" :key="k.key" class="kpi">
        <div class="v">{{ fmt(k.value) }}</div>
        <div class="l">{{ k.label }}</div>
      </div>
    </div>
    <el-row :gutter="16">
      <el-col :md="12" :xs="24">
        <div class="surface">
          <div class="surface__title">{{ t("dashboard.today") }}</div>
          <el-table :data="todayRows" size="small" :empty-text="t('common.empty')">
            <el-table-column :label="t('audit.action')"><template #default="{ row }">{{ actionLabel(row.action) }}</template></el-table-column>
            <el-table-column :label="t('common.qty')" align="right"><template #default="{ row }"><span class="num">{{ fmt(row.qty) }}</span></template></el-table-column>
          </el-table>
        </div>
      </el-col>
      <el-col :md="12" :xs="24">
        <div class="surface">
          <div class="surface__title">{{ t("dashboard.shortcuts") }}</div>
          <div class="shortcuts">
            <el-button v-for="s in shortcuts" :key="s.path" @click="router.push(s.path)">
              <el-icon><component :is="s.icon" /></el-icon><span>{{ t(s.title) }}</span>
            </el-button>
          </div>
          <el-alert v-if="(data?.pending_transfers || 0) > 0" type="warning" :closable="false" show-icon class="mt"
            :title="t('dashboard.pendingTransfers', { n: data?.pending_transfers })" />
        </div>
      </el-col>
    </el-row>
    <div v-if="store.isAdmin" class="surface">
      <div class="surface__title">{{ t("dashboard.recentJobs") }}</div>
      <el-table :data="data?.recent_jobs || []" size="small" :empty-text="t('common.empty')">
        <el-table-column prop="id" label="#" width="70" />
        <el-table-column prop="bill_no" :label="t('common.billNo')" />
        <el-table-column prop="pi_no" label="PI" />
        <el-table-column prop="factory_code" :label="t('common.factory')" />
        <el-table-column :label="t('generate.progress')" width="220">
          <template #default="{ row }">
            <el-progress :percentage="row.qty ? Math.round((row.done_qty * 100) / row.qty) : 0"
              :status="row.status === 'SUCCESS' ? 'success' : row.status === 'FAILED' ? 'exception' : undefined" />
          </template>
        </el-table-column>
        <el-table-column prop="created_at" :label="t('common.time')" width="170" />
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useAuthStore } from "@/stores/auth";
import { actionLabel, statusLabel } from "@/constants";
import { MENU } from "@/router";
import PageHead from "@/components/PageHead.vue";

const { t } = useI18n();
const router = useRouter();
const store = useAuthStore();
const data = ref<any>(null);

const fmt = (n: number | undefined) => (n ?? 0).toLocaleString();
const scopeDesc = computed(() =>
  store.boundFactory ? t("dashboard.scopeFactory", { f: store.profile?.factory_name || store.boundFactory }) : t("dashboard.scopeAll"),
);
const kpis = computed(() => {
  const c = data.value?.status_counts || {};
  const rows = [
    ...(store.isAdmin ? [{ key: "PENDING_ALLOC", label: statusLabel("PENDING_ALLOC"), value: c.PENDING_ALLOC }] : []),
    { key: "TO_ACQUIRE", label: statusLabel("TO_ACQUIRE"), value: c.TO_ACQUIRE },
    { key: "TO_PRINT", label: statusLabel("TO_PRINT"), value: c.TO_PRINT },
    { key: "APPLYING", label: statusLabel("APPLYING"), value: c.APPLYING },
    { key: "transfers", label: t("dashboard.pendingTransferKpi"), value: data.value?.pending_transfers },
  ];
  if (!store.boundFactory) rows.push({ key: "total", label: t("dashboard.totalGenerated"), value: data.value?.total_generated });
  return rows;
});
const todayRows = computed(() =>
  Object.entries(data.value?.today_actions || {}).map(([action, qty]) => ({ action, qty: qty as number })),
);
const shortcuts = computed(() =>
  MENU.flatMap((g) => g.items).filter((m) => m.path !== "/" && m.roles.includes(store.role)).slice(0, 6),
);

onMounted(async () => {
  data.value = await api.dashboard();
});
</script>

<style scoped>
.shortcuts { display: flex; flex-wrap: wrap; gap: 8px; }
.shortcuts .el-button { margin: 0; }
.shortcuts span { margin-left: 4px; }
.mt { margin-top: 12px; }
.el-col { margin-bottom: 16px; }
</style>
