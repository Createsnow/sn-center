<template>
  <div class="page">
    <PageHead :title="t('acquire.title')" :desc="t('acquire.desc')">
      <el-button @click="toRecords()"><el-icon><Tickets /></el-icon><span>{{ t("acquire.records") }}</span></el-button>
    </PageHead>

    <div class="surface">
      <div class="toolbar">
        <el-radio-group v-model="view" @change="clearPicked">
          <el-radio-button v-for="v in VIEWS" :key="v" :value="v">{{ t(`acquire.view${v}`) }}<span class="cnt num">{{ rowsOf(v).length }}</span></el-radio-button>
        </el-radio-group>
        <FactorySelect v-if="!store.boundFactory" v-model="f.factory_code" clearable :placeholder="t('acquire.allFactories')" @update:model-value="reload" />
        <el-tag v-else type="warning" effect="plain" size="large">{{ store.profile?.factory_name || store.boundFactory }}</el-tag>
        <el-input v-model="f.pi" class="pi" :placeholder="t('acquire.piSearch')" clearable prefix-icon="Search" @keyup.enter="reload" @clear="reload" />
        <span class="muted summary">
          {{ t("acquire.summary", { a: totals.to_acquire, p: totals.to_print }) }}
          <span v-if="totals.applying" class="warn">· {{ t("acquire.summaryApplying", { x: totals.applying }) }}</span>
        </span>
      </div>
      <el-alert v-if="store.isQuery" type="info" :closable="false" show-icon :title="t('acquire.queryReadOnly')" class="mb" />

      <el-table ref="table" v-loading="pisLoading" :data="rowsOf(view)" size="small" row-key="_key" class="pis"
        :empty-text="t(`acquire.empty${view}`)" @selection-change="(rows: any[]) => (picked = rows)" @row-click="onRowClick">
        <el-table-column v-if="actable" type="selection" width="40" />
        <el-table-column prop="pi_no" label="PI" min-width="170"><template #default="{ row }"><b>{{ row.pi_no }}</b></template></el-table-column>
        <el-table-column v-if="!store.boundFactory" :label="t('common.factory')" min-width="110"><template #default="{ row }">{{ nameOf(row.factory_code) }}</template></el-table-column>
        <el-table-column prop="customer_code" :label="t('common.customer')" min-width="100" />
        <el-table-column :label="t('acquire.progress')" min-width="230">
          <template #default="{ row }">
            <span class="pipe num">
              <el-tag v-if="row.to_acquire" size="small" type="primary">{{ t("status.TO_ACQUIRE") }} {{ row.to_acquire }}</el-tag>
              <span v-else class="zero">{{ t("status.TO_ACQUIRE") }} 0</span>
              <span class="arr">→</span>
              <el-tag v-if="row.to_print" size="small" type="warning">{{ t("status.TO_PRINT") }} {{ row.to_print }}</el-tag>
              <span v-else class="zero">{{ t("status.TO_PRINT") }} 0</span>
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('status.APPLYING')" align="right" width="90">
          <template #default="{ row }"><span class="num" :class="row.applying ? 'warn' : 'zero'">{{ row.applying }}</span></template>
        </el-table-column>
        <el-table-column v-if="actable" :label="t('common.actions')" width="150" align="right">
          <template #default="{ row }">
            <el-button v-if="rowStep(row)" size="small" :type="btnType(rowStep(row)!)" :loading="busy === `${rowStep(row)}:${row._key}`"
              :disabled="!!busy && busy !== `${rowStep(row)}:${row._key}`" @click.stop="runOne(rowStep(row)!, row)">
              {{ stepLabel(rowStep(row)!, row) }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <p class="muted">{{ t("acquire.rule") }}</p>
    </div>

    <transition name="el-fade-in">
      <div v-if="actable && picked.length" class="bulk">
        <span>{{ t("acquire.picked", { n: picked.length }) }}</span>
        <span class="sp" />
        <el-button size="small" :disabled="!!busy" @click="clearPicked">{{ t("acquire.clearPicked") }}</el-button>
        <el-button v-if="bulkShows('TO_ACQUIRE')" size="small" type="primary" :disabled="!!busy" :loading="busy === 'bulk:TO_ACQUIRE'"
          @click="runBulk('TO_ACQUIRE')">{{ t("acquire.bulkTake", { n: pickedOf("TO_ACQUIRE").length, q: pickedQty("TO_ACQUIRE") }) }}</el-button>
        <el-button v-if="bulkShows('TO_PRINT')" size="small" type="warning" :disabled="!!busy" :loading="busy === 'bulk:TO_PRINT'"
          @click="runBulk('TO_PRINT')">{{ t("acquire.bulkPrint", { n: pickedOf("TO_PRINT").length, q: pickedQty("TO_PRINT") }) }}</el-button>
      </div>
    </transition>

    <el-drawer :model-value="!!detail" size="760px" @close="detail = null">
      <template #header>
        <div v-if="detail" class="dh">
          <b>PI {{ detail.pi_no }}</b>
          <el-tag effect="plain" size="small">{{ nameOf(detail.factory_code) }}<template v-if="detail.customer_code"> · {{ detail.customer_code }}</template></el-tag>
        </div>
      </template>
      <template v-if="detail">
        <div class="row">
          <el-button v-if="actable && rowStep(detail)" :type="btnType(rowStep(detail)!)" :loading="busy === `${rowStep(detail)}:${detail._key}`"
            :disabled="!!busy" @click="runOne(rowStep(detail)!, detail)">{{ stepLabel(rowStep(detail)!, detail) }}</el-button>
          <el-button v-if="actable" @click="toTransfer(detail)">{{ t("acquire.toTransfer") }}</el-button>
          <el-button @click="toRecords(detail)">{{ t("acquire.piRecords") }}</el-button>
        </div>
        <div class="row seg-bar">
          <el-radio-group v-model="segStatus" size="small" @change="loadSegments(1)">
            <el-radio-button value="">{{ t("common.all") }}</el-radio-button>
            <el-radio-button v-for="s in ['TO_ACQUIRE', 'TO_PRINT', 'APPLYING']" :key="s" :value="s">{{ statusLabel(s) }}</el-radio-button>
          </el-radio-group>
          <span class="sp" />
          <el-switch v-model="segAll" :active-text="t('acquire.allFactoriesOfPi')" @change="loadSegments(1)" />
        </div>
        <el-alert v-if="segAll" type="info" :closable="false" show-icon :title="t('acquire.piAllReadOnly')" class="mb" />
        <el-table v-loading="segState.loading" :data="segments" size="small" :empty-text="t('common.empty')">
          <el-table-column v-if="segAll" :label="t('common.factory')" width="110"><template #default="{ row }">{{ nameOf(row.factory_code) }}</template></el-table-column>
          <el-table-column :label="t('common.material')" min-width="110">
            <template #default="{ row }">{{ row.material_code || t("orders.noMaterial") }}</template>
          </el-table-column>
          <el-table-column :label="t('common.status')" width="90"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
          <el-table-column :label="t('acquire.range')" min-width="230">
            <template #default="{ row }">
              <div class="sn-mono">{{ row.start_sn }} ~ {{ row.end_sn }}</div>
              <div class="muted num">{{ row.start_seq }} ~ {{ row.end_seq }}<template v-if="row.seq_pi_no && row.seq_pi_no !== row.pi_no"> · {{ t("acquire.seqOwner") }} {{ row.seq_pi_no }}</template></div>
            </template>
          </el-table-column>
          <el-table-column :label="t('common.qty')" align="right" width="70"><template #default="{ row }"><span class="num">{{ row.qty }}</span></template></el-table-column>
          <el-table-column v-if="actable" width="100" align="right">
            <template #default="{ row }">
              <el-button v-if="canMoveOut(row)" link type="primary" @click="toTransfer(detail, row.material_code)">{{ t("acquire.moveMaterial") }}</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination class="pager" layout="total, prev, pager, next" :total="segState.total" :page-size="segState.pageSize"
          :current-page="segState.page" @current-change="loadSegments" />
      </template>
    </el-drawer>

    <el-dialog v-model="filesOpen" :title="t('acquire.filesTitle')" width="560px">
      <p class="muted">{{ t("acquire.filesHint") }}</p>
      <el-table :data="printedFiles" size="small">
        <el-table-column prop="pi_no" label="PI" min-width="150" />
        <el-table-column prop="print_no" :label="t('acquire.printNo')" min-width="160" />
        <el-table-column prop="qty" :label="t('common.qty')" align="right" width="70" />
        <el-table-column width="110" align="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="download(row.print_no, 'xlsx')">xlsx</el-button>
            <el-button link type="primary" @click="download(row.print_no, 'csv')">csv</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { api, urls } from "@/api";
import { ApiError } from "@/api/http";
import { useAuthStore } from "@/stores/auth";
import { usePaged } from "@/composables/usePaged";
import { useFactories } from "@/composables/useFactories";
import { statusLabel } from "@/constants";
import { newRequestId } from "@/requestId";
import { downloadWithToken } from "@/utils/download";
import PageHead from "@/components/PageHead.vue";
import FactorySelect from "@/components/FactorySelect.vue";
import StatusTag from "@/components/StatusTag.vue";

type Step = "TO_ACQUIRE" | "TO_PRINT";
type View = "ALL" | Step | "APPLYING";
const VIEWS: View[] = ["ALL", "APPLYING", "TO_ACQUIRE", "TO_PRINT"];
const MOVABLE = ["TO_ACQUIRE", "TO_PRINT", "PRINTED"];

const { t } = useI18n();
const router = useRouter();
const store = useAuthStore();
const { nameOf } = useFactories();
const f = reactive({ factory_code: "", pi: "" });
const view = ref<View>("ALL");
const pis = ref<any[]>([]);
const pisLoading = ref(false);
const busy = ref("");
const table = ref<any>(null);
const picked = ref<any[]>([]);
/** 抽屉里打开的 PI 行。 */
const detail = ref<any>(null);
const segStatus = ref("");
/** 抽屉里查看同一 PI 在各厂的号（只读）。 */
const segAll = ref(false);
const filesOpen = ref(false);
const printedFiles = ref<any[]>([]);

const actable = computed(() => store.canAct);
const totals = computed(() =>
  pis.value.reduce((s, r) => ({ to_acquire: s.to_acquire + r.to_acquire, to_print: s.to_print + r.to_print, applying: s.applying + r.applying }),
    { to_acquire: 0, to_print: 0, applying: 0 }),
);
const qtyOf = (row: any, st: Step) => (st === "TO_ACQUIRE" ? row.to_acquire : row.to_print);
const nextStep = (row: any): Step | null => (row.to_acquire > 0 ? "TO_ACQUIRE" : row.to_print > 0 ? "TO_PRINT" : null);
/** 待领取 / 待打印标签只做本标签的工序；全部、申请中按流程取下一步。 */
const rowStep = (row: any): Step | null => {
  if (view.value === "TO_ACQUIRE" || view.value === "TO_PRINT") return qtyOf(row, view.value) > 0 ? view.value : null;
  return nextStep(row);
};
const btnType = (st: Step) => (st === "TO_ACQUIRE" ? "primary" : "warning");
const stepLabel = (st: Step, row: any) => t(st === "TO_ACQUIRE" ? "acquire.takeN" : "acquire.printN", { n: qtyOf(row, st) });
/** 批量栏按行按钮分组：每个按钮只做所选行里行按钮是该工序的 PI。 */
const pickedOf = (st: Step) => picked.value.filter((r) => rowStep(r) === st);
const pickedQty = (st: Step) => pickedOf(st).reduce((s, r) => s + qtyOf(r, st), 0);
const bulkShows = (st: Step) => pickedOf(st).length > 0;

function rowsOf(v: View) {
  if (v === "ALL") return pis.value;
  if (v === "APPLYING") return pis.value.filter((r) => r.applying > 0);
  return pis.value.filter((r) => qtyOf(r, v) > 0);
}

const segParams = () => {
  const d = detail.value;
  if (!segAll.value) return { factory_code: d.factory_code, pi: d.pi_no };
  return store.isFactory ? { pi: d.pi_no, pi_all: true } : { pi: d.pi_no };
};
const { items: segments, state: segState, load: loadSegments } = usePaged<any>(
  (q) => api.acquireSegments({ ...segParams(), status: segStatus.value, ...q }), 20,
);

function clearPicked() {
  table.value?.clearSelection?.();
  picked.value = [];
}

async function reload() {
  pisLoading.value = true;
  try {
    pis.value = (await api.acquirePis({ factory_code: f.factory_code, pi: f.pi.trim() })).map((r: any) => ({ ...r, _key: `${r.factory_code}|${r.pi_no}` }));
  } finally {
    pisLoading.value = false;
  }
  clearPicked();
  if (detail.value) {
    detail.value = pis.value.find((r) => r._key === detail.value._key) || { ...detail.value, to_acquire: 0, to_print: 0 };
    loadSegments();
  }
}

function onRowClick(row: any, column: any) {
  if (column?.type === "selection") return;
  openDetail(row);
}

function openDetail(row: any) {
  detail.value = row;
  segStatus.value = "";
  segAll.value = false;
  loadSegments(1);
}

const canMoveOut = (seg: any) => !segAll.value && !!seg.material_code && MOVABLE.includes(seg.status);

function toTransfer(row: any, material?: string) {
  const query: Record<string, string> = { factory: row.factory_code, pi: row.pi_no };
  if (material) query.material = material;
  router.push({ path: "/transfer", query });
}

function toRecords(row?: any) {
  router.push({ path: "/query", query: row ? { tab: "batches", factory: row.factory_code, pi: row.pi_no } : { tab: "batches" } });
}

/** 对一张 PI 执行领取或打印。silent 时失败不弹窗，由调用方汇总。 */
function exec(st: Step, row: any, silent: boolean) {
  const body = { factory_code: row.factory_code, pi_no: row.pi_no, request_no: newRequestId() };
  return st === "TO_ACQUIRE" ? api.take(body, silent) : api.print(body, silent);
}

/** 页面与 MES 对等，列表上的数量可能已被 MES 改过：以返回的实际枚数为准，成功失败都刷新列表。 */
async function runOne(st: Step, row: any) {
  const shown = qtyOf(row, st);
  const args = { n: shown, pi: row.pi_no, factory: nameOf(row.factory_code) };
  await ElMessageBox.confirm(t(st === "TO_ACQUIRE" ? "acquire.takeConfirm" : "acquire.printConfirm", args), stepLabel(st, row), { type: "warning" });
  busy.value = `${st}:${row._key}`;
  try {
    const r = await exec(st, row, true);
    if (r.qty !== shown) {
      ElMessage.warning({ message: t("acquire.qtyChanged", { n: r.qty, m: shown }), duration: 6000, showClose: true });
    } else if (st === "TO_ACQUIRE") {
      ElMessage.success(t("acquire.taken", { n: r.qty, batch: r.batch_no }));
    } else {
      ElMessage.success(t("acquire.printed", { n: r.qty }));
    }
    if (st === "TO_PRINT") await download(r.print_no, "xlsx");
  } catch (e: any) {
    if (e instanceof ApiError && e.code === "PRINT_NOTHING") ElMessage.warning({ message: t("acquire.printNothing"), duration: 6000, showClose: true });
    else ElMessage.error(e.message);
  } finally {
    busy.value = "";
    await reload();
  }
}

/** 批量：逐张提交，每张一个请求号；某张失败不影响其他张，最后汇总。批量打印完成后列出打印文件，避免浏览器拦截连续下载。 */
async function runBulk(st: Step) {
  const rows = pickedOf(st);
  const key = st === "TO_ACQUIRE" ? "acquire.bulkTakeConfirm" : "acquire.bulkPrintConfirm";
  await ElMessageBox.confirm(t(key, { n: rows.length, q: pickedQty(st) }), t(st === "TO_ACQUIRE" ? "acquire.take" : "acquire.print"), { type: "warning" });
  busy.value = `bulk:${st}`;
  const failed: string[] = [];
  const done: any[] = [];
  try {
    for (const row of rows) {
      try {
        done.push({ ...(await exec(st, row, true)), pi_no: row.pi_no });
      } catch (e: any) {
        failed.push(`${row.pi_no}: ${e.message}`);
      }
    }
  } finally {
    busy.value = "";
  }
  if (done.length) ElMessage.success(t("acquire.bulkDone", { n: done.length, q: done.reduce((s, r) => s + r.qty, 0) }));
  if (failed.length) ElMessage.error({ message: t("acquire.bulkFailed", { n: failed.length, list: failed.join("; ") }), duration: 8000, showClose: true });
  if (st === "TO_PRINT" && done.length) {
    printedFiles.value = done;
    filesOpen.value = true;
  }
  await reload();
}

async function download(printNo: string, format: string) {
  try {
    await downloadWithToken(urls.printFile(printNo, format), `print_${printNo}.${format}`);
  } catch (e: any) {
    ElMessage.error(e.message);
  }
}

onMounted(reload);
</script>

<style scoped>
.pi { width: 220px; }
.summary { margin-left: auto; }
.mb { margin-bottom: 12px; }
.cnt { margin-left: 6px; font-size: 12px; opacity: 0.75; }
.pis :deep(.el-table__row) { cursor: pointer; }
.pipe { display: inline-flex; align-items: center; gap: 6px; }
.arr { color: var(--app-muted); font-size: 12px; }
.zero { color: var(--app-muted); }
.warn { color: var(--el-color-danger); font-weight: 600; }
.bulk { position: sticky; bottom: 0; z-index: 5; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-top: 12px;
  padding: 10px 16px; border-radius: 10px; background: var(--app-ink); color: #fff; font-size: 13px; box-shadow: 0 -6px 20px rgba(15, 23, 42, 0.15); }
.sp { flex: 1; }
.dh { display: flex; align-items: center; gap: 10px; font-size: 15px; color: var(--app-text); }
.row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.seg-bar { margin: 16px 0 12px; }
</style>
