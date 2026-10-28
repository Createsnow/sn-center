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
    <el-option v-for="f in options" :key="f.factory_code" :label="`${f.factory_name}（${f.factory_code}）${f.mes_enabled ? ' · MES' : ''}${f.enabled ? '' : t('common.disabledSuffix')}`" :value="f.factory_code" />
  </el-select>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { useFactories } from "@/composables/useFactories";
import { useAuthStore } from "@/stores/auth";

/** scoped：只列当前账户授权的生产组织（系统管理员列全部）；转组织的目标组织等不限范围的选择不加。 */
const props = defineProps<{ modelValue: string; clearable?: boolean; disabled?: boolean; placeholder?: string; scoped?: boolean }>();
const emit = defineEmits<{ (e: "update:modelValue", v: string): void }>();
const { t } = useI18n();
const { factories } = useFactories();
const store = useAuthStore();
const options = computed(() =>
  props.scoped && !store.isAdmin ? factories.value.filter((f) => store.scopeFactories.includes(f.factory_code)) : factories.value,
);
</script>

<style scoped>
.factory-select { width: 220px; }
</style>
