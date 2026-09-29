<template>
  <el-select
    :model-value="modelValue"
    filterable
    :clearable="clearable"
    :disabled="disabled"
    :placeholder="placeholder || t('common.factory')"
    class="factory-select"
    @update:model-value="(v: string) => emit('update:modelValue', v ?? '')"
  >
    <el-option v-for="f in factories" :key="f.factory_code" :label="`${f.factory_name}（${f.factory_code}）`" :value="f.factory_code" />
  </el-select>
</template>

<script setup lang="ts">
import { useI18n } from "vue-i18n";
import { useFactories } from "@/composables/useFactories";

defineProps<{ modelValue: string; clearable?: boolean; disabled?: boolean; placeholder?: string }>();
const emit = defineEmits<{ (e: "update:modelValue", v: string): void }>();
const { t } = useI18n();
const { factories } = useFactories();
</script>

<style scoped>
.factory-select { width: 220px; }
</style>
