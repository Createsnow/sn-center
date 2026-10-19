<template>
  <div class="page">
    <PageHead :title="t('acquire.title')" :desc="t('acquire.desc')" />

    <el-tabs v-model="tab" class="tabs" @tab-change="onTab">
      <!-- ================= 按「生产组织 + PI」领取 ================= -->
      <el-tab-pane :label="t('acquire.tabPis')" name="pis">
        <div class="surface">
          <div class="toolbar">
            <el-radio-group v-model="f.tab" @change="loadPis">
              <el-radio-button v-for="v in PI_TABS" :key="v" :value="v">{{ t(`acquire.tab${v}`) }}</el-radio-button>
            </el-radio-group>
            <FactorySelect v-if="!store.bound" v-model="f.factory_code" clearable :placeholder="t('app.allFactories')" @update:model-value="loadPis" />
            <el-select v-model="f.mes" clearable class="w140" :placeholder="t('common.mes')" @change="loadPis">
              <el-option :label="t('acquire.mesYes')" value="true" />
              <el-option :label="t('acquire.mesNo')" value="false" />
            </el-select>
            <el-input v-model="f.pi" class="w200" :placeholder="t('acquire.piSearch')" clearable prefix-icon="Search" @keyup.enter="loadPis" @clear="loadPis" />
            <el-button @click="loadPis"><el-icon><Refresh /></el-icon><span>{{ t("common.refresh") }}</span></el-button>
            <span class="muted summary">{{ t("acquire.summary", { a: totals.acquirable, p: totals.applying }) }}</span>
          </div>
          <el-alert v-if="!actable" type="info" :closable="false" show-icon :title="t('acquire.queryReadOnly')" class="mb" />

          <el-table v-loading="pisLoading" :data="pagedPis" size="small" class="clickable" :empty-text="t('common.empty')"
            @selection-change="(rows: PiRow[]) => (picked = rows)" @row-click="openDetail">
            <el-table-column v-if="actable" type="selection" width="40" :selectable="(r: PiRow) => r.acquirable > 0" />
            <el-table-column prop="pi_no" label="PI" min-width="170"><template #default="{ row }"><b>{{ row.pi_no }}</b></template></el-table-column>
            <el-table-column v-if="store.scopeFactories.length !== 1" :label="t('common.factory')" min-width="130">
              <template #default="{ row }">{{ nameOf(row.factory_code) }}</template>
            </el-table-column>
            <el-table-column prop="customer_code" :label="t('common.customer')" min-width="100" />
            <el-table-column :label="t('common.mes')" width="110">
              <template #default="{ row }">
                <el-tag v-if="row.mes_enabled" size="small" type="success" effect="plain">MES</el-tag>
                <span v-else class="muted">{{ t("common.no") }}</span>
                <el-tag v-if="row.pushing" size="small" type="warning" class="ml">{{ t("acquire.pushing") }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('acquire.acquirable')" align="right" width="96">
              <template #default="{ row }"><span class="num" :class="{ zero: !row.acquirable }">{{ row.acquirable }}</span></template>
            </el-table-column>
            <el-table-column :label="t('status.APPLYING')" align="right" width="110">
              <template #default="{ row }"><span class="num" :class="row.applying ? 'warn' : 'zero'">{{ row.applying }}</span></template>
            </el-table-column>
            <el-table-column :label="t('acquire.frozen')" align="right" width="110">
              <template #default="{ row }">
                <span v-if="row.mes_enabled" class="num" :class="row.frozen ? 'warn' : 'zero'">{{ row.frozen }}</span>
                <span v-else class="zero">—</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('acquire.suspended')" align="right" width="96">
              <template #default="{ row }"><span class="num" :class="row.suspended ? 'warn' : 'zero'">{{ row.suspended }}</span></template>
            </el-table-column>
            <el-table-column v-if="actable" :label="t('common.actions')" width="110" align="right">
              <template #default="{ row }">
                <el-button size="small" type="primary" :disabled="!row.acquirable || !!busy" :loading="busy === keyOf(row)"
                  @click.stop="openTake(row)">{{ retryReq[keyOf(row)] ? t("acquire.retry") : t("acquire.take") }}</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="pis.length" :page-size="pageSize"
            :current-page="page" :page-sizes="[20, 50, 100]" @current-change="(p: number) => (page = p)" @size-change="(s: number) => { pageSize = s; page = 1; }" />
          <p class="muted">{{ t("acquire.rule") }}</p>
        </div>

        <transition name="el-fade-in">
          <div v-if="actable && picked.length" class="bulk">
            <span>{{ t("acquire.picked", { n: picked.length, q: pickedQty }) }}</span>
            <span class="sp" />
            <el-select v-model="format" size="small" class="w100">
              <el-option label="xlsx" value="xlsx" /><el-option label="csv" value="csv" />
            </el-select>
            <el-button size="small" type="primary" :loading="busy === 'bulk'" :disabled="!!busy && busy !== 'bulk'" @click="runBulk">
              {{ t("acquire.bulkTake", { n: picked.length }) }}
            </el-button>
          </div>
        </transition>
      </el-tab-pane>

      <!-- ================= 领取批次 ================= -->
      <el-tab-pane :label="t('acquire.tabBatches')" name="batches">
        <div class="surface">
          <div class="toolbar">
            <FactorySelect v-if="!store.bound" v-model="bf.factory_code" clearable :placeholder="t('app.allFactories')" @update:model-value="batches.load(1)" />
            <el-input v-model="bf.pi" class="w180" placeholder="PI" clearable @keyup.enter="batches.load(1)" @clear="batches.load(1)" />
            <el-input v-model="bf.batch_no" class="w200" :placeholder="t('common.batchNo')" clearable @keyup.enter="batches.load(1)" @clear="batches.load(1)" />
            <el-input v-model="bf.request_no" class="w200" :placeholder="t('acquire.requestNo')" clearable @keyup.enter="batches.load(1)" @clear="batches.load(1)" />
            <el-button type="primary" @click="batches.load(1)">{{ t("common.search") }}</el-button>
          </div>
          <el-table v-loading="batches.state.loading" :data="batches.items.value" size="small" :empty-text="t('common.empty')">
            <el-table-column prop="batch_no" :label="t('common.batchNo')" min-width="190"><template #default="{ row }"><span class="sn-mono">{{ row.batch_no }}</span></template></el-table-column>
            <el-table-column :label="t('common.factory')" min-width="120"><template #default="{ row }">{{ nameOf(row.factory_code) }}</template></el-table-column>
            <el-table-column prop="pi_no" label="PI" min-width="140" />
            <el-table-column prop="request_no" :label="t('acquire.requestNo')" min-width="150" show-overflow-tooltip />
            <el-table-column :label="t('common.status')" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'DONE' ? 'success' : 'warning'">{{ t(`acquire.batchStatus.${row.status}`) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('common.qty')" align="right" width="80"><template #default="{ row }"><span class="num">{{ row.qty }}</span></template></el-table-column>
            <el-table-column :label="t('common.mes')" width="80"><template #default="{ row }">{{ row.mes_org ? t("common.yes") : t("common.no") }}</template></el-table-column>
            <el-table-column prop="created_by" :label="t('common.operator')" width="100" />
            <el-table-column :label="t('common.createdAt')" width="160"><template #default="{ row }">{{ fmt(row.created_at) }}</template></el-table-column>
            <el-table-column :label="t('common.actions')" width="230" align="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openItems(row)">{{ t("common.detail") }}</el-button>
                <template v-if="row.status === 'DONE'">
                  <el-button link type="primary" @click="download(row.batch_no, 'xlsx')">xlsx</el-button>
                  <el-button link type="primary" @click="download(row.batch_no, 'csv')">csv</el-button>
                </template>
                <el-button v-else-if="canRetry(row)" link type="warning" :loading="busy === row.batch_no" @click="retryBatch(row)">{{ t("acquire.retry") }}</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="batches.state.total" :page-size="batches.state.pageSize"
            :current-page="batches.state.page" :page-sizes="[20, 50, 100]" @current-change="batches.load" @size-change="batches.onSize" />
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 领取确认 -->
    <el-dialog v-model="take.open" :title="t('acquire.confirmTitle')" width="520px">
      <template v-if="take.row">
        <el-descriptions :column="1" size="small" border>
          <el-descriptions-item :label="t('common.factory')">{{ nameOf(take.row.factory_code) }}（{{ take.row.factory_code }}）</el-descriptions-item>
          <el-descriptions-item label="PI">{{ take.row.pi_no }}</el-descriptions-item>
          <el-descriptions-item :label="t('acquire.acquirable')"><b class="num big">{{ take.preview?.acquirable ?? "…" }}</b></el-descriptions-item>
          <template v-if="take.preview?.mes_enabled">
            <el-descriptions-item :label="t('acquire.frozen')"><span class="num" :class="{ warn: take.preview.frozen }">{{ take.preview.frozen }}</span></el-descriptions-item>
            <el-descriptions-item :label="t('acquire.reassign')"><span class="num">{{ take.preview.reassign }}</span></el-descriptions-item>
          </template>
          <el-descriptions-item :label="t('acquire.suspended')"><span class="num" :class="{ warn: take.preview?.suspended }">{{ take.preview?.suspended ?? 0 }}</span></el-descriptions-item>
          <el-descriptions-item :label="t('status.APPLYING')"><span class="num">{{ take.preview?.applying ?? 0 }}</span></el-descriptions-item>
          <el-descriptions-item :label="t('acquire.requestNo')"><span class="sn-mono muted">{{ take.requestNo }}</span></el-descriptions-item>
          <el-descriptions-item :label="t('acquire.format')">
            <el-radio-group v-model="format" size="small"><el-radio-button value="xlsx">xlsx</el-radio-button><el-radio-button value="csv">csv</el-radio-button></el-radio-group>
          </el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="take.preview?.mes_enabled" type="warning" show-icon :closable="false" class="mt" :title="t('acquire.mesNote')" />
        <el-alert v-if="take.retry" type="info" show-icon :closable="false" class="mt" :title="t('acquire.retryNote')" />
      </template>
      <template #footer>
        <el-button @click="take.open = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" :loading="!!busy" :disabled="!take.preview?.acquirable && !take.retry" @click="confirmTake">{{ t("acquire.take") }}</el-button>
      </template>
    </el-dialog>

    <!-- PI 详情：号段 -->
    <el-drawer :model-value="!!detail" size="820px" @close="detail = null">
      <template #header>
        <div v-if="detail" class="dh">
          <b>PI {{ detail.pi_no }}</b>
          <el-tag effect="plain" size="small">{{ nameOf(detail.factory_code) }}<template v-if="detail.customer_code"> · {{ detail.customer_code }}</template></el-tag>
          <el-tag v-if="detail.mes_enabled" size="small" type="success" effect="plain">MES</el-tag>
        </div>
      </template>
      <template v-if="detail">
        <div class="row">
          <el-button v-if="actable" type="primary" :disabled="!detail.acquirable || !!busy" @click="openTake(detail)">{{ t("acquire.take") }}</el-button>
          <el-button v-if="actable" @click="toTransfer(detail)">{{ t("acquire.toTransfer") }}</el-button>
        </div>
        <div class="toolbar seg-bar">
          <el-select v-model="sf.status" clearable class="w160" :placeholder="t('common.status')" @change="segs.load(1)">
            <el-option v-for="s in STATUS_CODES.filter((x) => x !== 'PENDING_ALLOC')" :key="s" :label="statusLabel(s)" :value="s" />
          </el-select>
          <el-switch v-model="sf.pi_all" :active-text="t('acquire.piAll')" @change="segs.load(1)" />
        </div>
        <el-alert v-if="sf.pi_all" type="info" show-icon :closable="false" class="mb" :title="t('acquire.piAllNote')" />
        <el-table v-loading="segs.state.loading" :data="segs.items.value" size="small" :empty-text="t('common.empty')">
          <el-table-column v-if="sf.pi_all" :label="t('common.factory')" min-width="110"><template #default="{ row }">{{ nameOf(row.factory_code) }}</template></el-table-column>
          <el-table-column :label="t('common.billNo')" min-width="120"><template #default="{ row }">{{ row.bill_no }} / {{ row.bill_seq }}</template></el-table-column>
          <el-table-column prop="material_code" :label="t('common.material')" min-width="90" />
          <el-table-column :label="t('acquire.segment')" min-width="250">
            <template #default="{ row }">
              <div class="sn-mono">{{ row.start_sn }}</div>
              <div v-if="row.end_sn !== row.start_sn" class="sn-mono">~ {{ row.end_sn }}</div>
              <div class="muted">{{ row.start_seq }} ~ {{ row.end_seq }}<template v-if="row.transferred_in"> · {{ t("acquire.seqOf", { pi: row.seq_pi_no }) }}</template></div>
            </template>
          </el-table-column>
          <el-table-column :label="t('common.qty')" align="right" width="70"><template #default="{ row }"><span class="num">{{ row.qty }}</span></template></el-table-column>
          <el-table-column :label="t('common.status')" width="130">
            <template #default="{ row }">
              <StatusTag :status="row.status" />
              <el-tag v-if="row.suspend" size="small" type="danger" class="ml">{{ suspendLabel(row.suspend) }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination class="pager" layout="total, prev, pager, next" :total="segs.state.total" :page-size="segs.state.pageSize"
          :current-page="segs.state.page" @current-change="segs.load" />
      </template>
    </el-drawer>

    <!-- 领取批次明细 -->
    <el-drawer :model-value="!!itemsOf" size="820px" :title="itemsOf ? t('acquire.itemsOf', { b: itemsOf.batch_no }) : ''" @close="itemsOf = null">
      <template v-if="itemsOf">
        <el-table v-loading="bitems.state.loading" :data="bitems.items.value" size="small" :empty-text="t('common.empty')">
          <el-table-column prop="line_no" label="#" width="60" />
          <el-table-column :label="t('common.sn')" min-width="190"><template #default="{ row }"><span class="sn-mono">{{ row.sn }}</span></template></el-table-column>
          <el-table-column prop="seq_text" :label="t('common.seqText')" width="100" />
          <el-table-column prop="seq_dec" :label="t('common.seqDec')" width="100" />
          <el-table-column :label="t('common.billNo')" min-width="120"><template #default="{ row }">{{ row.bill_no }} / {{ row.bill_seq }}</template></el-table-column>
          <el-table-column prop="material_code" :label="t('common.material')" width="100" />
          <el-table-column prop="length" :label="t('common.length')" width="70" />
          <el-table-column :label="t('common.status')" width="100"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
        </el-table>
        <el-pagination class="pager" layout="total, prev, pager, next" :total="bitems.state.total" :page-size="bitems.state.pageSize"
          :current-page="bitems.state.page" @current-change="bitems.load" />
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { api, urls } from "@/api";
import { ApiError } from "@/api/http";
import PageHead from "@/components/PageHead.vue";
import FactorySelect from "@/components/FactorySelect.vue";
import StatusTag from "@/components/StatusTag.vue";
import { useFactories } from "@/composables/useFactories";
import { usePaged } from "@/composables/usePaged";
import { useTime } from "@/composables/useTime";
import { STATUS_CODES, statusLabel, suspendLabel } from "@/constants";
import { newRequestId } from "@/requestId";
import { useAuthStore } from "@/stores/auth";
import { downloadWithToken } from "@/utils/download";

interface PiRow {
  factory_code: string;
  pi_no: string;
  customer_code: string;
  mes_enabled: boolean;
  acquirable: number;
  applying: number;
  frozen: number;
  suspended: number;
  pushing: boolean;
}
interface AcqBatch {
  batch_no: string;
  factory_code: string;
  pi_no: string;
  request_no: string;
  mes_org: boolean;
  status: "DONE" | "INCOMPLETE";
  qty: number;
  created_by: string;
  created_at: string;
  finished_at: string | null;
  replayed?: boolean;
  inline_file?: boolean;
}
interface Preview {
  mes_enabled: boolean;
  acquirable: number;
  frozen: number;
  suspended: number;
  reassign: number;
  applying: number;
}

const PI_TABS = ["ALL", "ACQUIRABLE", "APPLYING"] as const;

const { t } = useI18n();
const store = useAuthStore();
const route = useRoute();
const router = useRouter();
const { nameOf } = useFactories();
const { fmt } = useTime();

/** 总部可代任一组织领取，操作员只领本组织；查询员只读。 */
const actable = computed(() => store.canAct);
const tab = ref<"pis" | "batches">(route.query.tab === "batches" ? "batches" : "pis");
const format = ref<"xlsx" | "csv">("xlsx");
const busy = ref("");

// ---------------- PI 列表
const f = reactive({ tab: "ALL" as (typeof PI_TABS)[number], factory_code: "", mes: "", pi: String(route.query.pi || "") });
const pis = ref<PiRow[]>([]);
const pisLoading = ref(false);
const picked = ref<PiRow[]>([]);
const page = ref(1);
const pageSize = ref(20);
const pagedPis = computed(() => pis.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value));
const totals = computed(() => ({
  acquirable: pis.value.reduce((s, r) => s + r.acquirable, 0),
  applying: pis.value.reduce((s, r) => s + r.applying, 0),
}));
const pickedQty = computed(() => picked.value.reduce((s, r) => s + r.acquirable, 0));
const keyOf = (r: { factory_code: string; pi_no: string }) => `${r.factory_code}|${r.pi_no}`;
/** 领取中途失败的请求号：保留下来，「重试」时沿用（同一请求号接着认领，不会重复领取）。 */
const retryReq = reactive<Record<string, string>>({});

async function loadPis() {
  pisLoading.value = true;
  try {
    pis.value = await api.acquirePis({ tab: f.tab, factory_code: f.factory_code, pi: f.pi.trim(), mes: f.mes });
    page.value = 1;
  } finally {
    pisLoading.value = false;
  }
}

// ---------------- 领取
const take = reactive({ open: false, row: null as PiRow | null, preview: null as Preview | null, requestNo: "", retry: false });

async function openTake(row: PiRow) {
  const k = keyOf(row);
  Object.assign(take, { open: true, row, preview: null, requestNo: retryReq[k] || newRequestId(), retry: !!retryReq[k] });
  take.preview = await api.acquirePreview({ factory_code: row.factory_code, pi: row.pi_no });
}

async function takeOne(factory: string, pi: string, requestNo: string, silent = false): Promise<AcqBatch> {
  const k = `${factory}|${pi}`;
  retryReq[k] = requestNo;
  const b: AcqBatch = await api.take({ factory_code: factory, pi_no: pi, request_no: requestNo }, silent);
  delete retryReq[k];
  return b;
}

async function afterTake(b: AcqBatch) {
  if (b.replayed) ElMessage.info(t("acquire.replayed", { b: b.batch_no }));
  else ElMessage.success(t("acquire.taken", { n: b.qty, b: b.batch_no }));
  if (b.inline_file) await download(b.batch_no, format.value);
  else ElMessage.info({ message: t("acquire.fileLater", { b: b.batch_no }), duration: 8000, showClose: true });
}

async function confirmTake() {
  const row = take.row;
  if (!row) return;
  busy.value = keyOf(row);
  try {
    const b = await takeOne(row.factory_code, row.pi_no, take.requestNo);
    take.open = false;
    await afterTake(b);
  } catch {
    /* 拦截器已提示；请求号保留供重试 */
  } finally {
    busy.value = "";
    loadPis();
    if (tab.value === "batches") batches.load();
  }
}

async function runBulk() {
  const rows = picked.value.filter((r) => r.acquirable > 0);
  if (!rows.length) return;
  await ElMessageBox.confirm(t("acquire.bulkConfirm", { n: rows.length, q: pickedQty.value }), t("acquire.confirmTitle"), { type: "warning" });
  busy.value = "bulk";
  const ok: AcqBatch[] = [];
  const failed: string[] = [];
  try {
    for (const r of rows) {
      try {
        const b = await takeOne(r.factory_code, r.pi_no, retryReq[keyOf(r)] || newRequestId(), true);
        ok.push(b);
        if (b.inline_file) await download(b.batch_no, format.value).catch(() => failed.push(`${r.pi_no}：${t("common.requestFailed")}`));
      } catch (e) {
        failed.push(`${r.pi_no}：${(e as Error).message}`);
      }
    }
  } finally {
    busy.value = "";
  }
  const qty = ok.reduce((s, b) => s + b.qty, 0);
  if (failed.length) {
    ElMessageBox.alert(
      [t("acquire.bulkDone", { ok: ok.length, q: qty, fail: failed.length }), ...failed].join("\n"),
      t("acquire.bulkResult"),
      { type: "warning", customStyle: { whiteSpace: "pre-line" } },
    );
  } else {
    ElMessage.success(t("acquire.bulkDone", { ok: ok.length, q: qty, fail: 0 }));
  }
  picked.value = [];
  loadPis();
}

async function download(batchNo: string, fmtName: string) {
  try {
    await downloadWithToken(urls.acquireFile(batchNo, fmtName), `${batchNo}.${fmtName}`);
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

// ---------------- 领取批次
const bf = reactive({ factory_code: "", pi: "", batch_no: "", request_no: "" });
const batches = usePaged<AcqBatch>((q) => api.acquireBatches({ ...q, ...bf, pi: bf.pi.trim(), batch_no: bf.batch_no.trim(), request_no: bf.request_no.trim() }));

function canRetry(b: AcqBatch) {
  return actable.value && (store.isAdmin || b.created_by === store.profile?.emp_no);
}

async function retryBatch(b: AcqBatch) {
  busy.value = b.batch_no;
  try {
    await afterTake(await takeOne(b.factory_code, b.pi_no, b.request_no));
  } catch (e) {
    if (!(e instanceof ApiError)) throw e;
  } finally {
    busy.value = "";
    batches.load();
  }
}

const itemsOf = ref<AcqBatch | null>(null);
const bitems = usePaged<any>((q) => api.acquireBatchItems(itemsOf.value!.batch_no, q), 50);
function openItems(b: AcqBatch) {
  itemsOf.value = b;
  bitems.load(1);
}

// ---------------- 详情：号段
const detail = ref<PiRow | null>(null);
const sf = reactive({ status: "", pi_all: false });
const segs = usePaged<any>((q) =>
  api.acquireSegments({ ...q, pi: detail.value!.pi_no, status: sf.status, ...(sf.pi_all ? { pi_all: true } : { factory_code: detail.value!.factory_code }) }),
);
function openDetail(row: PiRow) {
  detail.value = row;
  Object.assign(sf, { status: "", pi_all: false });
  segs.load(1);
}

function toTransfer(row: PiRow) {
  router.push({ path: "/transfer", query: { tab: "org", new: "1", factory_code: row.factory_code, pi: row.pi_no } });
}

function onTab(name: string | number) {
  if (name === "batches") batches.load(1);
}

onMounted(() => {
  loadPis();
  if (tab.value === "batches") batches.load(1);
});
</script>

<style scoped>
.w100 { width: 100px; }
.w140 { width: 140px; }
.w160 { width: 160px; }
.w180 { width: 180px; }
.w200 { width: 200px; }
.summary { margin-left: auto; }
.mb { margin-bottom: 12px; }
.mt { margin-top: 12px; }
.ml { margin-left: 6px; }
.big { font-size: 18px; }
.clickable :deep(.el-table__row) { cursor: pointer; }
.zero { color: var(--app-muted); }
.warn { color: var(--el-color-danger); font-weight: 600; }
.bulk { position: sticky; bottom: 0; z-index: 5; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-top: 12px;
  padding: 10px 16px; border-radius: 10px; background: var(--app-ink); color: #fff; font-size: 13px; box-shadow: 0 -6px 20px rgba(15, 23, 42, 0.15); }
.sp { flex: 1; }
.dh { display: flex; align-items: center; gap: 10px; font-size: 15px; color: var(--app-text); }
.row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.seg-bar { margin: 16px 0 12px; }
</style>
