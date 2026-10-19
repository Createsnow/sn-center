<template>
  <div class="page page--fill">
    <PageHead :title="t('audit.title')" :desc="retention">
      <el-button :loading="exporting" @click="exportFile"><el-icon><Download /></el-icon><span>{{ t("audit.exportXlsx") }}</span></el-button>
    </PageHead>
    <div class="surface fill">
      <div class="toolbar">
        <el-tooltip :content="t('audit.tzTip', { tz })" placement="top">
          <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" :start-placeholder="t('common.dateFrom')" :end-placeholder="t('common.dateTo')" />
        </el-tooltip>
        <el-select v-model="f.action" clearable filterable :placeholder="t('audit.action')">
          <el-option v-for="a in AUDIT_ACTIONS" :key="a" :value="a" :label="actionLabel(a)" />
        </el-select>
        <el-select v-model="f.result" clearable :placeholder="t('audit.result')">
          <el-option value="OK" :label="t('audit.ok')" /><el-option value="FAIL" :label="t('audit.fail')" />
        </el-select>
        <el-select v-model="f.source" clearable :placeholder="t('audit.source')">
          <el-option value="PAGE" :label="t('audit.srcPAGE')" />
          <el-option value="API" :label="t('audit.srcAPI')" />
          <el-option value="SYSTEM" :label="t('audit.srcSYSTEM')" />
        </el-select>
        <el-input v-model="f.operator" :placeholder="t('audit.operator')" clearable @keyup.enter="load(1)" />
        <FactorySelect v-if="!store.bound" v-model="f.factory_code" clearable />
        <el-input v-model="f.pi" placeholder="PI" clearable @keyup.enter="load(1)" />
        <el-input v-model="f.bill_no" :placeholder="t('common.billNo')" clearable @keyup.enter="load(1)" />
        <el-input v-model="f.batch_no" :placeholder="t('common.batchNo')" clearable @keyup.enter="load(1)" />
        <el-input v-model="f.keyword" :placeholder="t('audit.keyword')" clearable class="w-kw" @keyup.enter="load(1)" />
        <el-button type="primary" @click="load(1)">{{ t("common.search") }}</el-button>
      </div>
      <div class="fill-body">
        <el-table v-loading="state.loading" :data="items" size="small" height="100%" :empty-text="t('common.empty')" row-key="id">
          <el-table-column type="expand">
            <template #default="{ row }">
              <el-descriptions :column="3" size="small" border class="exp">
                <el-descriptions-item :label="t('common.customer')">{{ row.customer_code }}</el-descriptions-item>
                <el-descriptions-item :label="t('common.material')">{{ row.material_code }}</el-descriptions-item>
                <el-descriptions-item :label="t('common.billNo')">{{ row.bill_no }}</el-descriptions-item>
                <el-descriptions-item :label="t('common.batchNo')">{{ row.batch_no }}</el-descriptions-item>
                <el-descriptions-item :label="t('audit.requestNo')">{{ row.request_no }}</el-descriptions-item>
                <el-descriptions-item :label="t('audit.transferNo')">{{ row.transfer_no }}</el-descriptions-item>
                <el-descriptions-item :label="t('common.reason')" :span="3">{{ row.reason }}</el-descriptions-item>
                <el-descriptions-item :label="t('audit.error')" :span="3">{{ row.error_msg }}</el-descriptions-item>
                <el-descriptions-item :label="t('audit.detail')" :span="3"><span class="detail">{{ row.detail }}</span></el-descriptions-item>
                <el-descriptions-item label="Trace ID" :span="3">{{ row.trace_id }}</el-descriptions-item>
              </el-descriptions>
            </template>
          </el-table-column>
          <el-table-column :label="t('common.time')" width="200">
            <template #default="{ row }">{{ fmt(row.created_at) }}<el-tag v-if="row.archived" size="small" type="info" class="arc">{{ t("audit.archived") }}</el-tag></template>
          </el-table-column>
          <el-table-column :label="t('common.operator')" width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ row.operator }} {{ row.operator_name }}</template>
          </el-table-column>
          <el-table-column :label="t('audit.role')" width="110" show-overflow-tooltip><template #default="{ row }">{{ roleLabel(row.role) }}</template></el-table-column>
          <el-table-column :label="t('audit.source')" width="70"><template #default="{ row }">{{ sourceLabel(row.source) }}</template></el-table-column>
          <el-table-column :label="t('audit.action')" width="130" show-overflow-tooltip><template #default="{ row }">{{ actionLabel(row.action) }}</template></el-table-column>
          <el-table-column :label="t('audit.result')" width="70">
            <template #default="{ row }"><el-tag size="small" :type="row.result === 'OK' ? 'success' : 'danger'">{{ row.result === "OK" ? t("audit.ok") : t("audit.fail") }}</el-tag></template>
          </el-table-column>
          <el-table-column :label="t('common.factory')" width="120" show-overflow-tooltip><template #default="{ row }">{{ nameOf(row.factory_code) }}</template></el-table-column>
          <el-table-column prop="pi_no" label="PI" min-width="140" show-overflow-tooltip />
          <el-table-column prop="bill_no" :label="t('common.billNo')" min-width="130" show-overflow-tooltip />
          <el-table-column :label="t('audit.range')" min-width="240">
            <template #default="{ row }"><span v-if="row.start_sn" class="sn-mono">{{ row.start_sn }}<template v-if="row.end_sn && row.end_sn !== row.start_sn"> ~ {{ row.end_sn }}</template></span></template>
          </el-table-column>
          <el-table-column prop="qty" :label="t('common.qty')" align="right" width="70" />
          <el-table-column :label="t('audit.status')" width="160">
            <template #default="{ row }">
              <span v-if="row.before_status || row.after_status">{{ statusText(row.before_status) || "—" }} → {{ statusText(row.after_status) || "—" }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('common.batchNo')" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ row.batch_no || row.request_no || row.transfer_no }}</template>
          </el-table-column>
          <el-table-column prop="reason" :label="t('common.reason')" min-width="140" show-overflow-tooltip />
          <el-table-column prop="error_msg" :label="t('audit.error')" min-width="160" show-overflow-tooltip />
          <el-table-column prop="detail" :label="t('audit.detail')" min-width="200" show-overflow-tooltip />
        </el-table>
      </div>
      <div class="foot">
        <span v-if="state.capped" class="muted">{{ t("common.capped", { n: state.total }) }}</span>
        <span v-else />
        <el-pagination layout="total, sizes, prev, pager, next" :total="state.total" :page-size="state.pageSize"
          :current-page="state.page" :page-sizes="[20, 50, 100]" @current-change="load" @size-change="onSize" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { api, urls } from "@/api";
import { useAuthStore } from "@/stores/auth";
import { usePaged } from "@/composables/usePaged";
import { useFactories } from "@/composables/useFactories";
import { useTime } from "@/composables/useTime";
import { AUDIT_ACTIONS, actionLabel, roleLabel, statusLabel } from "@/constants";
import { downloadWithToken } from "@/utils/download";
import PageHead from "@/components/PageHead.vue";
import FactorySelect from "@/components/FactorySelect.vue";

const { t } = useI18n();
const store = useAuthStore();
const { nameOf } = useFactories();
const { fmt, tz, offset } = useTime();

/** 默认最近 30 天；日期按显示时区计（后端按同一时区换算成 UTC 区间）。 */
const localDay = (ms: number) => new Date(ms + offset.value * 60_000).toISOString().slice(0, 10);
const range = ref<[string, string] | null>([localDay(Date.now() - 29 * 86400_000), localDay(Date.now())]);
const f = reactive({ action: "", result: "", source: "", operator: "", factory_code: "", pi: "", bill_no: "", batch_no: "", keyword: "" });
const meta = ref<any>(null);
const exporting = ref(false);
const statusText = (s?: string | null) => (s === "PREVIOUS" ? t("audit.previous") : statusLabel(s));
const sourceLabel = (s?: string | null) => (s === "PAGE" ? t("audit.srcPAGE") : s === "API" ? t("audit.srcAPI") : s === "SYSTEM" ? t("audit.srcSYSTEM") : s || "");

const params = () => ({
  ...Object.fromEntries(Object.entries(f).map(([k, v]) => [k, v.trim()])),
  from: range.value?.[0],
  to: range.value?.[1],
});
const { items, state, load, onSize } = usePaged<any>((q) => api.audits({ ...params(), ...q }));
const retention = computed(() => {
  const m = meta.value;
  if (!m) return "";
  if (m.permanent) return t("audit.retentionForever");
  const online = t("audit.retention", { days: m.retention_days });
  const arc = m.archive_retention_days ? t("audit.archiveKeep", { days: m.archive_retention_days }) : t("audit.archiveForever");
  const span = m.archive_from ? t("audit.archiveSpan", { from: String(m.archive_from).slice(0, 10), to: String(m.archive_to).slice(0, 10) }) : "";
  return `${online}${arc}${span}`;
});

/** 默认最多 20 万条，超过只导出前 20 万条，先提示。 */
async function exportFile() {
  exporting.value = true;
  try {
    const p = params();
    const c = await api.auditExportCheck(p);
    if (c.truncated) {
      try {
        await ElMessageBox.confirm(t("common.exportTruncated", { limit: c.limit }), t("common.export"), { type: "warning" });
      } catch {
        return;
      }
    }
    await downloadWithToken(urls.auditExport({ ...p, format: "xlsx" }), "audit_export.xlsx");
  } catch (e: any) {
    if (e?.message) ElMessage.error(e.message);
  } finally {
    exporting.value = false;
  }
}

onMounted(async () => {
  meta.value = await api.auditMeta();
  await load(1);
});
</script>

<style scoped>
.arc { margin-left: 6px; }
.exp { padding: 4px 48px; }
.detail { white-space: pre-wrap; word-break: break-all; }
.w-kw { width: 240px !important; }
.foot { display: flex; justify-content: space-between; align-items: center; margin-top: 12px; }
</style>
