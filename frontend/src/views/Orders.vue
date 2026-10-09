<template>
  <div class="page page--fill">
    <PageHead :title="t('orders.title')" :desc="t('orders.desc')">
      <template v-if="store.isAdmin">
        <el-input v-model="syncBillNo" :placeholder="t('orders.billToSync')" clearable class="w200" @keyup.enter="syncOne" />
        <el-button :loading="syncing === 'one'" :disabled="!syncBillNo.trim()" @click="syncOne">{{ t("orders.syncOne") }}</el-button>
        <el-button type="primary" :loading="syncing === 'all'" @click="syncAll">
          <el-icon><Refresh /></el-icon><span>{{ t("orders.syncAll") }}</span>
        </el-button>
      </template>
    </PageHead>

    <el-alert v-if="meta?.unmapped_orgs?.length" type="warning" show-icon :closable="false"
      :title="t('orders.unmapped', { orgs: meta.unmapped_orgs.join('、') })" />

    <div class="surface meta">
      <span>{{ t("orders.syncedAt") }}：<b>{{ meta?.synced_at || "—" }}</b></span>
      <span>{{ t("orders.bills") }}：<b class="num">{{ meta?.bills ?? 0 }}</b></span>
      <span>{{ t("orders.rows") }}：<b class="num">{{ meta?.rows ?? 0 }}</b></span>
      <span class="muted">{{ t("orders.excluded", { n: meta?.excluded_empty_customer ?? 0 }) }}</span>
      <span v-if="sched" class="sched">
        <span>{{ t("orders.sched.title") }}：</span>
        <el-tag v-if="sched.enabled" size="small" type="success">{{ t("orders.sched.every", { every: intervalText(sched.interval_minutes) }) }}</el-tag>
        <el-tag v-else size="small" type="info">{{ t("orders.sched.off") }}</el-tag>
        <span v-if="sched.enabled" class="muted">{{ t("orders.sched.next") }} {{ sched.next_run_at || "—" }}</span>
        <el-tooltip v-if="sched.last_run_at" :content="lastText" placement="bottom">
          <el-icon :class="sched.last_ok ? 'ok' : 'bad'">
            <CircleCheckFilled v-if="sched.last_ok" /><WarningFilled v-else />
          </el-icon>
        </el-tooltip>
        <el-button v-if="store.isAdmin" link type="primary" @click="openSched">
          <el-icon><Setting /></el-icon><span>{{ t("orders.sched.settings") }}</span>
        </el-button>
      </span>
    </div>

    <el-dialog v-model="schedOpen" :title="t('orders.sched.dialogTitle')" width="480px">
      <el-form label-width="110px">
        <el-form-item :label="t('orders.sched.enabled')">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item :label="t('orders.sched.interval')">
          <div class="interval">
            <el-input-number v-model="form.amount" :min="1" :max="unitMax" :step="1" step-strictly controls-position="right" />
            <el-select v-model="form.unit" class="unit">
              <el-option v-for="u in UNITS" :key="u.key" :value="u.key" :label="t(`orders.sched.unit.${u.key}`)" />
            </el-select>
          </div>
          <div class="muted hint">{{ t("orders.sched.intervalHint", { min: sched?.min_interval ?? 10 }) }}</div>
        </el-form-item>
        <el-form-item v-if="sched?.last_run_at" :label="t('orders.sched.last')">
          <span :class="sched.last_ok ? 'ok' : 'bad'">{{ lastText }}</span>
        </el-form-item>
        <el-form-item v-if="sched?.updated_at" :label="t('orders.sched.updated')">
          <span class="muted">{{ sched.updated_by }} · {{ sched.updated_at }}</span>
        </el-form-item>
      </el-form>
      <el-alert type="info" :closable="false" :title="t('orders.sched.note')" />
      <template #footer>
        <el-button @click="schedOpen = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" :loading="savingSched" @click="saveSched">{{ t("common.save") }}</el-button>
      </template>
    </el-dialog>

    <el-row :gutter="16" class="cols">
      <el-col :lg="16" :xs="24">
        <div class="surface fill">
          <div class="toolbar">
            <el-input v-model="f.q" :placeholder="t('orders.search')" clearable prefix-icon="Search" class="w-search" @keyup.enter="load(1)" @clear="load(1)" />
            <el-input v-model="f.customer" :placeholder="t('common.customer')" clearable @keyup.enter="load(1)" @clear="load(1)" />
            <FactorySelect v-if="!store.boundFactory" v-model="f.factory_code" clearable @update:model-value="load(1)" />
            <el-button type="primary" @click="load(1)">{{ t("common.search") }}</el-button>
          </div>
          <div class="fill-body">
          <el-table v-loading="state.loading" :data="items" highlight-current-row size="small" height="100%"
            :empty-text="t('common.empty')" @current-change="select">
            <el-table-column prop="bill_no" :label="t('common.billNo')" min-width="120" show-overflow-tooltip />
            <el-table-column prop="customer_number" :label="t('common.customer')" width="80" show-overflow-tooltip />
            <el-table-column prop="pi" label="PI" min-width="140" show-overflow-tooltip />
            <el-table-column :label="t('orders.org')" min-width="100" show-overflow-tooltip>
              <template #default="{ row }">
                <span>{{ row.prd_org_name }}</span>
                <el-tag v-if="!row.factory_code" size="small" type="danger" class="ml">{{ t("orders.noFactory") }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('orders.status')" width="84" show-overflow-tooltip>
              <template #default="{ row }">{{ statusText(row.statuses) }}</template>
            </el-table-column>
            <el-table-column :label="t('orders.totalQty')" align="right" width="76">
              <template #default="{ row }"><span class="num">{{ row.total_qty }}</span></template>
            </el-table-column>
            <el-table-column :label="t('orders.generated')" align="right" width="70">
              <template #default="{ row }"><span class="num">{{ row.generated_qty }}</span></template>
            </el-table-column>
            <el-table-column :label="t('orders.quota')" align="right" width="70">
              <template #default="{ row }"><span class="num" :class="{ zero: !row.quota }">{{ row.quota }}</span></template>
            </el-table-column>
          </el-table>
          </div>
          <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="state.total" :page-size="state.pageSize"
            :current-page="state.page" :page-sizes="[20, 50, 100]" @current-change="load" @size-change="onSize" />
        </div>
      </el-col>
      <el-col :lg="8" :xs="24">
        <div class="surface fill">
          <div class="surface__title">
            <span>{{ current ? t("orders.linesOf", { bill: current.bill_no }) : t("orders.lines") }}</span>
            <el-button v-if="current && store.isAdmin" type="primary" size="small"
              @click="router.push({ path: '/generate', query: { bill_no: current.bill_no } })">
              {{ t("orders.toGenerate") }}
            </el-button>
          </div>
          <template v-if="current">
            <el-descriptions :column="1" size="small" border class="mb">
              <el-descriptions-item label="PI">{{ current.pi }}</el-descriptions-item>
              <el-descriptions-item label="PO">{{ current.po }}</el-descriptions-item>
              <el-descriptions-item :label="t('common.customer')">{{ current.customer_number }}</el-descriptions-item>
              <el-descriptions-item :label="t('common.factory')">{{ current.prd_org_name }}（{{ current.factory_code || "—" }}）</el-descriptions-item>
            </el-descriptions>
            <div class="fill-body">
            <el-table v-loading="linesLoading" :data="lines" size="small" height="100%" :empty-text="t('common.empty')">
              <el-table-column prop="line_seq" label="#" width="48" />
              <el-table-column :label="t('common.material')" min-width="100">
                <template #default="{ row }"><span v-if="row.material_number">{{ row.material_number }}</span><span v-else class="muted">{{ t("orders.noMaterial") }}</span></template>
              </el-table-column>
              <el-table-column :label="t('orders.materialName')" min-width="120" show-overflow-tooltip>
                <template #default="{ row }">
                  <div>{{ row.material_name }}</div>
                  <div v-if="row.demand_bill_no" class="muted">{{ t("orders.demand") }} {{ row.demand_bill_no }}</div>
                </template>
              </el-table-column>
              <el-table-column :label="t('common.qty')" align="right" width="70">
                <template #default="{ row }"><span class="num">{{ Number(row.qty) }}</span></template>
              </el-table-column>
            </el-table>
            </div>
          </template>
          <el-empty v-else :description="t('orders.pick')" />
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import { api } from "@/api";
import { useAuthStore } from "@/stores/auth";
import { usePaged } from "@/composables/usePaged";
import PageHead from "@/components/PageHead.vue";
import FactorySelect from "@/components/FactorySelect.vue";

const { t } = useI18n();
const router = useRouter();
const store = useAuthStore();
const f = reactive({ q: "", customer: "", factory_code: "" });
const meta = ref<any>(null);
const current = ref<any>(null);
const lines = ref<any[]>([]);
const linesLoading = ref(false);
const syncBillNo = ref("");
const syncing = ref<"" | "all" | "one">("");

const sched = ref<any>(null);
const schedOpen = ref(false);
const savingSched = ref(false);
const UNITS = [
  { key: "minute", minutes: 1 },
  { key: "hour", minutes: 60 },
  { key: "day", minutes: 1440 },
] as const;
type Unit = (typeof UNITS)[number]["key"];
const form = reactive<{ enabled: boolean; amount: number; unit: Unit }>({ enabled: false, amount: 1, unit: "day" });
const unitMinutes = (u: Unit) => UNITS.find((x) => x.key === u)!.minutes;
const unitMax = computed(() => Math.floor((sched.value?.max_interval ?? 10080) / unitMinutes(form.unit)));

/** 分钟数拆成「最大的整除单位」：1440 → 1 天，120 → 2 小时，90 → 90 分钟 */
function splitInterval(minutes: number): { amount: number; unit: Unit } {
  const u = [...UNITS].reverse().find((x) => minutes % x.minutes === 0)!;
  return { amount: minutes / u.minutes, unit: u.key };
}
function intervalText(minutes: number) {
  const { amount, unit } = splitInterval(minutes);
  return `${amount} ${t(`orders.sched.unit.${unit}`)}`;
}
const lastText = computed(() => {
  const s = sched.value;
  if (!s?.last_run_at) return "";
  return s.last_ok
    ? t("orders.sched.lastOk", { time: s.last_run_at, bills: s.last_bills ?? 0, rows: s.last_rows ?? 0 })
    : t("orders.sched.lastFail", { time: s.last_run_at, msg: s.last_message });
});

function openSched() {
  const s = sched.value;
  Object.assign(form, { enabled: !!s?.enabled, ...splitInterval(s?.interval_minutes ?? 1440) });
  schedOpen.value = true;
}

async function saveSched() {
  const minutes = form.amount * unitMinutes(form.unit);
  const min = sched.value?.min_interval ?? 10;
  const max = sched.value?.max_interval ?? 10080;
  if (minutes < min || minutes > max) {
    ElMessage.error(t("orders.sched.intervalHint", { min }));
    return;
  }
  savingSched.value = true;
  try {
    sched.value = await api.saveOrderSyncSchedule({ enabled: form.enabled, interval_minutes: minutes });
    ElMessage.success(t("common.saved"));
    schedOpen.value = false;
  } finally {
    savingSched.value = false;
  }
}

const { items, state, load, onSize } = usePaged<any>((q) => api.orders({ ...f, ...q }));

function statusText(s: string) {
  return (s || "").split(",").map((x) => t(`orders.st${x}`)).join(" / ");
}

async function select(row: any) {
  current.value = row;
  if (!row) return;
  linesLoading.value = true;
  try {
    lines.value = await api.orderLines(row.bill_no);
  } finally {
    linesLoading.value = false;
  }
}

async function refresh() {
  [meta.value, sched.value] = await Promise.all([api.orderMeta(), api.orderSyncSchedule()]);
  await load(1);
}

async function syncAll() {
  syncing.value = "all";
  try {
    const r: any = await api.syncOrders();
    ElMessage.success(t("orders.syncedAll", { bills: r.bills, rows: r.rows }));
    await refresh();
  } finally {
    syncing.value = "";
  }
}

async function syncOne() {
  const no = syncBillNo.value.trim();
  if (!no) return;
  syncing.value = "one";
  try {
    const r: any = await api.syncOrders(no);
    ElMessage.success(r.removed ? t("orders.syncedRemoved", { bill: no }) : t("orders.syncedOne", { bill: no, rows: r.rows }));
    await refresh();
  } finally {
    syncing.value = "";
  }
}

onMounted(refresh);
</script>

<style scoped>
.meta { display: flex; gap: 24px; flex-wrap: wrap; align-items: center; font-size: 13px; padding: 12px 20px; }
.w200 { width: 200px; }
.toolbar .w-search { width: 280px; }
.sched { margin-left: auto; display: inline-flex; align-items: center; gap: 8px; }
.sched .el-icon { font-size: 16px; }
.ok { color: var(--el-color-success); }
.bad { color: var(--el-color-danger); }
.interval { display: flex; gap: 8px; }
.interval .unit { width: 100px; }
.hint { width: 100%; margin-top: 4px; }
.ml { margin-left: 6px; }
.mb { margin-bottom: 12px; }
.zero { color: var(--app-muted); }
.el-col { margin-bottom: 16px; }
/* 宽屏左右两栏铺满剩余高度，各自在卡片内滚动 */
@media (min-width: 1200px) {
  .cols { flex: 1; min-height: 0; flex-wrap: nowrap; }
  .cols > .el-col { margin-bottom: 0; display: flex; flex-direction: column; }
  .cols > .el-col > .surface { flex: 1; }
}
</style>
