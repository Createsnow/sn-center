<template>
  <el-drawer :model-value="!!batchNo" :title="t('query.batchDetail', { no: batchNo })" size="880px" append-to-body @close="emit('update:batchNo', '')">
    <template v-if="batch">
      <div class="drawer-actions">
        <el-button size="small" @click="exportBatch"><el-icon><Download /></el-icon><span>{{ t("common.downloadXlsx") }}</span></el-button>
        <template v-if="store.isAdmin">
          <el-button v-if="canConfirm(batch)" size="small" type="primary" @click="manual?.open(batch.batch_no, 'confirm')">{{ t("query.confirmBatch") }}</el-button>
          <el-button v-if="canVoid(batch)" size="small" type="danger" plain @click="manual?.open(batch.batch_no, 'void')">{{ t("query.voidBatch") }}</el-button>
        </template>
      </div>
      <el-descriptions :column="2" size="small" border class="mb">
        <el-descriptions-item :label="t('query.batchSource')">{{ batchSourceLabel(batch.source) }} · {{ batchTypeLabel(batch.batch_type) }}</el-descriptions-item>
        <el-descriptions-item :label="t('query.result')"><el-tag size="small" :type="BATCH_RESULT_TYPE[batch.result] || 'info'">{{ batchResultLabel(batch.result) }}</el-tag></el-descriptions-item>
        <el-descriptions-item :label="t('common.factory')">{{ nameOf(batch.factory_code) }}（{{ batch.factory_code }}）</el-descriptions-item>
        <el-descriptions-item label="PI">{{ batch.pi_no }}</el-descriptions-item>
        <el-descriptions-item :label="t('common.qty')">{{ batch.qty }}</el-descriptions-item>
        <el-descriptions-item :label="t('query.receiptCounts')">{{ batch.accepted_qty }} / {{ batch.duplicate_qty }} / {{ batch.conflict_qty }} / {{ batch.stale_qty }}</el-descriptions-item>
        <el-descriptions-item :label="t('query.confirmMethod')">{{ confirmMethodLabel(batch.confirm_method) || "—" }}</el-descriptions-item>
        <el-descriptions-item :label="t('query.confirmedAt')">{{ fmt(batch.confirmed_at) }}</el-descriptions-item>
        <el-descriptions-item v-if="batch.source === 'PUSH'" :label="t('query.pushState')">{{ pushStateLabel(batch.push_state) }} · {{ t("query.retry") }} {{ batch.retry_count }}</el-descriptions-item>
        <el-descriptions-item v-if="batch.source === 'PUSH'" :label="t('query.pushCount')">{{ batch.push_count }} / {{ batch.repush_count }}</el-descriptions-item>
        <el-descriptions-item v-if="batch.fail_reason" :label="t('query.failReason')" :span="2">{{ batch.fail_code ? `[${batch.fail_code}] ` : "" }}{{ batch.fail_reason }}</el-descriptions-item>
        <el-descriptions-item v-if="batch.manual_at" :label="t('query.manual')" :span="2">
          <div>{{ t("query.manualBy", { by: batch.manual_by, at: fmt(batch.manual_at) }) }}</div>
          <div>{{ t("common.reason") }}：{{ batch.manual_reason }}</div>
          <div>{{ t("batch.basis") }}：{{ batch.manual_basis }}</div>
        </el-descriptions-item>
        <el-descriptions-item v-if="batch.rebuilt_from" :label="t('query.rebuiltFrom')"><el-button link type="primary" @click="emit('update:batchNo', batch.rebuilt_from)">{{ batch.rebuilt_from }}</el-button></el-descriptions-item>
        <el-descriptions-item v-if="batch.rebuilt_to" :label="t('query.rebuiltTo')"><el-button link type="primary" @click="emit('update:batchNo', batch.rebuilt_to)">{{ batch.rebuilt_to }}</el-button></el-descriptions-item>
        <el-descriptions-item v-if="batch.transfer_doc_no" :label="t('query.transferNo')">{{ batch.transfer_doc_no }}</el-descriptions-item>
        <el-descriptions-item :label="t('query.bills')" :span="2">{{ (batch.bills || []).join("、") }}</el-descriptions-item>
        <el-descriptions-item :label="t('query.materials')" :span="2">{{ (batch.materials || []).join("、") }}</el-descriptions-item>
        <el-descriptions-item :label="t('query.digest')" :span="2"><span class="sn-mono digest">{{ batch.items_digest }}</span></el-descriptions-item>
        <el-descriptions-item :label="t('common.operator')">{{ batch.created_by }}</el-descriptions-item>
        <el-descriptions-item :label="t('common.createdAt')">{{ fmt(batch.created_at) }}</el-descriptions-item>
      </el-descriptions>

      <div class="surface__title">{{ t("query.segments") }}</div>
      <el-table :data="batch.segments || []" size="small" class="mb" :empty-text="t('common.empty')">
        <el-table-column prop="seq_pi_no" :label="t('query.seqOwner')" min-width="140" />
        <el-table-column :label="t('query.segRange')" min-width="300">
          <template #default="{ row }"><span class="sn-mono">{{ row.start_sn }} ~ {{ row.end_sn }}</span></template>
        </el-table-column>
        <el-table-column :label="t('common.seqDec')" width="130"><template #default="{ row }">{{ row.start_seq }} ~ {{ row.end_seq }}</template></el-table-column>
        <el-table-column prop="qty" :label="t('common.qty')" width="70" align="right" />
      </el-table>

      <div class="surface__title">{{ t("query.receipts") }}</div>
      <el-table :data="batch.receipts || []" size="small" class="mb" :empty-text="t('common.empty')">
        <el-table-column :label="t('common.time')" width="160"><template #default="{ row }">{{ fmt(row.created_at) }}</template></el-table-column>
        <el-table-column :label="t('query.receiptMethod')" width="110"><template #default="{ row }">{{ confirmMethodLabel(row.method) }}</template></el-table-column>
        <el-table-column :label="t('query.receiptOutcome')" width="100"><template #default="{ row }">{{ receiptOutcomeLabel(row.outcome) }}</template></el-table-column>
        <el-table-column :label="t('query.receiptVerdict')" width="100"><template #default="{ row }">{{ receiptVerdictLabel(row.verdict) }}</template></el-table-column>
        <el-table-column prop="detail" :label="t('query.alertDetail')" min-width="200" show-overflow-tooltip />
        <el-table-column prop="created_by" :label="t('common.operator')" width="90" />
      </el-table>
    </template>

    <div class="surface__title">{{ t("query.items") }}</div>
    <el-table v-loading="itemState.loading" :data="items" size="small" :empty-text="t('common.empty')">
      <el-table-column prop="line_no" :label="t('query.lineNo')" width="60" />
      <el-table-column :label="t('common.sn')" min-width="170"><template #default="{ row }"><span class="sn-mono">{{ row.sn }}</span></template></el-table-column>
      <el-table-column prop="rev" label="rev" width="50" />
      <el-table-column :label="t('query.pushType')" width="80"><template #default="{ row }">{{ pushTypeLabel(row.push_type) }}</template></el-table-column>
      <el-table-column :label="t('query.billSeq')" min-width="140"><template #default="{ row }">{{ row.bill_no }} / {{ row.bill_seq }}</template></el-table-column>
      <el-table-column prop="material_code" :label="t('common.material')" width="100" />
      <el-table-column :label="t('common.attachNo')" width="90"><template #default="{ row }">{{ row.attach_no || "—" }}</template></el-table-column>
      <el-table-column :label="t('common.length')" width="70"><template #default="{ row }">{{ row.length || "—" }}</template></el-table-column>
      <el-table-column :label="t('query.receiptResult')" width="100">
        <template #default="{ row }"><el-tag v-if="row.receipt_result" size="small" :type="RECEIPT_RESULT_TYPE[row.receipt_result] || 'info'">{{ receiptResultLabel(row.receipt_result) }}</el-tag></template>
      </el-table-column>
      <el-table-column :label="t('query.existing')" min-width="150">
        <template #default="{ row }"><span v-if="row.existing_rev != null || row.existing_batch_no">rev {{ row.existing_rev ?? "—" }} · {{ row.existing_batch_no || "—" }}</span></template>
      </el-table-column>
      <el-table-column :label="t('query.currentState')" width="140">
        <template #default="{ row }"><StatusTag v-if="row.status" :status="row.status" /> <span class="muted">{{ row.current_factory }}</span></template>
      </el-table-column>
    </el-table>
    <el-pagination class="pager" layout="total, prev, pager, next" :total="itemState.total" :page-size="itemState.pageSize"
      :current-page="itemState.page" @current-change="loadItems" />

    <BatchManualDialog ref="manual" @done="onManualDone" />
  </el-drawer>
</template>

<script setup lang="ts">
/** 交付批次（推送、拉取、转出）详情：结果、确认方式、人工处理、号段、回执存档、明细与逐枚回执；总部可人工确认、作废。
 *  v-model:batch-no 打开 / 关闭（空串为关闭）；人工处理后发出 changed。 */
import { ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import { api, urls } from "@/api";
import { useAuthStore } from "@/stores/auth";
import { usePaged } from "@/composables/usePaged";
import { useFactories } from "@/composables/useFactories";
import { useTime } from "@/composables/useTime";
import {
  BATCH_RESULT_TYPE, RECEIPT_RESULT_TYPE, batchResultLabel, batchSourceLabel, batchTypeLabel, confirmMethodLabel, pushStateLabel,
  pushTypeLabel, receiptOutcomeLabel, receiptResultLabel, receiptVerdictLabel,
} from "@/constants";
import { canConfirm, canVoid } from "@/utils/batch";
import { downloadWithToken } from "@/utils/download";
import StatusTag from "@/components/StatusTag.vue";
import BatchManualDialog from "@/components/batch/BatchManualDialog.vue";

const props = defineProps<{ batchNo: string }>();
const emit = defineEmits<{ "update:batchNo": [no: string]; changed: [] }>();

const { t } = useI18n();
const store = useAuthStore();
const { nameOf } = useFactories();
const { fmt } = useTime();
const batch = ref<any>(null);
const manual = ref<InstanceType<typeof BatchManualDialog> | null>(null);
const { items, state: itemState, load: loadItems } = usePaged<any>((q) => api.deliveryBatchItems(props.batchNo, q), 50);

async function load() {
  batch.value = null;
  if (!props.batchNo) return;
  loadItems(1);
  batch.value = await api.deliveryBatch(props.batchNo);
}

async function exportBatch() {
  try {
    await downloadWithToken(urls.deliveryExport(props.batchNo), `${props.batchNo}.xlsx`);
  } catch (e: any) {
    ElMessage.error(e.message);
  }
}

async function onManualDone() {
  await load();
  emit("changed");
}

watch(() => props.batchNo, load, { immediate: true });
</script>

<style scoped>
.mb { margin-bottom: 12px; }
.drawer-actions { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 12px; }
.digest { word-break: break-all; font-size: 12px; }
</style>
