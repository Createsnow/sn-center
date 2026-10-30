<template>
  <div class="ap">
    <el-table v-loading="list.state.loading" :data="list.items.value" size="small" class="clickable" highlight-current-row
      :row-class-name="({ row }: any) => (cur && keyOf(row) === keyOf(cur) ? 'current-row' : '')" :empty-text="t('allocate.noAllocated')" @row-click="select">
      <el-table-column prop="bill_no" :label="t('common.billNo')" min-width="140" show-overflow-tooltip><template #default="{ row }"><b>{{ row.bill_no }}</b></template></el-table-column>
      <el-table-column v-if="store.scopeFactories.length !== 1" :label="t('common.factory')" min-width="130" show-overflow-tooltip>
        <template #default="{ row }">{{ nameOf(row.factory_code) }}</template>
      </el-table-column>
      <el-table-column :label="t('export.qty')" min-width="200">
        <template #default="{ row }">
          <span class="num">{{ num(row.qty) }}</span>
          <!-- 导出与推送相互独立：不显示推送进度，只提示申请中（可能转走）、暂停 -->
          <el-tag v-if="row.applying" size="small" type="warning" effect="plain" class="ml">{{ t("export.applying", { n: num(row.applying) }) }}</el-tag>
          <el-tag v-if="row.suspended" size="small" type="danger" effect="plain" class="ml">{{ t("export.suspended", { n: num(row.suspended) }) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('common.actions')" width="100" align="right">
        <template #default="{ row }">
          <el-button link type="primary" :loading="busy === keyOf(row)" @click.stop="download(row)">xlsx</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-if="list.state.total > list.state.pageSize" class="pager" small layout="total, prev, pager, next" :total="list.state.total"
      :page-size="list.state.pageSize" :current-page="list.state.page" @current-change="list.load" />

    <!-- 明细：所选单据逐枚列出全部 SN（进制、进制流水、十进制流水），可按物料编码、SN 起止号筛选 -->
    <section v-if="cur" class="detail">
      <div class="toolbar">
        <b>{{ cur.bill_no }}</b>
        <el-tag effect="plain" size="small">{{ cur.factory_code }} {{ nameOf(cur.factory_code) }}<template v-if="cur.customer_code"> · {{ cur.customer_code }}</template></el-tag>
        <span class="sp" />
        <el-button size="small" type="primary" :loading="busy === keyOf(cur)" @click="download(cur)">{{ t("export.download", { f: "xlsx" }) }}</el-button>
      </div>
      <div class="toolbar">
        <el-select v-model="material" v-loading="linesLoading" size="small" class="w-mat" :placeholder="t('common.material')" clearable @change="sns.load(1)">
          <el-option v-for="mc in materials" :key="mc" :value="mc" :label="mc" />
        </el-select>
        <el-input v-model="snFrom" size="small" class="w-sn" :placeholder="t('export.snFrom')" clearable @keyup.enter="sns.load(1)" @clear="sns.load(1)" />
        <span class="muted">~</span>
        <el-input v-model="snTo" size="small" class="w-sn" :placeholder="t('export.snTo')" clearable @keyup.enter="sns.load(1)" @clear="sns.load(1)" />
        <el-button size="small" @click="sns.load(1)">{{ t("common.search") }}</el-button>
        <el-button size="small" link type="primary" @click="resetFilter">{{ t("common.reset") }}</el-button>
      </div>

      <el-table v-loading="sns.state.loading" :data="snRows" size="small" :span-method="spanGroup" :row-class-name="groupClass" :empty-text="t('common.empty')">
        <el-table-column :label="t('export.lineNo')" width="70">
          <template #default="{ row }">
            <span v-if="row.group" class="grp"><b>#{{ row.bill_seq }}</b><span>{{ t("export.groupTitle", { m: row.material_code || "—" }) }}</span>
              <span class="muted">{{ t("export.groupMeta", { a: row.attach_no || "—", l: row.length || "—", n: num(row.qty) }) }}</span></span>
            <template v-else>{{ row.line_no }}</template>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.sn')" min-width="200">
          <template #default="{ row }">
            <span class="sn-mono">{{ row.sn }}</span>
            <el-tag v-if="row.transferred_in" size="small" type="info" effect="plain" class="ml">{{ t("export.seqOf", { pi: row.seq_pi_no }) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('rules.base')" width="70" align="right"><template #default="{ row }">{{ row.group ? "" : row.base ?? "—" }}</template></el-table-column>
        <el-table-column :label="t('common.seqText')" width="110"><template #default="{ row }"><span class="sn-mono">{{ row.seq_text }}</span></template></el-table-column>
        <el-table-column prop="seq_dec" :label="t('common.seqDec')" width="100" align="right" />
        <el-table-column :label="t('common.status')" width="160">
          <template #default="{ row }">
            <template v-if="!row.group">
              <StatusTag :status="row.status" />
              <el-tag v-if="row.suspend" size="small" type="danger" class="ml">{{ suspendLabel(row.suspend) }}</el-tag>
            </template>
          </template>
        </el-table-column>
        <el-table-column :label="t('query.deliveryBatch')" min-width="150"><template #default="{ row }"><span class="sn-mono">{{ row.group ? "" : row.delivery_batch_no || "—" }}</span></template></el-table-column>
      </el-table>
      <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="sns.state.total" :page-size="sns.state.pageSize"
        :current-page="sns.state.page" :page-sizes="[100, 200, 500]" @current-change="sns.load" @size-change="sns.onSize" />
    </section>
  </div>
</template>

<script setup lang="ts">
/** SN 分配页「已分配」页签的右侧（F-14 SN 导出）：所选 PI 已分配的号（不论是否对接 MES）按「生产组织 + 单据」列出，
 *  下方直接逐枚列出所选单据的 SN（进制、进制流水、十进制流水），可下载 xlsx，只读、可反复下载。
 *  与分配后的推送、拉取相互独立：推送成败都不影响列出和下载。 */
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import { api, urls } from "@/api";
import StatusTag from "@/components/StatusTag.vue";
import { useFactories } from "@/composables/useFactories";
import { usePaged } from "@/composables/usePaged";
import { suspendLabel } from "@/constants";
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
  applying: number;
}

/** pi：所在 PI；bill：左侧所选单据（默认展开它的明细）。 */
const props = defineProps<{ pi: string; bill: string }>();
const emit = defineEmits<{ (e: "pick", billNo: string): void }>();

const { t } = useI18n();
const store = useAuthStore();
const { nameOf } = useFactories();
const num = (n: number) => Number(n).toLocaleString();

const list = usePaged<BillRow>((q) => api.exportBills({ ...q, pi: props.pi }), 100);

/** 重新列出本 PI 的已分配单据，并选中左侧所选单据（没有时选第一行）。 */
async function load() {
  await list.load(1);
  const rows = list.items.value;
  const keep = cur.value && rows.find((r) => keyOf(r) === keyOf(cur.value!));
  open(keep || rows.find((r) => r.bill_no === props.bill) || rows[0] || null, true);
}

const busy = ref("");
const keyOf = (r: BillRow) => `${r.factory_code}|${r.pi_no}|${r.bill_no}`;
async function download(r: BillRow) {
  busy.value = keyOf(r);
  try {
    const url = urls.exportFile({ factory_code: r.factory_code, pi: r.pi_no, bill_no: r.bill_no });
    await downloadWithToken(url, `SN_${r.pi_no}_${r.bill_no}.xlsx`);
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    busy.value = "";
  }
}

interface LineRow {
  bill_seq: number;
  material_code: string;
  attach_no: string;
  length: string;
  qty: number;
  suspended: number;
  applying: number;
}
interface SnRow {
  line_no: number;
  sn: string;
  bill_seq: number;
  material_code: string;
  attach_no: string;
  length: string;
  seq_pi_no: string;
  transferred_in: boolean;
  base: number | null;
  seq_text: string;
  seq_dec: number;
  status: string;
  delivery_batch_no: string;
  suspend: string | null;
}
/** 表格里物料行的分组标题行（整行合并）。 */
type GroupRow = LineRow & { group: true };

/** 当前展开明细的单据。 */
const cur = ref<BillRow | null>(null);
const lines = ref<LineRow[]>([]);
const linesLoading = ref(false);
/** 筛选：物料编码（空 = 全部）、SN 起止号（含两端，可只填一端；须是本单据已分配的号）。 */
const material = ref("");
const snFrom = ref("");
const snTo = ref("");
const keyOfLine = (l: { bill_seq: number; material_code: string }) => `${l.bill_seq}|${l.material_code}`;
/** 本单据的物料编码（下拉选项）。 */
const materials = computed(() => [...new Set(lines.value.map((l) => l.material_code).filter(Boolean))]);
function resetFilter() {
  material.value = "";
  snFrom.value = "";
  snTo.value = "";
  sns.load(1);
}

/** 明细逐枚列出该单据的全部 SN（不按号段合并），顺序同下载文件；可只看一个物料行、按完整 SN 搜索。 */
const sns = usePaged<SnRow>((q) => {
  const d = cur.value!;
  return api.exportItems({
    ...q, factory_code: d.factory_code, pi: d.pi_no, bill_no: d.bill_no,
    material: material.value || undefined, sn_from: snFrom.value.trim(), sn_to: snTo.value.trim(),
  });
}, 100);

/** 看全部且有多个物料行时，每遇到新的物料行插一行分组标题：#序号 物料 附件项次 · 米数 · 枚数。 */
const snRows = computed<(SnRow | GroupRow)[]>(() => {
  // 当前筛选下只有一个物料行时不插分组标题
  if (lines.value.filter((l) => !material.value || l.material_code === material.value).length < 2) return sns.items.value;
  const out: (SnRow | GroupRow)[] = [];
  let prev = "";
  for (const r of sns.items.value) {
    const k = keyOfLine(r);
    if (k !== prev) {
      const l = lines.value.find((x) => keyOfLine(x) === k);
      out.push({ bill_seq: r.bill_seq, material_code: r.material_code, attach_no: r.attach_no, length: r.length,
        qty: 0, suspended: 0, applying: 0, ...l, group: true });
      prev = k;
    }
    out.push(r);
  }
  return out;
});
const COLS = 7;
function spanGroup({ row, columnIndex }: { row: SnRow | GroupRow; columnIndex: number }) {
  if (!("group" in row)) return [1, 1];
  return columnIndex === 0 ? [1, COLS] : [0, 0];
}
const groupClass = ({ row }: { row: SnRow | GroupRow }) => ("group" in row ? "grp-row" : "");

function select(row: BillRow) {
  open(row);
  emit("pick", row.bill_no);
}

/** 展开一行的明细；force：同一行也重新加载（刷新后）。 */
async function open(row: BillRow | null, force = false) {
  if (!force && row && cur.value && keyOf(row) === keyOf(cur.value)) {
    cur.value = row;
    return;
  }
  cur.value = row;
  lines.value = [];
  material.value = "";
  snFrom.value = "";
  snTo.value = "";
  if (!row) return;
  sns.load(1);
  linesLoading.value = true;
  try {
    const got: LineRow[] = await api.exportLines({ factory_code: row.factory_code, pi: row.pi_no, bill_no: row.bill_no });
    if (cur.value === row) lines.value = got;
  } finally {
    linesLoading.value = false;
  }
}

watch(() => props.pi, () => {
  cur.value = null;
  load();
}, { immediate: true });

// 左侧换了同一 PI 下的另一张单据
watch(() => props.bill, (no) => {
  if (cur.value?.bill_no === no) return;
  const r = list.items.value.find((x) => x.bill_no === no);
  if (r) open(r);
});

defineExpose({ load });
</script>

<style scoped>
.ap { display: flex; flex-direction: column; gap: 12px; }
.ml { margin-left: 6px; }
.w-mat { width: 180px; }
.w-sn { width: 200px; }
.detail { border: 1px solid var(--el-border-color-lighter); border-radius: 8px; padding: 10px 12px; }
.toolbar { align-items: center; flex-wrap: wrap; }
.grp { display: inline-flex; align-items: center; gap: 10px; white-space: nowrap; }
:deep(.grp-row > td) { background: var(--el-fill-color-light) !important; }
:deep(.current-row > td) { background: var(--el-color-primary-light-9) !important; }
.sp { flex: 1; }
.clickable :deep(.el-table__row) { cursor: pointer; }
</style>
