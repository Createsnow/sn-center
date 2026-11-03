<template>
  <!-- 流水来源：底部一个按钮（显示当前来源，有空闲号段时带角标），点开弹层：从最大号之后接着编（默认）/ 指定起止号，
       下面列出空闲号段（来源单据、起止流水、枚数），「用这段」填入起止号 -->
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
      <template v-if="gaps">
        <b class="free-h">{{ t("generate.freeSegs") }}</b>
        <el-table v-if="gaps.count" :data="gaps.segments" size="small" max-height="220">
          <el-table-column :label="t('generate.srcBill')" min-width="110">
            <template #default="{ row }">{{ row.bill_no || "—" }}</template>
          </el-table-column>
          <el-table-column :label="t('generate.seqStart')" prop="start_seq" align="right" />
          <el-table-column :label="t('generate.seqEnd')" prop="end_seq" align="right" />
          <el-table-column :label="t('common.qty')" align="right">
            <template #default="{ row }"><span class="num">{{ num(row.qty) }}</span></template>
          </el-table-column>
          <el-table-column width="64" align="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="use(row)">{{ t("generate.gapUse") }}</el-button>
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
/** 生成的流水来源。gaps：本 PI 的空闲号段（生成详情 / 预演返回，带来源单据）；modelValue：{ mode, start, end }；
 *  first：自动预演算出的第一个号（自定义规则指定了起始号时可能大于 PI 下一个号），没有时按 PI 下一个号。
 *  空闲号段列表（或单据号段弹窗）里点「用这段」填入起止号并切到指定起止号。 */
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { num } from "./format";

export type SeqValue = { mode: "MAX" | "RANGE"; start: number | null; end: number | null };

const props = defineProps<{ modelValue: SeqValue; gaps: any; first?: number | null }>();
const emit = defineEmits<{ "update:modelValue": [v: SeqValue] }>();
const { t } = useI18n();
const open = ref(false);

const nextSeq = computed(() => (props.modelValue.mode === "MAX" && props.first) || props.gaps?.next_seq || 1);
const label = computed(() => {
  const v = props.modelValue;
  if (v.mode === "MAX") return t("generate.seqBtnMax", { n: nextSeq.value });
  if (!v.start) return t("generate.seqBtnUnset");
  return t("generate.seqBtnRange", { start: v.start, end: v.end ?? v.start });
});

function set(p: Partial<SeqValue>) {
  emit("update:modelValue", { ...props.modelValue, ...p });
}

function use(row: { start_seq: number; end_seq: number }) {
  set({ mode: "RANGE", start: row.start_seq, end: row.end_seq });
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
p { margin: 0; }
</style>
