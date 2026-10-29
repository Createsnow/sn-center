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
            <!-- 版本历史：改格式出的版本一行一版（新→旧）；为别的 PI 换前缀另出的版本挂在来源版本下 -->
            <div class="versions">
              <div class="muted small">{{ t("rules.history", { n: row.versions.length }) }}</div>
              <div v-for="v in historyOf(row)" :key="v.id" class="ver">
                <span class="ver-no">
                  <b>v{{ v.version }}</b>
                  <el-tag v-if="v.version === row.rule.current_version" size="small" type="primary" disable-transitions>{{ t("rules.current") }}</el-tag>
                </span>
                <div class="ver-line">
                  <span class="sn-mono">{{ v.format }}</span>
                  <span v-if="v.charset !== CHARSETS[v.base]" class="muted">{{ t("rules.charset") }} <span class="sn-mono">{{ v.charset }}</span></span>
                  <el-tag size="small" :type="v.used ? 'success' : 'info'" disable-transitions>{{ v.used ? t("rules.used") : t("rules.notUsed") }}</el-tag>
                  <span v-if="v.claimed_pi" class="muted">{{ t("rules.claimedPi") }} {{ v.claimed_pi }}</span>
                  <span class="muted">{{ v.created_by }} · {{ fmt(v.created_at, false) }}</span>
                </div>
                <div v-if="v.copies.length" class="ver-line copies">
                  <span class="muted">{{ t("rules.copies") }}</span>
                  <el-tooltip v-for="c in v.copies" :key="c.id" :content="`${c.used ? t('rules.used') : t('rules.notUsed')} · ${c.created_by} · ${fmt(c.created_at, false)}`">
                    <span class="chip" :class="{ unused: !c.used }">v{{ c.version }} · {{ c.claimed_pi }} · <span class="sn-mono">{{ c.prefix }}</span></span>
                  </el-tooltip>
                </div>
              </div>
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
        <el-table-column :label="t('rules.owner')" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag v-if="!row.rule.factory_code" size="small" type="info" effect="plain">{{ t("rules.ownerGlobal") }}</el-tag>
            <span v-else>{{ factoryName(row.rule.factory_code) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('rules.version')" width="64"><template #default="{ row }">v{{ row.rule.current_version }}</template></el-table-column>
        <el-table-column :label="t('rules.format')" min-width="230">
          <template #default="{ row }"><span class="sn-mono">{{ row.current?.format }}</span></template>
        </el-table-column>
        <el-table-column :label="t('rules.withLength')" width="80">
          <template #default="{ row }">{{ row.current?.with_length ? t("common.yes") : t("common.no") }}</template>
        </el-table-column>
        <el-table-column :label="t('rules.withUnit')" width="80">
          <template #default="{ row }">{{ row.current?.with_unit ? t("common.yes") : t("common.no") }}</template>
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
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="130" fixed="right">
          <template #default="{ row }">
            <el-tooltip :disabled="editable(row)" :content="t('rules.globalReadonly')">
              <span><el-button link type="primary" :disabled="!editable(row)" @click="openEdit(row)">{{ t("common.edit") }}</el-button></span>
            </el-tooltip>
            <el-tooltip :disabled="editable(row) && deletable(row)" :content="editable(row) ? t('rules.deleteBlocked') : t('rules.globalReadonly')">
              <span class="ml"><el-button link type="danger" :disabled="!editable(row) || !deletable(row)" @click="remove(row)">{{ t("common.delete") }}</el-button></span>
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
        <!-- 所属组织：系统管理员可不选（全局规则）；普通操作员须选授权组织（只授权一个时固定为它）；建好后不能改 -->
        <el-form-item v-if="!editing" :label="t('rules.owner')" :required="!store.isAdmin">
          <FactorySelect v-model="form.factory_code" scoped :clearable="store.isAdmin" :disabled="!!store.boundFactory"
            :placeholder="store.isAdmin ? t('rules.ownerGlobal') : t('rules.owner')" />
          <div class="muted hint">{{ store.isAdmin ? t("rules.ownerHint") : t("rules.ownerOperatorHint") }}</div>
        </el-form-item>
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
        <!-- 按 SN 的组成顺序：前缀 → 流水 → 米数前分隔符 + 米数 + 米数后分隔符 → 单位 → 后缀 -->
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
        <el-form-item :label="t('rules.withLength')">
          <el-switch v-model="form.with_length" />
          <span class="muted ml">{{ t("rules.withLengthHint") }}</span>
        </el-form-item>
        <template v-if="form.with_length">
          <el-form-item :label="t('rules.sepBefore')">
            <el-input v-model.trim="form.sep_before" class="w160 sn-mono" maxlength="16" :placeholder="t('common.optional')" />
            <span class="muted ml">{{ t("rules.sepHint") }}</span>
          </el-form-item>
          <el-form-item :label="t('rules.lengthPad')">
            <el-input-number v-model="form.length_pad" :min="0" :max="32" />
            <div class="muted hint">{{ t("rules.lengthPadHint") }}</div>
          </el-form-item>
          <el-form-item :label="t('rules.sepAfter')">
            <el-input v-model.trim="form.sep_after" class="w160 sn-mono" maxlength="16" :placeholder="t('common.optional')" />
          </el-form-item>
        </template>
        <el-form-item :label="t('rules.withUnit')">
          <el-switch v-model="form.with_unit" />
          <span class="muted ml">{{ t("rules.withUnitHint") }}</span>
        </el-form-item>
        <el-form-item :label="t('rules.suffix')">
          <el-input v-model.trim="form.suffix" class="sn-mono" :placeholder="t('common.optional')" />
          <div class="muted hint">{{ t("rules.suffixHint") }}</div>
        </el-form-item>
        <el-form-item v-if="form.with_length" :label="t('rules.sampleLength')">
          <el-input v-model.trim="sampleLength" class="w160" placeholder="100" />
        </el-form-item>
        <el-form-item v-if="form.with_unit" :label="t('rules.sampleUnit')">
          <el-input v-model.trim="sampleUnit" class="w160" placeholder="M" />
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
import FactorySelect from "@/components/FactorySelect.vue";
import { BASES, CHARSETS } from "@/constants";
import { useTime } from "@/composables/useTime";
import { useFactories } from "@/composables/useFactories";
import { useAuthStore } from "@/stores/auth";

const { t } = useI18n();
const { fmt } = useTime();
const store = useAuthStore();
const { factories } = useFactories();
const factoryName = (code: string) => {
  const f = factories.value.find((x) => x.factory_code === code);
  return f ? `${f.factory_name}（${code}）` : code;
};
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
const sampleUnit = ref("");
/** 规则库流水位数默认 5（自定义规则默认 4，在生成页）。 */
const EMPTY = {
  rule_code: "", rule_name: "", factory_code: "", category_scope: "NONE", category_value: "", prefix: "", suffix: "", base: 10, seq_len: 5, charset: "",
  with_length: true, length_pad: 0, sep_before: "", sep_after: "", with_unit: false,
};
const form = reactive({ ...EMPTY });
const defaultCharset = computed(() => CHARSETS[form.base]);
const inCat = (s: string) => (s === "ALL" ? rows.value : rows.value.filter((r) => r.rule.category_scope === s));
const catValues = computed(() => [...new Set(inCat(cat.value).map((r) => r.rule.category_value as string))].filter(Boolean).sort());
const shown = computed(() => (catVal.value ? inCat(cat.value).filter((r) => r.rule.category_value === catVal.value) : inCat(cat.value)));
const countOf = (s: string) => inCat(s).length;
watch(cat, () => (catVal.value = ""));
/** 版本历史：改格式出的版本（新→旧），各带为别的 PI 换前缀另出的版本（derived_from 指向它）。 */
function historyOf(row: any) {
  const ids = new Set(row.versions.map((v: any) => v.id));
  return row.versions
    .filter((v: any) => !v.derived_from || !ids.has(v.derived_from))
    .map((v: any) => ({ ...v, copies: row.versions.filter((c: any) => c.derived_from === v.id) }));
}

/** 编辑时提示：格式不变 / 就地修改（当前版本没生成过号）/ 另出一版（生成过号，须换新前缀）。 */
const editHint = computed(() => {
  const c = editing.value?.current;
  if (!c) return "";
  const charset = form.charset || CHARSETS[form.base];
  // 不带米数时补 0 位数、分隔符不起作用（后端统一存空）
  const len = form.with_length;
  const changed = c.prefix !== form.prefix || c.suffix !== form.suffix || c.base !== form.base || c.seq_len !== form.seq_len
    || c.charset !== charset || !!c.with_length !== len || !!c.with_unit !== form.with_unit
    || (c.length_pad || 0) !== (len ? form.length_pad || 0 : 0)
    || (c.length_sep_before || "") !== (len ? form.sep_before : "") || (c.length_sep_after || "") !== (len ? form.sep_after : "");
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
  Object.assign(form, EMPTY, { factory_code: store.boundFactory });
  dialog.value = true;
}

function openEdit(row: any) {
  editing.value = row;
  const c = row.current;
  Object.assign(form, {
    rule_name: row.rule.rule_name, category_scope: row.rule.category_scope, category_value: row.rule.category_value,
    prefix: c.prefix, suffix: c.suffix, base: c.base, seq_len: c.seq_len, with_length: !!c.with_length,
    length_pad: c.length_pad || 0, sep_before: c.length_sep_before || "", sep_after: c.length_sep_after || "", with_unit: !!c.with_unit,
    charset: c.charset === CHARSETS[c.base] ? "" : c.charset,
  });
  dialog.value = true;
}

const spec = () => ({
  prefix: form.prefix, suffix: form.suffix, base: form.base, seq_len: form.seq_len, charset: form.charset, with_length: form.with_length,
  length_pad: form.length_pad || 0, sep_before: form.sep_before, sep_after: form.sep_after, with_unit: form.with_unit,
});

let sampleTimer: number | undefined;
watch(() => [form.prefix, form.suffix, form.base, form.seq_len, form.charset, form.with_length, form.length_pad, form.sep_before,
  form.sep_after, form.with_unit, sampleLength.value, sampleUnit.value, dialog.value], () => {
  window.clearTimeout(sampleTimer);
  if (!dialog.value) return;
  sampleTimer = window.setTimeout(async () => {
    try {
      sample.value = await api.previewRule({ ...spec(), length: sampleLength.value, unit: sampleUnit.value });
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
      await api.createRule({
        rule_code: form.rule_code, rule_name: form.rule_name, factory_code: form.factory_code || null, ...spec(), ...category(),
      });
      ElMessage.success(t("common.saved"));
    }
    dialog.value = false;
    await load();
  } finally {
    saving.value = false;
  }
}

/** 修改、删除：系统管理员不限；普通操作员只能改授权组织的规则，全局规则只能选用（后端同样校验）。 */
const editable = (row: any) => store.isAdmin || (!!row.rule.factory_code && store.canWrite(row.rule.factory_code));
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
.versions { padding: 4px 16px 12px 48px; display: flex; flex-direction: column; gap: 2px; font-size: 13px; }
.ver { display: grid; grid-template-columns: 96px minmax(0, 1fr); align-items: center; column-gap: 12px; row-gap: 4px;
  padding: 6px 0; border-bottom: 1px dashed var(--el-border-color-lighter); }
.ver:last-child { border-bottom: none; }
.ver-no { display: inline-flex; align-items: center; gap: 6px; }
.ver-line { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 12px; }
.copies { grid-column: 2; gap: 4px 6px; font-size: 12px; }
.chip { padding: 0 8px; line-height: 20px; border-radius: 10px; color: var(--app-text); background: var(--el-fill-color-light); border: 1px solid var(--el-border-color-lighter); }
.chip.unused { color: var(--app-muted); border-style: dashed; }
.samples { display: flex; flex-direction: column; gap: 4px; }
.sample-list { display: flex; flex-wrap: wrap; gap: 4px 12px; }
.sample-list .max { color: var(--el-color-warning); }
.err { color: var(--el-color-danger); font-size: 12px; }
</style>
