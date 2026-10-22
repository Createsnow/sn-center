<template>
  <div class="page">
    <PageHead :title="t('export.title')" :desc="t('export.desc')" />

    <div class="surface">
      <div class="toolbar">
        <FactorySelect v-if="!store.bound" v-model="f.factory_code" clearable :placeholder="t('app.allFactories')" @update:model-value="list.load(1)" />
        <el-select v-model="f.mes" clearable class="w140" :placeholder="t('common.mes')" @change="list.load(1)">
          <el-option :label="t('export.mesYes')" value="true" />
          <el-option :label="t('export.mesNo')" value="false" />
        </el-select>
        <el-input v-model="f.q" class="w220" :placeholder="t('export.search')" clearable prefix-icon="Search" @keyup.enter="list.load(1)" @clear="list.load(1)" />
        <el-button @click="list.load(1)"><el-icon><Refresh /></el-icon><span>{{ t("common.refresh") }}</span></el-button>
      </div>

      <el-table v-loading="list.state.loading" :data="list.items.value" size="small" class="clickable" :empty-text="t('common.empty')" @row-click="openDetail">
        <el-table-column prop="bill_no" :label="t('common.billNo')" min-width="140" show-overflow-tooltip><template #default="{ row }"><b>{{ row.bill_no }}</b></template></el-table-column>
        <el-table-column prop="pi_no" label="PI" min-width="130" show-overflow-tooltip />
        <el-table-column v-if="store.scopeFactories.length !== 1" :label="t('common.factory')" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ nameOf(row.factory_code) }}</template>
        </el-table-column>
        <el-table-column prop="customer_code" :label="t('common.customer')" min-width="90" />
        <el-table-column :label="t('common.mes')" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.mes_enabled" size="small" type="success" effect="plain">MES</el-tag>
            <span v-else class="muted">{{ t("common.no") }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('export.qty')" min-width="200">
          <template #default="{ row }">
            <span class="num">{{ num(row.qty) }}</span>
            <!-- 只提示需要留意的号：MES 已有（已推送）、申请中、暂停；各状态明细在明细抽屉里看 -->
            <el-tag v-if="row.statuses.PUSHED" size="small" type="warning" effect="plain" class="ml">{{ t("export.inMes", { n: num(row.statuses.PUSHED) }) }}</el-tag>
            <el-tag v-if="row.statuses.APPLYING" size="small" type="warning" effect="plain" class="ml">{{ t("export.applying", { n: num(row.statuses.APPLYING) }) }}</el-tag>
            <el-tag v-if="row.suspended" size="small" type="danger" effect="plain" class="ml">{{ t("export.suspended", { n: num(row.suspended) }) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="150" align="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openDetail(row)">{{ t("common.detail") }}</el-button>
            <el-button link type="primary" :loading="busy === keyOf(row, 'xlsx')" @click.stop="download(row, 'xlsx')">xlsx</el-button>
            <el-button link type="primary" :loading="busy === keyOf(row, 'csv')" @click.stop="download(row, 'csv')">csv</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="list.state.total" :page-size="list.state.pageSize"
        :current-page="list.state.page" :page-sizes="[20, 50, 100]" @current-change="list.load" @size-change="list.onSize" />
      <p class="muted">{{ t("export.rule") }}</p>
    </div>

    <!-- 明细：概况 + 物料行 + 号段 -->
    <el-drawer :model-value="!!detail" size="900px" @close="detail = null">
      <template #header>
        <div v-if="detail" class="dh">
          <b>{{ detail.bill_no }}</b>
          <el-tag effect="plain" size="small">PI {{ detail.pi_no }}</el-tag>
          <el-tag effect="plain" size="small">{{ nameOf(detail.factory_code) }}<template v-if="detail.customer_code"> · {{ detail.customer_code }}</template></el-tag>
          <el-tag v-if="detail.mes_enabled" size="small" type="success" effect="plain">MES</el-tag>
        </div>
      </template>
      <template v-if="detail">
        <div class="toolbar">
          <span class="total">{{ t("export.total", { n: num(detail.qty), l: lines.length || "…" }) }}</span>
          <span class="sp" />
          <span class="muted">{{ t("export.fileCols") }}</span>
          <el-button size="small" type="primary" :loading="busy === keyOf(detail, 'xlsx')" @click="download(detail, 'xlsx')">{{ t("export.download", { f: "xlsx" }) }}</el-button>
          <el-button size="small" :loading="busy === keyOf(detail, 'csv')" @click="download(detail, 'csv')">{{ t("export.download", { f: "csv" }) }}</el-button>
        </div>
        <el-alert v-if="notes.length" type="warning" show-icon :closable="false" class="mb">
          <template #title><div v-for="n in notes" :key="n">{{ n }}</div></template>
        </el-alert>

        <div class="surface__title">{{ t("export.lines") }}</div>
        <el-table v-loading="linesLoading" :data="lines" size="small" class="mb" :empty-text="t('common.empty')">
          <el-table-column prop="bill_seq" :label="t('export.seq')" width="90" />
          <el-table-column prop="material_code" :label="t('common.material')" min-width="110" />
          <el-table-column :label="t('common.length')" width="80"><template #default="{ row }">{{ row.length || "—" }}</template></el-table-column>
          <el-table-column :label="t('common.qty')" align="right" width="80"><template #default="{ row }"><span class="num">{{ num(row.qty) }}</span></template></el-table-column>
          <el-table-column :label="t('common.status')" min-width="260">
            <template #default="{ row }">
              <el-tag v-for="(n, s) in row.statuses" :key="s" size="small" :type="STATUS_TYPE[s] || 'info'" class="mr">{{ statusLabel(String(s)) }} {{ num(n) }}</el-tag>
              <el-tag v-if="row.suspended" size="small" type="danger" effect="plain">{{ t("export.suspended", { n: num(row.suspended) }) }}</el-tag>
            </template>
          </el-table-column>
        </el-table>

        <div class="seg-head">
          <span class="surface__title">{{ t("export.segments") }}</span>
          <el-radio-group v-model="sf.status" size="small" @change="segs.load(1)">
            <el-radio-button value="">{{ t("export.allStatus", { n: num(detail.qty) }) }}</el-radio-button>
            <el-radio-button v-for="(n, s) in detail.statuses" :key="s" :value="s">{{ statusLabel(String(s)) }} {{ num(n) }}</el-radio-button>
          </el-radio-group>
        </div>
        <el-table v-loading="segs.state.loading" :data="segRows" size="small" :span-method="spanLine" :empty-text="t('common.empty')">
          <el-table-column prop="bill_seq" :label="t('export.seq')" width="90" />
          <el-table-column prop="material_code" :label="t('common.material')" min-width="100" />
          <el-table-column :label="t('export.segment')" min-width="280">
            <template #default="{ row }">
              <div class="sn-mono">{{ row.start_sn }}<template v-if="row.end_sn !== row.start_sn"> ~ {{ row.end_sn }}</template></div>
              <div v-if="row.gap" class="muted small">{{ row.gap.n === 1 ? t("export.gap1", { r: row.gap.range }) : t("export.gap", { r: row.gap.range, n: num(row.gap.n) }) }}</div>
              <el-tag v-if="row.transferred_in" size="small" type="info" effect="plain" class="mt4">{{ t("export.seqOf", { pi: row.seq_pi_no }) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('common.seqDec')" width="120">
            <template #default="{ row }"><span class="num">{{ row.start_seq === row.end_seq ? row.start_seq : `${row.start_seq} ~ ${row.end_seq}` }}</span></template>
          </el-table-column>
          <el-table-column :label="t('common.qty')" align="right" width="70"><template #default="{ row }"><span class="num">{{ num(row.qty) }}</span></template></el-table-column>
          <el-table-column :label="t('common.status')" width="150">
            <template #default="{ row }">
              <StatusTag :status="row.status" />
              <el-tag v-if="row.suspend" size="small" type="danger" class="ml">{{ suspendLabel(row.suspend) }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination class="pager" layout="slot, prev, pager, next" :total="segs.state.total" :page-size="segs.state.pageSize"
          :current-page="segs.state.page" @current-change="segs.load">
          <span class="muted seg-total">{{ t("export.segTotal", { n: num(segs.state.total) }) }}</span>
        </el-pagination>
        <p class="muted">{{ t("export.segNote") }}</p>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
/** SN 导出：已分配的号（不论是否对接 MES）按「生产组织 + PI + 单据」列出；看明细或直接下载，只读、可反复下载。 */
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute } from "vue-router";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import { api, urls } from "@/api";
import PageHead from "@/components/PageHead.vue";
import FactorySelect from "@/components/FactorySelect.vue";
import StatusTag from "@/components/StatusTag.vue";
import { useFactories } from "@/composables/useFactories";
import { usePaged } from "@/composables/usePaged";
import { STATUS_TYPE, statusLabel, suspendLabel } from "@/constants";
import { useAuthStore } from "@/stores/auth";
import { downloadWithToken } from "@/utils/download";

interface BillRow {
  factory_code: string;
  pi_no: string;
  bill_no: string;
  customer_code: string;
  mes_enabled: boolean;
  qty: number;
  suspended: number;
  statuses: Record<string, number>;
}

const { t } = useI18n();
const store = useAuthStore();
const route = useRoute();
const { nameOf } = useFactories();
const num = (n: number) => Number(n).toLocaleString();

const f = reactive({ factory_code: "", mes: "", q: String(route.query.pi || route.query.bill_no || "") });
const list = usePaged<BillRow>((q) => api.exportBills({ ...q, factory_code: f.factory_code, mes: f.mes, q: f.q.trim() }));

const busy = ref("");
const keyOf = (r: BillRow, fmt: string) => `${r.factory_code}|${r.pi_no}|${r.bill_no}|${fmt}`;
async function download(r: BillRow, fmt: "xlsx" | "csv") {
  busy.value = keyOf(r, fmt);
  try {
    const url = urls.exportFile({ factory_code: r.factory_code, pi: r.pi_no, bill_no: r.bill_no, format: fmt });
    await downloadWithToken(url, `SN_${r.pi_no}_${r.bill_no}.${fmt}`);
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    busy.value = "";
  }
}

interface LineRow {
  bill_seq: number;
  material_code: string;
  length: string;
  qty: number;
  suspended: number;
  statuses: Record<string, number>;
}
interface SegRow {
  bill_seq: number;
  material_code: string;
  seq_pi_no: string;
  start_seq: number;
  end_seq: number;
  start_sn: string;
  end_sn: string;
  qty: number;
  status: string;
  suspend: string | null;
  transferred_in: boolean;
}

const detail = ref<BillRow | null>(null);
const sf = reactive({ status: "" });
const lines = ref<LineRow[]>([]);
const linesLoading = ref(false);
const segs = usePaged<SegRow>((q) =>
  api.exportSegments({ ...q, factory_code: detail.value!.factory_code, pi: detail.value!.pi_no, bill_no: detail.value!.bill_no, status: sf.status }),
);

async function openDetail(row: BillRow) {
  detail.value = row;
  sf.status = "";
  lines.value = [];
  segs.load(1);
  linesLoading.value = true;
  try {
    const got: LineRow[] = await api.exportLines({ factory_code: row.factory_code, pi: row.pi_no, bill_no: row.bill_no });
    if (detail.value === row) lines.value = got;
  } finally {
    linesLoading.value = false;
  }
}

/** 需要留意的号：MES 已有（防重复打标）、申请中（可能转走）、暂停交付。 */
const notes = computed(() => {
  const d = detail.value;
  if (!d) return [];
  const out: string[] = [];
  if (d.statuses.PUSHED) out.push(t("export.inMesNote", { n: num(d.statuses.PUSHED) }));
  if (d.statuses.APPLYING) out.push(t("export.applyingNote", { n: num(d.statuses.APPLYING) }));
  if (d.suspended) out.push(t("export.suspendedNote", { n: num(d.suspended) }));
  return out;
});

const sameLine = (a: SegRow, b: SegRow) => a.bill_seq === b.bill_seq && a.material_code === b.material_code;
/**
 * 号段加上「与上一段之间缺的流水」：同一物料行、同一流水归属内不连续时标出，说明一行为什么拆成几段。
 * 按状态筛选时其他状态的号不在列表里，不标。
 */
const segRows = computed(() =>
  segs.items.value.map((r, i, all) => {
    const prev = all[i - 1];
    let gap: { range: string; n: number } | null = null;
    if (!sf.status && prev && sameLine(prev, r) && prev.seq_pi_no === r.seq_pi_no && r.start_seq > prev.end_seq + 1) {
      const from = prev.end_seq + 1;
      const to = r.start_seq - 1;
      gap = { range: from === to ? String(from) : `${from} ~ ${to}`, n: to - from + 1 };
    }
    return { ...r, gap };
  }),
);

/** 同一物料行连续的号段合并「单据序号」「物料编码」两格。 */
function spanLine({ row, rowIndex, columnIndex }: { row: SegRow; rowIndex: number; columnIndex: number }) {
  if (columnIndex > 1) return [1, 1];
  const rows = segRows.value;
  if (rowIndex > 0 && sameLine(rows[rowIndex - 1], row)) return [0, 0];
  let n = 1;
  while (rowIndex + n < rows.length && sameLine(rows[rowIndex + n], row)) n++;
  return [n, 1];
}

onMounted(() => list.load(1));
</script>

<style scoped>
.w140 { width: 140px; }
.w220 { width: 220px; }
.ml { margin-left: 6px; }
.mr { margin-right: 6px; }
.mb { margin-bottom: 12px; }
.mt4 { margin-top: 4px; }
.small { font-size: 12px; }
.total { font-size: 14px; font-weight: 600; color: var(--app-text); }
.seg-head { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 8px; }
.seg-head .surface__title { margin: 0; }
.seg-total { margin-right: 12px; font-size: 13px; }
.sp { flex: 1; }
.clickable :deep(.el-table__row) { cursor: pointer; }
.dh { display: flex; align-items: center; gap: 10px; font-size: 15px; color: var(--app-text); }
</style>
