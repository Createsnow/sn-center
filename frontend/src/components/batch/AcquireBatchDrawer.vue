<template>
  <el-drawer :model-value="!!batchNo" :title="t('query.acquireDetail', { no: batchNo })" size="820px" append-to-body @close="emit('update:batchNo', '')">
    <div v-if="downloadable" class="drawer-actions">
      <el-button size="small" @click="download"><el-icon><Download /></el-icon><span>{{ t("common.downloadXlsx") }}</span></el-button>
    </div>
    <el-table v-loading="state.loading" :data="items" size="small" :empty-text="t('common.empty')">
      <el-table-column prop="line_no" :label="t('query.lineNo')" width="60" />
      <el-table-column :label="t('common.sn')" min-width="180"><template #default="{ row }"><span class="sn-mono">{{ row.sn }}</span></template></el-table-column>
      <el-table-column :label="t('common.seqText')" width="100"><template #default="{ row }"><span class="sn-mono">{{ row.seq_text }}</span></template></el-table-column>
      <el-table-column prop="seq_dec" :label="t('common.seqDec')" width="90" />
      <el-table-column :label="t('query.billSeq')" min-width="140"><template #default="{ row }">{{ row.bill_no }} / {{ row.bill_seq }}</template></el-table-column>
      <el-table-column prop="material_code" :label="t('common.material')" width="100" />
      <el-table-column :label="t('common.attachNo')" width="90"><template #default="{ row }">{{ row.attach_no || "—" }}</template></el-table-column>
      <el-table-column prop="length" :label="t('common.length')" width="70" />
      <el-table-column :label="t('common.status')" width="100"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
    </el-table>
    <el-pagination class="pager" layout="total, prev, pager, next" :total="state.total" :page-size="state.pageSize"
      :current-page="state.page" @current-change="load" />
  </el-drawer>
</template>

<script setup lang="ts">
/** 领取批次明细（只读），可反复下载领取文件。v-model:batch-no 打开 / 关闭；领取未完成的批次传 :downloadable="false"。 */
import { watch } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import { api, urls } from "@/api";
import { usePaged } from "@/composables/usePaged";
import { downloadWithToken } from "@/utils/download";
import StatusTag from "@/components/StatusTag.vue";

const props = withDefaults(defineProps<{ batchNo: string; downloadable?: boolean }>(), { downloadable: true });
const emit = defineEmits<{ "update:batchNo": [no: string] }>();

const { t } = useI18n();
const { items, state, load } = usePaged<any>((q) => api.acquireBatchItems(props.batchNo, q), 50);

async function download() {
  try {
    await downloadWithToken(urls.acquireFile(props.batchNo), `${props.batchNo}.xlsx`);
  } catch (e: any) {
    ElMessage.error(e.message);
  }
}

watch(() => props.batchNo, (no) => no && load(1), { immediate: true });
</script>

<style scoped>
.drawer-actions { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 12px; }
</style>
