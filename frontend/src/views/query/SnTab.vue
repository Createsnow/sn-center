<template>
  <div class="toolbar">
    <FactorySelect v-if="store.isAdmin" v-model="f.factory_code" clearable />
    <el-input v-model="f.pi" placeholder="PI" clearable @keyup.enter="load(1)" />
    <el-input v-model="f.customer_code" :placeholder="t('common.customer')" clearable @keyup.enter="load(1)" />
    <el-input v-model="f.bill_no" :placeholder="t('common.billNo')" clearable @keyup.enter="load(1)" />
    <el-input v-model="f.material_code" :placeholder="t('common.material')" clearable @keyup.enter="load(1)" />
    <el-input v-model="f.length" :placeholder="t('common.length')" clearable class="w120" @keyup.enter="load(1)" />
    <el-select v-model="f.status" clearable :placeholder="t('common.status')">
      <el-option v-for="s in STATUS_CODES" :key="s" :value="s" :label="statusLabel(s)" />
      <el-option value="SUSPENDED" :label="t('query.suspended')" />
    </el-select>
    <el-select v-model="f.delivery_mode" clearable :placeholder="t('query.deliveryMode')">
      <el-option v-for="m in MODES" :key="m" :value="m" :label="deliveryModeLabel(m)" />
    </el-select>
    <el-input v-model="f.sn" :placeholder="t('common.sn')" clearable class="sn-mono" @keyup.enter="load(1)" />
    <el-input v-model="f.batch_no" :placeholder="t('common.batchNo')" clearable @keyup.enter="load(1)" />
    <div class="seq-range">
      <span class="seq-range__label">{{ t("query.seqRange") }}</span>
      <el-input v-model="f.seq_from" :placeholder="t('query.seqFrom')" clearable class="sn-mono" @keyup.enter="load(1)" />
      <span>–</span>
      <el-input v-model="f.seq_to" :placeholder="t('query.seqTo')" clearable class="sn-mono" @keyup.enter="load(1)" />
    </div>
    <el-switch v-if="!store.isAdmin && store.bound" v-model="f.pi_all" :active-text="t('query.piAll')" :disabled="!f.pi.trim()" />
    <el-button type="primary" @click="load(1)">{{ t("common.search") }}</el-button>
    <el-button @click="reset">{{ t("common.reset") }}</el-button>
    <span class="push" />
    <el-button :loading="exporting" @click="exportSn('xlsx')"><el-icon><Download /></el-icon><span>{{ t("common.downloadXlsx") }}</span></el-button>
    <el-button :loading="exporting" @click="exportSn('csv')">{{ t("common.downloadCsv") }}</el-button>
  </div>
  <el-alert v-if="f.pi_all" type="info" :closable="false" show-icon :title="t('query.piAllTip')" class="mb" />
  <el-alert v-if="hasRange" type="info" :closable="false" show-icon :title="t('query.seqRangeTip')" class="mb" />
  <div v-if="range" class="range-sum mb">
    <span>{{ t("query.rangeFound", { n: range.found }) }}</span>
    <span v-for="(n, s) in range.by_status" :key="s"><StatusTag :status="String(s)" /> <b>{{ n }}</b></span>
    <span v-if="range.expected != null" class="range-sum__note">
      {{ range.missing ? t("query.rangeMissing", { expected: range.expected, n: range.missing }) : t("query.rangeFull") }}
    </span>
  </div>
  <div class="fill-body">
    <el-table v-loading="state.loading" height="100%" :data="items" size="small" :empty-text="t('common.empty')">
      <el-table-column :label="t('common.sn')" min-width="180" fixed>
        <template #default="{ row }"><span class="sn-mono">{{ row.sn }}</span></template>
      </el-table-column>
      <el-table-column :label="t('common.seqText')" width="110"><template #default="{ row }"><span class="sn-mono">{{ row.seq_text }}</span></template></el-table-column>
      <el-table-column prop="seq_dec" :label="t('common.seqDec')" width="100" align="right" />
      <el-table-column label="PI" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">
          <span>{{ row.pi_no }}</span>
          <el-tag v-if="row.pi_changed" size="small" type="warning" class="ml">{{ t("query.piChanged") }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('query.billSeq')" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ row.bill_no }} / {{ row.bill_seq }}</template>
      </el-table-column>
      <el-table-column prop="material_code" :label="t('common.material')" width="110" show-overflow-tooltip />
      <el-table-column prop="length" :label="t('common.length')" width="70" />
      <el-table-column prop="customer_code" :label="t('common.customer')" width="90" show-overflow-tooltip />
      <el-table-column :label="t('common.factory')" width="120" show-overflow-tooltip>
        <template #default="{ row }">{{ nameOf(row.factory_code) }}</template>
      </el-table-column>
      <el-table-column :label="t('common.status')" width="130">
        <template #default="{ row }">
          <StatusTag :status="row.status" />
          <el-tag v-if="row.suspend" size="small" type="danger" class="ml">{{ suspendLabel(row.suspend) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('query.deliveryMode')" width="90">
        <template #default="{ row }">{{ deliveryModeLabel(row.delivery_mode) }}</template>
      </el-table-column>
      <el-table-column prop="seq_pi_no" :label="t('query.seqOwner')" min-width="140" show-overflow-tooltip />
      <el-table-column :label="t('query.deliveryBatch')" min-width="200">
        <template #default="{ row }">
          <el-button v-if="row.delivery_batch_no" link type="primary" @click="emit('openDelivery', row.delivery_batch_no)">{{ row.delivery_batch_no }}</el-button>
        </template>
      </el-table-column>
      <el-table-column :label="t('query.acquireBatch')" min-width="180">
        <template #default="{ row }">
          <el-button v-if="row.acquire_batch_no" link type="primary" @click="emit('openAcquire', row.acquire_batch_no)">{{ row.acquire_batch_no }}</el-button>
        </template>
      </el-table-column>
      <el-table-column prop="rev" label="rev" width="56" align="right" />
      <el-table-column :label="t('query.pushType')" width="90">
        <template #default="{ row }">
          {{ pushTypeLabel(row.push_type) }}
          <el-tag v-if="row.transferred_in" size="small" type="info" class="ml">{{ t("query.transferredIn") }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('query.mesHolder')" width="120" show-overflow-tooltip>
        <template #default="{ row }">{{ row.mes_holder ? nameOf(row.mes_holder) : "" }}</template>
      </el-table-column>
      <el-table-column :label="t('query.generatedAt')" width="160"><template #default="{ row }">{{ fmt(row.created_at) }}</template></el-table-column>
      <el-table-column :label="t('query.allocatedAt')" width="160"><template #default="{ row }">{{ fmt(row.allocated_at) }}</template></el-table-column>
      <el-table-column :label="t('query.deliveredAt')" width="160"><template #default="{ row }">{{ fmt(row.delivered_at) }}</template></el-table-column>
    </el-table>
  </div>
  <div class="foot">
    <span v-if="state.capped" class="muted">{{ t("common.capped", { n: state.total }) }}</span>
    <span v-else />
    <el-pagination layout="total, sizes, prev, pager, next" :total="state.total" :page-size="state.pageSize"
      :current-page="state.page" :page-sizes="[20, 50, 100, 500]" @current-change="load" @size-change="onSize" />
  </div>
</template>

<script setup lang="ts">
/** SN 明细（F-18）：按组织 / 客户 / PI / 单据 / 物料 / 米标 / 状态 / 交付方式 / 批次 / 流水范围筛选，导出。 */
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { api, urls } from "@/api";
import { useAuthStore } from "@/stores/auth";
import { usePaged } from "@/composables/usePaged";
import { useFactories } from "@/composables/useFactories";
import { useTime } from "@/composables/useTime";
import { STATUS_CODES, deliveryModeLabel, pushTypeLabel, statusLabel, suspendLabel } from "@/constants";
import { downloadWithToken } from "@/utils/download";
import FactorySelect from "@/components/FactorySelect.vue";
import StatusTag from "@/components/StatusTag.vue";
import { trimmed } from "./filters";

const MODES = ["PUSH", "PULL", "PAGE"];

const props = defineProps<{ init: { factory_code: string; pi: string; bill_no: string; batch_no: string } }>();
const emit = defineEmits<{ openDelivery: [no: string]; openAcquire: [no: string] }>();

const { t } = useI18n();
const store = useAuthStore();
const { nameOf } = useFactories();
const { fmt } = useTime();

const blank = () => ({
  factory_code: "", pi: "", customer_code: "", bill_no: "", material_code: "", length: "",
  status: "", delivery_mode: "", sn: "", batch_no: "", seq_from: "", seq_to: "", pi_all: false,
});
const f = reactive({ ...blank(), ...props.init });
const exporting = ref(false);
const params = () => ({ ...trimmed(f), pi_all: f.pi_all || undefined });

type RangeSummary = { found: number; expected: number | null; missing: number | null; by_status: Record<string, number> };
const range = ref<RangeSummary | null>(null);
const hasRange = computed(() => !!(f.seq_from.trim() || f.seq_to.trim()));
const { items, state, load, onSize } = usePaged<any>(async (q) => {
  range.value = null;
  const r = await api.items({ ...params(), ...q });
  range.value = r.range ?? null;
  return r;
});

watch(() => f.pi, (v) => {
  if (!v.trim()) f.pi_all = false;
});

function reset() {
  Object.assign(f, blank());
  load(1);
}

/** 超过导出上限只导出前 N 行，先提示。 */
async function exportSn(format: string) {
  exporting.value = true;
  try {
    const p = params();
    const c = await api.snExportCheck(p);
    if (c.truncated) {
      try {
        await ElMessageBox.confirm(t("common.exportTruncated", { limit: c.limit }), t("common.export"), { type: "warning" });
      } catch {
        return;
      }
    }
    await downloadWithToken(urls.snExport({ ...p, format }), `sn_export.${format}`);
  } catch (e: any) {
    if (e?.message) ElMessage.error(e.message);
  } finally {
    exporting.value = false;
  }
}

onMounted(() => load(1));
</script>

<style scoped>
.mb { margin-bottom: 12px; }
.ml { margin-left: 4px; }
.w120 { width: 120px !important; }
.push { margin-left: auto; }
.seq-range { display: flex; align-items: center; gap: 6px; }
.seq-range .el-input { width: 150px; }
.seq-range__label { color: var(--el-text-color-regular); white-space: nowrap; }
.range-sum { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 16px; padding: 8px 12px; border-radius: 4px; background: var(--el-fill-color-light); font-size: 13px; }
.range-sum b { font-variant-numeric: tabular-nums; }
.range-sum__note { color: var(--el-text-color-secondary); }
.foot { display: flex; justify-content: space-between; align-items: center; margin-top: 12px; }
</style>
