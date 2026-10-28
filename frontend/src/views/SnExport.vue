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
            <!-- 导出与推送相互独立：不显示推送进度，只提示申请中（可能转走）、暂停 -->
            <el-tag v-if="row.applying" size="small" type="warning" effect="plain" class="ml">{{ t("export.applying", { n: num(row.applying) }) }}</el-tag>
            <el-tag v-if="row.suspended" size="small" type="danger" effect="plain" class="ml">{{ t("export.suspended", { n: num(row.suspended) }) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="150" align="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openDetail(row)">{{ t("common.detail") }}</el-button>
            <el-button link type="primary" :loading="busy === keyOf(row)" @click.stop="download(row)">xlsx</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="list.state.total" :page-size="list.state.pageSize"
        :current-page="list.state.page" :page-sizes="[20, 50, 100]" @current-change="list.load" @size-change="list.onSize" />
      <p class="muted">{{ t("export.rule") }}</p>
    </div>

    <!-- 明细：概况（单据级字段只显示一次）→ 按物料行页签 + 搜索 → 逐枚列出全部 SN（按物料行分组） -->
    <el-drawer :model-value="!!detail" size="min(1120px, 96vw)" @close="detail = null">
      <template #header>
        <div v-if="detail" class="dh">
          <b>{{ detail.bill_no }}</b>
          <el-tag effect="plain" size="small">PI {{ detail.pi_no }}</el-tag>
          <el-tag effect="plain" size="small">{{ nameOf(detail.factory_code) }}<template v-if="detail.customer_code"> · {{ detail.customer_code }}</template></el-tag>
          <el-tag v-if="detail.mes_enabled" size="small" type="success" effect="plain">MES</el-tag>
        </div>
      </template>
      <template v-if="detail">
        <div class="kv">
          <span>{{ t("common.factory") }}</span><b>{{ detail.factory_code }} {{ nameOf(detail.factory_code) }}</b>
          <span>{{ t("common.customer") }}</span><b>{{ detail.customer_code || "—" }}</b>
          <span>PI</span><b>{{ detail.pi_no }}</b>
          <span>{{ t("common.billNo") }}</span><b>{{ detail.bill_no }}</b>
          <span>{{ t("export.qty") }}</span><b class="num">{{ num(detail.qty) }}</b>
          <span>{{ t("export.lines") }}</span><b class="num">{{ lines.length }}</b>
          <span>{{ t("export.applySus") }}</span><b class="num" :class="{ warn: detail.applying || detail.suspended }">{{ num(detail.applying) }} / {{ num(detail.suspended) }}</b>
        </div>

        <div class="toolbar">
          <el-radio-group v-model="lineKey" v-loading="linesLoading" size="small" class="line-tabs" @change="sns.load(1)">
            <el-radio-button value="">{{ t("export.allTab", { n: num(detail.qty) }) }}</el-radio-button>
            <el-radio-button v-for="l in lines" :key="keyOfLine(l)" :value="keyOfLine(l)">{{ lineLabel(l) }}</el-radio-button>
          </el-radio-group>
          <span class="sp" />
          <span class="tools">
            <el-input v-model="snQ" size="small" class="w200" :placeholder="t('export.searchSn')" clearable prefix-icon="Search"
              @keyup.enter="sns.load(1)" @clear="sns.load(1)" />
            <el-button size="small" type="primary" :loading="busy === keyOf(detail)" @click="download(detail)">{{ t("export.download", { f: "xlsx" }) }}</el-button>
          </span>
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
          <el-table-column :label="t('query.deliveryMode')" width="100"><template #default="{ row }">{{ row.group ? "" : deliveryModeLabel(row.delivery_mode) || "—" }}</template></el-table-column>
          <el-table-column :label="t('query.deliveryBatch')" min-width="150"><template #default="{ row }"><span class="sn-mono">{{ row.group ? "" : row.delivery_batch_no || "—" }}</span></template></el-table-column>
          <el-table-column :label="t('query.acquireBatch')" min-width="130"><template #default="{ row }"><span class="sn-mono">{{ row.group ? "" : row.acquire_batch_no || "—" }}</span></template></el-table-column>
        </el-table>
        <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="sns.state.total" :page-size="sns.state.pageSize"
          :current-page="sns.state.page" :page-sizes="[100, 200, 500]" @current-change="sns.load" @size-change="sns.onSize" />
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
/** SN 导出：已分配的号（不论是否对接 MES）按「生产组织 + PI + 单据」列出；看明细或直接下载，只读、可反复下载。
 *  与分配后的推送、拉取相互独立：不显示推送进度，推送成败都不影响列出和下载。 */
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
import { deliveryModeLabel, suspendLabel } from "@/constants";
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

const { t } = useI18n();
const store = useAuthStore();
const route = useRoute();
const { nameOf } = useFactories();
const num = (n: number) => Number(n).toLocaleString();

const f = reactive({ factory_code: "", mes: "", q: String(route.query.pi || route.query.bill_no || "") });
const list = usePaged<BillRow>((q) => api.exportBills({ ...q, factory_code: f.factory_code, mes: f.mes, q: f.q.trim() }));

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
  seq_text: string;
  seq_dec: number;
  status: string;
  delivery_mode: string;
  delivery_batch_no: string;
  acquire_batch_no: string;
  suspend: string | null;
}
/** 表格里物料行的分组标题行（整行合并）。 */
type GroupRow = LineRow & { group: true };

const detail = ref<BillRow | null>(null);
const lines = ref<LineRow[]>([]);
const linesLoading = ref(false);
/** 当前页签：空 = 全部，否则为「单据序号|物料编码」。 */
const lineKey = ref("");
const snQ = ref("");
const keyOfLine = (l: { bill_seq: number; material_code: string }) => `${l.bill_seq}|${l.material_code}`;
const lineLabel = (l: LineRow) =>
  t("export.lineTab", { seq: l.bill_seq, m: l.material_code || "—", a: l.attach_no || "—", l: l.length || "—", n: num(l.qty) });

/** 明细逐枚列出该单据的全部 SN（不按号段合并），顺序同下载文件；可只看一个物料行、按完整 SN 搜索。 */
const sns = usePaged<SnRow>((q) => {
  const d = detail.value!;
  const [seq, ...mat] = lineKey.value ? lineKey.value.split("|") : [];
  return api.exportItems({
    ...q, factory_code: d.factory_code, pi: d.pi_no, bill_no: d.bill_no,
    bill_seq: seq, material: seq === undefined ? undefined : mat.join("|"), q: snQ.value.trim(),
  });
}, 100);

/** 每遇到新的物料行插一行分组标题：#序号 物料 附件项次 · 米数 · 枚数。 */
const snRows = computed<(SnRow | GroupRow)[]>(() => {
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
const COLS = 8;
function spanGroup({ row, columnIndex }: { row: SnRow | GroupRow; columnIndex: number }) {
  if (!("group" in row)) return [1, 1];
  return columnIndex === 0 ? [1, COLS] : [0, 0];
}
const groupClass = ({ row }: { row: SnRow | GroupRow }) => ("group" in row ? "grp-row" : "");

async function openDetail(row: BillRow) {
  detail.value = row;
  lines.value = [];
  lineKey.value = "";
  snQ.value = "";
  sns.load(1);
  linesLoading.value = true;
  try {
    const got: LineRow[] = await api.exportLines({ factory_code: row.factory_code, pi: row.pi_no, bill_no: row.bill_no });
    if (detail.value === row) lines.value = got;
  } finally {
    linesLoading.value = false;
  }
}

onMounted(() => list.load(1));
</script>

<style scoped>
.w140 { width: 140px; }
.w220 { width: 220px; }
.ml { margin-left: 6px; }
.w200 { width: 200px; }
.kv { display: grid; grid-template-columns: auto 1fr auto 1fr; gap: 4px 18px; padding: 10px 14px; margin-bottom: 12px; font-size: 13px;
  border: 1px solid var(--el-border-color-lighter); border-radius: 8px; background: var(--el-fill-color-lighter); }
.kv > span { color: var(--app-muted); }
.kv b { color: var(--app-text); font-weight: 600; }
.kv b.warn { color: var(--el-color-warning-dark-2); }
.toolbar { align-items: flex-start; flex-wrap: nowrap; }
.tools { display: inline-flex; gap: 8px; flex-shrink: 0; }
.line-tabs { flex-wrap: wrap; row-gap: 6px; }
.grp { display: inline-flex; align-items: center; gap: 10px; white-space: nowrap; }
:deep(.grp-row > td) { background: var(--el-fill-color-light) !important; }
.sp { flex: 1; }
.clickable :deep(.el-table__row) { cursor: pointer; }
.dh { display: flex; align-items: center; gap: 10px; font-size: 15px; color: var(--app-text); }
</style>
