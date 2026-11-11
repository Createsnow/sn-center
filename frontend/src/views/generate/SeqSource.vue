<template>
  <!-- 流水来源：底部一个按钮（显示当前来源，有空闲号段时带角标），点开弹层：按预留号段（默认，没有预留的接在最大号之后）/ 指定起止号；
       指定起止号时可选范围不够时剩下的号接在最大号之后（默认）还是用勾选的空号段；
       下面列出空闲号段（来源单据、起止流水、枚数），「用这段」填入起止号，指定起止号且选了「用勾选的空号段」时可勾选 -->
  <el-popover v-model:visible="open" placement="top-end" :width="460" trigger="click" popper-class="seq-pop">
    <template #reference>
      <el-button>
        {{ label }}
        <span v-if="modelValue.mode === 'MAX' && gaps?.count" class="badge num">{{ num(gaps.count) }}</span>
        <el-icon class="el-icon--right"><ArrowDown /></el-icon>
      </el-button>
    </template>
    <div class="seq-src">
      <b class="title">{{ t("generate.seqTitle") }}</b>
      <el-radio-group :model-value="modelValue.mode" class="modes" @update:model-value="(v: any) => set({ mode: v })">
        <el-radio value="MAX">{{ t("generate.seqMaxFull", { n: nextSeq }) }}</el-radio>
        <el-radio value="RANGE">{{ t("generate.seqRangeFull") }}</el-radio>
      </el-radio-group>
      <div class="range">
        <el-input-number :model-value="modelValue.start ?? undefined" size="small" :min="1" :step="1" step-strictly controls-position="right"
          :placeholder="t('generate.seqStart')" class="no" @update:model-value="(v: any) => set({ mode: 'RANGE', start: v ?? null })" />
        <span class="muted">~</span>
        <el-input-number :model-value="modelValue.end ?? undefined" size="small" :min="1" :step="1" step-strictly controls-position="right"
          :placeholder="t('generate.seqEnd')" class="no" @update:model-value="(v: any) => set({ mode: 'RANGE', end: v ?? null })" />
      </div>
      <div v-if="modelValue.mode === 'RANGE'" class="rest">
        <span class="muted">{{ t("generate.seqRest") }}</span>
        <el-radio-group :model-value="restGaps ? 'GAPS' : 'MAX'" class="modes" @update:model-value="(v: any) => setRest(v === 'GAPS')">
          <el-radio value="MAX">{{ t("generate.seqRestMax") }}</el-radio>
          <el-radio value="GAPS" :disabled="!gaps?.count">{{ t("generate.seqRestGaps") }}</el-radio>
        </el-radio-group>
      </div>
      <template v-if="gaps">
        <b class="free-h">{{ t("generate.freeSegs") }}</b>
        <el-table v-if="rows.length" :data="rows" size="small" max-height="220">
          <el-table-column v-if="modelValue.mode === 'RANGE' && restGaps" width="36">
            <template #default="{ row }">
              <el-checkbox :model-value="picked(row)" size="small" @update:model-value="(v: any) => pick(row, !!v)" />
            </template>
          </el-table-column>
          <el-table-column :label="t('generate.srcBill')" min-width="110">
            <template #default="{ row }">
              {{ row.bill_no || "—" }}
              <!-- 所选物料行自己的预留（指定起止号时放弃预留、退回成空号段，可勾选用） -->
              <el-tag v-if="row.own" size="small" type="info">{{ t("generate.ownReserved", { seq: row.seq }) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('generate.seqStart')" prop="start_seq" align="right" />
          <el-table-column :label="t('generate.seqEnd')" prop="end_seq" align="right" />
          <el-table-column :label="t('common.qty')" align="right">
            <template #default="{ row }"><span class="num">{{ num(row.qty) }}</span></template>
          </el-table-column>
          <el-table-column width="64" align="right">
            <template #default="{ row }">
              <el-button v-if="!row.own" link type="primary" size="small" @click="use(row)">{{ t("generate.gapUse") }}</el-button>
            </template>
          </el-table-column>
        </el-table>
        <p v-else class="muted">{{ t("generate.gapsNone") }}</p>
        <p v-if="gaps.truncated" class="muted">{{ t("generate.gapsTruncated", { n: gaps.segments.length }) }}</p>
      </template>
    </div>
  </el-popover>
</template>

<script setup lang="ts">
/** 生成的流水来源。gaps：本 PI 的空闲号段（生成详情 / 预演返回，带来源单据）；modelValue：{ mode, start, end, gaps }
 *  （gaps：指定起止号时范围不够、剩下的号用的空号段 [[起, 止], …]，空为接在最大号之后）；
 *  first：自动预演算出的第一个号（自定义规则指定了起始号时可能大于 PI 下一个号），没有时按 PI 下一个号。
 *  空闲号段列表（或单据号段弹窗）里点「用这段」填入起止号并切到指定起止号。 */
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { num } from "./format";

export type SeqValue = { mode: "MAX" | "RANGE"; start: number | null; end: number | null; gaps?: [number, number][] };

const props = defineProps<{ modelValue: SeqValue; gaps: any; first?: number | null; own?: any[] }>();
const emit = defineEmits<{ "update:modelValue": [v: SeqValue] }>();
const { t } = useI18n();
const open = ref(false);
/** 指定起止号时剩下的号用勾选的空号段（否则接在最大号之后） */
const restGaps = ref(false);
watch(() => props.modelValue.mode, (m) => { if (m !== "RANGE") restGaps.value = false; });

/** 空闲号段；指定起止号、剩下的号用勾选的空号段时，另列所选物料行自己预留里空着的号段（own，生成时退回成空号段） */
const rows = computed<any[]>(() => {
  const free = props.gaps?.segments ?? [];
  if (props.modelValue.mode !== "RANGE" || !restGaps.value || !props.own?.length) return free;
  return [...free, ...props.own.map((x: any) => ({ ...x, own: true }))].sort((x, y) => x.start_seq - y.start_seq);
});
const nextSeq = computed(() => (props.modelValue.mode === "MAX" && props.first) || props.gaps?.next_seq || 1);
const label = computed(() => {
  const v = props.modelValue;
  if (v.mode === "MAX") return t("generate.seqBtnMax", { n: nextSeq.value });
  if (!v.start) return t("generate.seqBtnUnset");
  if (v.gaps?.length) return t("generate.seqBtnRangeGaps", { start: v.start, end: v.end ?? v.start, n: v.gaps.length });
  return t("generate.seqBtnRange", { start: v.start, end: v.end ?? v.start });
});

function set(p: Partial<SeqValue>) {
  emit("update:modelValue", { ...props.modelValue, ...p });
}

function use(row: { start_seq: number; end_seq: number }) {
  set({ mode: "RANGE", start: row.start_seq, end: row.end_seq });
}

function setRest(on: boolean) {
  restGaps.value = on;
  if (!on) set({ gaps: [] });
}
const picked = (row: { start_seq: number; end_seq: number }) =>
  !!props.modelValue.gaps?.some((g) => g[0] === row.start_seq && g[1] === row.end_seq);
function pick(row: { start_seq: number; end_seq: number }, on: boolean) {
  const rest = (props.modelValue.gaps || []).filter((g) => !(g[0] === row.start_seq && g[1] === row.end_seq));
  set({ gaps: on ? [...rest, [row.start_seq, row.end_seq]] : rest });
}
</script>

<style scoped>
.badge { display: inline-block; min-width: 16px; height: 16px; padding: 0 4px; margin-left: 6px; border-radius: 8px; font-size: 11px; line-height: 16px;
  text-align: center; color: #fff; background: var(--el-color-warning); }
.seq-src { display: flex; flex-direction: column; gap: 6px; }
.title { font-size: 13px; }
.modes { display: flex; flex-direction: column; align-items: flex-start; }
.modes :deep(.el-radio) { margin-right: 0; height: 26px; }
.range { display: flex; align-items: center; gap: 6px; padding-left: 22px; }
.no { width: 130px; }
.free-h { margin-top: 6px; }
.rest { display: flex; flex-direction: column; gap: 2px; padding-left: 22px; }
p { margin: 0; }
</style>
