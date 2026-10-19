<template>
  <div class="page">
    <PageHead :title="t('rules.title')" :desc="t('rules.desc')">
      <el-button type="primary" @click="openCreate"><el-icon><Plus /></el-icon><span>{{ t("rules.create") }}</span></el-button>
    </PageHead>
    <div class="surface">
      <div class="toolbar">
        <el-radio-group v-model="cat">
          <el-radio-button v-for="s in FILTERS" :key="s" :value="s">
            {{ t(`rules.cat${s}`) }}<span class="cnt">{{ countOf(s) }}</span>
          </el-radio-button>
        </el-radio-group>
        <el-select v-if="cat === 'CUSTOMER' || cat === 'PI'" v-model="catVal" filterable clearable
          :placeholder="t(cat === 'CUSTOMER' ? 'rules.filterCustomer' : 'rules.filterPi')">
          <el-option v-for="v in catValues" :key="v" :value="v" :label="v" />
        </el-select>
        <el-input v-model="q" clearable :placeholder="t('rules.search')" prefix-icon="Search" @keyup.enter="load" @clear="load" />
        <el-button @click="load">{{ t("common.search") }}</el-button>
      </div>
      <el-table v-loading="loading" :data="shown" size="small" row-key="rule.id" :empty-text="t('common.empty')">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="versions">
              <div class="surface__title">{{ t("rules.versionsOf", { code: row.rule.rule_code }) }}</div>
              <el-table :data="row.versions" size="small" :empty-text="t('common.empty')">
                <el-table-column :label="t('rules.version')" width="70">
                  <template #default="{ row: v }">
                    <b>v{{ v.version }}</b>
                    <el-tag v-if="v.version === row.rule.current_version" size="small" type="primary" class="ml">{{ t("rules.current") }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column :label="t('rules.format')" min-width="220"><template #default="{ row: v }"><span class="sn-mono">{{ v.format }}</span></template></el-table-column>
                <el-table-column :label="t('rules.charset')" min-width="200" show-overflow-tooltip>
                  <template #default="{ row: v }"><span class="sn-mono">{{ v.charset }}</span></template>
                </el-table-column>
                <el-table-column :label="t('rules.claimedPi')" min-width="120"><template #default="{ row: v }">{{ v.claimed_pi || "—" }}</template></el-table-column>
                <el-table-column :label="t('rules.derivedFrom')" width="110">
                  <template #default="{ row: v }">{{ v.derived_from ? `v${versionNo(row, v.derived_from)}` : "—" }}</template>
                </el-table-column>
                <el-table-column :label="t('rules.used')" width="80">
                  <template #default="{ row: v }"><el-tag size="small" :type="v.used ? 'success' : 'info'">{{ v.used ? t("common.yes") : t("common.no") }}</el-tag></template>
                </el-table-column>
                <el-table-column :label="t('rules.createdBy')" min-width="150">
                  <template #default="{ row: v }">{{ v.created_by }} · {{ fmt(v.created_at, false) }}</template>
                </el-table-column>
              </el-table>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('rules.code')" min-width="120"><template #default="{ row }"><b>{{ row.rule.rule_code }}</b></template></el-table-column>
        <el-table-column :label="t('rules.name')" min-width="140" show-overflow-tooltip><template #default="{ row }">{{ row.rule.rule_name }}</template></el-table-column>
        <el-table-column :label="t('rules.category')" min-width="140">
          <template #default="{ row }">
            <el-tag size="small" :type="row.rule.category_scope === 'PI' ? 'danger' : row.rule.category_scope === 'CUSTOMER' ? 'warning' : 'info'">
              {{ t(`rules.cat${row.rule.category_scope}`) }}
            </el-tag>
            <span class="ml">{{ row.rule.category_value }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('rules.version')" width="64"><template #default="{ row }">v{{ row.rule.current_version }}</template></el-table-column>
        <el-table-column :label="t('rules.format')" min-width="230">
          <template #default="{ row }"><span class="sn-mono">{{ row.current?.format }}</span></template>
        </el-table-column>
        <el-table-column :label="t('rules.withLength')" width="80">
          <template #default="{ row }">{{ row.current?.with_length ? t("common.yes") : t("common.no") }}</template>
        </el-table-column>
        <el-table-column :label="t('rules.used')" width="80">
          <template #default="{ row }"><el-tag size="small" :type="row.used ? 'success' : 'info'">{{ row.used ? t("common.yes") : t("common.no") }}</el-tag></template>
        </el-table-column>
        <el-table-column :label="t('rules.pis')" min-width="140" show-overflow-tooltip>
          <template #default="{ row }"><span v-if="row.pis.length">{{ row.pis.join("、") }}</span><span v-else class="muted">—</span></template>
        </el-table-column>
        <el-table-column :label="t('rules.bindings')" min-width="160">
          <template #default="{ row }">
            <div v-if="row.bindings.pi.length" class="small">PI：{{ row.bindings.pi.join("、") }}</div>
            <div v-if="row.bindings.customer.length" class="small">{{ t("common.customer") }}：{{ row.bindings.customer.join("、") }}</div>
            <span v-if="!row.bindings.pi.length && !row.bindings.customer.length" class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">{{ t("common.edit") }}</el-button>
            <el-tooltip :disabled="deletable(row)" :content="t('rules.deleteBlocked')">
              <span class="ml"><el-button link type="danger" :disabled="!deletable(row)" @click="remove(row)">{{ t("common.delete") }}</el-button></span>
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" :title="editing ? t('rules.editTitle', { code: editing.rule.rule_code }) : t('rules.create')" width="660px">
      <el-alert v-if="editing && editHint" :type="editHint === 'NEW_VERSION' ? 'warning' : 'info'" :closable="false" show-icon class="mb"
        :title="t(`rules.hint${editHint}`)" />
      <el-form label-width="120px">
        <el-form-item v-if="!editing" :label="t('rules.code')" required>
          <el-input v-model.trim="form.rule_code" :placeholder="t('rules.codeHint')" />
        </el-form-item>
        <el-form-item :label="t('rules.name')" required><el-input v-model="form.rule_name" maxlength="128" /></el-form-item>
        <el-form-item :label="t('rules.category')">
          <el-radio-group v-model="form.category_scope">
            <el-radio-button v-for="s in CATEGORIES" :key="s" :value="s">{{ t(`rules.cat${s}`) }}</el-radio-button>
          </el-radio-group>
          <div class="muted hint">{{ t("rules.categoryHint") }}</div>
        </el-form-item>
        <el-form-item v-if="form.category_scope === 'CUSTOMER'" :label="t('common.customer')" required>
          <el-select v-model="form.category_value" filterable allow-create default-first-option remote :remote-method="searchCustomers" class="w100">
            <el-option v-for="c in customers" :key="c" :value="c" :label="c" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.category_scope === 'PI'" label="PI" required><el-input v-model.trim="form.category_value" /></el-form-item>
        <el-form-item :label="t('rules.prefix')" required>
          <el-input v-model.trim="form.prefix" class="sn-mono" />
          <div class="muted hint">{{ t("rules.prefixHint") }}</div>
        </el-form-item>
        <el-form-item :label="t('rules.withLength')">
          <el-switch v-model="form.with_length" />
          <span class="muted ml">{{ t("rules.withLengthHint") }}</span>
        </el-form-item>
        <el-form-item :label="t('rules.base')">
          <el-radio-group v-model="form.base" @change="form.charset = ''">
            <el-radio-button v-for="b in BASES" :key="b" :value="b">{{ b }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="t('rules.seqLen')"><el-input-number v-model="form.seq_len" :min="1" :max="12" /></el-form-item>
        <el-form-item :label="t('rules.charset')">
          <el-input v-model.trim="form.charset" :placeholder="defaultCharset" class="sn-mono" />
          <div class="muted hint">{{ t("rules.charsetHint") }}</div>
        </el-form-item>
        <el-form-item :label="t('rules.suffix')">
          <el-input v-model.trim="form.suffix" class="sn-mono" :placeholder="t('common.optional')" />
          <div class="muted hint">{{ t("rules.suffixHint") }}</div>
        </el-form-item>
        <el-form-item :label="t('rules.sampleLength')">
          <el-input v-model.trim="sampleLength" class="w160" placeholder="100" />
        </el-form-item>
        <el-form-item :label="t('rules.sample')">
          <div v-if="sample" class="samples">
            <div class="muted">{{ sample.format }}</div>
            <div class="sample-list">
              <span v-for="(s, i) in sample.samples" :key="s.seq_dec" class="sn-mono" :class="{ max: i === sample.samples.length - 1 && sample.max_seq > 5 }">{{ s.sn }}</span>
            </div>
            <div class="muted">{{ t("rules.maxSeq", { n: Number(sample.max_seq).toLocaleString() }) }}</div>
          </div>
          <span v-else class="err">{{ sampleError }}</span>
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
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "@/api";
import PageHead from "@/components/PageHead.vue";
import { BASES, CHARSETS } from "@/constants";
import { useTime } from "@/composables/useTime";

const { t } = useI18n();
const { fmt } = useTime();
/** 分类：只用于筛选和推荐，不影响生效（绑定在生成页做，按客户绑定只能走接口）。 */
const CATEGORIES = ["NONE", "CUSTOMER", "PI"];
const FILTERS = ["ALL", ...CATEGORIES];
const rows = ref<any[]>([]);
const loading = ref(false);
const cat = ref("ALL");
const q = ref("");
const catVal = ref("");
const dialog = ref(false);
const editing = ref<any>(null);
const saving = ref(false);
const customers = ref<string[]>([]);
const sample = ref<any>(null);
const sampleError = ref("");
const sampleLength = ref("");
/** 规则库流水位数默认 5（自定义规则默认 6，在生成页）。 */
const EMPTY = { rule_code: "", rule_name: "", category_scope: "NONE", category_value: "", prefix: "", suffix: "", base: 10, seq_len: 5, charset: "", with_length: true };
const form = reactive({ ...EMPTY });
const defaultCharset = computed(() => CHARSETS[form.base]);
const inCat = (s: string) => (s === "ALL" ? rows.value : rows.value.filter((r) => r.rule.category_scope === s));
const catValues = computed(() => [...new Set(inCat(cat.value).map((r) => r.rule.category_value as string))].filter(Boolean).sort());
const shown = computed(() => (catVal.value ? inCat(cat.value).filter((r) => r.rule.category_value === catVal.value) : inCat(cat.value)));
const countOf = (s: string) => inCat(s).length;
watch(cat, () => (catVal.value = ""));
const versionNo = (row: any, id: number) => row.versions.find((v: any) => v.id === id)?.version ?? "?";

/** 编辑时提示：格式不变 / 就地修改（当前版本没生成过号）/ 另出一版（生成过号，须换新前缀）。 */
const editHint = computed(() => {
  const c = editing.value?.current;
  if (!c) return "";
  const charset = form.charset || CHARSETS[form.base];
  const changed = c.prefix !== form.prefix || c.suffix !== form.suffix || c.base !== form.base || c.seq_len !== form.seq_len
    || c.charset !== charset || !!c.with_length !== form.with_length;
  if (!changed) return c.used ? "USED" : "";
  return c.used ? "NEW_VERSION" : "IN_PLACE";
});

async function load() {
  loading.value = true;
  try {
    rows.value = await api.rules({ q: q.value });
  } finally {
    loading.value = false;
  }
}

async function searchCustomers(text: string) {
  customers.value = await api.orderCustomers(text);
}

function openCreate() {
  editing.value = null;
  Object.assign(form, EMPTY);
  dialog.value = true;
}

function openEdit(row: any) {
  editing.value = row;
  const c = row.current;
  Object.assign(form, {
    rule_name: row.rule.rule_name, category_scope: row.rule.category_scope, category_value: row.rule.category_value,
    prefix: c.prefix, suffix: c.suffix, base: c.base, seq_len: c.seq_len, with_length: !!c.with_length,
    charset: c.charset === CHARSETS[c.base] ? "" : c.charset,
  });
  dialog.value = true;
}

const spec = () => ({ prefix: form.prefix, suffix: form.suffix, base: form.base, seq_len: form.seq_len, charset: form.charset, with_length: form.with_length });

let sampleTimer: number | undefined;
watch(() => [form.prefix, form.suffix, form.base, form.seq_len, form.charset, form.with_length, sampleLength.value, dialog.value], () => {
  window.clearTimeout(sampleTimer);
  if (!dialog.value) return;
  sampleTimer = window.setTimeout(async () => {
    try {
      sample.value = await api.previewRule({ ...spec(), length: sampleLength.value });
      sampleError.value = "";
    } catch (e: any) {
      sample.value = null;
      sampleError.value = e?.message || "";
    }
  }, 250);
});

const category = () => ({ category_scope: form.category_scope, category_value: form.category_scope === "NONE" ? "" : form.category_value });

async function save() {
  saving.value = true;
  try {
    if (editing.value) {
      const r: any = await api.updateRule(editing.value.rule.id, { rule_name: form.rule_name, ...spec(), ...category() });
      ElMessage.success(t(`rules.result${r.action}`, { v: r.rule?.rule?.current_version }));
    } else {
      await api.createRule({ rule_code: form.rule_code, rule_name: form.rule_name, ...spec(), ...category() });
      ElMessage.success(t("common.saved"));
    }
    dialog.value = false;
    await load();
  } finally {
    saving.value = false;
  }
}

/** 只能删从没生成过号、也没指定给任何 PI 的规则（后端同样校验）。 */
const deletable = (row: any) => !row.used && !row.pis.length && !row.bindings.pi.length;

async function remove(row: any) {
  const r = row.rule;
  await ElMessageBox.confirm(t("rules.deleteConfirm", { code: r.rule_code, name: r.rule_name }), t("common.delete"), {
    type: "warning", confirmButtonText: t("common.delete"), cancelButtonText: t("common.cancel"), confirmButtonClass: "el-button--danger",
  });
  await api.deleteRule(r.id);
  ElMessage.success(t("rules.deleted", { code: r.rule_code }));
  await load();
}

onMounted(load);
</script>

<style scoped>
.ml { margin-left: 6px; }
.mb { margin-bottom: 12px; }
.small { font-size: 12px; }
.hint { width: 100%; line-height: 1.5; margin-top: 2px; }
.cnt { margin-left: 6px; font-size: 12px; opacity: 0.75; }
.w100 { width: 100%; }
.w160 { width: 160px; }
.versions { padding: 4px 16px 12px 48px; }
.samples { display: flex; flex-direction: column; gap: 4px; }
.sample-list { display: flex; flex-wrap: wrap; gap: 4px 12px; }
.sample-list .max { color: var(--el-color-warning); }
.err { color: var(--el-color-danger); font-size: 12px; }
</style>
