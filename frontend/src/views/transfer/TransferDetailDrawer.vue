<template>
  <el-drawer :model-value="!!det" size="980px" @close="det = null">
    <template #header>
      <div v-if="det" class="dh">
        <b class="sn-mono">{{ det.doc.transfer_no }}</b>
        <el-tag v-if="det.kind === 'ORG'" size="small" :type="TRANSFER_STATUS_TYPE[det.doc.status] || 'info'">{{ transferStatusLabel(det.doc.status) }}</el-tag>
        <el-tag v-if="det.doc.direction" size="small" effect="plain">{{ t(`transfer.direction.${det.doc.direction}`) }}</el-tag>
      </div>
    </template>
    <template v-if="det">
      <el-descriptions :column="2" size="small" border>
        <template v-if="det.kind === 'BILL'">
          <el-descriptions-item :label="t('transfer.mode')">{{ t(`transfer.billMode.${det.doc.mode}`) }}</el-descriptions-item>
          <el-descriptions-item :label="t('transfer.scope')">{{ det.doc.scope_text }}</el-descriptions-item>
          <el-descriptions-item :label="t('transfer.fromBill')">{{ det.doc.from_bill }} · {{ det.doc.from_pi }}</el-descriptions-item>
          <el-descriptions-item :label="t('transfer.toBill')">{{ det.doc.to_bill }} · {{ det.doc.to_pi }}</el-descriptions-item>
          <el-descriptions-item :label="t('common.operator')">{{ det.doc.created_by }} · {{ fmt(det.doc.created_at) }}</el-descriptions-item>
        </template>
        <template v-else>
          <el-descriptions-item :label="t('transfer.mode')">{{ t(`transfer.orgMode.${det.doc.mode}`) }}</el-descriptions-item>
          <el-descriptions-item :label="t('transfer.scope')">{{ scopeLabel(det.doc.scope) }} {{ det.doc.from_material || det.doc.from_sn }}</el-descriptions-item>
          <el-descriptions-item :label="t('transfer.source')">{{ nameOf(det.doc.from_factory) }} · {{ det.doc.from_pi }}</el-descriptions-item>
          <el-descriptions-item :label="t('transfer.target')">
            {{ nameOf(det.doc.to_factory) }} · {{ det.doc.to_pi }} {{ det.doc.to_customer }} {{ det.doc.to_material }}
            <el-tag v-if="det.doc.manual_fields" size="small" type="info" class="ml">{{ t("transfer.manual") }}：{{ det.doc.manual_fields }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('transfer.applied')">{{ det.doc.applied_by }} · {{ fmt(det.doc.applied_at) }}</el-descriptions-item>
          <el-descriptions-item :label="t('transfer.decided')">
            <template v-if="det.doc.decided_by">{{ det.doc.decided_by }} · {{ fmt(det.doc.decided_at) }}<span v-if="det.doc.decide_note"> · {{ det.doc.decide_note }}</span></template>
            <span v-else class="muted">—</span>
          </el-descriptions-item>
        </template>
        <el-descriptions-item :label="t('common.qty')"><span class="num">{{ det.doc.qty }}</span></el-descriptions-item>
        <el-descriptions-item :label="t('common.reason')">{{ det.doc.reason }}</el-descriptions-item>
      </el-descriptions>

      <div class="row mt">
        <el-button v-if="det.kind === 'ORG' && det.doc.status === 'APPROVED'" size="small" @click="downloadOutList(det.doc)">
          <el-icon><Download /></el-icon><span>{{ t("transfer.outList") }}</span>
        </el-button>
      </div>

      <template v-if="det.doc.deliveries?.length || det.doc.out_batches?.length">
        <h4 class="sub">{{ t("transfer.pushTitle") }}</h4>
        <el-table v-if="det.doc.deliveries?.length" :data="det.doc.deliveries" size="small">
          <el-table-column :label="t('transfer.pushTo')" min-width="160"><template #default="{ row }">{{ nameOf(row.factory_code) }} · {{ row.pi_no }}</template></el-table-column>
          <el-table-column :label="t('common.qty')" align="right" width="70"><template #default="{ row }"><span class="num">{{ row.qty }}</span></template></el-table-column>
          <el-table-column :label="t('status.TO_PUSH')" align="right" width="80"><template #default="{ row }"><span class="num">{{ row.to_push }}</span></template></el-table-column>
          <el-table-column :label="t('transfer.batches')" min-width="200">
            <template #default="{ row }">
              <template v-if="row.batches.length">
                <el-button v-for="no in row.batches" :key="no" link type="primary" class="sn-mono small" @click="batchNo = no">{{ no }}</el-button>
              </template>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('common.actions')" width="170" align="right">
            <template #default="{ row }">
              <el-button v-if="store.isAdmin && row.remaining && (row.remaining.batches || row.remaining.unbatched)" size="small"
                :loading="busy === `resume:${row.factory_code}:${row.pi_no}`" @click="resume(row.factory_code, row.pi_no)">
                {{ t("transfer.resume", { n: row.remaining.pushes }) }}
              </el-button>
              <span v-else-if="!row.remaining" class="muted">{{ t("transfer.noMes") }}</span>
            </template>
          </el-table-column>
        </el-table>
        <el-table v-if="det.doc.out_batches?.length" :data="det.doc.out_batches" size="small" class="mt">
          <el-table-column :label="t('transfer.outBatch')" min-width="220">
            <template #default="{ row }"><el-button link type="primary" class="sn-mono" @click="batchNo = row.batch_no">{{ row.batch_no }}</el-button></template>
          </el-table-column>
          <el-table-column :label="t('common.factory')" min-width="120"><template #default="{ row }">{{ nameOf(row.factory_code) }}</template></el-table-column>
          <el-table-column :label="t('common.qty')" align="right" width="70"><template #default="{ row }"><span class="num">{{ row.qty }}</span></template></el-table-column>
          <el-table-column :label="t('common.status')" width="110">
            <template #default="{ row }"><el-tag size="small" :type="BATCH_RESULT_TYPE[row.result] || 'info'">{{ batchResultLabel(row.result) }}</el-tag></template>
          </el-table-column>
          <el-table-column :label="t('transfer.failReason')" min-width="160" show-overflow-tooltip><template #default="{ row }">{{ row.fail_reason || "—" }}</template></el-table-column>
          <el-table-column :label="t('common.actions')" width="230" align="right">
            <template #default="{ row }">
              <template v-if="store.isAdmin && canConfirm(row)">
                <el-button link type="primary" :loading="busy === `resume:${row.factory_code}:#XO`" @click="resume(row.factory_code, '#XO')">{{ t("transfer.resumeShort") }}</el-button>
                <el-button link type="success" @click="manual?.open(row.batch_no, 'confirm')">{{ t("transfer.manualConfirm") }}</el-button>
                <el-button v-if="canVoid(row)" link type="danger" @click="manual?.open(row.batch_no, 'void')">{{ t("transfer.void") }}</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <h4 class="sub">{{ t("transfer.itemsTitle") }}</h4>
      <el-table v-loading="ditems.state.loading" :data="ditems.items.value" size="small" :empty-text="t('common.empty')">
        <el-table-column :label="t('common.sn')" min-width="190">
          <template #default="{ row }"><div class="sn-mono">{{ row.sn }}</div><div class="muted">{{ row.seq_text }} · {{ row.seq_pi_no }}</div></template>
        </el-table-column>
        <el-table-column :label="t('transfer.before')" min-width="200">
          <template #default="{ row }">
            <div>{{ statusLabel(row.from_status) }} · {{ row.from_factory || "—" }} · {{ row.from_pi }}</div>
            <div class="muted">{{ row.from_bill }}/{{ row.from_seq }} · {{ row.from_material || "—" }} · rev {{ row.from_rev }}</div>
            <div v-if="row.from_delivery_batch || row.from_acquire_batch" class="muted sn-mono small">{{ row.from_delivery_batch || row.from_acquire_batch }}</div>
          </template>
        </el-table-column>
        <el-table-column :label="t('transfer.after')" min-width="200">
          <template #default="{ row }">
            <div>{{ statusLabel(row.to_status) }} · {{ row.to_factory || "—" }} · {{ row.to_pi }}</div>
            <div class="muted">{{ row.to_bill }}/{{ row.to_seq }} · {{ row.to_material || "—" }} · rev {{ row.to_rev }}</div>
            <div v-if="row.out_batch_no" class="muted sn-mono small">{{ t("transfer.outBatch") }} {{ row.out_batch_no }}</div>
          </template>
        </el-table-column>
        <el-table-column :label="t('transfer.current')" width="150">
          <template #default="{ row }">
            <el-tag v-if="row.current_status === 'TRANSFERRED_OUT'" size="small" type="info">{{ t("transfer.transferredOut") }}</el-tag>
            <StatusTag v-else-if="row.current_status" :status="row.current_status" />
            <div v-if="row.cur_batch" class="muted sn-mono small">{{ row.cur_batch }}</div>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" layout="total, prev, pager, next" :total="ditems.state.total" :page-size="ditems.state.pageSize"
        :current-page="ditems.state.page" @current-change="ditems.load" />
    </template>

    <BatchManualDialog ref="manual" @done="refresh" />
    <DeliveryBatchDrawer v-model:batch-no="batchNo" @changed="refresh" />
  </el-drawer>
</template>

<script setup lang="ts">
/** 转单 / 转组织单详情：单据信息、转出号清单下载、转移后的推送（续推、转出批次人工确认 / 作废）与逐枚明细。
 *  父组件用 ref 调 show(类型, 单据) 或 open(类型, id)。 */
import { ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import { api, urls } from "@/api";
import StatusTag from "@/components/StatusTag.vue";
import BatchManualDialog from "@/components/batch/BatchManualDialog.vue";
import DeliveryBatchDrawer from "@/components/batch/DeliveryBatchDrawer.vue";
import { useFactories } from "@/composables/useFactories";
import { usePaged } from "@/composables/usePaged";
import { useTime } from "@/composables/useTime";
import { BATCH_RESULT_TYPE, TRANSFER_STATUS_TYPE, batchResultLabel, scopeLabel, statusLabel, transferStatusLabel } from "@/constants";
import { useAuthStore } from "@/stores/auth";
import { canConfirm, canVoid } from "@/utils/batch";
import { downloadWithToken } from "@/utils/download";

type Kind = "BILL" | "ORG";

const { t } = useI18n();
const store = useAuthStore();
const { nameOf } = useFactories();
const { fmt } = useTime();
const busy = ref("");
const manual = ref<InstanceType<typeof BatchManualDialog> | null>(null);
const batchNo = ref("");

const det = ref<{ kind: Kind; doc: any } | null>(null);
const ditems = usePaged<any>((q) => (det.value!.kind === "BILL" ? api.billTransferItems(det.value!.doc.id, q) : api.orgTransferItems(det.value!.doc.id, q)), 50);

function show(kind: Kind, doc: any) {
  det.value = { kind, doc };
  ditems.load(1);
}
async function open(kind: Kind, id: number) {
  show(kind, kind === "BILL" ? await api.billTransferOne(id) : await api.orgTransfer(id));
}
async function refresh() {
  if (det.value) await open(det.value.kind, det.value.doc.id);
}

async function resume(factory: string, key: string) {
  busy.value = `resume:${factory}:${key}`;
  try {
    await api.resumePush(factory, key);
    ElMessage.success(t("transfer.resumed"));
    await refresh();
  } finally {
    busy.value = "";
  }
}

async function downloadOutList(doc: any) {
  try {
    await downloadWithToken(urls.outList(doc.id, "xlsx"), `out_${doc.transfer_no}.xlsx`);
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

defineExpose({ show, open });
</script>

<style scoped>
.ml { margin-left: 8px; }
.mt { margin-top: 12px; }
.dh { display: flex; align-items: center; gap: 10px; font-size: 15px; color: var(--app-text); }
.row { display: flex; gap: 8px; flex-wrap: wrap; }
.sub { margin: 18px 0 8px; font-size: 14px; }
.small { font-size: 11px; }
</style>
