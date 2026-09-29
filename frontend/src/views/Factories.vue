<template>
  <div class="page">
    <PageHead :title="t('factories.title')" :desc="t('factories.desc')">
      <el-button @click="dialog = true"><el-icon><Plus /></el-icon><span>{{ t("factories.create") }}</span></el-button>
      <el-button type="primary" :loading="syncing" @click="sync"><el-icon><Refresh /></el-icon><span>{{ t("factories.sync") }}</span></el-button>
    </PageHead>
    <div class="surface">
      <el-table :data="factories" size="small" :empty-text="t('common.empty')">
        <el-table-column prop="factory_code" :label="t('factories.code')" width="160" />
        <el-table-column prop="factory_name" :label="t('factories.name')" />
        <el-table-column :label="t('factories.source')" width="120">
          <template #default="{ row }"><el-tag size="small" :type="row.source === 'K3' ? 'success' : 'warning'">{{ t(`factories.src${row.source}`) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="updated_at" :label="t('common.time')" width="170" />
      </el-table>
    </div>
    <el-dialog v-model="dialog" :title="t('factories.create')" width="440px">
      <el-form label-width="90px">
        <el-form-item :label="t('factories.code')" required><el-input v-model="form.factory_code" /></el-form-item>
        <el-form-item :label="t('factories.name')" required><el-input v-model="form.factory_name" /></el-form-item>
      </el-form>
      <p class="muted">{{ t("factories.nameHint") }}</p>
      <template #footer>
        <el-button @click="dialog = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" @click="create">{{ t("common.save") }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import { api } from "@/api";
import { useFactories } from "@/composables/useFactories";
import PageHead from "@/components/PageHead.vue";

const { t } = useI18n();
const { factories, load } = useFactories();
const syncing = ref(false);
const dialog = ref(false);
const form = reactive({ factory_code: "", factory_name: "" });

async function sync() {
  syncing.value = true;
  try {
    const r: any = await api.syncFactories();
    ElMessage.success(t("factories.synced", { total: r.total, inserted: r.inserted, updated: r.updated }));
    if (r.conflicts?.length) ElMessage.warning(t("factories.conflicts", { list: r.conflicts.join("、") }));
    await load(true);
  } finally {
    syncing.value = false;
  }
}

async function create() {
  await api.createFactory({ ...form });
  ElMessage.success(t("common.saved"));
  dialog.value = false;
  Object.assign(form, { factory_code: "", factory_name: "" });
  await load(true);
}

load(true);
</script>
