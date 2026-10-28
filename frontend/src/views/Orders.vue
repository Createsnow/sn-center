<template>
  <div class="page page--fill">
    <PageHead :title="t('orders.title')" :desc="t('orders.desc')">
      <template v-if="store.canAct">
        <el-input v-model="syncBillNo" :placeholder="t('common.billNo')" clearable class="w200" @keyup.enter="syncOne()" />
        <el-button :loading="syncing === 'one'" :disabled="!syncBillNo.trim()" @click="syncOne()">{{ t("orders.syncOne") }}</el-button>
        <el-button type="primary" :loading="syncing === 'compare'" @click="compareNow">
          <el-icon><Refresh /></el-icon><span>{{ t("orders.compareNow") }}</span>
        </el-button>
        <el-button v-if="store.isAdmin" :loading="syncing === 'rebuild'" @click="rebuild">{{ t("orders.rebuild") }}</el-button>
      </template>
    </PageHead>

    <el-alert v-if="meta?.compare?.last_ok === false" type="error" show-icon :closable="false"
      :title="t('orders.syncFailed', { time: fmt(meta.compare.last_run_at), msg: meta.compare.last_message })" />

    <div class="surface meta">
      <span>{{ t("orders.syncedAt") }}：<b>{{ fmt(meta?.synced_at) }}</b></span>
      <span>{{ t("orders.bills") }}：<b class="num">{{ meta?.bills ?? 0 }}</b></span>
      <span>{{ t("orders.rows") }}：<b class="num">{{ meta?.rows ?? 0 }}</b></span>
      <span class="muted">{{ t("orders.emptyCustomer", { n: meta?.empty_customer_bills ?? 0 }) }}</span>
      <span>{{ t("orders.attachPending") }}：<b class="num" :class="{ warn: meta?.attach_pending }">{{ meta?.attach_pending ?? 0 }}</b></span>
      <span>
        {{ t("orders.compare") }}：
        <template v-if="meta?.compare?.last_run_at">
          <span>{{ fmt(meta.compare.last_run_at) }}</span>
          <el-tag size="small" class="ml" :type="meta.compare.last_ok ? 'success' : 'danger'">
            {{ meta.compare.last_ok ? t("orders.compareOk") : t("orders.compareFail") }}
          </el-tag>
        </template>
        <span v-else class="muted">—</span>
      </span>
      <el-button link :type="meta?.open_alerts ? 'danger' : 'info'" @click="tab = 'alerts'">
        {{ t("orders.openAlerts", { n: meta?.open_alerts ?? 0 }) }}
      </el-button>
    </div>

    <el-tabs v-model="tab" class="tabs">
      <el-tab-pane :label="t('orders.tabBills')" name="bills" />
      <el-tab-pane name="alerts">
        <template #label>
          <span>{{ t("orders.tabAlerts") }}</span>
          <el-badge v-if="meta?.open_alerts" :value="meta.open_alerts" class="badge" />
        </template>
      </el-tab-pane>
    </el-tabs>

    <el-row v-show="tab === 'bills'" :gutter="16" class="cols">
      <el-col :lg="14" :xs="24">
        <div class="surface fill">
          <div class="toolbar">
            <el-input v-model="f.q" :placeholder="t('orders.search')" clearable prefix-icon="Search" class="w-search" @keyup.enter="load(1)" @clear="load(1)" />
            <el-input v-model="f.customer" :placeholder="t('common.customer')" clearable @keyup.enter="load(1)" @clear="load(1)" />
            <FactorySelect v-if="store.multiScope" v-model="f.factory_code" scoped clearable @update:model-value="load(1)" />
            <el-button type="primary" @click="load(1)">{{ t("common.search") }}</el-button>
          </div>
          <div class="fill-body">
            <el-table v-loading="state.loading" :data="items" highlight-current-row size="small" height="100%"
              :empty-text="t('common.empty')" @current-change="select">
              <el-table-column prop="bill_no" :label="t('common.billNo')" min-width="130" show-overflow-tooltip />
              <el-table-column prop="pi" label="PI" min-width="130" show-overflow-tooltip />
              <el-table-column :label="t('orders.state')" min-width="140">
                <template #default="{ row }"><BillTags :s="row" :doc-status="row.doc_status" /></template>
              </el-table-column>
              <el-table-column :label="t('orders.totalQty')" align="right" width="80">
                <template #default="{ row }"><span class="num">{{ row.total_qty }}</span></template>
              </el-table-column>
              <el-table-column :label="t('orders.generated')" align="right" width="72">
                <template #default="{ row }"><span class="num">{{ row.generated_qty }}</span></template>
              </el-table-column>
              <el-table-column :label="t('orders.quota')" align="right" width="72">
                <template #default="{ row }"><span class="num" :class="{ zero: !row.quota }">{{ row.quota }}</span></template>
              </el-table-column>
              <el-table-column :label="t('common.factory')" min-width="120" show-overflow-tooltip>
                <template #default="{ row }">{{ row.org_name }}<span class="muted">（{{ row.org_code }}）</span></template>
              </el-table-column>
              <el-table-column prop="po" label="PO" min-width="110" show-overflow-tooltip />
              <el-table-column prop="customer_code" :label="t('common.customer')" width="90" show-overflow-tooltip />
              <!-- 金蝶修改日期（金蝶时间原样显示，放最后一列）；列表按它从新到旧排 -->
              <el-table-column :label="t('orders.modifyDate')" width="160">
                <template #default="{ row }"><span class="num">{{ row.modify_time || "—" }}</span></template>
              </el-table-column>
            </el-table>
          </div>
          <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="state.total" :page-size="state.pageSize"
            :current-page="state.page" :page-sizes="[20, 50, 100]" @current-change="load" @size-change="onSize" />
        </div>
      </el-col>
      <el-col :lg="10" :xs="24">
        <div class="surface fill">
          <div class="surface__title">
            <span>{{ detail ? t("orders.linesOf", { bill: detail.bill_no }) : t("orders.lines") }}</span>
            <el-button v-if="detail && store.canAct" type="primary" size="small"
              @click="router.push({ path: '/generate', query: { bill_no: detail.bill_no } })">
              {{ t("orders.toGenerate") }}
            </el-button>
          </div>
          <template v-if="detail">
            <el-descriptions :column="2" size="small" border class="mb">
              <el-descriptions-item label="PI">{{ detail.pi }}</el-descriptions-item>
              <el-descriptions-item label="PO">{{ detail.po }}</el-descriptions-item>
              <el-descriptions-item :label="t('common.customer')">{{ detail.customer_code }}</el-descriptions-item>
              <el-descriptions-item :label="t('common.factory')">{{ detail.org_name }}（{{ detail.org_code }}）</el-descriptions-item>
              <el-descriptions-item :label="t('orders.state')"><BillTags :s="detail.state" :doc-status="detail.doc_status" /></el-descriptions-item>
              <el-descriptions-item :label="t('orders.modifyDate')">{{ detail.modify_time || "—" }}</el-descriptions-item>
            </el-descriptions>
            <div class="fill-body">
              <el-table v-loading="linesLoading" :data="detail.lines" size="small" height="100%" :empty-text="t('common.empty')">
                <el-table-column prop="seq" label="#" width="44" />
                <el-table-column :label="t('common.attachNo')" min-width="120">
                  <template #default="{ row }">
                    <div class="attach">
                      <span v-if="row.attach_source === 'EMPTY'" class="err">{{ t("orders.attachEmpty") }}</span>
                      <span v-else>{{ row.attach_no }}</span>
                    </div>
                    <div v-if="row.attach_k3_conflict" class="muted warn">
                      {{ t("orders.attachConflict", { k3: row.attach_k3, manual: row.attach_manual }) }}
                    </div>
                  </template>
                </el-table-column>
                <el-table-column :label="t('common.material')" min-width="110" show-overflow-tooltip>
                  <template #default="{ row }">
                    <div>{{ row.material_code }}</div>
                    <div class="muted">{{ row.material_name }}</div>
                  </template>
                </el-table-column>
                <el-table-column :label="t('common.length')" width="90" show-overflow-tooltip>
                  <template #default="{ row }">
                    <!-- 金蝶米数为空且未填写：标红「缺失」（规则带米数时须在生成页填写）；其他照金蝶值 / 页面填写的值显示 -->
                    <span v-if="row.length_source === 'EMPTY'" class="err">{{ t("orders.lengthEmpty") }}</span>
                    <span v-else>{{ row.length }}</span>
                  </template>
                </el-table-column>
                <el-table-column :label="t('common.unit')" width="70" show-overflow-tooltip>
                  <template #default="{ row }">
                    <!-- 金蝶产品单位为空且未填写：标红「缺失」（规则带单位时须在生成页填写）；其他照金蝶值（含中文也照用）/ 页面填写的值显示 -->
                    <span v-if="row.unit_source === 'EMPTY'" class="err">{{ t("orders.unitEmpty") }}</span>
                    <span v-else>{{ row.unit }}</span>
                  </template>
                </el-table-column>
                <el-table-column :label="t('common.qty')" align="right" width="60">
                  <template #default="{ row }"><span class="num">{{ row.qty }}</span></template>
                </el-table-column>
                <el-table-column :label="t('orders.generated')" align="right" width="64">
                  <template #default="{ row }"><span class="num">{{ row.generated_qty }}</span></template>
                </el-table-column>
                <el-table-column :label="t('orders.quota')" align="right" width="64">
                  <template #default="{ row }"><span class="num" :class="{ zero: !row.quota }">{{ row.quota }}</span></template>
                </el-table-column>
                <el-table-column :label="t('orders.rule')" min-width="160" show-overflow-tooltip>
                  <template #default="{ row }">
                    <template v-if="row.rule">
                      <el-tag size="small" effect="plain">{{ t(`orders.ruleSource.${row.rule.source}`) }}</el-tag>
                      <span class="ml">{{ row.rule.format }}</span>
                    </template>
                    <span v-else class="muted">{{ t("orders.noRule") }}</span>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </template>
          <el-empty v-else :description="t('orders.pick')" />
        </div>
      </el-col>
    </el-row>

    <div v-show="tab === 'alerts'" class="surface fill">
      <div class="toolbar">
        <el-radio-group v-model="af.status" @change="loadAlerts(1)">
          <el-radio-button value="OPEN">{{ t("orders.alertOpen") }}</el-radio-button>
          <el-radio-button value="CLOSED">{{ t("orders.alertClosed") }}</el-radio-button>
          <el-radio-button value="ALL">{{ t("common.all") }}</el-radio-button>
        </el-radio-group>
        <el-select v-model="af.alert_type" clearable :placeholder="t('orders.alertType')" @change="loadAlerts(1)">
          <el-option v-for="x in ALERT_TYPES" :key="x" :value="x" :label="t(`orders.alertTypes.${x}`)" />
        </el-select>
        <el-input v-model="af.bill_no" :placeholder="t('common.billNo')" clearable @keyup.enter="loadAlerts(1)" @clear="loadAlerts(1)" />
        <el-button type="primary" @click="loadAlerts(1)">{{ t("common.search") }}</el-button>
      </div>
      <div class="fill-body">
        <el-table v-loading="alerts.state.loading" :data="alerts.items.value" size="small" height="100%" :empty-text="t('common.empty')">
          <el-table-column :label="t('common.time')" width="150">
            <template #default="{ row }">{{ fmt(row.created_at) }}</template>
          </el-table-column>
          <el-table-column prop="bill_no" :label="t('common.billNo')" min-width="120" show-overflow-tooltip />
          <el-table-column :label="t('orders.alertType')" min-width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="ALERT_TAG[row.alert_type] || 'info'">{{ t(`orders.alertTypes.${row.alert_type}`) }}</el-tag>
              <span v-if="row.seq" class="muted ml">#{{ row.seq }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('orders.change')" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <span>{{ row.before_value || "—" }}</span><span class="arrow">→</span><span>{{ row.after_value || "—" }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="pi" label="PI" min-width="120" show-overflow-tooltip />
          <el-table-column :label="t('common.factory')" min-width="110" show-overflow-tooltip>
            <template #default="{ row }">{{ row.org_name || row.org_code || "—" }}</template>
          </el-table-column>
          <el-table-column :label="t('orders.state')" min-width="110">
            <template #default="{ row }"><BillTags :s="row" /></template>
          </el-table-column>
          <el-table-column :label="t('orders.counts')" min-width="150">
            <template #default="{ row }">
              <div class="muted">{{ t("orders.countSuspended", { n: row.suspended_qty }) }}</div>
              <div class="muted">{{ t("orders.countDelivered", { n: row.delivered_qty }) }}</div>
              <div class="muted">{{ t("orders.countInOrg", { n: row.in_org_qty }) }}</div>
            </template>
          </el-table-column>
          <el-table-column :label="t('common.status')" min-width="130">
            <template #default="{ row }">
              <el-tag v-if="row.status === 'OPEN'" size="small" type="warning">{{ t("orders.alertOpen") }}</el-tag>
              <template v-else>
                <el-tag size="small" type="success">{{ t("orders.alertClosed") }}</el-tag>
                <div class="muted">{{ row.close_action ? t(`orders.alertActions.${row.close_action}`) : "" }} {{ row.closed_by || "" }}</div>
                <div v-if="row.note" class="muted">{{ row.note }}</div>
              </template>
            </template>
          </el-table-column>
          <el-table-column v-if="store.canAct" :label="t('common.actions')" min-width="170" fixed="right">
            <template #default="{ row }">
              <el-button v-for="a in row.actions" :key="a" link type="primary" @click="handle(row, a)">{{ t(`orders.alertActions.${a}`) }}</el-button>
              <el-button v-if="row.status === 'OPEN' && row.alert_type === 'REVERSAL'" link @click="syncOne(row.bill_no)">
                {{ t("orders.syncNow") }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="alerts.state.total" :page-size="alerts.state.pageSize"
        :current-page="alerts.state.page" :page-sizes="[20, 50, 100]" @current-change="loadAlerts" @size-change="alerts.onSize" />
    </div>

    <el-dialog v-model="mapDlg.open" :title="t('orders.mapTitle')" width="520px">
      <p class="muted">{{ t("orders.mapHint", { bill: mapDlg.alert?.bill_no, seq: mapDlg.alert?.seq, material: mapDlg.alert?.before_value }) }}</p>
      <el-form label-width="110px">
        <el-form-item :label="t('orders.mapTarget')" required>
          <el-select v-model="mapDlg.target" class="w-full">
            <el-option v-for="l in mapDlg.lines" :key="l.seq" :value="l.seq"
              :label="`#${l.seq} ${l.material_code} ${l.material_name}（${t('common.qty')} ${l.qty}）`" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('common.note')">
          <el-input v-model="mapDlg.note" type="textarea" :rows="2" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="mapDlg.open = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" :disabled="!mapDlg.target" :loading="mapDlg.saving" @click="submitMap">{{ t("common.confirm") }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { defineComponent, h, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox, ElTag } from "element-plus";
import { api } from "@/api";
import { useAuthStore } from "@/stores/auth";
import { usePaged } from "@/composables/usePaged";
import { useTime } from "@/composables/useTime";
import PageHead from "@/components/PageHead.vue";
import FactorySelect from "@/components/FactorySelect.vue";
import { firstSyncThisLogin } from "@/utils/syncOnce";
import { leaveStateLabel, suspendLabel } from "@/constants";
import type { BillState, OrderBill, OrderLine } from "@/types/api";

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const store = useAuthStore();
const { fmt } = useTime();

const ALERT_TYPES = ["PI", "ORG", "CUSTOMER", "QTY", "MATERIAL", "ATTACH", "REVERSAL", "DELETED"];
const ALERT_TAG: Record<string, "" | "info" | "warning" | "danger" | "success" | "primary"> = {
  PI: "danger", ORG: "danger", CUSTOMER: "danger", MATERIAL: "danger", REVERSAL: "danger", QTY: "warning", ATTACH: "warning", DELETED: "info",
};

/** 单据状态标签：金蝶单据状态（快照只含已审核，订单页每张单据都显示「已审核」）、暂停交付、离开快照、PI 冻结、未处理提醒。 */
const BillTags = defineComponent({
  props: { s: { type: Object as () => Partial<BillState> | null, default: null }, docStatus: { type: String, default: "" } },
  setup(props) {
    return () => {
      const s = props.s || {};
      const tags = [];
      if (props.docStatus) tags.push(h(ElTag, { size: "small", type: "success", effect: "plain" }, () => t(`orders.docStatus.${props.docStatus}`)));
      if (s.suspend) tags.push(h(ElTag, { size: "small", type: "danger" }, () => suspendLabel(s.suspend)));
      if (s.leave_state) tags.push(h(ElTag, { size: "small", type: "warning" }, () => leaveStateLabel(s.leave_state)));
      if (s.pi_frozen) tags.push(h(ElTag, { size: "small", type: "danger" }, () => t("orders.piFrozen")));
      if (s.open_alerts) tags.push(h(ElTag, { size: "small", type: "warning", effect: "plain" }, () => t("orders.alertsN", { n: s.open_alerts })));
      return h("div", { class: "tags" }, tags);
    };
  },
});

const tab = ref<"bills" | "alerts">("bills");
const f = reactive({ q: "", customer: "", factory_code: "" });
const meta = ref<any>(null);
const current = ref<OrderBill | null>(null);
const detail = ref<(OrderBill & { lines: OrderLine[]; state: BillState }) | null>(null);
const linesLoading = ref(false);
const syncBillNo = ref("");
const syncing = ref<"" | "one" | "compare" | "rebuild">("");

const { items, state, load, onSize } = usePaged<OrderBill>((q) => api.orders({ ...f, ...q }));

const af = reactive({ status: "OPEN", alert_type: "", bill_no: "" });
const alerts = usePaged<any>((q) => api.orderAlerts({ ...af, ...q }));
const loadAlerts = (p?: number) => alerts.load(p);

async function select(row: OrderBill | null) {
  current.value = row;
  if (!row) return;
  await loadLines(row.bill_no);
}

async function loadLines(billNo: string) {
  linesLoading.value = true;
  try {
    detail.value = await api.orderLines(billNo);
  } catch {
    detail.value = null;
  } finally {
    linesLoading.value = false;
  }
}

async function refresh() {
  meta.value = await api.orderMeta();
  await load();
  if (current.value) await loadLines(current.value.bill_no);
  if (tab.value === "alerts") await loadAlerts();
}

async function syncOne(bill?: string) {
  const no = (bill ?? syncBillNo.value).trim();
  if (!no) return;
  syncing.value = "one";
  try {
    const r: any = await api.syncOrder(no);
    const msg = t(`orders.outcome.${r.outcome}`, { bill: no, rows: r.rows, status: r.doc_status || "—" });
    if (r.outcome === "REPLACED") ElMessage.success(msg);
    else ElMessage.warning({ message: msg, duration: 6000, showClose: true });
    await refresh();
  } finally {
    syncing.value = "";
  }
}

async function compareNow() {
  syncing.value = "compare";
  try {
    const r: any = await api.compareOrders();
    if (r.skipped) ElMessage.info(t("orders.compareSkipped"));
    else ElMessage.success(t("orders.compareDone", { replaced: r.replaced, removed: r.removed, rows: r.rows }));
    await refresh();
  } finally {
    syncing.value = "";
  }
}

async function rebuild() {
  try {
    await ElMessageBox.confirm(t("orders.rebuildConfirm"), t("orders.rebuild"), { type: "warning" });
  } catch {
    return;
  }
  syncing.value = "rebuild";
  try {
    const r: any = await api.rebuildOrders();
    ElMessage.success(t("orders.rebuildDone", { bills: r.bills, rows: r.rows }));
    await refresh();
  } finally {
    syncing.value = "";
  }
}

const mapDlg = reactive<{ open: boolean; alert: any; lines: OrderLine[]; target: number | undefined; note: string; saving: boolean }>({
  open: false, alert: null, lines: [], target: undefined, note: "", saving: false,
});

async function handle(row: any, action: string) {
  if (action === "MAP") {
    const r: any = await api.orderLines(row.bill_no);
    Object.assign(mapDlg, { open: true, alert: row, lines: (r.lines || []).filter((l: OrderLine) => l.seq !== row.seq), target: undefined, note: "" });
    return;
  }
  let note = "";
  try {
    if (action === "VOID") {
      const r = await ElMessageBox.prompt(t("orders.voidHint"), t("orders.alertActions.VOID"), {
        inputType: "textarea",
        inputPlaceholder: t("common.reasonPlaceholder"),
        inputValidator: (v: string) => (!!v && !!v.trim() && v.trim().length <= 500) || t("orders.reasonRequired"),
      });
      note = (r as { value: string }).value.trim();
    } else {
      await ElMessageBox.confirm(t(`orders.actionHint.${action}`, { bill: row.bill_no }), t(`orders.alertActions.${action}`), { type: "warning" });
    }
  } catch {
    return;
  }
  await api.handleOrderAlert(row.id, { action, note: note || undefined });
  ElMessage.success(t("common.done"));
  await refresh();
  await loadAlerts();
}

async function submitMap() {
  if (!mapDlg.alert || !mapDlg.target) return;
  mapDlg.saving = true;
  try {
    await api.handleOrderAlert(mapDlg.alert.id, { action: "MAP", target_seq: mapDlg.target, note: mapDlg.note.trim() || undefined });
    ElMessage.success(t("common.done"));
    mapDlg.open = false;
    await refresh();
    await loadAlerts();
  } finally {
    mapDlg.saving = false;
  }
}

watch(tab, (v) => {
  if (v === "alerts") loadAlerts(1);
});

/**
 * 进入页面先显示现有快照，同时触发一次进页同步（后台比对同步，立即返回）；
 * 本页面、本次登录只触发一次，稍后刷新一次列表以显示同步结果。
 */
onMounted(async () => {
  if (route.query.tab === "alerts") tab.value = "alerts";
  await refresh();
  if (firstSyncThisLogin("orders", store.token)) {
    const r: any = await api.pageSyncOrders().catch(() => null);
    if (r?.started) setTimeout(() => refresh().catch(() => undefined), 4000);
  }
});
</script>

<style scoped>
.meta { display: flex; gap: 20px; flex-wrap: wrap; align-items: center; font-size: 13px; padding: 12px 20px; }
.w200 { width: 200px; }
.w-full { width: 100%; }
.toolbar .w-search { width: 280px; }
.tabs { margin-bottom: -16px; }
.badge { margin-left: 6px; vertical-align: top; }
.ml { margin-left: 6px; }
.mb { margin-bottom: 12px; }
.zero { color: var(--app-muted); }
.warn { color: var(--el-color-warning); }
.err { color: var(--el-color-danger); }
.arrow { margin: 0 6px; color: var(--app-muted); }
.attach { display: flex; align-items: center; gap: 6px; }
:deep(.tags) { display: flex; flex-wrap: wrap; gap: 4px; }
.el-col { margin-bottom: 16px; }
/* 宽屏左右两栏铺满剩余高度，各自在卡片内滚动 */
@media (min-width: 1200px) {
  .cols { flex: 1; min-height: 0; flex-wrap: nowrap; }
  .cols > .el-col { margin-bottom: 0; display: flex; flex-direction: column; }
  .cols > .el-col > .surface { flex: 1; }
}
</style>
