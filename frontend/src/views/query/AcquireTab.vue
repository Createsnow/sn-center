<template>
  <div class="toolbar">
    <FactorySelect v-if="store.isAdmin" v-model="f.factory_code" clearable />
    <el-input v-model="f.pi" placeholder="PI" clearable @keyup.enter="load(1)" />
    <el-input v-model="f.batch_no" :placeholder="t('common.batchNo')" clearable @keyup.enter="load(1)" />
    <el-input v-model="f.request_no" :placeholder="t('query.requestNo')" clearable @keyup.enter="load(1)" />
    <el-button type="primary" @click="load(1)">{{ t("common.search") }}</el-button>
  </div>
  <div class="fill-body">
    <el-table v-loading="state.loading" height="100%" :data="items" size="small" :empty-text="t('common.empty')">
      <el-table-column :label="t('common.batchNo')" min-width="190">
        <template #default="{ row }"><el-button link type="primary" @click="emit('openAcquire', row.batch_no)">{{ row.batch_no }}</el-button></template>
      </el-table-column>
      <el-table-column :label="t('common.factory')" width="120" show-overflow-tooltip><template #default="{ row }">{{ nameOf(row.factory_code) }}</template></el-table-column>
      <el-table-column prop="pi_no" label="PI" min-width="140" show-overflow-tooltip />
      <el-table-column prop="request_no" :label="t('query.requestNo')" min-width="160" show-overflow-tooltip />
      <el-table-column :label="t('query.mesOrg')" width="100"><template #default="{ row }">{{ row.mes_org ? t("common.yes") : t("common.no") }}</template></el-table-column>
      <el-table-column :label="t('common.status')" width="90">
        <template #default="{ row }"><el-tag size="small" :type="row.status === 'DONE' ? 'success' : 'warning'">{{ codeLabel(t, "query.acqStatus", row.status) }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="qty" :label="t('common.qty')" width="80" align="right" />
      <el-table-column prop="created_by" :label="t('common.operator')" width="100" />
      <el-table-column :label="t('common.createdAt')" width="160"><template #default="{ row }">{{ fmt(row.created_at) }}</template></el-table-column>
      <el-table-column :label="t('query.finishedAt')" width="160"><template #default="{ row }">{{ fmt(row.finished_at) }}</template></el-table-column>
      <el-table-column :label="t('common.download')" width="120">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="row.status !== 'DONE'" @click="download(row.batch_no, 'xlsx')">xlsx</el-button>
          <el-button link type="primary" :disabled="row.status !== 'DONE'" @click="download(row.batch_no, 'csv')">csv</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
  <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="state.total" :page-size="state.pageSize"
    :current-page="state.page" :page-sizes="[20, 50, 100]" @current-change="load" @size-change="onSize" />
</template>

<script setup lang="ts">
/** 领取批次列表（只读）：可反复下载领取文件；点批次号打开明细。 */
import { onMounted, reactive } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import { api, urls } from "@/api";
import { useAuthStore } from "@/stores/auth";
import { usePaged } from "@/composables/usePaged";
import { useFactories } from "@/composables/useFactories";
import { useTime } from "@/composables/useTime";
import { downloadWithToken } from "@/utils/download";
import FactorySelect from "@/components/FactorySelect.vue";
import { codeLabel, trimmed } from "./filters";

const props = defineProps<{ init: { factory_code: string; pi: string; batch_no: string } }>();
const emit = defineEmits<{ openAcquire: [no: string] }>();

const { t } = useI18n();
const store = useAuthStore();
const { nameOf } = useFactories();
const { fmt } = useTime();

const f = reactive({ request_no: "", ...props.init });
const { items, state, load, onSize } = usePaged<any>((q) => api.acquireBatches({ ...trimmed(f), ...q }));

async function download(no: string, format: string) {
  try {
    await downloadWithToken(urls.acquireFile(no, format), `${no}.${format}`);
  } catch (e: any) {
    ElMessage.error(e.message);
  }
}

onMounted(() => load(1));
</script>
