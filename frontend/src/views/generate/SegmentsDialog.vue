<template>
  <!-- 单据号段：已用号段（本单据现有的号按物料行分开列出起止号，行内按流水连续合并，转入的号单独成段）/ 空闲号段（本单据删号腾出）；
       不给单据时只列整张 PI 的空闲号段（多一列来源单据）。已用号段可看 SN 明细（只有一列 SN，只看该物料行该段）；空闲号段可「用这段」 -->
  <el-dialog v-model="visible" :title="title" :width="bill && tab === 'use' ? 'min(1000px, 96vw)' : 'min(780px, 96vw)'">
    <div v-loading="loading" class="segs">
      <el-tabs v-if="bill" v-model="tab">
        <el-tab-pane :label="t('generate.segTabUse', { n: num(useRows.length) })" name="use" />
        <el-tab-pane :label="t('generate.segTabFree', { n: num(freeRows.length) })" name="free" />
      </el-tabs>

      <template v-if="bill && tab === 'use'">
        <!-- 同一物料行的几段相邻排，序号 / 物料 / 附件项次合并单元格（多段时物料下写出段数与枚数） -->
        <el-table :data="useRows" size="small" max-height="420" :empty-text="t('common.empty')" :span-method="lineSpan" class="use-table">
          <el-table-column :label="t('common.billSeq')" width="68" class-name="line-cell">
            <template #default="{ row }">{{ row.bill_seq }}</template>
          </el-table-column>
          <el-table-column :label="t('common.material')" min-width="110" class-name="line-cell">
            <template #default="{ row, $index }">
              <div>{{ row.material_code || t("generate.noMaterial") }}</div>
              <div v-if="lineGroups[$index]?.span > 1" class="muted sub">
                {{ t("generate.segLineSum", { n: num(lineGroups[$index].span), qty: num(lineGroups[$index].qty) }) }}
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="t('common.attachNo')" min-width="80" class-name="line-cell">
            <template #default="{ row }">{{ row.attach_no || "—" }}</template>
          </el-table-column>
          <el-table-column :label="t('generate.segStartSn')" min-width="130">
            <template #default="{ row }"><span class="sn-mono">{{ row.start_sn }}</span></template>
          </el-table-column>
          <el-table-column :label="t('generate.segEndSn')" min-width="130">
            <template #default="{ row }"><span class="sn-mono">{{ row.end_sn }}</span></template>
          </el-table-column>
          <el-table-column :label="t('generate.segSeq')" align="right" min-width="110">
            <template #default="{ row }"><span class="num">{{ `${row.start_seq}～${row.end_seq}` }}</span></template>
          </el-table-column>
          <el-table-column :label="t('common.qty')" align="right" width="80">
            <template #default="{ row }"><span class="num">{{ num(row.qty) }}</span></template>
          </el-table-column>
          <el-table-column :label="t('common.status')" min-width="180">
            <template #default="{ row }">
              <span class="tags">
                <el-tag v-if="row.transferred_in" size="small" type="warning">{{ t("generate.segTransferIn", { pi: row.seq_pi_no }) }}</el-tag>
                <el-tag v-if="row.allocated" size="small" type="success">{{ t("generate.segAllocated", { n: num(row.allocated) }) }}</el-tag>
                <el-tag v-if="row.pending" size="small">{{ t("generate.segPending", { n: num(row.pending) }) }}</el-tag>
              </span>
            </template>
          </el-table-column>
          <el-table-column width="64" align="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openSns(row)">{{ t("generate.snDetail") }}</el-button>
            </template>
          </el-table-column>
        </el-table>
        <p v-if="data?.use_truncated" class="muted">{{ t("generate.gapsTruncated", { n: data.use.length }) }}</p>
      </template>

      <template v-else>
        <el-table :data="freeRows" size="small" max-height="420" :empty-text="t('generate.gapsNone')">
          <el-table-column v-if="!bill" :label="t('generate.srcBill')" min-width="130">
            <template #default="{ row }">{{ row.bill_no || "—" }}</template>
          </el-table-column>
          <el-table-column :label="t('generate.seqStart')" prop="start_seq" align="right" />
          <el-table-column :label="t('generate.seqEnd')" prop="end_seq" align="right" />
          <el-table-column :label="t('common.qty')" align="right">
            <template #default="{ row }"><span class="num">{{ num(row.qty) }}</span></template>
          </el-table-column>
          <el-table-column width="72" align="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="use(row)">{{ t("generate.gapUse") }}</el-button>
            </template>
          </el-table-column>
        </el-table>
        <p v-if="freeTruncated" class="muted">{{ t("generate.gapsTruncated", { n: freeRows.length }) }}</p>
      </template>
    </div>

    <!-- SN 明细：只有一列 SN，按流水排、分页 -->
    <el-dialog v-model="sns.open" :title="snsTitle" width="min(380px, 96vw)" append-to-body>
      <el-table v-loading="sns.loading" :data="sns.items" size="small" :max-height="440" :empty-text="t('common.empty')">
        <el-table-column label="SN">
          <template #default="{ row }"><span class="sn-mono">{{ row }}</span></template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" small layout="total, prev, pager, next" :total="sns.total" :page-size="SN_PAGE"
        :current-page="sns.page" :pager-count="5" @current-change="loadSns" />
    </el-dialog>
  </el-dialog>
</template>

<script setup lang="ts">
/** 单据号段弹窗。父组件用 ref 调 open({ bill, tab }) 或 open({})（整张 PI 的空闲号段）；
 *  gaps：生成详情里的 PI 空闲号段（不给单据时直接用）；「用这段」发出 use（起止流水），由父组件填进底部「流水」。 */
import { computed, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { num } from "./format";

const SN_PAGE = 20;

const props = defineProps<{ pi: string; gaps: any }>();
const emit = defineEmits<{ use: [range: { start: number; end: number }] }>();
const { t } = useI18n();

const visible = ref(false);
const loading = ref(false);
const bill = ref("");
const tab = ref<"use" | "free">("use");
const data = ref<any>(null);

const title = computed(() => (bill.value ? t("generate.segTitle", { bill: bill.value }) : t("generate.segPiTitle", { pi: props.pi })));
/** 已用号段：本单据现有的号按流水合并成的段（不含最大号之后：那是整张 PI 共用的，看底部「流水」）。 */
const useRows = computed<any[]>(() => data.value?.use ?? []);
const freeRows = computed<any[]>(() => (bill.value ? data.value?.free ?? [] : props.gaps?.segments ?? []));
const freeTruncated = computed(() => (bill.value ? data.value?.free_truncated : props.gaps?.truncated));

/** 物料行合并：相邻、序号 / 物料 / 附件项次都相同的段合成一组；组首行记 span（段数）与 qty（枚数），其余行 span = 0。 */
const lineGroups = computed(() => {
  const rows = useRows.value;
  const out: { span: number; qty: number }[] = rows.map(() => ({ span: 0, qty: 0 }));
  const same = (a: any, b: any) => a.bill_seq === b.bill_seq && a.material_code === b.material_code && a.attach_no === b.attach_no;
  let head = 0;
  rows.forEach((r, i) => {
    if (i > 0 && same(rows[head], r)) {
      out[head].span += 1;
      out[head].qty += r.qty;
    } else {
      head = i;
      out[i] = { span: 1, qty: r.qty };
    }
  });
  return out;
});

/** 前三列（序号、物料、附件项次）按物料行合并。 */
function lineSpan({ rowIndex, columnIndex }: { rowIndex: number; columnIndex: number }) {
  if (columnIndex > 2) return { rowspan: 1, colspan: 1 };
  const span = lineGroups.value[rowIndex]?.span ?? 1;
  return { rowspan: span, colspan: span ? 1 : 0 };
}

async function open(opts: { bill?: string; tab?: "use" | "free" }) {
  bill.value = opts.bill || "";
  tab.value = opts.tab || "use";
  data.value = null;
  visible.value = true;
  if (!bill.value) return;
  loading.value = true;
  try {
    data.value = await api.genSegments(props.pi, bill.value);
  } catch {
    visible.value = false;
  } finally {
    loading.value = false;
  }
}

function use(row: { start_seq: number; end_seq: number }) {
  emit("use", { start: row.start_seq, end: row.end_seq });
  visible.value = false;
}

const sns = reactive({ open: false, loading: false, seg: null as any, items: [] as string[], total: 0, page: 1 });
const snsTitle = computed(() =>
  sns.seg ? t("generate.snDetailTitle", { start: sns.seg.start_seq, end: sns.seg.end_seq, n: num(sns.seg.qty) }) : "",
);

function openSns(seg: any) {
  Object.assign(sns, { open: true, seg, items: [], total: seg.qty, page: 1 });
  loadSns(1);
}

async function loadSns(page: number) {
  const seg = sns.seg;
  sns.loading = true;
  try {
    const r = await api.genSegmentSns({
      pi: props.pi, bill_no: bill.value, bill_seq: seg.bill_seq, seq_pi: seg.seq_pi_no, start: seg.start_seq, end: seg.end_seq,
      page, size: SN_PAGE,
    });
    Object.assign(sns, { items: r.items, total: r.total, page: r.page });
  } finally {
    sns.loading = false;
  }
}

defineExpose({ open });
</script>

<style scoped>
.segs { display: flex; flex-direction: column; gap: 8px; min-height: 120px; }
.segs :deep(.el-tabs__header) { margin-bottom: 4px; }
.tags { display: inline-flex; gap: 4px; flex-wrap: wrap; }
p { margin: 0; }
.pager { margin-top: 10px; justify-content: flex-end; }
.sn-mono { font-family: var(--app-mono, monospace); }
.sub { font-size: 12px; }
.use-table :deep(td.line-cell) { background: var(--el-fill-color-lighter); border-right: 1px solid var(--el-table-border-color); }
</style>
