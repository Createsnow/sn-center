<template>
  <div class="page page--fill">
    <PageHead :title="t('allocate.title')" :desc="t('allocate.desc')">
      <span class="muted">{{ t("allocate.summary", { pending: num(list.summary.pending_alloc), unbatched: num(list.summary.unbatched) }) }}</span>
    </PageHead>

    <div class="split fill">
      <!-- 左：PI 列表 -->
      <div class="surface pane list-pane">
        <div class="toolbar">
          <el-radio-group v-model="tab" size="small" @change="loadList()">
            <el-radio-button v-for="v in TABS" :key="v" :value="v">{{ t(`allocate.tab.${v}`) }}</el-radio-button>
          </el-radio-group>
          <el-input v-model="q" class="kw" size="small" :placeholder="t('generate.search')" clearable prefix-icon="Search" @keyup.enter="loadList()" @clear="loadList()" />
        </div>
        <div class="fill-body">
          <el-table v-loading="listLoading" :data="paged" size="small" row-key="pi_no" height="100%" highlight-current-row :current-row-key="pi"
            :row-class-name="({ row }: any) => (row.pi_no === pi ? 'current-row' : '')" :empty-text="t('common.empty')" @row-click="(row: any) => pick(row.pi_no)">
            <el-table-column label="PI" min-width="130">
              <template #default="{ row }">
                <b>{{ row.pi_no }}</b>
                <div class="muted">{{ row.customer_code || "—" }}</div>
              </template>
            </el-table-column>
            <el-table-column :label="t('generate.pendingAlloc')" align="right" width="86">
              <template #default="{ row }"><span class="num" :class="{ zero: !row.pending_alloc }">{{ num(row.pending_alloc) }}</span></template>
            </el-table-column>
            <el-table-column :label="t('common.status')" width="96">
              <template #default="{ row }">
                <el-tag size="small" :type="STATE_TAG[rowState(row)]" disable-transitions>{{ t(`allocate.state.${rowState(row)}`) }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </div>
        <el-pagination class="pager" small layout="prev, pager, next" :total="list.items.length" :page-size="PAGE"
          :current-page="page" @current-change="(p: number) => (page = p)" />
        <p v-if="list.capped" class="muted">{{ t("generate.capped", { n: LIST_LIMIT }) }}</p>
      </div>

      <!-- 右：所选 PI 的分配与推送 -->
      <div v-loading="ctxLoading" class="surface pane detail-pane">
        <p v-if="!ctx" class="muted empty">{{ t("allocate.pickPi") }}</p>
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

          <!-- 待分配的号：勾选物料行为范围，不勾选 = 整张 PI -->
          <section class="box">
            <div class="box-h">
              <b>{{ t("allocate.linesTitle") }}</b>
              <span class="muted">{{ t("allocate.linesHint") }}</span>
            </div>
            <el-table ref="lineTable" :data="ctx.lines" size="small" max-height="360" row-key="key" :empty-text="t('allocate.nothingPending')"
              @selection-change="onSelect">
              <el-table-column type="selection" width="40" :selectable="(row: any) => !row.skip" />
              <el-table-column :label="t('common.billNo')" prop="bill_no" min-width="120" />
              <el-table-column :label="t('common.billSeq')" prop="seq" width="72" />
              <el-table-column :label="t('common.material')" prop="material_code" min-width="100" show-overflow-tooltip />
              <el-table-column :label="t('common.length')" width="70">
                <template #default="{ row }">{{ row.length || "—" }}</template>
              </el-table-column>
              <el-table-column :label="t('generate.pendingAlloc')" align="right" width="80">
                <template #default="{ row }"><span class="num">{{ num(row.qty) }}</span></template>
              </el-table-column>
              <el-table-column :label="t('generate.snRange')" min-width="210">
                <template #default="{ row }"><span class="sn-mono">{{ row.start_sn }} ~ {{ row.end_sn }}</span></template>
              </el-table-column>
              <el-table-column :label="t('allocate.toOrg')" min-width="110" show-overflow-tooltip>
                <template #default="{ row }">{{ row.org_code ? row.org_name || nameOf(row.org_code) : "—" }}</template>
              </el-table-column>
              <el-table-column :label="t('allocate.way')" width="120">
                <template #default="{ row }">
                  <el-tag v-if="row.skip" size="small" type="danger" effect="plain">{{ t(`allocate.skip.${row.skip}`) }}</el-tag>
                  <el-tag v-else size="small" :type="row.mes_enabled ? 'success' : 'info'">{{ row.mes_enabled ? t("allocate.wayPush") : t("allocate.wayAcquire") }}</el-tag>
                </template>
              </el-table-column>
            </el-table>
            <div class="bar">
              <span><span class="muted">{{ t("generate.scope") }}</span> <b>{{ picked.length ? t("generate.scopeLinesN", { n: picked.length }) : t("generate.scopeWholePi") }}</b></span>
              <el-button v-if="picked.length" link type="primary" @click="clearSel">{{ t("generate.clearSel") }}</el-button>
              <span class="push" />
              <span v-loading="alloc.loading" class="orgs">
                <span v-for="o in alloc.data?.orgs || []" :key="o.org_code" class="muted">
                  {{ o.mes_enabled ? t("allocate.orgPush", { org: o.org_name || nameOf(o.org_code), n: num(o.qty), m: o.pushes }) : t("allocate.orgAcquire", { org: o.org_name || nameOf(o.org_code), n: num(o.qty) }) }}
                </span>
                <span v-if="alloc.data?.skipped.length" class="warn">{{ t("allocate.skippedN", { n: alloc.data.skipped.length }) }}</span>
              </span>
              <el-button type="success" :disabled="!alloc.data?.qty" :loading="busy === 'alloc'" @click="doAllocate">
                {{ alloc.data?.pushes ? t("allocate.allocBtnPush", { n: num(alloc.data?.qty || 0), m: alloc.data.pushes }) : t("allocate.allocBtn", { n: num(alloc.data?.qty || 0) }) }}
              </el-button>
            </div>
          </section>

          <!-- 推送进度 / 分配记录 -->
          <el-tabs v-model="dtab" class="box tabs-box">
            <el-tab-pane :label="t('allocate.tabPush')" name="push">
              <p v-if="!ctx.push.length" class="muted">{{ t("allocate.noPush") }}</p>
              <div v-for="p in ctx.push" :key="p.factory_code" class="push-row">
                <b>{{ nameOf(p.factory_code) }}</b>
                <el-tag v-if="p.task" size="small" :type="TASK_TAG[p.task.state] || 'info'">{{ taskStateLabel(p.task.state) }}</el-tag>
                <span class="muted num">{{ t("allocate.pushProgress", { i: p.done_batches, n: p.total_batches, qty: num(p.pushed_qty) }) }}</span>
                <span v-if="p.task?.message" class="muted">{{ p.task.message }}</span>
                <span v-if="p.last_problem" class="err">{{ p.last_problem.batch_no }}：{{ p.last_problem.fail_reason || pushStateLabel(p.last_problem.push_state) }}</span>
                <span class="push" />
                <el-button v-if="canResume(p)" size="small" type="warning" plain :loading="busy === `resume:${p.factory_code}`" @click="resume(p.factory_code)">
                  {{ t("allocate.resumeBtn", { n: p.remaining.pushes }) }}
                </el-button>
              </div>
              <p class="muted">{{ t("allocate.acquireNote") }} <router-link to="/acquire">{{ t("nav.acquire") }}</router-link></p>
            </el-tab-pane>
            <el-tab-pane :label="t('allocate.tabBatches', { n: ctx.batches.length })" name="batches">
              <el-table :data="ctx.batches" size="small" max-height="360" :empty-text="t('common.empty')">
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
                <el-table-column :label="t('allocate.batchResult')" width="130">
                  <template #default="{ row }">
                    <el-tooltip :disabled="!row.fail_reason" :content="row.fail_reason || ''">
                      <el-tag size="small" :type="BATCH_RESULT_TYPE[row.result] || 'info'">{{ batchResultLabel(row.result) }}</el-tag>
                    </el-tooltip>
                    <div v-if="row.push_state && row.push_state !== 'IDLE'" class="muted">{{ pushStateLabel(row.push_state) }}</div>
                  </template>
                </el-table-column>
                <el-table-column :label="t('allocate.confirmMethod')" width="100">
                  <template #default="{ row }">{{ confirmMethodLabel(row.confirm_method) || "—" }}</template>
                </el-table-column>
                <el-table-column :label="t('common.operator')" prop="created_by" width="90" show-overflow-tooltip />
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
            </el-tab-pane>
          </el-tabs>
        </template>
      </div>
    </div>

    <BatchManualDialog ref="manual" @done="reload" />
    <DeliveryBatchDrawer v-model:batch-no="batchNo" @changed="reload" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "@/api";
import { useFactories } from "@/composables/useFactories";
import { useTime } from "@/composables/useTime";
import {
  BATCH_RESULT_TYPE, batchResultLabel, batchSourceLabel, confirmMethodLabel, pushStateLabel, taskStateLabel,
} from "@/constants";
import { canConfirm, canVoid } from "@/utils/batch";
import PageHead from "@/components/PageHead.vue";
import BatchManualDialog from "@/components/batch/BatchManualDialog.vue";
import DeliveryBatchDrawer from "@/components/batch/DeliveryBatchDrawer.vue";

type Tab = "TO_ALLOCATE" | "ALLOCATED";
type RowState = "INTERRUPTED" | "PENDING" | "ALLOCATED";
type TagType = "" | "info" | "primary" | "success" | "warning" | "danger";
type LineRef = { bill_no: string; seq: number };

const TABS: Tab[] = ["TO_ALLOCATE", "ALLOCATED"];
const PAGE = 20;
const LIST_LIMIT = 500;
const STATE_TAG: Record<RowState, TagType> = { INTERRUPTED: "danger", PENDING: "success", ALLOCATED: "info" };
const TASK_TAG: Record<string, TagType> = { QUEUED: "primary", RUNNING: "primary", WAITING: "warning", STOPPED: "danger", DONE: "success" };
const ACTIVE_TASK = ["QUEUED", "RUNNING", "WAITING"];

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const { nameOf } = useFactories();
const { fmt: fmtTime } = useTime();
const num = (n: number | string | null | undefined) => (n == null ? "—" : Number(n).toLocaleString());
const busy = ref("");

// ================================================================ PI 列表

const tab = ref<Tab>("TO_ALLOCATE");
const q = ref("");
const page = ref(1);
const listLoading = ref(false);
const list = ref<any>({ items: [], capped: false, summary: { pending_alloc: 0, unbatched: 0 } });
const paged = computed(() => list.value.items.slice((page.value - 1) * PAGE, page.value * PAGE));

async function loadList(silent = false) {
  if (!silent) listLoading.value = true;
  try {
    list.value = await api.allocPis({ tab: tab.value, q: q.value.trim() });
    if (!silent) page.value = 1;
  } finally {
    listLoading.value = false;
  }
  // 没指定 PI 时打开列表第一张
  if (!pi.value && list.value.items.length) pick(list.value.items[0].pi_no);
}

function rowState(row: any): RowState {
  if (row.push_interrupted) return "INTERRUPTED";
  return row.pending_alloc > 0 ? "PENDING" : "ALLOCATED";
}

// ================================================================ 详情

const pi = computed(() => String(route.query.pi || ""));
const ctx = ref<any>(null);
const ctxLoading = ref(false);
const dtab = ref("push");
let ctxTimer: ReturnType<typeof setTimeout> | undefined;

function pick(no: string) {
  if (no !== pi.value) router.replace({ query: { pi: no } });
}

async function loadCtx(silent = false) {
  clearTimeout(ctxTimer);
  if (!pi.value) {
    ctx.value = null;
    return;
  }
  if (!silent) ctxLoading.value = true;
  try {
    const c = await api.allocContext(pi.value);
    c.lines.forEach((l: any) => (l.key = `${l.bill_no}/${l.seq}/${l.org_code || ""}`));
    ctx.value = c;
  } finally {
    ctxLoading.value = false;
  }
  if (!silent) {
    picked.value = [];
    loadAlloc();
  }
  // 有推送任务在跑时，定时刷新推送状态
  if (ctx.value?.push.some((p: any) => p.task && ACTIVE_TASK.includes(p.task.state))) ctxTimer = setTimeout(() => loadCtx(true), 5000);
}

async function reload() {
  await loadCtx();
  loadList(true);
}

const canResume = (p: any) => p.mes_enabled && p.remaining?.pushes > 0 && !(p.task && ACTIVE_TASK.includes(p.task.state));
/** 推送中断（有失败或未确认的批次、或有未入批的号且没有运行中的推送任务）且可续推的组织。 */
const brokenPush = computed(() => (ctx.value?.push ?? []).filter((p: any) => canResume(p)));

// ---------------- 分配：勾选物料行为范围，不勾选 = 整张 PI；按钮写「分配 N 枚（推送 M 次）」

const picked = ref<LineRef[]>([]);
const lineTable = ref<any>(null);
const alloc = reactive({ loading: false, data: null as any });
let allocTimer: ReturnType<typeof setTimeout> | undefined;
const scopeBody = computed(() => (picked.value.length ? { lines: picked.value } : {}));

function onSelect(rows: any[]) {
  picked.value = rows.map((r) => ({ bill_no: r.bill_no, seq: r.seq }));
  clearTimeout(allocTimer);
  allocTimer = setTimeout(loadAlloc, 300);
}
function clearSel() {
  lineTable.value?.clearSelection();
}

async function loadAlloc() {
  if (!ctx.value) return;
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

// ---------------- 分配记录：批次详情、人工确认 / 作废（5.2.5 第 7 条）

const manual = ref<InstanceType<typeof BatchManualDialog> | null>(null);
const batchNo = ref("");

// ================================================================ 联动

watch(pi, () => {
  dtab.value = "push";
  loadCtx();
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
.zero { color: var(--el-text-color-placeholder); }
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
.bar { display: flex; align-items: center; gap: 10px; padding: 10px 12px; border-top: 1px solid var(--el-border-color); background: var(--el-fill-color-lighter); border-radius: 0 0 8px 8px; font-size: 13px; }
.orgs { display: flex; flex-direction: column; align-items: flex-end; gap: 2px; min-height: 28px; justify-content: center; }
.tabs-box { padding: 0 12px 8px; }
.push-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding: 8px 0; border-bottom: 1px dashed var(--el-border-color-lighter); font-size: 13px; }
.list-pane :deep(.current-row > td) { background: var(--el-color-primary-light-9) !important; }
.list-pane :deep(.el-table__row) { cursor: pointer; }
</style>
