<template>
  <div class="page">
    <PageHead :title="t('users.title')" :desc="t('users.desc')">
      <el-button type="primary" @click="openCreate"><el-icon><Plus /></el-icon><span>{{ t("users.create") }}</span></el-button>
    </PageHead>
    <div class="surface">
      <div class="toolbar">
        <el-input v-model="f.q" :placeholder="t('users.search')" clearable prefix-icon="Search" @keyup.enter="load(1)" @clear="load(1)" />
        <el-select v-model="f.role" clearable :placeholder="t('users.role')" @change="load(1)">
          <el-option v-for="r in ROLE_CODES" :key="r" :value="r" :label="roleLabel(r)" />
        </el-select>
        <el-select v-model="f.status" clearable :placeholder="t('common.status')" @change="load(1)">
          <el-option value="ACTIVE" :label="t('users.active')" /><el-option value="DISABLED" :label="t('users.disabled')" />
        </el-select>
        <FactorySelect v-model="f.factory_code" clearable @update:model-value="load(1)" />
      </div>
      <el-table v-loading="state.loading" :data="items" size="small" :empty-text="t('common.empty')">
        <el-table-column prop="emp_no" :label="t('users.empNo')" width="120" />
        <el-table-column prop="name" :label="t('users.name')" width="120" />
        <el-table-column :label="t('users.role')" width="120"><template #default="{ row }">{{ roleLabel(row.role) }}</template></el-table-column>
        <el-table-column :label="t('common.factory')" min-width="140">
          <template #default="{ row }">{{ row.factory_code ? `${row.factory_name || ""}（${row.factory_code}）` : t("users.noFactory") }}</template>
        </el-table-column>
        <el-table-column :label="t('common.status')" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status === "ACTIVE" ? t("users.active") : t("users.disabled") }}</el-tag>
            <el-tag v-if="row.must_change_pwd" size="small" type="warning" class="ml">{{ t("users.mustChange") }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="last_login_at" :label="t('users.lastLogin')" width="160" />
        <el-table-column :label="t('users.created')" width="160"><template #default="{ row }">{{ row.created_by }} · {{ row.created_at }}</template></el-table-column>
        <el-table-column :label="t('common.actions')" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">{{ t("common.edit") }}</el-button>
            <el-button link @click="reset(row)">{{ t("users.resetPwd") }}</el-button>
            <el-button v-if="row.status === 'ACTIVE'" link type="danger" :disabled="row.id === store.profile?.id" @click="disable(row)">{{ t("users.disable") }}</el-button>
            <el-button v-else link type="success" @click="enable(row)">{{ t("users.enable") }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" layout="total, prev, pager, next" :total="state.total" :page-size="state.pageSize"
        :current-page="state.page" @current-change="load" />
    </div>

    <el-dialog v-model="dialog" :title="editing ? t('users.editTitle', { emp: editing.emp_no }) : t('users.create')" width="520px">
      <el-form label-width="100px">
        <el-form-item :label="t('users.empNo')" required><el-input v-model="form.emp_no" :disabled="!!editing" /></el-form-item>
        <el-form-item :label="t('users.name')" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item :label="t('users.role')" required>
          <el-radio-group v-model="form.role">
            <el-radio-button v-for="r in ROLE_CODES" :key="r" :value="r">{{ roleLabel(r) }}</el-radio-button>
          </el-radio-group>
          <div class="muted">{{ t(`users.roleHint.${form.role}`) }}</div>
        </el-form-item>
        <el-form-item v-if="form.role !== 'admin'" :label="t('common.factory')" :required="form.role === 'factory_operator'">
          <FactorySelect v-model="form.factory_code" clearable />
        </el-form-item>
        <el-form-item v-if="!editing" :label="t('users.initPwd')" required>
          <el-input v-model="form.password" type="password" show-password />
          <div class="muted">{{ t("password.policy") }} {{ t("users.firstLogin") }}</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ t("common.save") }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "@/api";
import { useAuthStore } from "@/stores/auth";
import { usePaged } from "@/composables/usePaged";
import { ROLE_CODES, roleLabel } from "@/constants";
import PageHead from "@/components/PageHead.vue";
import FactorySelect from "@/components/FactorySelect.vue";

const { t } = useI18n();
const store = useAuthStore();
const f = reactive({ q: "", role: "", status: "", factory_code: "" });
const dialog = ref(false);
const editing = ref<any>(null);
const saving = ref(false);
const form = reactive({ emp_no: "", name: "", role: "factory_operator", factory_code: "", password: "" });
const { items, state, load } = usePaged<any>((q) => api.users({ ...f, ...q }));

function openCreate() {
  editing.value = null;
  Object.assign(form, { emp_no: "", name: "", role: "factory_operator", factory_code: "", password: "" });
  dialog.value = true;
}

function openEdit(row: any) {
  editing.value = row;
  Object.assign(form, { emp_no: row.emp_no, name: row.name, role: row.role, factory_code: row.factory_code || "", password: "" });
  dialog.value = true;
}

async function save() {
  saving.value = true;
  const factory = form.role === "admin" ? null : form.factory_code || null;
  try {
    if (editing.value) {
      await api.updateUser(editing.value.id, { name: form.name, role: form.role, factory_code: factory });
    } else {
      await api.createUser({ emp_no: form.emp_no.trim(), name: form.name, role: form.role, factory_code: factory, password: form.password });
    }
    ElMessage.success(t("common.saved"));
    dialog.value = false;
    await load();
  } finally {
    saving.value = false;
  }
}

async function disable(row: any) {
  await ElMessageBox.confirm(t("users.disableConfirm", { emp: row.emp_no, name: row.name }), t("users.disable"), { type: "warning" });
  await api.disableUser(row.id);
  ElMessage.success(t("common.done"));
  await load();
}

async function enable(row: any) {
  await api.enableUser(row.id);
  ElMessage.success(t("common.done"));
  await load();
}

async function reset(row: any) {
  const { value } = await ElMessageBox.prompt(t("users.resetPrompt", { emp: row.emp_no }), t("users.resetPwd"), {
    inputType: "password",
    inputValidator: (v: string) => (!!v && v.length >= 8 && /[A-Za-z]/.test(v) && /\d/.test(v)) || t("password.policy"),
  });
  await api.resetPassword(row.id, value);
  ElMessage.success(t("users.resetDone"));
}

onMounted(() => load(1));
</script>

<style scoped>
.ml { margin-left: 4px; }
</style>
