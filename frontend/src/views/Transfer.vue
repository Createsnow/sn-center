<template>
  <div class="page">
    <PageHead :title="t('transfer.title')" :desc="t('transfer.desc')">
      <el-button v-if="store.canAct" type="primary" @click="openNew()"><el-icon><Plus /></el-icon><span>{{ t("transfer.new") }}</span></el-button>
    </PageHead>

    <!-- ================= 转移记录（转单 + 转组织） ================= -->
    <div class="surface">
      <div class="toolbar">
        <el-radio-group v-model="lq.status" @change="list.load(1)">
          <el-radio-button v-for="s in STATUSES" :key="s" :value="s">
            {{ s ? transferStatusLabel(s) : t("common.all") }}<span v-if="s === 'PENDING' && pending" class="cnt num">{{ pending }}</span>
          </el-radio-button>
        </el-radio-group>
        <el-select v-model="lq.kind" class="w140" clearable :placeholder="t('transfer.allKinds')" @change="list.load(1)">
          <el-option v-for="k in KINDS" :key="k" :value="k" :label="t(`transfer.kindName.${k}`)" />
        </el-select>
        <FactorySelect v-if="!store.bound" v-model="lq.factory_code" clearable :placeholder="t('app.allFactories')" @update:model-value="list.load(1)" />
        <el-input v-model="lq.pi" :placeholder="t('transfer.piInOut')" clearable @keyup.enter="list.load(1)" @clear="list.load(1)" />
        <el-input v-model="lq.bill_no" :placeholder="t('common.billNo')" clearable @keyup.enter="list.load(1)" @clear="list.load(1)" />
        <el-button type="primary" @click="list.load(1)">{{ t("common.search") }}</el-button>
      </div>
      <el-table v-loading="list.state.loading" :data="list.items.value" size="small" :empty-text="t('common.empty')" :row-class-name="rowClass">
        <el-table-column :label="t('transfer.no')" min-width="180">
          <template #default="{ row }">
            <el-button link type="primary" class="sn-mono" @click="detail?.open(row.kind, row.id)">{{ row.transfer_no }}</el-button>
            <div class="muted">
              <el-tag size="small" effect="plain" :type="row.kind === 'BILL' ? 'primary' : 'warning'">{{ t(`transfer.kindName.${row.kind}`) }}</el-tag>
              <span class="ml">{{ modeLabel(row) }}</span>
              <el-tag v-if="row.direction" size="small" :type="row.direction === 'OUT' ? 'warning' : 'success'" class="ml">{{ t(`transfer.direction.${row.direction}`) }}</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.status')" width="90">
          <template #default="{ row }"><el-tag size="small" :type="TRANSFER_STATUS_TYPE[row.status] || 'info'">{{ transferStatusLabel(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column :label="t('transfer.fromTo')" min-width="360">
          <template #default="{ row }">
            <div class="flow">
              <div class="end">
                <div class="one" :title="row.from_bill">{{ row.from_factory ? `${nameOf(row.from_factory)} · ` : "" }}{{ row.from_pi }}<template v-if="row.from_bill"> · {{ row.from_bill }}</template></div>
                <div class="muted one" :title="sourceText(row)">{{ sourceText(row) }}</div>
              </div>
              <el-icon class="arrow"><Right /></el-icon>
              <div class="end">
                <div class="one" :title="row.to_factory ? nameOf(row.to_factory) : undefined">
                  <b>{{ row.to_factory ? `${nameOf(row.to_factory)} · ` : "" }}{{ row.to_pi }}</b>
                  <el-tag v-if="row.to_factory_disabled" size="small" type="danger" class="ml">{{ t("transfer.targetDisabled") }}</el-tag>
                </div>
                <div class="muted one" :title="targetText(row)">{{ targetText(row) }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.qty')" align="right" width="70"><template #default="{ row }"><span class="num">{{ row.qty }}</span></template></el-table-column>
        <el-table-column prop="reason" :label="t('common.reason')" min-width="110" show-overflow-tooltip />
        <el-table-column :label="t('common.operator')" width="150">
          <template #default="{ row }"><div>{{ row.created_by }}</div><div class="muted">{{ fmt(row.created_at) }}</div></template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="170" align="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="detail?.open(row.kind, row.id)">{{ t("common.detail") }}</el-button>
            <template v-if="row.status === 'PENDING'">
              <template v-if="store.isAdmin">
                <el-button link type="success" :disabled="row.to_factory_disabled" @click="decide(row, 'approve')">{{ t("transfer.approve") }}</el-button>
                <el-button link type="danger" @click="decide(row, 'reject')">{{ t("transfer.reject") }}</el-button>
              </template>
              <el-button v-else-if="store.isFactory && row.direction === 'OUT'" link type="warning" @click="withdraw(row)">{{ t("transfer.withdraw") }}</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="list.state.total" :page-size="list.state.pageSize"
        :current-page="list.state.page" :page-sizes="[20, 50, 100]" @current-change="list.load" @size-change="list.onSize" />
    </div>

    <TransferDialog ref="dlg" @done="onDone" />
    <TransferDetailDrawer ref="detail" @decide="decide" @withdraw="withdraw" />
  </div>
</template>

<script setup lang="ts">
/** SN 转移（F-15、F-16、F-17）：转单与转组织合在一张记录表（申请、审批、撤回）；新建与详情见 views/transfer。 */
import { onMounted, reactive, ref } from "vue";
import { useRoute } from "vue-router";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "@/api";
import PageHead from "@/components/PageHead.vue";
import FactorySelect from "@/components/FactorySelect.vue";
import { useFactories } from "@/composables/useFactories";
import { usePaged } from "@/composables/usePaged";
import { useTime } from "@/composables/useTime";
import { TRANSFER_STATUS_TYPE, scopeLabel, transferStatusLabel } from "@/constants";
import { useAuthStore } from "@/stores/auth";
import TransferDialog from "@/views/transfer/TransferDialog.vue";
import TransferDetailDrawer from "@/views/transfer/TransferDetailDrawer.vue";
import { type Kind, KINDS, snRange } from "@/views/transfer/types";

const STATUSES = ["", "PENDING", "APPROVED", "REJECTED", "WITHDRAWN"];

const { t } = useI18n();
const store = useAuthStore();
const route = useRoute();
const { nameOf } = useFactories();
const { fmt } = useTime();
const dlg = ref<InstanceType<typeof TransferDialog> | null>(null);
const detail = ref<InstanceType<typeof TransferDetailDrawer> | null>(null);

// ---------------- 转移记录：转单与转组织合在一张表（新的在前）；首页「待处理」带 status=PENDING 进来
const lq = reactive({
  status: typeof route.query.status === "string" ? route.query.status : "", kind: "", factory_code: "", pi: "", bill_no: "",
});
const pending = ref(0);
const list = usePaged<any>(async (q) => {
  const r = await api.transfers({ ...q, ...lq, pi: lq.pi.trim(), bill_no: lq.bill_no.trim() });
  pending.value = r.pending ?? 0;
  return r;
});

function modeLabel(row: any) {
  return row.kind === "BILL" ? t(`transfer.billMode.${row.mode}`) : t(`transfer.orgMode.${row.mode}`);
}
function rowClass({ row }: { row: any }) {
  return row.status === "PENDING" ? "is-pending" : "";
}
/** 来源范围：整张单据 / 物料 / 号段。转单没有物料、号段列，取范围说明「PI / 单据 / 物料 / 起止号」去掉 PI、单据；
 *  早期转单没有范围，只显示范围说明。 */
function sourceText(d: any) {
  if (!d.scope) return d.scope_text;
  const rest = d.kind === "BILL" ? (d.scope_text || "").split(" / ").slice(2) : [d.scope !== "BILL" && d.from_material, d.from_sn && snRange(d)];
  return [scopeLabel(d.scope), ...rest].filter(Boolean).join(" · ");
}
/** 目标：单据 / 序号 · 物料 · 客户（早期单子没有目标单据）。 */
function targetText(d: { to_bill?: string; to_seq?: number; to_material?: string; to_customer?: string }) {
  return [
    d.to_bill && (d.to_seq ? `${d.to_bill} / ${d.to_seq}` : d.to_bill),
    d.to_material && `${t("common.material")} ${d.to_material}`,
    d.to_customer && `${t("common.customer")} ${d.to_customer}`,
  ].filter(Boolean).join(" · ");
}

async function decide(row: any, kind: "approve" | "reject") {
  const { value } = await ElMessageBox.prompt(
    t(kind === "approve" ? "transfer.approveConfirm" : "transfer.rejectConfirm", { no: row.transfer_no, n: row.qty }),
    kind === "approve" ? t("transfer.approve") : t("transfer.reject"),
    { inputType: "textarea", inputPlaceholder: t("transfer.notePlaceholder"), inputValidator: (v) => (v || "").length <= 500, type: "warning" },
  );
  const r = kind === "approve" ? await api.approveOrg(row.id, value || undefined) : await api.rejectOrg(row.id, value || undefined);
  ElMessage.success(t("common.done"));
  list.load();
  if (kind === "approve") detail.value?.open("ORG", r.id);
  else detail.value?.refresh();
}

async function withdraw(row: any) {
  await ElMessageBox.confirm(t("transfer.withdrawConfirm", { no: row.transfer_no }), t("transfer.withdraw"), { type: "warning" });
  await api.withdrawOrg(row.id);
  ElMessage.success(t("common.done"));
  list.load();
  detail.value?.refresh();
}

/** 总部默认转单，生产组织操作员只能申请转组织（对话框里总部可切换类型）。 */
function openNew(kind?: Kind, prefill: { factory_code?: string; pi?: string; material?: string } = {}) {
  dlg.value?.open(kind ?? (store.isAdmin ? "BILL" : "ORG"), prefill);
}

function onDone(kind: Kind, doc: any) {
  list.load(1);
  if (kind === "BILL") detail.value?.show("BILL", doc);
  else if (doc.status === "APPROVED") detail.value?.open("ORG", doc.id);
}

onMounted(() => {
  list.load(1);
  if (route.query.new === "1" && store.canAct) {
    openNew(route.query.kind === "BILL" ? "BILL" : "ORG", {
      factory_code: route.query.factory_code ? String(route.query.factory_code) : undefined,
      pi: route.query.pi ? String(route.query.pi) : undefined,
      material: route.query.material !== undefined ? String(route.query.material) : undefined,
    });
  }
});
</script>

<style scoped>
.ml { margin-left: 8px; }
.cnt { margin-left: 6px; font-size: 12px; }
.one { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.toolbar .w140 { width: 140px; }
.toolbar :deep(.el-input), .toolbar :deep(.factory-select) { width: 170px; }
:deep(.el-table .is-pending td.el-table__cell:first-child) { box-shadow: inset 3px 0 0 var(--el-color-warning); }
.flow { display: grid; grid-template-columns: minmax(0, 1fr) 16px minmax(0, 1fr); align-items: center; gap: 8px; }
.flow .end { min-width: 0; }
.flow .arrow { color: var(--app-muted); }
</style>
