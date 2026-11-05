<template>
  <el-dialog :model-value="modelValue" :title="t('generate.genTitle')" width="min(1180px, 96vw)" @update:model-value="(v: boolean) => emit('update:modelValue', v)">
    <template v-if="preview?.generatable">
      <!-- 手动起止号：提示用了多少个空闲流水、溢出多少枚从最大号之后接着编 -->
      <el-alert v-if="preview.seq?.mode === 'RANGE'" :type="preview.overflow_qty ? 'warning' : 'info'" :closable="false" show-icon class="usage"
        :title="t('generate.seqUsage', { start: preview.seq.start, end: preview.seq.end, used: num(preview.range_qty), over: num(preview.overflow_qty), next: overflowFrom })" />
      <!-- 同一单据内附件项次相同：提示将按行号（单据序号）从小到大生成（不拦截） -->
      <el-alert v-if="dups.length" type="warning" :closable="false" show-icon class="usage" :title="t('generate.attachDupHint')">
        <div v-for="g in dups" :key="g.bill_no + '#' + g.attach">
          {{ t("generate.attachDupItem", { bill: g.bill_no, attach: g.attach, seqs: g.lines.map((l: any) => l.seq).join("、") }) }}
        </div>
      </el-alert>
      <!-- 列依次为物料编码、前缀、进制、流水位数、流水号（起 ~ 止）、附件项次、米数、产品单位、开始 / 结束 SN；
           米数、产品单位为写进 SN 的值，规则不带时为「—」 -->
      <el-table :data="preview.items" size="small" max-height="320">
        <el-table-column :label="t('common.material')" prop="material_code" min-width="110" show-overflow-tooltip />
        <el-table-column :label="t('rules.prefix')" min-width="80" show-overflow-tooltip>
          <template #default="{ row }"><span class="sn-mono">{{ row.prefix ?? "—" }}</span></template>
        </el-table-column>
        <el-table-column :label="t('rules.base')" width="56" align="right">
          <template #default="{ row }">{{ row.base ?? "—" }}</template>
        </el-table-column>
        <el-table-column :label="t('rules.seqDigits')" width="76" align="right">
          <template #default="{ row }">{{ row.seq_len ?? "—" }}</template>
        </el-table-column>
        <el-table-column :label="t('rules.seqNo')" min-width="150">
          <template #default="{ row }">
            <div v-for="(g, i) in segsOf(row)" :key="i" class="sn-mono">{{ seqRange(g) }}</div>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.attachNo')" prop="attach" min-width="80" show-overflow-tooltip />
        <el-table-column :label="t('common.length')" min-width="64" show-overflow-tooltip>
          <template #default="{ row }">{{ row.length ?? "—" }}</template>
        </el-table-column>
        <el-table-column :label="t('common.unit')" min-width="70" show-overflow-tooltip>
          <template #default="{ row }">{{ row.unit ?? "—" }}</template>
        </el-table-column>
        <el-table-column :label="t('generate.startSn')" min-width="170">
          <template #default="{ row }">
            <div v-for="(g, i) in segsOf(row)" :key="i" class="sn-mono">{{ g.start_sn }}</div>
          </template>
        </el-table-column>
        <el-table-column :label="t('generate.endSn')" min-width="170">
          <template #default="{ row }">
            <div v-for="(g, i) in segsOf(row)" :key="i" class="sn-mono">{{ g.end_sn }}</div>
          </template>
        </el-table-column>
      </el-table>
      <el-checkbox v-if="unlockLimit" v-model="unlocked" class="unlock">{{ t("generate.unlock", { limit: num(unlockLimit) }) }}</el-checkbox>
    </template>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" :loading="loading" :disabled="!!unlockLimit && !unlocked" @click="emit('confirm')">{{ t("generate.genBtn", { n: num(preview?.qty || 0) }) }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/** 确认生成：只列本次各物料行的物料编码、前缀、进制、流水位数、流水号、附件项次、米数、产品单位与开始 / 结束 SN；
 *  用空号段时一个物料行可有几段号，每段一行（流水号、开始 / 结束 SN 对齐）；手动起止号时顶部提示用了多少个空闲流水、
 *  溢出多少枚从最大号之后接着编；
 *  同一单据内附件项次相同时顶部提示将按行号（单据序号）从小到大生成；
 *  超过单次默认上限（unlockLimit > 0）时须先勾选解除限制。确认后由父组件凭预演令牌生成。 */
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { num } from "./format";
import { dupAttach } from "@/genScope.js";

const props = defineProps<{ modelValue: boolean; preview: any; unlockLimit: number; loading: boolean }>();
const emit = defineEmits<{ "update:modelValue": [open: boolean]; confirm: [] }>();
const { t } = useI18n();
const unlocked = ref(false);
/** 物料行的号段：一段或几段（用空号段时）。 */
const segsOf = (row: any): any[] => row.seg_list || [row];
/** 一段号的流水号（规则进制、定长）：起 ~ 止，只有一枚时只写一个；旧预演没带时为「—」。 */
function seqRange(g: any): string {
  if (!g.start_seq_text) return "—";
  return g.start_seq_text === g.end_seq_text ? g.start_seq_text : `${g.start_seq_text} ~ ${g.end_seq_text}`;
}
/** 溢出部分从哪个十进制流水起编：max(原最大号, 止号) + 1。 */
const overflowFrom = computed(() => {
  const p = props.preview;
  return p?.seq?.mode === "RANGE" ? Math.max(Number(p.base_last_seq) || 0, Number(p.seq.end) || 0) + 1 : 0;
});
/** 同一单据内附件项次相同的物料行（预演各行已按排号顺序排好）。 */
const dups = computed<any[]>(() => (props.preview?.generatable ? dupAttach(props.preview.items || [], (x: any) => x.attach) : []));
// 每次打开都要重新勾选
watch(() => props.modelValue, (open) => { if (open) unlocked.value = false; });
</script>

<style scoped>
.unlock { margin-top: 10px; }
.usage { margin-bottom: 10px; }
.unlock :deep(.el-checkbox__label) { color: var(--el-color-warning-dark-2); }
.sn-mono { word-break: break-all; }
</style>
