<template>
  <div class="page">
    <PageHead :title="t('acquire.title')" :desc="t('acquire.desc')" />

    <div class="surface filters">
      <div class="toolbar">
        <FactorySelect v-if="!store.boundFactory" v-model="f.factory_code" clearable :placeholder="t('acquire.allFactories')" @update:model-value="reload" />
        <el-tag v-else type="warning" effect="plain" size="large">{{ store.profile?.factory_name || store.boundFactory }}</el-tag>
        <el-input v-model="f.pi" placeholder="PI" clearable @keyup.enter="reload" @clear="reload" />
        <el-switch v-if="store.isFactory" v-model="f.pi_all" :active-text="t('acquire.piAll')" :disabled="!f.pi.trim()" @change="reload" />
        <el-button type="primary" @click="reload">{{ t("common.search") }}</el-button>
        <span v-if="!f.pi_all" class="muted summary">
          {{ t("acquire.summary", { n: pis.length, a: totals.to_acquire, p: totals.to_print }) }}
          <span v-if="totals.applying" class="warn">· {{ t("acquire.summaryApplying", { x: totals.applying }) }}</span>
        </span>
      </div>
      <el-alert v-if="readOnly" type="info" :closable="false" show-icon :title="readOnlyText" />
    </div>

    <div class="surface">
      <el-tabs v-model="tab" @tab-change="onTab">
        <el-tab-pane v-for="st in f.pi_all ? [] : STEPS" :key="st" :name="st">
          <template #label>
            {{ t(`status.${st}`) }}<span class="cnt num">{{ stepRows(st).length }}</span>
          </template>
          <el-table :ref="(el: any) => (tables[st] = el)" v-loading="pisLoading" :data="stepRows(st)" size="small" row-key="_key"
            :empty-text="t(st === 'TO_ACQUIRE' ? 'acquire.emptyAcquire' : 'acquire.emptyPrint')" @selection-change="(rows: any[]) => (picked[st] = rows)">
            <el-table-column v-if="actable" type="selection" width="40" reserve-selection />
            <el-table-column prop="pi_no" label="PI" min-width="170"><template #default="{ row }"><b>{{ row.pi_no }}</b></template></el-table-column>
            <el-table-column :label="t('common.factory')" min-width="110"><template #default="{ row }">{{ nameOf(row.factory_code) }}</template></el-table-column>
            <el-table-column prop="customer_code" :label="t('common.customer')" min-width="100" />
            <el-table-column :label="t(`status.${st}`)" align="right" width="100">
              <template #default="{ row }"><b class="num">{{ qtyOf(row, st) }}</b></template>
            </el-table-column>
            <el-table-column :label="t('status.APPLYING')" align="right" width="90">
              <template #default="{ row }"><span class="num" :class="row.applying ? 'warn' : 'zero'">{{ row.applying }}</span></template>
            </el-table-column>
            <el-table-column :label="t('common.actions')" width="170" align="center">
              <template #default="{ row }">
                <el-button v-if="actable" size="small" :type="st === 'TO_ACQUIRE' ? 'primary' : 'warning'" :loading="busy === `${st}:${row._key}`"
                  :disabled="!!busy && busy !== `${st}:${row._key}`" @click="runOne(st, row)">{{ actLabel(st) }}</el-button>
                <el-button link type="primary" @click="focusPi(row)">{{ t("acquire.segments") }}</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="actable && stepRows(st).length" class="bulk">
            <span>{{ t("acquire.picked", { n: picked[st].length, q: pickedQty(st) }) }}</span>
            <el-button size="small" :type="st === 'TO_ACQUIRE' ? 'primary' : 'warning'" :disabled="!picked[st].length || !!busy"
              :loading="busy === `bulk:${st}`" @click="runBulk(st)">{{ t(st === "TO_ACQUIRE" ? "acquire.bulkTake" : "acquire.bulkPrint") }}</el-button>
            <span class="muted">{{ t("acquire.bulkNote") }}</span>
          </div>
          <p v-if="st === 'TO_ACQUIRE'" class="muted">{{ t("acquire.rule") }}</p>
        </el-tab-pane>

        <el-tab-pane :label="t('acquire.batches')" name="batches">
          <FocusBar />
          <el-table v-loading="batchState.loading" :data="batches" size="small" :empty-text="t('common.empty')">
            <el-table-column prop="batch_no" :label="t('acquire.batchNo')" min-width="190" />
            <el-table-column :label="t('common.factory')" width="120"><template #default="{ row }">{{ nameOf(row.factory_code) }}</template></el-table-column>
            <el-table-column prop="pi_no" label="PI" min-width="150" />
            <el-table-column prop="qty" :label="t('common.qty')" align="right" width="80" />
            <el-table-column :label="t('audit.source')" width="80"><template #default="{ row }">{{ t(`audit.src${row.source}`) }}</template></el-table-column>
            <el-table-column prop="request_no" :label="t('acquire.requestNo')" min-width="150" show-overflow-tooltip />
            <el-table-column prop="created_by" :label="t('common.operator')" width="90" />
            <el-table-column prop="created_at" :label="t('common.time')" width="160" />
            <el-table-column :label="t('common.actions')" width="80">
              <template #default="{ row }"><el-button link type="primary" @click="openBatch(row)">{{ t("common.detail") }}</el-button></template>
            </el-table-column>
          </el-table>
          <el-pagination class="pager" layout="total, prev, pager, next" :total="batchState.total" :page-size="batchState.pageSize"
            :current-page="batchState.page" @current-change="loadBatches" />
        </el-tab-pane>

        <el-tab-pane :label="t('acquire.prints')" name="prints">
          <FocusBar />
          <el-table v-loading="printState.loading" :data="prints" size="small" :empty-text="t('common.empty')">
            <el-table-column prop="print_no" :label="t('acquire.printNo')" min-width="190" />
            <el-table-column :label="t('common.factory')" width="120"><template #default="{ row }">{{ nameOf(row.factory_code) }}</template></el-table-column>
            <el-table-column prop="pi_no" label="PI" min-width="150" />
            <el-table-column prop="qty" :label="t('common.qty')" align="right" width="80" />
            <el-table-column prop="created_by" :label="t('common.operator')" width="90" />
            <el-table-column prop="created_at" :label="t('common.time')" width="160" />
            <el-table-column :label="t('acquire.file')" width="140">
              <template #default="{ row }">
                <el-button link type="primary" @click="download(row.print_no, 'xlsx')">xlsx</el-button>
                <el-button link type="primary" @click="download(row.print_no, 'csv')">csv</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination class="pager" layout="total, prev, pager, next" :total="printState.total" :page-size="printState.pageSize"
            :current-page="printState.page" @current-change="loadPrints" />
        </el-tab-pane>

        <el-tab-pane :label="t('acquire.segments')" name="segments">
          <FocusBar />
          <div class="seg-bar">
            <el-radio-group v-model="f.status" size="small" @change="reloadSegments">
              <el-radio-button value="">{{ t("common.all") }}</el-radio-button>
              <el-radio-button v-for="s in ['TO_ACQUIRE', 'TO_PRINT', 'APPLYING']" :key="s" :value="s">{{ statusLabel(s) }}</el-radio-button>
            </el-radio-group>
            <el-input v-model="f.material_code" size="small" class="mat" :placeholder="t('common.material')" clearable
              @keyup.enter="reloadSegments" @clear="reloadSegments" />
            <span class="muted">{{ t("acquire.segmentsHint") }}</span>
          </div>
          <el-table v-loading="segState.loading" :data="segments" size="small" :empty-text="t('common.empty')">
            <el-table-column :label="t('common.factory')" width="120"><template #default="{ row }">{{ nameOf(row.factory_code) }}</template></el-table-column>
            <el-table-column prop="pi_no" label="PI" min-width="160" />
            <el-table-column :label="t('common.material')" width="120">
              <template #default="{ row }">{{ row.material_code || t("orders.noMaterial") }}</template>
            </el-table-column>
            <el-table-column :label="t('common.status')" width="100"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
            <el-table-column :label="t('acquire.range')" min-width="260">
              <template #default="{ row }"><span class="sn-mono">{{ row.start_sn }} ~ {{ row.end_sn }}</span></template>
            </el-table-column>
            <el-table-column :label="t('acquire.decRange')" width="150">
              <template #default="{ row }"><span class="num">{{ row.start_seq }} ~ {{ row.end_seq }}</span></template>
            </el-table-column>
            <el-table-column :label="t('common.qty')" align="right" width="80"><template #default="{ row }"><span class="num">{{ row.qty }}</span></template></el-table-column>
            <el-table-column prop="seq_pi_no" :label="t('acquire.seqOwner')" min-width="150" show-overflow-tooltip />
          </el-table>
          <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="segState.total" :page-size="segState.pageSize"
            :current-page="segState.page" :page-sizes="[20, 50, 100]" @current-change="loadSegments" @size-change="segSize" />
        </el-tab-pane>
      </el-tabs>
    </div>

    <el-drawer :model-value="!!batch" :title="t('acquire.batchDetail', { no: batch?.batch_no })" size="760px" @close="batch = null">
      <el-table v-loading="itemState.loading" :data="batchItems" size="small">
        <el-table-column prop="sn" :label="t('common.sn')" min-width="170"><template #default="{ row }"><span class="sn-mono">{{ row.sn }}</span></template></el-table-column>
        <el-table-column prop="seq_text" :label="t('common.seqText')" width="110"><template #default="{ row }"><span class="sn-mono">{{ row.seq_text }}</span></template></el-table-column>
        <el-table-column prop="seq_dec" :label="t('common.seqDec')" width="100" />
        <el-table-column prop="material_code" :label="t('common.material')" width="110" />
        <el-table-column :label="t('common.status')" width="90"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
        <el-table-column prop="pi_no" label="PI" min-width="140" />
      </el-table>
      <el-pagination class="pager" layout="total, prev, pager, next" :total="itemState.total" :page-size="itemState.pageSize"
        :current-page="itemState.page" @current-change="loadBatchItems" />
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElButton, ElMessage, ElMessageBox } from "element-plus";
import { api, urls } from "@/api";
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
const STEPS: Step[] = ["TO_ACQUIRE", "TO_PRINT"];

const { t } = useI18n();
const store = useAuthStore();
const { nameOf } = useFactories();
const f = reactive({ factory_code: "", pi: "", material_code: "", status: "", pi_all: false });
const pis = ref<any[]>([]);
const pisLoading = ref(false);
const busy = ref("");
const tab = ref<string>("TO_ACQUIRE");
const batch = ref<any>(null);
/** 两个工序标签各自的勾选行。 */
const picked = reactive<Record<Step, any[]>>({ TO_ACQUIRE: [], TO_PRINT: [] });
const tables: Record<string, any> = {};
/** 从工序列表点「号段」时聚焦的「工厂 + PI」；批次、打印记录、号段三个标签按它过滤。 */
const focus = ref<any>(null);

const readOnly = computed(() => store.isQuery || (store.isFactory && f.pi_all));
const actable = computed(() => store.canAct && !f.pi_all);
const readOnlyText = computed(() => (store.isQuery ? t("acquire.queryReadOnly") : t("acquire.piAllReadOnly")));
const totals = computed(() =>
  pis.value.reduce((s, r) => ({ to_acquire: s.to_acquire + r.to_acquire, to_print: s.to_print + r.to_print, applying: s.applying + r.applying }),
    { to_acquire: 0, to_print: 0, applying: 0 }),
);
const qtyOf = (row: any, st: Step) => (st === "TO_ACQUIRE" ? row.to_acquire : row.to_print);
const stepRows = (st: Step) => pis.value.filter((r) => qtyOf(r, st) > 0);
const pickedQty = (st: Step) => picked[st].reduce((s, r) => s + qtyOf(r, st), 0);
const actLabel = (st: Step) => t(st === "TO_ACQUIRE" ? "acquire.take" : "acquire.print");

const scope = () => (focus.value ? { factory_code: focus.value.factory_code, pi: focus.value.pi_no } : { factory_code: f.factory_code, pi: f.pi.trim() });
const params = () => ({ ...scope(), material_code: f.material_code.trim(), status: f.status, pi_all: f.pi_all || undefined });
const { items: segments, state: segState, load: loadSegments, onSize: segSize } = usePaged<any>((q) => api.acquireSegments({ ...params(), ...q }));
const { items: batches, state: batchState, load: loadBatches } = usePaged<any>((q) => api.batches({ ...scope(), ...q }), 10);
const { items: prints, state: printState, load: loadPrints } = usePaged<any>((q) => api.prints({ ...scope(), ...q }), 10);
const { items: batchItems, state: itemState, load: loadBatchItems } = usePaged<any>((q) => api.batchItems(batch.value.batch_no, q), 50);

/** 记录类标签顶部：聚焦某张 PI 时显示它和「看全部」。 */
const FocusBar = defineComponent(() => () =>
  focus.value
    ? h("div", { class: "focus" }, [
        h("b", `${nameOf(focus.value.factory_code)} · PI ${focus.value.pi_no}`),
        h(ElButton, { size: "small", onClick: unfocus }, () => t("acquire.showAll")),
      ])
    : null,
);

async function loadPis() {
  pisLoading.value = true;
  try {
    pis.value = f.pi_all ? [] : (await api.acquirePis({ factory_code: f.factory_code, pi: f.pi.trim() })).map((r: any) => ({ ...r, _key: `${r.factory_code}|${r.pi_no}` }));
  } finally {
    pisLoading.value = false;
  }
  STEPS.forEach((st) => tables[st]?.clearSelection?.());
}

function reloadSegments() {
  loadSegments(1);
}

function loadTab() {
  if (tab.value === "batches") return loadBatches(1);
  if (tab.value === "prints") return loadPrints(1);
  if (tab.value === "segments") return loadSegments(1);
}

async function reload() {
  if (f.pi_all && !f.pi.trim()) f.pi_all = false;
  focus.value = null;
  if (f.pi_all && STEPS.includes(tab.value as Step)) tab.value = "segments";
  await Promise.all([loadPis(), loadTab()]);
}

function onTab() {
  loadTab();
}

function focusPi(row: any) {
  focus.value = row;
  tab.value = "segments";
  loadTab();
}

function unfocus() {
  focus.value = null;
  loadTab();
}

/** 对一张 PI 执行领取或打印；打印成功后下载打印文件。silent 时失败不弹窗，由调用方汇总。 */
async function exec(st: Step, row: any, silent: boolean) {
  const body = { factory_code: row.factory_code, pi_no: row.pi_no, request_no: newRequestId() };
  if (st === "TO_ACQUIRE") return api.take(body, silent);
  const r = await api.print(body, silent);
  await download(r.print_no, "xlsx");
  return r;
}

async function runOne(st: Step, row: any) {
  const args = { n: qtyOf(row, st), pi: row.pi_no, factory: nameOf(row.factory_code) };
  await ElMessageBox.confirm(t(st === "TO_ACQUIRE" ? "acquire.takeConfirm" : "acquire.printConfirm", args), actLabel(st), { type: "warning" });
  busy.value = `${st}:${row._key}`;
  try {
    const r = await exec(st, row, false);
    ElMessage.success(st === "TO_ACQUIRE" ? t("acquire.taken", { n: r.qty, batch: r.batch_no }) : t("acquire.printed", { n: r.qty }));
    await loadPis();
  } finally {
    busy.value = "";
  }
}

/** 批量：逐张提交，每张一个请求号；某张失败不影响其他张，最后汇总。 */
async function runBulk(st: Step) {
  const rows = [...picked[st]];
  const key = st === "TO_ACQUIRE" ? "acquire.bulkTakeConfirm" : "acquire.bulkPrintConfirm";
  await ElMessageBox.confirm(t(key, { n: rows.length, q: pickedQty(st) }), actLabel(st), { type: "warning" });
  busy.value = `bulk:${st}`;
  const failed: string[] = [];
  let ok = 0;
  let qty = 0;
  try {
    for (const row of rows) {
      try {
        const r = await exec(st, row, true);
        ok += 1;
        qty += r.qty;
      } catch (e: any) {
        failed.push(`${row.pi_no}: ${e.message}`);
      }
    }
  } finally {
    busy.value = "";
  }
  if (ok) ElMessage.success(t("acquire.bulkDone", { n: ok, q: qty }));
  if (failed.length) ElMessage.error({ message: t("acquire.bulkFailed", { n: failed.length, list: failed.join("; ") }), duration: 8000, showClose: true });
  await loadPis();
}

async function download(printNo: string, format: string) {
  try {
    await downloadWithToken(urls.printFile(printNo, format), `print_${printNo}.${format}`);
  } catch (e: any) {
    ElMessage.error(e.message);
  }
}

function openBatch(row: any) {
  batch.value = row;
  loadBatchItems(1);
}

onMounted(reload);
</script>

<style scoped>
.filters .toolbar { margin-bottom: 0; }
.filters .el-alert { margin-top: 12px; }
.summary { margin-left: auto; }
.cnt { margin-left: 6px; padding: 0 7px; border-radius: 9px; font-size: 12px; background: var(--el-color-primary-light-9); color: var(--el-color-primary); }
.bulk { font-size: 13px; display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-top: 12px; padding: 10px 12px; border-radius: 8px; background: var(--el-color-primary-light-9); }
.focus { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.seg-bar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 12px; }
.seg-bar .mat { width: 180px; }
.zero { color: var(--app-muted); }
.warn { color: var(--el-color-danger); font-weight: 600; }
</style>
