<template>
  <!-- 流水来源：从最大号之后接着编（默认），或手动起止号（用空号段与未使用的号，不够时从最大号之后接着编） -->
  <div class="seq-src">
    <el-radio-group :model-value="modelValue.mode" size="small" @update:model-value="(v: any) => set({ mode: v })">
      <el-radio-button value="MAX">{{ t("generate.seqMax", { n: gaps?.next_seq ?? 1 }) }}</el-radio-button>
      <el-radio-button value="RANGE">{{ t("generate.seqRange") }}</el-radio-button>
    </el-radio-group>
    <template v-if="modelValue.mode === 'RANGE'">
      <el-input-number :model-value="modelValue.start ?? undefined" size="small" :min="1" :step="1" step-strictly controls-position="right"
        :placeholder="t('generate.seqStart')" class="no" @update:model-value="(v: any) => set({ start: v ?? null })" />
      <span class="muted">~</span>
      <el-input-number :model-value="modelValue.end ?? undefined" size="small" :min="1" :step="1" step-strictly controls-position="right"
        :placeholder="t('generate.seqEnd')" class="no" @update:model-value="(v: any) => set({ end: v ?? null })" />
    </template>
    <el-popover v-if="gaps" placement="top" :width="380" trigger="click">
      <template #reference>
        <el-button link :type="gaps.count ? 'warning' : 'info'" size="small">
          {{ t("generate.gapsLink", { n: num(gaps.count), qty: num(gaps.total) }) }}
        </el-button>
      </template>
      <p class="muted head">{{ t("generate.gapsHead", { last: gaps.last_seq }) }}</p>
      <el-table v-if="gaps.count" :data="gaps.segments" size="small" max-height="280">
        <el-table-column :label="t('generate.seqStart')" prop="start_seq" align="right" />
        <el-table-column :label="t('generate.seqEnd')" prop="end_seq" align="right" />
        <el-table-column :label="t('common.qty')" align="right">
          <template #default="{ row }"><span class="num">{{ num(row.qty) }}</span></template>
        </el-table-column>
        <el-table-column width="64" align="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="set({ mode: 'RANGE', start: row.start_seq, end: row.end_seq })">{{ t("generate.gapUse") }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <p v-else class="muted">{{ t("generate.gapsNone") }}</p>
      <p v-if="gaps.truncated" class="muted">{{ t("generate.gapsTruncated", { n: gaps.segments.length }) }}</p>
    </el-popover>
  </div>
</template>

<script setup lang="ts">
/** 生成的流水来源。gaps：本 PI 的空号段（生成详情 / 预演返回）；modelValue：{ mode, start, end }。
 *  空号段列表里点「用这段」填入起止号并切到手动起止号。 */
import { useI18n } from "vue-i18n";
import { num } from "./format";

export type SeqValue = { mode: "MAX" | "RANGE"; start: number | null; end: number | null };

const props = defineProps<{ modelValue: SeqValue; gaps: any }>();
const emit = defineEmits<{ "update:modelValue": [v: SeqValue] }>();
const { t } = useI18n();

function set(p: Partial<SeqValue>) {
  emit("update:modelValue", { ...props.modelValue, ...p });
}
</script>

<style scoped>
.seq-src { display: inline-flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.no { width: 120px; }
.head { margin: 0 0 6px; }
</style>
