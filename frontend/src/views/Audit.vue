<template>
  <div class="page page--fill">
    <PageHead :title="t('audit.title')" :desc="retention">
      <el-button :loading="exporting" @click="exportFile"><el-icon><Download /></el-icon><span>{{ t("query.exportXlsx") }}</span></el-button>
    </PageHead>
    <div class="surface fill">
      <div class="toolbar">
        <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" :start-placeholder="t('audit.from')" :end-placeholder="t('audit.to')" />
        <el-select v-model="f.action" clearable filterable :placeholder="t('audit.action')">
          <el-option v-for="a in AUDIT_ACTIONS" :key="a" :value="a" :label="actionLabel(a)" />
        </el-select>
        <el-select v-model="f.result" clearable :placeholder="t('audit.result')">
          <el-option value="OK" :label="t('audit.ok')" /><el-option value="FAIL" :label="t('audit.fail')" />
        </el-select>
        <el-select v-model="f.source" clearable :placeholder="t('audit.source')">
          <el-option v-for="s in ['PAGE', 'API', 'SYSTEM']" :key="s" :value="s" :label="t(`audit.src${s}`)" />
        </el-select>
        <el-input v-model="f.operator" :placeholder="t('audit.operator')" clearable />
        <FactorySelect v-if="!store.boundFactory" v-model="f.factory_code" clearable />
        <el-input v-model="f.pi" placeholder="PI" clearable />
        <el-input v-model="f.batch_no" :placeholder="t('acquire.batchNo')" clearable />
        <el-input v-model="f.keyword" :placeholder="t('audit.keyword')" clearable />
        <el-button type="primary" @click="load(1)">{{ t("common.search") }}</el-button>
      </div>
      <div class="fill-body">
      <el-table v-loading="state.loading" :data="items" size="small" height="100%" :empty-text="t('common.empty')" row-key="id">
        <el-table-column type="expand">
          <template #default="{ row }">
            <el-descriptions :column="3" size="small" border class="exp">
              <el-descriptions-item :label="t('common.customer')">{{ row.customer_code }}</el-descriptions-item>
              <el-descriptions-item :label="t('common.material')">{{ row.material_code }}</el-descriptions-item>
              <el-descriptions-item :label="t('query.sourceBill')">{{ row.bill_no }}</el-descriptions-item>
              <el-descriptions-item :label="t('acquire.batchNo')">{{ row.batch_no }}</el-descriptions-item>
              <el-descriptions-item :label="t('acquire.requestNo')">{{ row.request_no }}</el-descriptions-item>
              <el-descriptions-item :label="t('transfer.no')">{{ row.transfer_no }}</el-descriptions-item>
              <el-descriptions-item :label="t('transfer.reason')" :span="3">{{ row.reason }}</el-descriptions-item>
              <el-descriptions-item :label="t('audit.error')" :span="3">{{ row.error_msg }}</el-descriptions-item>
              <el-descriptions-item :label="t('audit.detail')" :span="3">{{ row.detail }}</el-descriptions-item>
              <el-descriptions-item label="Trace ID" :span="3">{{ row.trace_id }}</el-descriptions-item>
            </el-descriptions>
          </template>
        </el-table-column>
        <el-table-column prop="created_at" :label="t('common.time')" width="170" />
        <el-table-column :label="t('audit.operator')" width="130"><template #default="{ row }">{{ row.operator }} {{ row.operator_name }}</template></el-table-column>
        <el-table-column :label="t('audit.source')" width="70"><template #default="{ row }">{{ t(`audit.src${row.source}`) }}</template></el-table-column>
        <el-table-column :label="t('audit.action')" width="120"><template #default="{ row }">{{ actionLabel(row.action) }}</template></el-table-column>
        <el-table-column :label="t('audit.result')" width="70">
          <template #default="{ row }"><el-tag size="small" :type="row.result === 'OK' ? 'success' : 'danger'">{{ row.result === "OK" ? t("audit.ok") : t("audit.fail") }}</el-tag></template>
        </el-table-column>
        <el-table-column :label="t('common.factory')" width="110"><template #default="{ row }">{{ nameOf(row.factory_code) }}</template></el-table-column>
        <el-table-column prop="pi_no" label="PI" min-width="140" show-overflow-tooltip />
        <el-table-column :label="t('audit.range')" min-width="230">
          <template #default="{ row }"><span v-if="row.start_sn" class="sn-mono">{{ row.start_sn }}<template v-if="row.end_sn && row.end_sn !== row.start_sn"> ~ {{ row.end_sn }}</template></span></template>
        </el-table-column>
        <el-table-column prop="qty" :label="t('common.qty')" align="right" width="70" />
        <el-table-column :label="t('audit.status')" width="150">
          <template #default="{ row }">
            <span v-if="row.before_status || row.after_status">{{ statusLabel(row.before_status) || "—" }} → {{ statusLabel(row.after_status) || t("audit.previous") }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="error_msg" :label="t('audit.error')" min-width="160" show-overflow-tooltip />
      </el-table>
      </div>
      <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="state.total" :page-size="state.pageSize"
        :current-page="state.page" :page-sizes="[20, 50, 100]" @current-change="load" @size-change="onSize" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import { api, urls } from "@/api";
import { useAuthStore } from "@/stores/auth";
import { usePaged } from "@/composables/usePaged";
import { useFactories } from "@/composables/useFactories";
import { AUDIT_ACTIONS, actionLabel, statusLabel as baseStatus } from "@/constants";
import { downloadWithToken } from "@/utils/download";
import PageHead from "@/components/PageHead.vue";
import FactorySelect from "@/components/FactorySelect.vue";

const { t } = useI18n();
const store = useAuthStore();
const { nameOf } = useFactories();
const today = new Date();
const monthAgo = new Date(Date.now() - 30 * 86400_000);
const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
const range = ref<[string, string] | null>([iso(monthAgo), iso(today)]);
const f = reactive({ action: "", result: "", source: "", operator: "", factory_code: "", pi: "", batch_no: "", keyword: "" });
const meta = ref<any>(null);
const exporting = ref(false);
const statusLabel = (s?: string | null) => (s === "PREVIOUS" ? t("audit.previous") : baseStatus(s));

const params = () => ({ ...f, from: range.value?.[0], to: range.value?.[1] });
const { items, state, load, onSize } = usePaged<any>((q) => api.audits({ ...params(), ...q }));
const retention = computed(() =>
  meta.value ? (meta.value.permanent ? t("audit.retentionForever") : t("audit.retention", { days: meta.value.retention_days })) : "",
);

async function exportFile() {
  exporting.value = true;
  try {
    await downloadWithToken(urls.auditExport({ ...params(), format: "xlsx" }), "audit_export.xlsx");
  } catch (e: any) {
    ElMessage.error(e.message);
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
.exp { padding: 4px 48px; }
</style>
