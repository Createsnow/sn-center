<template>
  <div class="page">
    <PageHead :title="t('dashboard.title', { name: store.profile?.name || '' })" :desc="scopeDesc">
      <el-button :loading="loading" @click="load"><el-icon><Refresh /></el-icon><span>{{ t("common.refresh") }}</span></el-button>
    </PageHead>

    <div class="kpi-grid">
      <div v-for="k in kpis" :key="k.key" class="kpi" :class="{ link: !!k.to, alarm: k.alarm && (k.value || 0) > 0 }" @click="k.to && router.push(k.to)">
        <div class="v">{{ num(k.value) }}</div>
        <div class="l">{{ k.label }}</div>
      </div>
    </div>
    <p class="muted delay">{{ t("dashboard.delayNote") }}</p>

    <el-row :gutter="16">
      <el-col :md="12" :xs="24">
        <div class="surface">
          <div class="surface__title">
            <span>{{ t("dashboard.today") }}</span>
            <span class="muted">{{ t("dashboard.todaySince", { time: fmt(data?.today_start, false), tz: tzLabel(data?.tz_offset_min) }) }}</span>
          </div>
          <el-table :data="todayRows" size="small" :empty-text="t('common.empty')">
            <el-table-column :label="t('dashboard.item')">
              <template #default="{ row }">{{ row.label }}</template>
            </el-table-column>
            <el-table-column :label="t('common.qty')" align="right" width="120">
              <template #default="{ row }"><span class="num">{{ num(row.qty) }}</span></template>
            </el-table-column>
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
          <el-alert v-if="(data?.order_alerts || 0) > 0" type="warning" :closable="false" show-icon class="mt"
            :title="t('dashboard.orderAlerts', { n: data?.order_alerts })" />
        </div>
      </el-col>
    </el-row>

    <div v-if="store.isAdmin" class="surface">
      <div class="surface__title">
        <span>{{ t("dashboard.recentFailures") }}</span>
        <el-button link type="primary" @click="router.push({ path: '/query', query: { tab: 'delivery' } })">{{ t("dashboard.toBatches") }}</el-button>
      </div>
      <el-table :data="data?.recent_failures || []" size="small" :empty-text="t('common.empty')">
        <el-table-column :label="t('common.batchNo')" min-width="200">
          <template #default="{ row }">
            <el-button link type="primary" class="sn-mono" @click="openBatch(row.batch_no)">{{ row.batch_no }}</el-button>
          </template>
        </el-table-column>
        <el-table-column :label="t('dashboard.batchKind')" width="120">
          <template #default="{ row }">{{ batchSourceLabel(row.source) }} · {{ batchTypeLabel(row.batch_type) }}</template>
        </el-table-column>
        <el-table-column prop="factory_code" :label="t('common.factory')" min-width="110" show-overflow-tooltip />
        <el-table-column prop="pi_no" label="PI" min-width="120" show-overflow-tooltip />
        <el-table-column :label="t('common.qty')" align="right" width="70">
          <template #default="{ row }"><span class="num">{{ row.qty }}</span></template>
        </el-table-column>
        <el-table-column :label="t('common.status')" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="BATCH_RESULT_TYPE[row.result] || 'warning'">{{ batchResultLabel(row.result) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('dashboard.failReason')" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.fail_reason || (row.source === 'PULL' ? t("dashboard.pullNoReceipt") : "—") }}</template>
        </el-table-column>
        <el-table-column :label="t('common.time')" width="150">
          <template #default="{ row }">{{ fmt(row.last_push_at || row.pulled_at || row.created_at) }}</template>
        </el-table-column>
      </el-table>
    </div>

    <div v-if="store.isAdmin" class="surface">
      <div class="surface__title">
        <span>{{ t("dashboard.deliveryAlerts") }}</span>
        <el-button link type="primary" @click="router.push({ path: '/query', query: { tab: 'alerts' } })">{{ t("dashboard.toAlerts") }}</el-button>
      </div>
      <el-table :data="data?.open_delivery_alerts || []" size="small" :empty-text="t('common.empty')">
        <el-table-column :label="t('dashboard.alertType')" width="130">
          <template #default="{ row }"><el-tag size="small" type="danger">{{ t(`dashboard.dalert.${row.alert_type}`) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="factory_code" :label="t('common.factory')" min-width="110" show-overflow-tooltip />
        <el-table-column :label="t('common.batchNo')" min-width="190">
          <template #default="{ row }">
            <el-button v-if="row.batch_no" link type="primary" class="sn-mono" @click="openBatch(row.batch_no)">{{ row.batch_no }}</el-button>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.sn')" min-width="170">
          <template #default="{ row }"><span class="sn-mono">{{ row.sn || "—" }}</span></template>
        </el-table-column>
        <el-table-column prop="detail" :label="t('dashboard.alertDetail')" min-width="220" show-overflow-tooltip />
        <el-table-column :label="t('common.time')" width="150">
          <template #default="{ row }">{{ fmt(row.created_at) }}</template>
        </el-table-column>
      </el-table>
    </div>

    <div v-if="store.isAdmin" class="surface">
      <div class="surface__title">{{ t("dashboard.recentJobs") }}</div>
      <el-table :data="data?.recent_jobs || []" size="small" :empty-text="t('common.empty')">
        <el-table-column prop="gen_batch" :label="t('dashboard.genBatch')" min-width="200" show-overflow-tooltip />
        <el-table-column label="PI" min-width="130">
          <template #default="{ row }">
            <el-button link type="primary" @click="router.push({ path: '/generate', query: { pi: row.pi_no } })">{{ row.pi_no }}</el-button>
          </template>
        </el-table-column>
        <el-table-column :label="t('dashboard.progress')" width="220">
          <template #default="{ row }">
            <el-progress :percentage="row.qty ? Math.round((row.done_qty * 100) / row.qty) : 0"
              :status="row.status === 'SUCCESS' ? 'success' : row.status === 'FAILED' ? 'exception' : row.status === 'RUNNING' ? undefined : 'warning'" />
          </template>
        </el-table-column>
        <el-table-column :label="t('common.status')" width="100">
          <template #default="{ row }">{{ t(`dashboard.jobStatus.${row.status}`) }}</template>
        </el-table-column>
        <el-table-column :label="t('common.qty')" align="right" width="80">
          <template #default="{ row }"><span class="num">{{ num(row.qty) }}</span></template>
        </el-table-column>
        <el-table-column prop="created_by" :label="t('common.operator')" width="100" />
        <el-table-column :label="t('common.time')" width="150">
          <template #default="{ row }">{{ fmt(row.created_at) }}</template>
        </el-table-column>
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
import { useTime } from "@/composables/useTime";
import { BATCH_RESULT_TYPE, batchResultLabel, batchSourceLabel, batchTypeLabel, statusLabel } from "@/constants";
import { tzLabel } from "@/utils/time";
import { MENU } from "@/router";
import PageHead from "@/components/PageHead.vue";

const { t } = useI18n();
const router = useRouter();
const store = useAuthStore();
const { fmt } = useTime();
const data = ref<any>(null);
const loading = ref(false);

const num = (n: number | null | undefined) => (n ?? 0).toLocaleString();
const scopeDesc = computed(() => (store.bound ? t("dashboard.scopeFactory", { f: store.scopeNames }) : t("dashboard.scopeAll")));

type Kpi = { key: string; label: string; value: number | undefined; to?: string; alarm?: boolean };
const kpis = computed<Kpi[]>(() => {
  const d = data.value || {};
  const c = d.status_counts || {};
  const alerts = Object.values(d.delivery_alerts || {}).reduce((a: number, b) => a + Number(b || 0), 0);
  const rows: Kpi[] = [];
  if ("PENDING_ALLOC" in c) rows.push({ key: "PENDING_ALLOC", label: statusLabel("PENDING_ALLOC"), value: c.PENDING_ALLOC, to: "/allocate" });
  rows.push(
    { key: "TO_PUSH", label: statusLabel("TO_PUSH"), value: c.TO_PUSH },
    { key: "ALLOCATED", label: statusLabel("ALLOCATED"), value: c.ALLOCATED, to: "/export" },
    { key: "APPLYING", label: statusLabel("APPLYING"), value: c.APPLYING, to: "/transfer" },
    { key: "suspended", label: t("dashboard.suspended"), value: d.suspended, alarm: true },
    { key: "interrupted", label: t("dashboard.pushInterrupted"), value: d.push_interrupted, alarm: true, to: "/query?tab=delivery" },
    { key: "pull", label: t("dashboard.pullUnconfirmed"), value: d.pull_unconfirmed, alarm: true, to: "/query?tab=delivery" },
    { key: "transfers", label: t("dashboard.pendingTransferKpi"), value: d.pending_transfers, to: "/transfer?status=PENDING" },
    { key: "dalerts", label: t("dashboard.deliveryAlertKpi"), value: alerts, alarm: true, to: "/query?tab=alerts" },
  );
  if (store.isAdmin || store.isQuery) rows.push({ key: "oalerts", label: t("dashboard.orderAlertKpi"), value: d.order_alerts, alarm: true, to: "/orders?tab=alerts" });
  if (store.isAdmin) rows.push({ key: "running", label: t("dashboard.runningJobs"), value: d.running_jobs, to: "/generate" });
  if (d.total_generated !== null && d.total_generated !== undefined) rows.push({ key: "total", label: t("dashboard.totalGenerated"), value: d.total_generated });
  // 只有总部能处理的告警，其他角色不给跳转
  return store.isAdmin ? rows : rows.map((r) => (r.to?.startsWith("/query") || r.to === "/generate" || r.to === "/allocate" ? { ...r, to: r.to?.startsWith("/query") ? "/query" : undefined } : r));
});

/** 今日：生成、分配、推送（按确认方式分列）、MES 拉取、转单。 */
const TODAY_KEYS = ["SN_GENERATE", "SN_ALLOCATE", "CONFIRM_PUSH_RESPONSE", "CONFIRM_RECEIPT", "CONFIRM_MANUAL", "SN_PULL", "TRANSFER_BILL"];
const todayRows = computed(() => {
  const today: Record<string, number> = data.value?.today || {};
  const keys = [...TODAY_KEYS, ...Object.keys(today).filter((k) => !TODAY_KEYS.includes(k))];
  return keys.map((k) => ({ key: k, label: todayLabel(k), qty: today[k] ?? 0 }));
});
function todayLabel(k: string) {
  const key = `dashboard.todayItem.${k}`;
  const s = t(key);
  return s === key ? k : s;
}

const shortcuts = computed(() =>
  MENU.flatMap((g) => g.items)
    .filter((m) => m.path !== "/" && m.roles.includes(store.role) && (!("superAdmin" in m) || store.isSuperAdmin))
    .slice(0, 6),
);

function openBatch(batchNo: string) {
  router.push({ path: "/query", query: { tab: "delivery", batch_no: batchNo } });
}

async function load() {
  loading.value = true;
  try {
    data.value = await api.dashboard();
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.shortcuts { display: flex; flex-wrap: wrap; gap: 8px; }
.shortcuts .el-button { margin: 0; }
.shortcuts span { margin-left: 4px; }
.mt { margin-top: 12px; }
.delay { margin: -8px 0 0; }
.kpi.link { cursor: pointer; transition: border-color 0.15s; }
.kpi.link:hover { border-color: var(--el-color-primary); }
.kpi.alarm .v { color: var(--el-color-danger); }
.el-col { margin-bottom: 16px; }
</style>
