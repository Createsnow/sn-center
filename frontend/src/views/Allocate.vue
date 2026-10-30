<template>
  <div class="page page--fill">
    <PageHead :title="t('allocate.title')" :desc="t('allocate.desc')">
      <span class="muted">{{ t("allocate.summary", { pending: num(list.summary.pending_alloc), unbatched: num(list.summary.unbatched) }) }}</span>
    </PageHead>

    <div class="split fill">
      <!-- 左：单据列表（按 PI / 单据编号筛选） -->
      <div class="surface pane list-pane">
        <div class="toolbar">
          <el-radio-group v-model="tab" size="small" @change="loadList()">
            <el-radio-button v-for="v in TABS" :key="v" :value="v">{{ t(`allocate.tab.${v}`) }}</el-radio-button>
          </el-radio-group>
          <el-input v-model="q" class="kw" size="small" :placeholder="t('allocate.search')" clearable prefix-icon="Search" @keyup.enter="loadList()" @clear="loadList()" />
        </div>
        <div class="fill-body">
          <el-table v-loading="listLoading" :data="paged" size="small" :row-key="rowKey" height="100%" highlight-current-row
            :row-class-name="({ row }: any) => (isCurrent(row) ? 'current-row' : '')" :empty-text="t('common.empty')" @row-click="pick">
            <el-table-column :label="t('common.billNo')" min-width="150">
              <template #default="{ row }">
                <b>{{ row.bill_no }}</b>
                <div class="muted">PI {{ row.pi_no }}</div>
              </template>
            </el-table-column>
            <el-table-column :label="t('export.qty')" align="right" width="86">
              <template #default="{ row }"><span class="num">{{ num(row.qty) }}</span></template>
            </el-table-column>
            <el-table-column :label="t('common.status')" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="STATE_TAG[row.state as BillState]" disable-transitions>{{ t(`allocate.state.${row.state}`) }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </div>
        <el-pagination class="pager" small layout="prev, pager, next" :total="list.items.length" :page-size="PAGE"
          :current-page="page" @current-change="(p: number) => (page = p)" />
        <p v-if="list.capped" class="muted">{{ t("generate.capped", { n: LIST_LIMIT }) }}</p>
      </div>

      <!-- 右：所选单据所在 PI 的分配与推送（待分配页签）/ 已分配的号（已分配页签） -->
      <div v-loading="ctxLoading" class="surface pane detail-pane">
        <p v-if="!ctx" class="muted empty">{{ list.items.length ? t("allocate.pickBill") : t("common.empty") }}</p>
        <template v-else>
          <div class="dh">
            <div class="dh-title">
              <b>PI {{ ctx.pi_no }}</b>
              <span class="muted">{{ t("common.customer") }} {{ ctx.customer_code || "—" }}</span>
              <span class="push" />
              <router-link v-if="ctx.summary.quota" class="go" :to="{ path: '/generate', query: { pi: ctx.pi_no } }">
                {{ t("allocate.toGenerate", { n: num(ctx.summary.quota) }) }}<el-icon><Right /></el-icon>
              </router-link>
            </div>
            <div class="kv">
              <span>{{ t("generate.generated") }} <b class="num">{{ num(ctx.summary.generated_qty) }}</b></span>
              <span v-if="ctx.summary.transferred_in">{{ t("generate.transferredIn") }} <b class="num">{{ num(ctx.summary.transferred_in) }}</b></span>
              <span>{{ t("generate.pendingAlloc") }} <b class="num">{{ num(ctx.summary.pending_alloc) }}</b></span>
              <span>{{ t("generate.allocated") }} <b class="num">{{ num(ctx.summary.allocated_qty) }}</b></span>
              <span>{{ t("allocate.unbatched") }} <b class="num">{{ num(ctx.summary.unbatched) }}</b></span>
              <span v-if="ctx.summary.suspended" class="warn">{{ t("generate.suspendedQty", { n: num(ctx.summary.suspended) }) }}</span>
            </div>
          </div>

          <!-- 已分配页签：只列已分配的号（不分子页签），明细逐枚显示 SN、进制、流水 -->
          <AllocatedPane v-if="tab === 'ALLOCATED'" ref="allocated" :pi="ctx.pi_no" :bill="bill" @pick="(no: string) => pick({ pi_no: ctx.pi_no, bill_no: no })" />

          <template v-else>
            <!-- 推送中断：可续推的组织 -->
            <div v-for="p in brokenPush" :key="p.factory_code" class="banner warn-bg">
              <b>{{ t("allocate.interrupted") }}</b>
              <span>{{ nameOf(p.factory_code) }}</span>
              <span v-if="p.last_problem" class="muted">{{ p.last_problem.batch_no }}：{{ p.last_problem.fail_reason || pushStateLabel(p.last_problem.push_state) }}</span>
              <span class="push" />
              <el-button size="small" type="warning" :loading="busy === `resume:${p.factory_code}`" @click="resume(p.factory_code)">
                {{ t("allocate.resumeBtn", { n: p.remaining.pushes }) }}
              </el-button>
            </div>

            <!-- 单据物料行：勾选有待分配号的物料行为范围，不勾选 = 整张 PI；单据头、物料行都带推送进度条 -->
            <section v-if="bills.length" class="box">
              <div class="box-h">
                <b>{{ t("allocate.linesTitle") }}</b>
                <span class="push" />
                <el-button link type="primary" size="small" @click="foldAll(false)">{{ t("common.expandAll") }}</el-button>
                <el-button link type="primary" size="small" @click="foldAll(true)">{{ t("common.collapseAll") }}</el-button>
              </div>
              <!-- 按单据分组，点单据头展开 / 收起；收起只隐藏，勾选保留 -->
              <div class="bills">
                <div v-for="b in bills" :key="`${ctxVer}:${b.bill_no}`" class="bill" :class="{ focus: b.bill_no === bill }" :data-bill="b.bill_no">
                  <div class="bill-h" :title="isFolded(b.bill_no) ? t('common.expand') : t('common.collapse')" @click="toggleFold(b.bill_no)">
                    <el-icon class="fold" :class="{ open: !isFolded(b.bill_no) }"><ArrowRight /></el-icon>
                    <b>{{ b.bill_no }}</b>
                    <el-tag size="small" :type="STATE_TAG[b.state]" disable-transitions>{{ t(`allocate.state.${b.state}`) }}</el-tag>
                    <span class="muted num">{{ t("allocate.billCounts", { n: b.lines.length, qty: num(b.qty) }) }}</span>
                    <el-tag v-if="selByBill[b.bill_no]?.length" size="small" disable-transitions>{{ t("allocate.billPicked", { n: selByBill[b.bill_no].length }) }}</el-tag>
                    <span class="push" />
                    <span class="prog">
                      <el-progress :percentage="progressOf(b).pct" :status="PROG_STATUS[progressOf(b).state]" :show-text="false" :stroke-width="8" />
                      <span class="prog-t" :class="{ err: progressOf(b).state === 'FAILED' }">{{ progLabel(b) }}</span>
                    </span>
                  </div>
                  <el-table v-show="!isFolded(b.bill_no)" ref="lineTables" :data="b.lines" size="small" row-key="key"
                    @selection-change="(rows: any[]) => onSelect(b.bill_no, rows)">
                    <el-table-column type="selection" width="40" :selectable="(row: any) => row.pending_alloc > 0 && !row.skip" />
                    <el-table-column :label="t('common.billSeq')" prop="seq" width="72" />
                    <el-table-column :label="t('common.material')" prop="material_code" min-width="100" show-overflow-tooltip />
                    <el-table-column :label="t('export.qty')" align="right" width="80">
                      <template #default="{ row }"><span class="num">{{ num(row.qty) }}</span></template>
                    </el-table-column>
                    <el-table-column :label="t('generate.snRange')" min-width="210">
                      <template #default="{ row }"><span class="sn-mono">{{ row.start_sn }} ~ {{ row.end_sn }}</span></template>
                    </el-table-column>
                    <el-table-column :label="t('allocate.toOrg')" min-width="110" show-overflow-tooltip>
                      <template #default="{ row }">{{ row.org_code ? row.org_name || nameOf(row.org_code) : "—" }}</template>
                    </el-table-column>
                    <el-table-column :label="t('allocate.tabPush')" width="210">
                      <template #default="{ row }">
                        <el-tag v-if="row.skip" size="small" type="danger" effect="plain">{{ t(`allocate.skip.${row.skip}`) }}</el-tag>
                        <span v-else class="prog">
                          <el-progress :percentage="progressOf(row).pct" :status="PROG_STATUS[progressOf(row).state]" :show-text="false" :stroke-width="6" />
                          <span class="prog-t" :class="{ err: progressOf(row).state === 'FAILED' }">{{ progLabel(row) }}</span>
                        </span>
                      </template>
                    </el-table-column>
                  </el-table>
                </div>
              </div>
              <div v-if="ctx.summary.pending_alloc" class="bar">
                <span><span class="muted">{{ t("generate.scope") }}</span> <b>{{ picked.length ? t("generate.scopeLinesN", { n: picked.length }) : t("generate.scopeWholePi") }}</b></span>
                <el-button v-if="picked.length" link type="primary" @click="clearSel">{{ t("generate.clearSel") }}</el-button>
                <span class="push" />
                <span v-loading="alloc.loading" class="orgs">
                  <span v-for="o in alloc.data?.orgs || []" :key="o.org_code" class="muted">
                    {{ o.mes_enabled ? t("allocate.orgPush", { org: o.org_name || nameOf(o.org_code), n: num(o.qty), m: o.pushes }) : t("allocate.orgExport", { org: o.org_name || nameOf(o.org_code), n: num(o.qty) }) }}
                  </span>
                  <span v-if="alloc.data?.skipped.length" class="warn">{{ t("allocate.skippedN", { n: alloc.data.skipped.length }) }}</span>
                </span>
                <el-button type="success" :disabled="!alloc.data?.qty" :loading="busy === 'alloc'" @click="doAllocate">
                  {{ alloc.data?.pushes ? t("allocate.allocBtnPush", { m: alloc.data.pushes }) : t("allocate.allocBtn", { n: num(alloc.data?.qty || 0) }) }}
                </el-button>
              </div>
            </section>

            <!-- 推送进度：分配后有号待推送（推送中、失败、重试用尽）时显示，推完即收起；各组织第 i / N 次，下面依次分页列出推送批次 -->
            <el-tabs v-if="pushOpen" model-value="push" class="box tabs-box">
              <el-tab-pane :label="t('allocate.tabPush')" name="push">
                <div v-for="p in ctx.push" :key="p.factory_code" class="push-row">
                  <b>{{ nameOf(p.factory_code) }}</b>
                  <el-tag v-if="p.task" size="small" :type="p.waiting_receipt ? 'primary' : TASK_TAG[p.task.state] || 'info'">{{ p.waiting_receipt ? taskStateLabel("WAITING_RECEIPT") : taskStateLabel(p.task.state) }}</el-tag>
                  <span class="muted num">{{ t("allocate.pushProgress", { i: p.current_push, n: p.total_batches, qty: num(p.pushed_qty) }) }}</span>
                  <span v-if="p.waiting_receipt" class="muted">{{ t("allocate.waitingReceipt") }}</span>
                  <span v-else-if="p.task?.message" class="muted">{{ p.task.message }}</span>
                  <span v-if="p.last_problem" class="err">{{ p.last_problem.batch_no }}：{{ p.last_problem.fail_reason || pushStateLabel(p.last_problem.push_state) }}</span>
                </div>
                <el-table v-loading="batches.state.loading" :data="batches.items.value" size="small" :empty-text="t('common.empty')">
                  <el-table-column :label="t('common.batchNo')" min-width="200">
                    <template #default="{ row }">
                      <el-button link type="primary" class="sn-mono" @click="batchNo = row.batch_no">{{ row.batch_no }}</el-button>
                    </template>
                  </el-table-column>
                  <el-table-column :label="t('common.factory')" min-width="110" show-overflow-tooltip>
                    <template #default="{ row }">{{ nameOf(row.factory_code) }}</template>
                  </el-table-column>
                  <el-table-column :label="t('common.qty')" align="right" width="70">
                    <template #default="{ row }"><span class="num">{{ num(row.qty) }}</span></template>
                  </el-table-column>
                  <el-table-column :label="t('allocate.batchSource')" width="70">
                    <template #default="{ row }">{{ batchSourceLabel(row.source) }}</template>
                  </el-table-column>
                  <el-table-column :label="t('common.status')" width="130">
                    <template #default="{ row }">
                      <el-tooltip :disabled="!row.fail_reason" :content="row.fail_reason || ''">
                        <el-tag size="small" :type="BATCH_RESULT_TYPE[row.result] || 'info'">{{ batchResultLabel(row.result) }}</el-tag>
                      </el-tooltip>
                      <div v-if="row.push_state && row.push_state !== 'IDLE'" class="muted">{{ pushStateLabel(row.push_state) }}</div>
                    </template>
                  </el-table-column>
                  <el-table-column :label="t('common.time')" width="150">
                    <template #default="{ row }">{{ fmtTime(row.created_at) }}</template>
                  </el-table-column>
                  <el-table-column :label="t('common.actions')" width="130" align="right">
                    <template #default="{ row }">
                      <el-button v-if="canConfirm(row)" link type="primary" @click="manual?.open(row.batch_no, 'confirm')">{{ t("allocate.manualConfirm") }}</el-button>
                      <el-button v-if="canVoid(row)" link type="danger" @click="manual?.open(row.batch_no, 'void')">{{ t("allocate.void") }}</el-button>
                    </template>
                  </el-table-column>
                </el-table>
                <el-pagination v-if="batches.state.total > batches.state.pageSize" class="pager" small layout="total, prev, pager, next"
                  :total="batches.state.total" :page-size="batches.state.pageSize" :current-page="batches.state.page" @current-change="batches.load" />
              </el-tab-pane>
            </el-tabs>
          </template>
        </template>
      </div>
    </div>

    <BatchManualDialog ref="manual" @done="reload" />
    <DeliveryBatchDrawer v-model:batch-no="batchNo" @changed="reload" />
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "@/api";
import { useFactories } from "@/composables/useFactories";
import { usePaged } from "@/composables/usePaged";
import { useTime } from "@/composables/useTime";
import { BATCH_RESULT_TYPE, BILL_FOLD_OVER, batchResultLabel, batchSourceLabel, pushStateLabel, taskStateLabel } from "@/constants";
import { canConfirm, canVoid } from "@/utils/batch";
import PageHead from "@/components/PageHead.vue";
import BatchManualDialog from "@/components/batch/BatchManualDialog.vue";
import DeliveryBatchDrawer from "@/components/batch/DeliveryBatchDrawer.vue";
import AllocatedPane from "./allocate/AllocatedPane.vue";

type Tab = "TO_ALLOCATE" | "ALLOCATED";
/** 单据状态：待分配 / 待处理（已分配但推送未完成，含推送失败）/ 已分配。 */
type BillState = "PENDING" | "PROCESSING" | "ALLOCATED";
/** 物料行、单据的推送进度：待分配 / 待推送 / 推送失败 / 已完成。 */
type ProgState = "PENDING" | "TO_PUSH" | "FAILED" | "DONE";
type TagType = "" | "info" | "primary" | "success" | "warning" | "danger";
type LineRef = { bill_no: string; seq: number };
type Counts = { qty: number; pending_alloc: number; to_push: number; failed: number };
type BillRow = { pi_no: string; bill_no: string };

const TABS: Tab[] = ["TO_ALLOCATE", "ALLOCATED"];
const PAGE = 20;
const LIST_LIMIT = 500;
const STATE_TAG: Record<BillState, TagType> = { PENDING: "success", PROCESSING: "warning", ALLOCATED: "info" };
const PROG_STATUS: Record<ProgState, "" | "success" | "exception"> = { PENDING: "", TO_PUSH: "", FAILED: "exception", DONE: "success" };
const TASK_TAG: Record<string, TagType> = { QUEUED: "primary", RUNNING: "primary", WAITING: "warning", STOPPED: "danger", DONE: "success" };
const ACTIVE_TASK = ["QUEUED", "RUNNING", "WAITING"];

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const { nameOf } = useFactories();
const { fmt: fmtTime } = useTime();
const num = (n: number | string | null | undefined) => (n == null ? "—" : Number(n).toLocaleString());
const busy = ref("");

// ================================================================ 单据列表

const tab = ref<Tab>(route.query.tab === "ALLOCATED" ? "ALLOCATED" : "TO_ALLOCATE");
const q = ref("");
const page = ref(1);
const listLoading = ref(false);
const list = ref<any>({ items: [], capped: false, summary: { pending_alloc: 0, unbatched: 0 } });
const paged = computed(() => list.value.items.slice((page.value - 1) * PAGE, page.value * PAGE));
const rowKey = (row: BillRow) => `${row.pi_no}|${row.bill_no}`;
const isCurrent = (row: BillRow) => row.pi_no === pi.value && row.bill_no === bill.value;

async function loadList(silent = false) {
  if (!silent) listLoading.value = true;
  try {
    list.value = await api.allocBills({ tab: tab.value, q: q.value.trim() });
    if (!silent) page.value = 1;
  } finally {
    listLoading.value = false;
  }
  if (silent) return;
  // 右侧跟随左侧列表：所选单据不在列表里时改选第一张（只带 PI 进来时先找该 PI 的单据）；列表为空时右侧也为空
  const items: BillRow[] = list.value.items;
  const hit = items.find(isCurrent) || (!bill.value && items.find((r) => r.pi_no === pi.value)) || items[0];
  if (hit) pick(hit);
  else if (pi.value) router.replace({ query: { tab: tab.value } });
}

function pick(row: BillRow) {
  if (row.pi_no !== pi.value || row.bill_no !== bill.value) router.replace({ query: { pi: row.pi_no, bill: row.bill_no, tab: tab.value } });
}

// ================================================================ 详情（所选单据所在的 PI）

const pi = computed(() => String(route.query.pi || ""));
const bill = computed(() => String(route.query.bill || ""));
const ctx = ref<any>(null);
const ctxLoading = ref(false);
/** 非静默加载一次详情加 1：单据表格随之重建，表格内的勾选与 picked 一起清空。 */
const ctxVer = ref(0);
const allocated = ref<InstanceType<typeof AllocatedPane> | null>(null);
/** 待分配页签下本 PI 列出过的单据：推送完成变为已分配后仍留在原处，换 PI 时清空。 */
const shownBills = ref(new Set<string>());
let ctxTimer: ReturnType<typeof setTimeout> | undefined;

async function loadCtx(silent = false) {
  clearTimeout(ctxTimer);
  if (!pi.value) {
    ctx.value = null;
    return;
  }
  if (!silent) ctxLoading.value = true;
  try {
    const c = await api.allocContext(pi.value);
    c.lines.forEach((l: any) => (l.key = `${l.bill_no}/${l.seq}`));
    const fresh = c.pi_no !== ctx.value?.pi_no;
    if (fresh) shownBills.value = new Set();
    const s = new Set(shownBills.value);
    c.lines.forEach((l: any) => l.bill_state !== "ALLOCATED" && s.add(l.bill_no));
    shownBills.value = s;
    ctx.value = c;
    // 换了 PI：单据多时只展开所选单据；同一 PI 刷新保持原样
    if (fresh) focusBill(bills.value.length > BILL_FOLD_OVER);
  } finally {
    ctxLoading.value = false;
  }
  if (!silent) {
    ctxVer.value++;
    selByBill.value = {};
    loadAlloc();
  }
  if (pushOpen.value) batches.load(silent ? batches.state.page : 1);
  // 有推送任务在跑时，定时刷新推送进度
  if (ctx.value?.push.some((p: any) => p.task && ACTIVE_TASK.includes(p.task.state))) ctxTimer = setTimeout(() => loadCtx(true), 5000);
}

async function reload() {
  await loadCtx();
  allocated.value?.load();
  loadList(true);
}

const canResume = (p: any) => p.mes_enabled && p.remaining?.pushes > 0 && !(p.task && ACTIVE_TASK.includes(p.task.state));
/** 推送中断（有失败或未确认的批次、或有未入批的号且没有运行中的推送任务）且可续推的组织。 */
const brokenPush = computed(() => (ctx.value?.push ?? []).filter((p: any) => canResume(p)));
/** 推送未完成：本 PI 还有待推送的号（推送中、等回执、推送失败、重试用尽、暂停等）；分配前、推完后都没有。 */
const pushOpen = computed(() => (ctx.value?.lines ?? []).some((l: any) => l.to_push > 0));
/** 推送批次（推送与拉取建立的交付批次）按建立先后依次分页列出。 */
const batches = usePaged((q) => api.deliveryBatches({ ...q, pi: ctx.value.pi_no, batch_type: "DELIVERY", asc: 1 }), 20);

// ---------------- 推送进度：已完成 = 已推送或已分配（未对接 MES）的号；有失败批次时标红

function progressOf(x: Counts): { pct: number; state: ProgState } {
  const done = x.qty - x.pending_alloc - x.to_push;
  const pct = x.qty ? Math.floor((done * 100) / x.qty) : 0;
  const state: ProgState = x.failed ? "FAILED" : x.to_push ? "TO_PUSH" : x.pending_alloc ? "PENDING" : "DONE";
  return { pct, state };
}
function progLabel(x: Counts) {
  const p = progressOf(x);
  if (p.state === "FAILED") return t("allocate.prog.FAILED", { n: num(x.failed) });
  return `${t(`allocate.prog.${p.state}`)} ${p.pct}%`;
}

// ---------------- 分配：勾选物料行为范围，不勾选 = 整张 PI；按钮写「分配（推送 M 次）」，未对接 MES 时写「分配 N 枚」

/** 待分配页签下列出的单据（待分配、待处理，及本 PI 列出过的），物料行按单据分组（保持接口给的顺序）。 */
const bills = computed(() => {
  const m = new Map<string, Counts & { bill_no: string; state: BillState; lines: any[] }>();
  for (const l of ctx.value?.lines ?? []) {
    if (!shownBills.value.has(l.bill_no)) continue;
    let b = m.get(l.bill_no);
    if (!b) m.set(l.bill_no, (b = { bill_no: l.bill_no, state: l.bill_state, qty: 0, pending_alloc: 0, to_push: 0, failed: 0, lines: [] }));
    b.qty += l.qty;
    b.pending_alloc += l.pending_alloc;
    b.to_push += l.to_push;
    b.failed += l.failed;
    b.lines.push(l);
  }
  return [...m.values()];
});

/** 收起的单据（只影响显示，不影响勾选）。 */
const folded = ref(new Set<string>());
const isFolded = (no: string) => folded.value.has(no);
function toggleFold(no: string) {
  const s = new Set(folded.value);
  if (!s.delete(no)) s.add(no);
  folded.value = s;
}
function foldAll(on: boolean) {
  folded.value = new Set(on ? bills.value.map((b) => b.bill_no) : []);
}
/** 定位所选单据：展开并滚到可见处；others = 其余单据是否收起。 */
function focusBill(others?: boolean) {
  if (others !== undefined) foldAll(others);
  const s = new Set(folded.value);
  s.delete(bill.value);
  folded.value = s;
  nextTick(() => document.querySelector(`.bill[data-bill="${CSS.escape(bill.value)}"]`)?.scrollIntoView({ block: "nearest" }));
}

/** 各单据表格里勾选的物料行；picked 按单据顺序拼起来。 */
const selByBill = ref<Record<string, LineRef[]>>({});
const picked = computed<LineRef[]>(() => bills.value.flatMap((b) => selByBill.value[b.bill_no] ?? []));
const lineTables = ref<any[]>([]);
const alloc = reactive({ loading: false, data: null as any });
let allocTimer: ReturnType<typeof setTimeout> | undefined;
const scopeBody = computed(() => (picked.value.length ? { lines: picked.value } : {}));

function onSelect(no: string, rows: any[]) {
  selByBill.value = { ...selByBill.value, [no]: rows.map((r) => ({ bill_no: r.bill_no, seq: r.seq })) };
  clearTimeout(allocTimer);
  allocTimer = setTimeout(loadAlloc, 300);
}
function clearSel() {
  lineTables.value.forEach((tb) => tb?.clearSelection());
}

async function loadAlloc() {
  if (!ctx.value || tab.value !== "TO_ALLOCATE") return;
  alloc.loading = true;
  try {
    alloc.data = await api.allocPreview({ pi_no: ctx.value.pi_no, ...scopeBody.value });
  } catch {
    alloc.data = null;
  } finally {
    alloc.loading = false;
  }
}

async function doAllocate() {
  const d = alloc.data;
  const msg = d.pushes ? t("allocate.allocConfirmPush", { n: num(d.qty), m: d.pushes }) : t("allocate.allocConfirm", { n: num(d.qty) });
  await ElMessageBox.confirm(msg, t("allocate.allocTitle"), { type: "warning" });
  busy.value = "alloc";
  try {
    const r = await api.allocate({ pi_no: ctx.value.pi_no, ...scopeBody.value });
    ElMessage.success(t("allocate.allocDone", { n: num(r.qty) }));
    if (r.skipped?.length) ElMessage.warning(t("allocate.allocSkipped", { n: r.skipped.length }));
    // 分配后留在原处看推送进度条
    await reload();
  } finally {
    busy.value = "";
  }
}

async function resume(org: string) {
  busy.value = `resume:${org}`;
  try {
    const r = await api.resumePush(org, ctx.value.pi_no);
    ElMessage.success(t("allocate.resumed", { n: r.pushes }));
    setTimeout(reload, 800);
  } finally {
    busy.value = "";
  }
}

// ---------------- 推送批次：批次详情、人工确认 / 作废（5.2.5 第 7 条）

const manual = ref<InstanceType<typeof BatchManualDialog> | null>(null);
const batchNo = ref("");

// ================================================================ 联动

watch(pi, () => loadCtx());
watch(bill, () => {
  if (tab.value === "TO_ALLOCATE" && ctx.value?.pi_no === pi.value) focusBill();
});
watch(tab, () => {
  if (route.query.tab !== tab.value) router.replace({ query: { ...route.query, tab: tab.value } });
  loadAlloc();
});

onMounted(() => {
  loadList();
  loadCtx();
});

onUnmounted(() => {
  clearTimeout(ctxTimer);
  clearTimeout(allocTimer);
});
</script>

<style scoped>
.split { display: flex; flex-direction: row !important; gap: 16px; }
.pane { display: flex; flex-direction: column; min-height: 0; }
.list-pane { width: 380px; flex: none; }
.detail-pane { flex: 1; min-width: 0; overflow-y: auto; gap: 12px; }
.kw { width: 180px !important; }
.warn { color: var(--el-color-warning-dark-2); }
.err { color: var(--el-color-danger); font-size: 12px; }
.push { margin-left: auto; }
.empty { padding: 40px; text-align: center; }
.dh { display: flex; flex-direction: column; gap: 6px; }
.dh-title { display: flex; gap: 12px; align-items: center; font-size: 16px; color: var(--app-text); }
.go { display: inline-flex; align-items: center; gap: 4px; padding: 3px 12px; border-radius: 14px; font-size: 13px; text-decoration: none;
  color: var(--el-color-primary); background: var(--el-color-primary-light-9); border: 1px solid var(--el-color-primary-light-7); }
.go:hover { background: var(--el-color-primary-light-8); }
.kv { display: flex; flex-wrap: wrap; gap: 4px 18px; font-size: 12px; color: var(--app-muted); }
.kv b { color: var(--app-text); font-weight: 600; }
.banner { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding: 8px 14px; border-radius: 8px; font-size: 13px; }
.warn-bg { background: var(--el-color-warning-light-9); color: var(--el-color-warning-dark-2); }
.box { border: 1px solid var(--el-border-color-lighter); border-radius: 8px; }
.box-h { display: flex; align-items: center; gap: 10px; padding: 10px 12px; background: var(--el-fill-color-light); border-radius: 8px 8px 0 0; }
.bills { max-height: 420px; overflow-y: auto; padding: 8px 12px; display: flex; flex-direction: column; gap: 8px; }
.bill { border: 1px solid var(--el-border-color-lighter); border-radius: 6px; overflow: hidden; flex: none; }
.bill.focus { border-color: var(--el-color-primary-light-5); }
.bill-h { display: flex; align-items: center; gap: 8px; padding: 6px 10px; background: var(--el-fill-color-lighter); font-size: 13px; cursor: pointer; user-select: none; }
.bill-h:hover { background: var(--el-fill-color); }
.fold { color: var(--app-muted); transition: transform 0.15s; }
.fold.open { transform: rotate(90deg); }
.prog { display: inline-flex; align-items: center; gap: 8px; }
.prog :deep(.el-progress) { width: 72px; flex: none; }
.prog-t { font-size: 12px; color: var(--app-muted); white-space: nowrap; }
.bar { display: flex; align-items: center; gap: 10px; padding: 10px 12px; border-top: 1px solid var(--el-border-color); background: var(--el-fill-color-lighter); border-radius: 0 0 8px 8px; font-size: 13px; }
.orgs { display: flex; flex-direction: column; align-items: flex-end; gap: 2px; min-height: 28px; justify-content: center; }
.tabs-box { padding: 0 12px 8px; }
.push-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding: 8px 0; border-bottom: 1px dashed var(--el-border-color-lighter); font-size: 13px; }
.list-pane :deep(.current-row > td) { background: var(--el-color-primary-light-9) !important; }
.list-pane :deep(.el-table__row) { cursor: pointer; }
</style>
