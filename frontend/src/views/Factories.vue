<template>
  <div class="page">
    <PageHead :title="t('factories.title')" :desc="t('factories.desc')">
      <el-button @click="openCreate"><el-icon><Plus /></el-icon><span>{{ t("factories.create") }}</span></el-button>
      <el-button type="primary" :loading="syncing" @click="sync"><el-icon><Refresh /></el-icon><span>{{ t("factories.sync") }}</span></el-button>
    </PageHead>
    <div class="surface">
      <el-table :data="factories" size="small" :empty-text="t('common.empty')" :row-class-name="({ row }: any) => (row.enabled ? '' : 'off')">
        <el-table-column prop="factory_code" :label="t('factories.code')" width="160" />
        <el-table-column prop="factory_name" :label="t('factories.name')" />
        <el-table-column :label="t('factories.source')" width="100">
          <template #default="{ row }"><el-tag size="small" :type="row.source === 'K3' ? 'success' : 'warning'">{{ t(`factories.src${row.source}`) }}</el-tag></template>
        </el-table-column>
        <el-table-column :label="t('common.status')" width="100">
          <template #default="{ row }"><el-tag size="small" :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? t("factories.enabled") : t("factories.disabled") }}</el-tag></template>
        </el-table-column>
        <el-table-column :label="t('factories.boundUsers')" align="right" width="110">
          <template #default="{ row }"><span class="num">{{ row.bound_users ?? 0 }}</span></template>
        </el-table-column>
        <el-table-column prop="updated_at" :label="t('common.time')" width="170" />
        <el-table-column :label="t('common.actions')" width="150" align="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openRename(row)">{{ t("factories.rename") }}</el-button>
            <el-button v-if="row.enabled" link type="danger" :loading="busy === row.factory_code" @click="toggle(row, false)">{{ t("factories.disable") }}</el-button>
            <el-button v-else link type="primary" :loading="busy === row.factory_code" @click="toggle(row, true)">{{ t("factories.enable") }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <p class="muted hint">{{ t("factories.disableHint") }}</p>
    </div>

    <el-dialog v-model="dialog" :title="editing ? t('factories.rename') : t('factories.create')" width="460px">
      <el-form label-width="90px">
        <el-form-item :label="t('factories.code')" required><el-input v-model="form.factory_code" :disabled="!!editing" /></el-form-item>
        <el-form-item :label="t('factories.name')" required><el-input v-model="form.factory_name" /></el-form-item>
      </el-form>
      <el-alert v-if="editing" type="warning" :closable="false" show-icon :title="t('factories.renameHint')" />
      <p v-else class="muted">{{ t("factories.nameHint") }}</p>
      <template #footer>
        <el-button @click="dialog = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" @click="save">{{ t("common.save") }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "@/api";
import { useFactories } from "@/composables/useFactories";
import type { Factory } from "@/types/api";
import PageHead from "@/components/PageHead.vue";

const { t } = useI18n();
const { factories, load } = useFactories();
const syncing = ref(false);
const dialog = ref(false);
const busy = ref("");
const editing = ref<Factory | null>(null);
const form = reactive({ factory_code: "", factory_name: "" });

async function sync() {
  syncing.value = true;
  try {
    const r: any = await api.syncFactories();
    ElMessage.success(t("factories.synced", { total: r.total, inserted: r.inserted, updated: r.updated, disabled: r.disabled ?? 0, enabled: r.enabled ?? 0 }));
    if (r.conflicts?.length) ElMessage.warning(t("factories.conflicts", { list: r.conflicts.join("、") }));
    await load(true);
  } finally {
    syncing.value = false;
  }
}

function openCreate() {
  editing.value = null;
  Object.assign(form, { factory_code: "", factory_name: "" });
  dialog.value = true;
}

function openRename(f: Factory) {
  editing.value = f;
  Object.assign(form, { factory_code: f.factory_code, factory_name: f.factory_name });
  dialog.value = true;
}

async function save() {
  if (editing.value) await api.renameFactory(editing.value.factory_code, form.factory_name);
  else await api.createFactory({ ...form });
  ElMessage.success(t("common.saved"));
  dialog.value = false;
  await load(true);
}

/** 停用 / 启用：停用前提示影响范围与绑定该厂的账户数。 */
async function toggle(f: Factory, enabled: boolean) {
  const msg = enabled
    ? t("factories.enableConfirm", { name: f.factory_name })
    : t("factories.disableConfirm", { name: f.factory_name, n: f.bound_users ?? 0 });
  try {
    await ElMessageBox.confirm(msg, enabled ? t("factories.enable") : t("factories.disable"), { type: "warning" });
  } catch {
    return;
  }
  busy.value = f.factory_code;
  try {
    await api.setFactoryEnabled(f.factory_code, enabled);
    ElMessage.success(t("common.done"));
    await load(true);
  } finally {
    busy.value = "";
  }
}

/** 每次进入页面：先显示现有工厂，再自动从金蝶同步一次（失败时本地不动，提示由请求层弹出）。 */
load(true).then(() => sync());
</script>

<style scoped>
.hint { margin-top: 10px; font-size: 12px; }
:deep(.el-table__row.off) { color: var(--app-muted); }
</style>
