<template>
  <el-dialog :model-value="modelValue" :title="t('generate.genTitle')" width="min(1180px, 96vw)" @update:model-value="(v: boolean) => emit('update:modelValue', v)">
    <template v-if="preview?.generatable">
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
          <template #default="{ row }"><span class="sn-mono">{{ seqRange(row) }}</span></template>
        </el-table-column>
        <el-table-column :label="t('common.attachNo')" prop="attach" min-width="80" show-overflow-tooltip />
        <el-table-column :label="t('common.length')" min-width="64" show-overflow-tooltip>
          <template #default="{ row }">{{ row.length ?? "—" }}</template>
        </el-table-column>
        <el-table-column :label="t('common.unit')" min-width="70" show-overflow-tooltip>
          <template #default="{ row }">{{ row.unit ?? "—" }}</template>
        </el-table-column>
        <el-table-column :label="t('generate.startSn')" min-width="170">
          <template #default="{ row }"><span class="sn-mono">{{ row.start_sn }}</span></template>
        </el-table-column>
        <el-table-column :label="t('generate.endSn')" min-width="170">
          <template #default="{ row }"><span class="sn-mono">{{ row.end_sn }}</span></template>
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
/** 确认生成：只列本次各物料行的物料编码、前缀、进制、流水位数、流水号、附件项次、米数、产品单位与开始 / 结束 SN，
 *  不放提示文字；
 *  超过单次默认上限（unlockLimit > 0）时须先勾选解除限制。确认后由父组件凭预演令牌生成。 */
import { ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { num } from "./format";

const props = defineProps<{ modelValue: boolean; preview: any; unlockLimit: number; loading: boolean }>();
const emit = defineEmits<{ "update:modelValue": [open: boolean]; confirm: [] }>();
const { t } = useI18n();
const unlocked = ref(false);
/** 本行流水号（规则进制、定长）：起 ~ 止，只有一枚时只写一个；旧预演没带时为「—」。 */
function seqRange(row: any): string {
  if (!row.start_seq_text) return "—";
  return row.start_seq_text === row.end_seq_text ? row.start_seq_text : `${row.start_seq_text} ~ ${row.end_seq_text}`;
}
// 每次打开都要重新勾选
watch(() => props.modelValue, (open) => { if (open) unlocked.value = false; });
</script>

<style scoped>
.unlock { margin-top: 10px; }
.unlock :deep(.el-checkbox__label) { color: var(--el-color-warning-dark-2); }
.sn-mono { word-break: break-all; }
</style>
