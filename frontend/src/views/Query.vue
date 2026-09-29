<template>
  <div class="page">
    <PageHead :title="t('query.title')" :desc="t('query.desc')">
      <el-button :loading="exporting" @click="exportFile('xlsx')"><el-icon><Download /></el-icon><span>{{ t("query.exportXlsx") }}</span></el-button>
      <el-button :loading="exporting" @click="exportFile('csv')">CSV</el-button>
    </PageHead>
    <div class="surface">
      <div class="toolbar">
        <FactorySelect v-if="!store.boundFactory" v-model="f.factory_code" clearable :disabled="f.pi_all" />
        <el-tag v-else type="warning" effect="plain" size="large">{{ store.profile?.factory_name || store.boundFactory }}</el-tag>
        <el-input v-model="f.pi" placeholder="PI" clearable />
        <el-input v-model="f.material_code" :placeholder="t('common.material')" clearable />
        <el-input v-model="f.customer_code" :placeholder="t('common.customer')" clearable />
        <el-select v-model="f.status" clearable :placeholder="t('common.status')">
          <el-option v-for="s in STATUS_CODES" :key="s" :value="s" :label="statusLabel(s)" />
        </el-select>
        <el-input v-model="f.sn" :placeholder="t('common.sn')" clearable class="sn-mono" />
        <el-input v-model="f.bill_no" :placeholder="t('query.sourceBill')" clearable />
        <el-input v-model="f.batch_no" :placeholder="t('acquire.batchNo')" clearable />
        <el-switch v-if="store.isFactory" v-model="f.pi_all" :active-text="t('acquire.piAll')" :disabled="!f.pi.trim()" />
        <el-button type="primary" @click="load(1)">{{ t("common.search") }}</el-button>
      </div>
      <el-alert v-if="f.pi_all" type="info" :closable="false" show-icon :title="t('acquire.piAllReadOnly')" class="mb" />
      <el-table v-loading="state.loading" :data="items" size="small" :empty-text="t('common.empty')">
        <el-table-column :label="t('common.sn')" min-width="170" fixed><template #default="{ row }"><span class="sn-mono">{{ row.sn }}</span></template></el-table-column>
        <el-table-column :label="t('common.seqText')" width="110"><template #default="{ row }"><span class="sn-mono">{{ row.seq_text }}</span></template></el-table-column>
        <el-table-column prop="seq_dec" :label="t('common.seqDec')" width="100" />
        <el-table-column prop="pi_no" label="PI" min-width="150" />
        <el-table-column prop="material_code" :label="t('common.material')" width="110" />
        <el-table-column prop="customer_code" :label="t('common.customer')" width="90" />
        <el-table-column :label="t('common.factory')" width="110"><template #default="{ row }">{{ nameOf(row.factory_code) }}</template></el-table-column>
        <el-table-column :label="t('common.status')" width="90"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
        <el-table-column prop="seq_pi_no" :label="t('acquire.seqOwner')" min-width="150" show-overflow-tooltip />
        <el-table-column prop="bill_no" :label="t('query.sourceBill')" min-width="130" />
        <el-table-column prop="batch_no" :label="t('acquire.batchNo')" min-width="170" />
        <el-table-column :label="t('query.source')" width="80"><template #default="{ row }">{{ t(`query.src${row.source}`) }}</template></el-table-column>
        <el-table-column prop="created_at" :label="t('query.createdAt')" width="160" />
        <el-table-column prop="printed_at" :label="t('query.printedAt')" width="160" />
      </el-table>
      <div class="foot">
        <span v-if="state.capped" class="muted">{{ t("query.capped", { n: state.total }) }}</span>
        <el-pagination layout="total, sizes, prev, pager, next" :total="state.total" :page-size="state.pageSize"
          :current-page="state.page" :page-sizes="[20, 50, 100, 500]" @current-change="load" @size-change="onSize" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import { api, urls } from "@/api";
import { useAuthStore } from "@/stores/auth";
import { usePaged } from "@/composables/usePaged";
import { useFactories } from "@/composables/useFactories";
import { STATUS_CODES, statusLabel } from "@/constants";
import { downloadWithToken } from "@/utils/download";
import PageHead from "@/components/PageHead.vue";
import FactorySelect from "@/components/FactorySelect.vue";
import StatusTag from "@/components/StatusTag.vue";

const { t } = useI18n();
const store = useAuthStore();
const { nameOf } = useFactories();
const f = reactive({ factory_code: "", pi: "", material_code: "", customer_code: "", status: "", sn: "", bill_no: "", batch_no: "", pi_all: false });
const exporting = ref(false);
const params = () => ({ ...f, pi: f.pi.trim(), pi_all: f.pi_all || undefined });
const { items, state, load, onSize } = usePaged<any>((q) => api.items({ ...params(), ...q }));

watch(() => f.pi, (v) => {
  if (!v.trim()) f.pi_all = false;
});

async function exportFile(format: string) {
  exporting.value = true;
  try {
    await downloadWithToken(urls.snExport({ ...params(), format }), `sn_export.${format}`);
  } catch (e: any) {
    ElMessage.error(e.message);
  } finally {
    exporting.value = false;
  }
}

onMounted(() => load(1));
</script>

<style scoped>
.mb { margin-bottom: 12px; }
.foot { display: flex; justify-content: space-between; align-items: center; margin-top: 12px; }
</style>
