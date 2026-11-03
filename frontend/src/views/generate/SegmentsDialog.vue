<template>
  <!-- 单据号段：可用号段（现有的号按流水连续合并，转入的号单独成段；另有最大号 + 1 往后）/ 空闲号段（本单据删号腾出）；
       不给单据时只列整张 PI 的空闲号段（多一列来源单据）。可用号段可看 SN 明细（只有一列 SN）；空闲号段可「用这段」 -->
  <el-dialog v-model="visible" :title="title" width="min(780px, 96vw)">
    <div v-loading="loading" class="segs">
      <el-tabs v-if="bill" v-model="tab">
        <el-tab-pane :label="t('generate.segTabUse', { n: num(useRows.length) })" name="use" />
        <el-tab-pane :label="t('generate.segTabFree', { n: num(freeRows.length) })" name="free" />
      </el-tabs>

      <template v-if="bill && tab === 'use'">
        <el-table :data="useRows" size="small" max-height="420">
          <el-table-column :label="t('generate.segStartSn')" min-width="150">
            <template #default="{ row }">
              <span v-if="row.open" class="muted">—</span>
              <span v-else class="sn-mono">{{ row.start_sn }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('generate.segEndSn')" min-width="150">
            <template #default="{ row }">
              <span v-if="row.open" class="muted">{{ t("generate.segOpen") }}</span>
              <span v-else class="sn-mono">{{ row.end_sn }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('generate.segSeq')" align="right" min-width="110">
            <template #default="{ row }"><span class="num">{{ row.open ? `${row.start_seq}～` : `${row.start_seq}～${row.end_seq}` }}</span></template>
          </el-table-column>
          <el-table-column :label="t('common.qty')" align="right" width="80">
            <template #default="{ row }">
              <span v-if="row.open" class="muted">—</span>
              <span v-else class="num">{{ num(row.qty) }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('common.status')" min-width="190">
            <template #default="{ row }">
              <span class="tags">
                <el-tag v-if="row.open" size="small" type="info">{{ t("generate.segOpenTag") }}</el-tag>
                <template v-else>
                  <el-tag v-if="row.transferred_in" size="small" type="warning">{{ t("generate.segTransferIn", { pi: row.seq_pi_no }) }}</el-tag>
                  <el-tag v-if="row.allocated" size="small" type="success">{{ t("generate.segAllocated", { n: num(row.allocated) }) }}</el-tag>
                  <el-tag v-if="row.pending" size="small">{{ t("generate.segPending", { n: num(row.pending) }) }}</el-tag>
                </template>
              </span>
            </template>
          </el-table-column>
          <el-table-column width="64" align="right">
            <template #default="{ row }">
              <el-button v-if="!row.open" link type="primary" size="small" @click="openSns(row)">{{ t("generate.snDetail") }}</el-button>
            </template>
          </el-table-column>
        </el-table>
        <p v-if="data?.use_truncated" class="muted">{{ t("generate.gapsTruncated", { n: data.use.length }) }}</p>
        <p class="muted note">{{ t("generate.segUseNote", { last: data?.last_seq ?? 0 }) }}</p>
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
        <p class="muted note">{{ t("generate.segFreeNote") }}</p>
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
/** 可用号段：现有号段 + 最大号之后（往后不限，同一 PI 各单据共用流水）。 */
const useRows = computed<any[]>(() =>
  data.value ? [...data.value.use, { open: true, start_seq: data.value.next_seq, start_sn: "" }] : [],
);
const freeRows = computed<any[]>(() => (bill.value ? data.value?.free ?? [] : props.gaps?.segments ?? []));
const freeTruncated = computed(() => (bill.value ? data.value?.free_truncated : props.gaps?.truncated));

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
      pi: props.pi, bill_no: bill.value, seq_pi: seg.seq_pi_no, start: seg.start_seq, end: seg.end_seq, page, size: SN_PAGE,
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
.note { margin: 0; font-size: 12px; }
p { margin: 0; }
.pager { margin-top: 10px; justify-content: flex-end; }
.sn-mono { font-family: var(--app-mono, monospace); }
</style>
