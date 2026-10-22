<template>
  <div class="page">
    <PageHead :title="t('transfer.title')" :desc="t('transfer.desc')">
      <el-button v-if="tab === 'bill' && store.isAdmin" type="primary" @click="dlg?.open('BILL')"><el-icon><Plus /></el-icon><span>{{ t("transfer.newBill") }}</span></el-button>
      <el-button v-if="tab === 'org' && store.canAct" type="primary" @click="dlg?.open('ORG')"><el-icon><Plus /></el-icon><span>{{ store.isAdmin ? t("transfer.newDirect") : t("transfer.newApply") }}</span></el-button>
    </PageHead>

    <el-tabs v-model="tab" @tab-change="onTab">
      <!-- ================= 转单记录 ================= -->
      <el-tab-pane :label="t('transfer.tabBill')" name="bill">
        <div class="surface">
          <div class="toolbar">
            <FactorySelect v-if="!store.bound" v-model="bq.factory_code" clearable :placeholder="t('app.allFactories')" @update:model-value="bills.load(1)" />
            <el-input v-model="bq.pi" placeholder="PI" clearable @keyup.enter="bills.load(1)" @clear="bills.load(1)" />
            <el-input v-model="bq.bill_no" :placeholder="t('common.billNo')" clearable @keyup.enter="bills.load(1)" @clear="bills.load(1)" />
            <el-button type="primary" @click="bills.load(1)">{{ t("common.search") }}</el-button>
          </div>
          <el-table v-loading="bills.state.loading" :data="bills.items.value" size="small" :empty-text="t('common.empty')">
            <el-table-column prop="transfer_no" :label="t('transfer.no')" min-width="180"><template #default="{ row }"><span class="sn-mono">{{ row.transfer_no }}</span></template></el-table-column>
            <el-table-column :label="t('transfer.scope')" width="110"><template #default="{ row }">{{ row.scope ? scopeLabel(row.scope) : t(`transfer.billMode.${row.mode}`) }}</template></el-table-column>
            <el-table-column :label="t('transfer.fromTo')" min-width="240">
              <template #default="{ row }">
                <div>{{ row.from_bill }} → <b>{{ row.to_bill }}</b><span v-if="row.to_seq" class="muted"> / {{ row.to_seq }} · {{ row.to_material || t("transfer.emptyMaterial") }}</span></div>
                <div class="muted">{{ row.from_pi }} → {{ row.to_pi }}<template v-if="row.to_factory"> · {{ nameOf(row.to_factory) }}</template></div>
              </template>
            </el-table-column>
            <el-table-column prop="scope_text" :label="t('transfer.scope')" min-width="160" show-overflow-tooltip />
            <el-table-column :label="t('common.qty')" align="right" width="70"><template #default="{ row }"><span class="num">{{ row.qty }}</span></template></el-table-column>
            <el-table-column prop="reason" :label="t('common.reason')" min-width="140" show-overflow-tooltip />
            <el-table-column prop="created_by" :label="t('common.operator')" width="100" />
            <el-table-column :label="t('common.time')" width="160"><template #default="{ row }">{{ fmt(row.created_at) }}</template></el-table-column>
            <el-table-column :label="t('common.actions')" width="80" align="right">
              <template #default="{ row }"><el-button link type="primary" @click="detail?.open('BILL', row.id)">{{ t("common.detail") }}</el-button></template>
            </el-table-column>
          </el-table>
          <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="bills.state.total" :page-size="bills.state.pageSize"
            :current-page="bills.state.page" :page-sizes="[20, 50, 100]" @current-change="bills.load" @size-change="bills.onSize" />
        </div>
      </el-tab-pane>

      <!-- ================= 转组织记录 ================= -->
      <el-tab-pane name="org">
        <template #label>
          <span>{{ t("transfer.tabOrg") }}</span>
          <el-badge v-if="pending" :value="pending" class="badge" />
        </template>
        <div class="surface">
          <div class="toolbar">
            <el-radio-group v-model="oq.status" @change="orgs.load(1)">
              <el-radio-button v-for="s in ORG_STATUSES" :key="s" :value="s">
                {{ s ? transferStatusLabel(s) : t("common.all") }}<span v-if="s === 'PENDING' && pending" class="cnt num">{{ pending }}</span>
              </el-radio-button>
            </el-radio-group>
            <FactorySelect v-if="!store.bound" v-model="oq.factory_code" clearable :placeholder="t('app.allFactories')" @update:model-value="orgs.load(1)" />
            <el-input v-model="oq.pi" :placeholder="t('transfer.piInOut')" clearable @keyup.enter="orgs.load(1)" @clear="orgs.load(1)" />
            <el-input v-model="oq.bill_no" :placeholder="t('common.billNo')" clearable @keyup.enter="orgs.load(1)" @clear="orgs.load(1)" />
            <el-button type="primary" @click="orgs.load(1)">{{ t("common.search") }}</el-button>
          </div>
          <el-table v-loading="orgs.state.loading" :data="orgRows" size="small" :empty-text="t('common.empty')">
            <el-table-column prop="transfer_no" :label="t('transfer.no')" min-width="180">
              <template #default="{ row }">
                <span class="sn-mono">{{ row.transfer_no }}</span>
                <el-tag v-if="row.direction" size="small" :type="row.direction === 'OUT' ? 'warning' : 'success'" class="ml">{{ t(`transfer.direction.${row.direction}`) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('common.status')" width="100">
              <template #default="{ row }"><el-tag size="small" :type="TRANSFER_STATUS_TYPE[row.status] || 'info'">{{ transferStatusLabel(row.status) }}</el-tag></template>
            </el-table-column>
            <el-table-column :label="t('transfer.mode')" width="100"><template #default="{ row }">{{ t(`transfer.orgMode.${row.mode}`) }}</template></el-table-column>
            <el-table-column :label="t('transfer.source')" min-width="200">
              <template #default="{ row }">
                <div>{{ nameOf(row.from_factory) }} · {{ row.from_pi }}</div>
                <div class="muted">{{ scopeLabel(row.scope) }}<template v-if="row.from_bill"> · {{ row.from_bill }}</template><template v-if="row.from_material && (row.scope === 'MATERIAL' || row.scope === 'RANGE')"> · {{ row.from_material }}</template><template v-if="row.from_sn"> · <span class="sn-mono">{{ snRange(row) }}</span></template></div>
              </template>
            </el-table-column>
            <el-table-column :label="t('transfer.target')" min-width="200">
              <template #default="{ row }">
                <div>
                  {{ nameOf(row.to_factory) }} · {{ row.to_pi }}
                  <el-tag v-if="row.to_factory_disabled" size="small" type="danger" class="ml">{{ t("transfer.targetDisabled") }}</el-tag>
                </div>
                <div class="muted">{{ targetText(row) }}</div>
              </template>
            </el-table-column>
            <el-table-column :label="t('common.qty')" align="right" width="70"><template #default="{ row }"><span class="num">{{ row.qty }}</span></template></el-table-column>
            <el-table-column :label="t('transfer.applied')" width="160">
              <template #default="{ row }"><div>{{ row.applied_by }}</div><div class="muted">{{ fmt(row.applied_at) }}</div></template>
            </el-table-column>
            <el-table-column :label="t('common.actions')" width="200" align="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="detail?.open('ORG', row.id)">{{ t("common.detail") }}</el-button>
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
          <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="orgs.state.total" :page-size="orgs.state.pageSize"
            :current-page="orgs.state.page" :page-sizes="[20, 50, 100]" @current-change="orgs.load" @size-change="orgs.onSize" />
        </div>
      </el-tab-pane>
    </el-tabs>

    <TransferDialog ref="dlg" @done="onDone" />
    <TransferDetailDrawer ref="detail" />
  </div>
</template>

<script setup lang="ts">
/** SN 转移（F-15、F-16）：转单记录、转组织记录（申请、审批、撤回）；新建与详情见 views/transfer。 */
import { computed, onMounted, reactive, ref } from "vue";
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
import { snRange } from "@/views/transfer/types";

const ORG_STATUSES = ["", "PENDING", "APPROVED", "REJECTED", "WITHDRAWN"];

const { t } = useI18n();
const store = useAuthStore();
const route = useRoute();
const { nameOf } = useFactories();
const { fmt } = useTime();
const dlg = ref<InstanceType<typeof TransferDialog> | null>(null);
const detail = ref<InstanceType<typeof TransferDetailDrawer> | null>(null);

const tab = ref<"bill" | "org">(route.query.tab === "org" || !store.isAdmin ? "org" : "bill");

// ---------------- 转单列表
const bq = reactive({ factory_code: "", pi: "", bill_no: "" });
const bills = usePaged<any>((q) => api.billTransfers({ ...q, ...bq, pi: bq.pi.trim(), bill_no: bq.bill_no.trim() }));

// ---------------- 转组织列表（总部默认看待处理）
const oq = reactive({ status: store.isAdmin ? "PENDING" : "", factory_code: "", pi: "", bill_no: "" });
const pending = ref(0);
const orgs = usePaged<any>(async (q) => {
  const r = await api.orgTransfers({ ...q, ...oq, pi: oq.pi.trim(), bill_no: oq.bill_no.trim() });
  pending.value = r.pending ?? 0;
  return r;
});
const orgRows = computed(() =>
  orgs.items.value.map((x: any) => ({ ...x.transfer, direction: x.direction, to_factory_disabled: x.to_factory_disabled })),
);

function onTab(name: string | number) {
  if (name === "bill") bills.load(1);
  else orgs.load(1);
}

async function decide(row: any, kind: "approve" | "reject") {
  const { value } = await ElMessageBox.prompt(
    t(kind === "approve" ? "transfer.approveConfirm" : "transfer.rejectConfirm", { no: row.transfer_no, n: row.qty }),
    kind === "approve" ? t("transfer.approve") : t("transfer.reject"),
    { inputType: "textarea", inputPlaceholder: t("transfer.notePlaceholder"), inputValidator: (v) => (v || "").length <= 500, type: "warning" },
  );
  const r = kind === "approve" ? await api.approveOrg(row.id, value || undefined) : await api.rejectOrg(row.id, value || undefined);
  ElMessage.success(t("common.done"));
  orgs.load();
  if (kind === "approve") detail.value?.open("ORG", r.id);
}

async function withdraw(row: any) {
  await ElMessageBox.confirm(t("transfer.withdrawConfirm", { no: row.transfer_no }), t("transfer.withdraw"), { type: "warning" });
  await api.withdrawOrg(row.id);
  ElMessage.success(t("common.done"));
  orgs.load();
}

function onDone(kind: "BILL" | "ORG", doc: any) {
  if (kind === "BILL") {
    tab.value = "bill";
    bills.load(1);
    detail.value?.show("BILL", doc);
    return;
  }
  tab.value = "org";
  orgs.load(1);
  if (doc.status === "APPROVED") detail.value?.open("ORG", doc.id);
}

/** 转组织单的目标：单据 / 序号 · 物料 · 客户（早期单子没有目标单据）。 */
function targetText(d: { to_bill?: string; to_seq?: number; to_material?: string; to_customer?: string }) {
  return [
    d.to_bill && `${d.to_bill} / ${d.to_seq}`,
    d.to_material && `${t("common.material")} ${d.to_material}`,
    d.to_customer && `${t("common.customer")} ${d.to_customer}`,
  ].filter(Boolean).join(" · ");
}

onMounted(() => {
  if (tab.value === "bill") bills.load(1);
  orgs.load(1);
  if (route.query.new === "1" && store.canAct) {
    tab.value = "org";
    dlg.value?.open("ORG", {
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
.badge { margin-left: 4px; vertical-align: top; }
</style>
