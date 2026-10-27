<template>
  <el-dialog :model-value="modelValue" :title="t('generate.genTitle')" width="680px" @update:model-value="(v: boolean) => emit('update:modelValue', v)">
    <template v-if="preview?.generatable">
      <p>{{ t("generate.genConfirm", { m: preview.items.length, n: num(preview.qty), a: preview.start_sn, b: preview.end_sn }) }}</p>
      <el-table :data="preview.items" size="small" max-height="320">
        <el-table-column :label="t('common.billNo')" prop="bill_no" min-width="120" />
        <el-table-column :label="t('common.billSeq')" prop="seq" width="72" />
        <el-table-column :label="t('common.qty')" align="right" width="80">
          <template #default="{ row }"><span class="num">{{ num(row.qty) }}</span></template>
        </el-table-column>
        <el-table-column :label="t('generate.segment')" min-width="250">
          <template #default="{ row }"><span class="sn-mono">{{ row.start_sn }} ~ {{ row.end_sn }}</span></template>
        </el-table-column>
      </el-table>
      <p class="muted small">{{ t("generate.genNote", { n: num(syncThreshold), time: fmt(preview.expires_at) }) }}</p>
      <el-checkbox v-if="unlockLimit" v-model="unlocked" class="unlock">{{ t("generate.unlock", { limit: num(unlockLimit) }) }}</el-checkbox>
    </template>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" :loading="loading" :disabled="!!unlockLimit && !unlocked" @click="emit('confirm')">{{ t("generate.genBtn", { n: num(preview?.qty || 0) }) }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/** 确认生成：本次各物料行的号段；超过单次默认上限（unlockLimit > 0）时须先勾选解除限制。确认后由父组件凭预演令牌生成。 */
import { ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useTime } from "@/composables/useTime";
import { num } from "./format";

const props = defineProps<{ modelValue: boolean; preview: any; syncThreshold: number; unlockLimit: number; loading: boolean }>();
const emit = defineEmits<{ "update:modelValue": [open: boolean]; confirm: [] }>();
const { t } = useI18n();
const { fmt } = useTime();
const unlocked = ref(false);
// 每次打开都要重新勾选
watch(() => props.modelValue, (open) => { if (open) unlocked.value = false; });
</script>

<style scoped>
.small { font-size: 12px; }
.unlock :deep(.el-checkbox__label) { color: var(--el-color-warning-dark-2); }
</style>
