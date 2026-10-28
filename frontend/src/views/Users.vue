<template>
  <div class="page">
    <PageHead :title="t('users.title')" :desc="t('users.desc')">
      <el-button type="primary" @click="openCreate"><el-icon><Plus /></el-icon>
        <span>{{ tab === "mes" ? t("users.createMes") : t("users.create") }}</span>
      </el-button>
    </PageHead>
    <div class="surface">
      <el-tabs v-model="tab" @tab-change="onTab">
        <el-tab-pane name="page" :label="t('users.tabAll')" />
        <el-tab-pane name="mes" :label="t('users.tabMes')" />
      </el-tabs>
      <div class="toolbar">
        <el-input v-model="f.q" :placeholder="t('users.search')" clearable prefix-icon="Search" @keyup.enter="load(1)" @clear="load(1)" />
        <el-select v-if="tab === 'page'" v-model="f.role" clearable :placeholder="t('users.role')" @change="load(1)">
          <el-option v-for="r in ROLE_CODES" :key="r" :value="r" :label="roleLabel(r)" />
        </el-select>
        <el-select v-model="f.status" clearable :placeholder="t('common.status')" @change="load(1)">
          <el-option value="ACTIVE" :label="t('users.active')" /><el-option value="DISABLED" :label="t('users.disabled')" />
        </el-select>
        <FactorySelect v-model="f.factory_code" clearable @update:model-value="load(1)" />
      </div>
      <el-table v-loading="state.loading" :data="items" size="small" :empty-text="t('common.empty')">
        <el-table-column prop="emp_no" :label="t('users.empNo')" width="120" />
        <el-table-column prop="name" :label="t('users.name')" min-width="110" show-overflow-tooltip />
        <el-table-column :label="t('users.role')" width="150">
          <template #default="{ row }">
            <el-tag v-if="row.super_admin" size="small">{{ t("app.superAdmin") }}</el-tag>
            <span v-else>{{ roleLabel(row.role) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.factory')" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ factoryText(row) }}</template>
        </el-table-column>
        <el-table-column v-if="tab === 'mes'" :label="t('users.ipWhitelist')" min-width="160" show-overflow-tooltip>
          <template #default="{ row }"><span v-if="row.ip_whitelist" class="sn-mono">{{ row.ip_whitelist }}</span><span v-else class="muted">{{ t("users.ipAny") }}</span></template>
        </el-table-column>
        <el-table-column :label="t('common.status')" width="150">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status === "ACTIVE" ? t("users.active") : t("users.disabled") }}</el-tag>
            <el-tooltip v-if="isLocked(row)" :content="t('users.lockedUntil', { time: fmt(row.locked_until) })">
              <el-tag size="small" type="danger" class="ml">{{ t("users.locked") }}</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column :label="tab === 'mes' ? t('users.lastToken') : t('users.lastLogin')" width="160">
          <template #default="{ row }">{{ fmt(row.last_login_at) }}</template>
        </el-table-column>
        <el-table-column :label="t('users.created')" width="200"><template #default="{ row }">{{ row.created_by }} · {{ fmt(row.created_at) }}</template></el-table-column>
        <el-table-column :label="t('common.actions')" :width="tab === 'mes' ? 300 : 230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">{{ t("common.edit") }}</el-button>
            <template v-if="row.role === 'mes'">
              <el-button link type="primary" @click="viewSecret(row)">{{ t("users.viewSecret") }}</el-button>
              <el-button link @click="resetSecret(row)">{{ t("users.resetSecret") }}</el-button>
            </template>
            <el-button v-else link @click="reset(row)">{{ t("users.resetPwd") }}</el-button>
            <el-button v-if="row.status === 'ACTIVE'" link type="danger" :disabled="row.id === store.profile?.id || row.super_admin" @click="disable(row)">{{ t("users.disable") }}</el-button>
            <el-button v-else link type="success" @click="enable(row)">{{ t("users.enable") }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" layout="total, prev, pager, next" :total="state.total" :page-size="state.pageSize"
        :current-page="state.page" @current-change="load" />
    </div>

    <el-dialog v-model="dialog" :title="dialogTitle" width="560px">
      <el-form label-width="120px">
        <el-form-item :label="t('users.empNo')" required>
          <el-input v-model.trim="form.emp_no" :disabled="!!editing" maxlength="32" />
          <div v-if="!editing" class="muted hint">{{ t("users.empNoHint") }}</div>
        </el-form-item>
        <el-form-item :label="t('users.name')" required><el-input v-model="form.name" maxlength="64" /></el-form-item>
        <template v-if="isMes">
          <el-form-item :label="t('common.factory')" required>
            <el-select v-model="form.factory_code" filterable class="w100" :placeholder="t('users.mesFactoryPlaceholder')">
              <el-option v-for="x in mesFactories" :key="x.factory_code" :value="x.factory_code"
                :label="`${x.factory_name}（${x.factory_code}）${x.mes_enabled ? ' · MES' : t('users.mesOff')}${x.enabled ? '' : t('common.disabledSuffix')}`" />
            </el-select>
            <div class="muted hint">{{ t("users.mesFactoryHint") }}</div>
          </el-form-item>
          <el-form-item :label="t('users.ipWhitelist')">
            <el-input v-model="form.ip_whitelist" type="textarea" :rows="2" placeholder="10.0.0.5, 10.1.0.0/16" />
            <div class="muted hint">{{ t("users.ipHint") }}</div>
          </el-form-item>
          <el-alert v-if="!editing" type="info" :closable="false" show-icon :title="t('users.secretWillShow')" />
        </template>
        <template v-else>
          <el-form-item :label="t('users.role')" required>
            <el-radio-group v-model="form.role" :disabled="!!editing?.super_admin">
              <el-radio-button v-for="r in editing?.super_admin ? ['admin'] : ROLE_CODES" :key="r" :value="r">{{ roleLabel(r) }}</el-radio-button>
            </el-radio-group>
            <div class="muted hint">{{ t(`users.roleHint.${form.role}`) }}</div>
          </el-form-item>
          <el-form-item v-if="isScoped" :label="t('users.queryFactories')" required>
            <div class="query-factories">
              <el-select v-model="form.factory_codes" multiple filterable clearable collapse-tags collapse-tags-tooltip
                :max-collapse-tags="3" :placeholder="t('users.allFactoriesOption')">
                <el-option v-for="x in factories" :key="x.factory_code" :value="x.factory_code" :disabled="!x.enabled && !form.factory_codes.includes(x.factory_code)"
                  :label="`${x.factory_name}（${x.factory_code}）${x.enabled ? '' : t('common.disabledSuffix')}`" />
              </el-select>
              <el-button link type="primary" @click="form.factory_codes = enabledFactories.map((x) => x.factory_code)">{{ t("users.selectAll") }}</el-button>
            </div>
          </el-form-item>
          <el-form-item v-if="!editing" :label="t('users.initPwd')" required>
            <el-input v-model="form.password" type="password" show-password autocomplete="new-password" />
            <div class="muted hint">{{ t("password.policy") }}</div>
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ t("common.save") }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="secretOpen" :title="t('users.secretTitle')" width="520px" :close-on-click-modal="false">
      <el-alert type="info" :closable="false" show-icon :title="t('users.secretKeep')" class="mb" />
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item :label="t('users.empNo')"><span class="sn-mono">{{ secret.emp_no }}</span></el-descriptions-item>
        <el-descriptions-item :label="t('users.secret')">
          <span class="sn-mono secret">{{ secret.value }}</span>
          <el-button link type="primary" class="ml" @click="copy(secret.value, $event)">{{ t("common.copy") }}</el-button>
        </el-descriptions-item>
      </el-descriptions>
      <p class="muted">{{ t("users.secretUsage") }}</p>
      <template #footer>
        <el-button type="primary" @click="secretOpen = false">{{ t("users.secretClose") }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "@/api";
import { useAuthStore } from "@/stores/auth";
import { usePaged } from "@/composables/usePaged";
import { useTime } from "@/composables/useTime";
import { ROLE_CODES, roleLabel } from "@/constants";
import PageHead from "@/components/PageHead.vue";
import FactorySelect from "@/components/FactorySelect.vue";
import { useFactories } from "@/composables/useFactories";
import { copyText } from "@/utils/clipboard";

const { t } = useI18n();
const { fmt } = useTime();
const store = useAuthStore();
const { factories } = useFactories();
const PWD = /^(?=.*[A-Za-z])(?=.*\d)[A-Za-z0-9!@#$&*]{8,64}$/;
const tab = ref<"page" | "mes">("page");
const f = reactive({ q: "", role: "", status: "", factory_code: "" });
const dialog = ref(false);
const editing = ref<any>(null);
const saving = ref(false);
const EMPTY = { emp_no: "", name: "", role: "operator", factory_code: "", factory_codes: [] as string[], password: "", ip_whitelist: "" };
const form = reactive({ ...EMPTY, factory_codes: [] as string[] });
/** 账户页签只列页面账户，MES 接口账户页签只列 MES 接口账户（kind 由后端按角色区分）。 */
const { items, state, load } = usePaged<any>((q) => api.users({ ...f, kind: tab.value, role: tab.value === "mes" ? "" : f.role, ...q }));

const isMes = computed(() => form.role === "mes");
/** 普通操作员、审计员：授权一个或多个生产组织（至少一个）。 */
const isScoped = computed(() => form.role === "operator" || form.role === "auditor");
const enabledFactories = computed(() => factories.value.filter((x) => x.enabled));
/** MES 接口账户绑一个金蝶启用的生产组织（不要求已开启「对接 MES」，可先建账户联调）；已绑的被禁用后仍保留。 */
const mesFactories = computed(() => factories.value.filter((x) => x.enabled || x.factory_code === form.factory_code));
const dialogTitle = computed(() => {
  if (editing.value) return t("users.editTitle", { emp: editing.value.emp_no });
  return isMes.value ? t("users.createMes") : t("users.create");
});
const isLocked = (row: any) => !!row.locked_until && Date.parse(row.locked_until) > Date.now();

function onTab() {
  f.role = "";
  load(1);
}

function openCreate() {
  editing.value = null;
  Object.assign(form, { ...EMPTY, factory_codes: [], role: tab.value === "mes" ? "mes" : "operator" });
  dialog.value = true;
}

function openEdit(row: any) {
  editing.value = row;
  Object.assign(form, {
    emp_no: row.emp_no, name: row.name, role: row.role, factory_code: row.factory_code || "",
    factory_codes: row.role === "operator" || row.role === "auditor" ? [...(row.factory_codes || [])] : [],
    password: "", ip_whitelist: row.ip_whitelist || "",
  });
  dialog.value = true;
}

async function save() {
  if (!editing.value && !isMes.value && !PWD.test(form.password)) {
    ElMessage.error(t("password.policy"));
    return;
  }
  if (isScoped.value && !form.factory_codes.length) {
    ElMessage.error(t("users.allFactoriesOption"));
    return;
  }
  saving.value = true;
  // MES 接口账户绑一个组织；普通操作员、审计员授权一个或多个（至少一个）；系统管理员不绑
  const binding = {
    factory_code: isMes.value ? form.factory_code || null : null,
    factory_codes: isScoped.value ? form.factory_codes : [],
  };
  const extra = isMes.value ? { ip_whitelist: form.ip_whitelist } : {};
  try {
    if (editing.value) {
      await api.updateUser(editing.value.id, { name: form.name, role: form.role, ...binding, ...extra });
      ElMessage.success(t("common.saved"));
    } else {
      const r: any = await api.createUser({
        emp_no: form.emp_no, name: form.name, role: form.role, ...binding, ...extra, ...(isMes.value ? {} : { password: form.password }),
      });
      ElMessage.success(t("common.saved"));
      if (r?.secret) showSecret(r.emp_no, r.secret);
    }
    dialog.value = false;
    await load();
  } finally {
    saving.value = false;
  }
}

function factoryText(row: any) {
  const codes: string[] = row.factory_codes || [];
  if (!codes.length) return row.role === "admin" ? t("app.allFactories") : t("users.noFactory");
  return codes.map((c, i) => `${row.factory_names?.[i] || ""}（${c}）`).join("、");
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
    inputValidator: (v: string) => PWD.test(v || "") || t("password.policy"),
  });
  await api.resetPassword(row.id, value);
  ElMessage.success(t("users.resetDone"));
}

// ---------------- MES 接口账户密钥：创建、重置后弹出；之后可随时查看（留痕）或重置
const secretOpen = ref(false);
const secret = reactive({ emp_no: "", value: "" });

function showSecret(emp: string, value: string) {
  Object.assign(secret, { emp_no: emp, value });
  secretOpen.value = true;
}

async function viewSecret(row: any) {
  const r: any = await api.viewSecret(row.id);
  showSecret(r.emp_no, r.secret);
}

async function resetSecret(row: any) {
  await ElMessageBox.confirm(t("users.resetSecretConfirm", { emp: row.emp_no }), t("users.resetSecret"), { type: "warning" });
  const r: any = await api.resetSecret(row.id);
  showSecret(r.emp_no, r.secret);
  await load();
}

async function copy(text: string, e: MouseEvent) {
  // 临时文本框放在按钮旁（弹窗内），避开 el-dialog 的焦点陷阱
  const host = (e.currentTarget as HTMLElement | null)?.parentElement ?? undefined;
  if (await copyText(text, host)) ElMessage.success(t("common.copied"));
  else ElMessage.warning(t("common.copyFailed"));
}

onMounted(() => load(1));
</script>

<style scoped>
.ml { margin-left: 4px; }
.mb { margin-bottom: 12px; }
.w100 { width: 100%; }
.hint { width: 100%; line-height: 1.5; margin-top: 2px; }
.secret { word-break: break-all; user-select: all; }
.query-factories { display: flex; align-items: center; gap: 8px; width: 100%; }
.query-factories .el-select { flex: 1; }
</style>
