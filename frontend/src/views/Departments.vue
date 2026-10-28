<template>
  <div class="page">
    <PageHead :title="t('departments.title')" :desc="t('departments.desc')">
      <el-button :loading="loading" @click="load"><el-icon><Refresh /></el-icon><span>{{ t("common.refresh") }}</span></el-button>
    </PageHead>
    <div class="surface">
      <div class="toolbar">
        <FactorySelect v-if="store.multiScope" v-model="factory" scoped clearable @update:model-value="load" />
        <el-input v-model="kw" :placeholder="t('departments.search')" clearable prefix-icon="Search" class="w-search" />
      </div>
      <el-table v-loading="loading" :data="shown" size="small" :empty-text="t('departments.empty')">
        <el-table-column :label="t('common.factory')" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.org_name || row.org_code }}<span class="muted">（{{ row.org_code }}）</span></template>
        </el-table-column>
        <el-table-column prop="dept_code" :label="t('departments.code')" min-width="120" />
        <el-table-column prop="dept_name" :label="t('departments.name')" min-width="160" show-overflow-tooltip />
        <el-table-column :label="t('orders.bills')" align="right" width="100">
          <template #default="{ row }"><span class="num">{{ row.bills }}</span></template>
        </el-table-column>
        <el-table-column :label="t('orders.rows')" align="right" width="100">
          <template #default="{ row }"><span class="num">{{ row.line_count }}</span></template>
        </el-table-column>
        <el-table-column :label="t('orders.modifyDate')" width="170">
          <template #default="{ row }"><span class="num">{{ row.modify_time || "—" }}</span></template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
/** 生产部门（只读）：订单快照里出现过的生产车间（金蝶生产订单明细），按生产组织列出；部门主数据仍在金蝶维护。 */
import { computed, onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useAuthStore } from "@/stores/auth";
import PageHead from "@/components/PageHead.vue";
import FactorySelect from "@/components/FactorySelect.vue";

type Dept = { org_code: string; org_name: string; dept_code: string; dept_name: string; bills: number; line_count: number; modify_time: string | null };

const { t } = useI18n();
const store = useAuthStore();
const factory = ref("");
const kw = ref("");
const rows = ref<Dept[]>([]);
const loading = ref(false);

const shown = computed(() => {
  const k = kw.value.trim().toLowerCase();
  return k ? rows.value.filter((r) => `${r.dept_code} ${r.dept_name} ${r.org_code} ${r.org_name}`.toLowerCase().includes(k)) : rows.value;
});

async function load() {
  loading.value = true;
  try {
    rows.value = await api.orderDepartments({ factory_code: factory.value || undefined });
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.toolbar .w-search { width: 260px; }
</style>
