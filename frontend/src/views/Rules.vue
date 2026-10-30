<template>
  <div class="page">
    <PageHead :title="t('rules.title')" :desc="t('rules.desc')">
      <el-button type="primary" @click="openCreate"><el-icon><Plus /></el-icon><span>{{ t("rules.create") }}</span></el-button>
    </PageHead>
    <div class="surface">
      <div class="toolbar">
        <el-select v-model="piFilter" filterable clearable :placeholder="t('rules.filterPi')">
          <el-option v-for="v in piValues" :key="v" :value="v" :label="v" />
        </el-select>
        <el-input v-model="q" clearable :placeholder="t('rules.search')" prefix-icon="Search" @keyup.enter="search" @clear="search" />
        <el-button @click="search">{{ t("common.search") }}</el-button>
      </div>
      <!-- 点规则行在右侧抽屉看示例号；操作列的按钮不触发 -->
      <el-table v-loading="loading" :data="paged" size="small" row-key="rule.id" row-class-name="clickable" highlight-current-row
        :empty-text="t('common.empty')" @row-click="openDetail">
        <el-table-column :label="t('rules.name')" min-width="160" show-overflow-tooltip><template #default="{ row }">{{ row.rule.rule_name }}</template></el-table-column>
        <el-table-column :label="t('rules.version')" width="64"><template #default="{ row }">v{{ row.rule.current_version }}</template></el-table-column>
        <el-table-column label="PI" min-width="120" show-overflow-tooltip>
          <template #default="{ row }"><span v-if="piOf(row)">{{ piOf(row) }}</span><span v-else class="muted">—</span></template>
        </el-table-column>
        <el-table-column :label="t('rules.base')" width="72"><template #default="{ row }">{{ row.current?.base }}</template></el-table-column>
        <el-table-column :label="t('rules.seqCol')" width="88"><template #default="{ row }">{{ row.current?.seq_len }}</template></el-table-column>
        <el-table-column :label="t('rules.withLength')" width="80">
          <template #default="{ row }">{{ row.current?.with_length ? t("common.yes") : t("common.no") }}</template>
        </el-table-column>
        <el-table-column :label="t('rules.withUnit')" width="80">
          <template #default="{ row }">{{ row.current?.with_unit ? t("common.yes") : t("common.no") }}</template>
        </el-table-column>
        <el-table-column :label="t('rules.used')" width="80">
          <template #default="{ row }"><el-tag size="small" :type="row.used ? 'success' : 'info'">{{ row.used ? t("common.yes") : t("common.no") }}</el-tag></template>
        </el-table-column>
        <el-table-column :label="t('rules.factory')" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag v-if="!row.rule.factory_code" size="small" type="info" effect="plain">{{ t("rules.ownerGlobal") }}</el-tag>
            <span v-else>{{ factoryName(row.rule.factory_code) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="130" fixed="right">
          <template #default="{ row }">
            <span @click.stop>
            <el-tooltip :disabled="editable(row)" :content="t('rules.globalReadonly')">
              <span><el-button link type="primary" :disabled="!editable(row)" @click="openEdit(row)">{{ t("common.edit") }}</el-button></span>
            </el-tooltip>
            <el-tooltip :disabled="editable(row) && deletable(row)" :content="editable(row) ? t('rules.deleteBlocked') : t('rules.globalReadonly')">
              <span class="ml"><el-button link type="danger" :disabled="!editable(row) || !deletable(row)" @click="remove(row)">{{ t("common.delete") }}</el-button></span>
            </el-tooltip>
            </span>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="shown.length" :page-sizes="[20, 50, 100]"
        v-model:current-page="page" v-model:page-size="pageSize" />
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
        <!-- 规则只绑定 PI：新建必填；早先按未分类 / 客户分类的规则修改时须补填 -->
        <el-form-item label="PI" required>
          <el-input v-model.trim="form.pi" maxlength="64" />
          <div class="muted hint">{{ t("rules.piHint") }}</div>
        </el-form-item>
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

    <!-- 规则示例号：当前版本的前 5 个和最大号，米数、产品单位用示例值 -->
    <el-drawer :model-value="!!detail.row" size="900px" @close="detail.row = null">
      <template #header>
        <div v-if="detail.row" class="dh">
          <b>{{ detail.row.rule.rule_name }}</b>
          <el-tag size="small" effect="plain">v{{ detail.row.rule.current_version }}</el-tag>
          <span v-if="piOf(detail.row)" class="muted">PI {{ piOf(detail.row) }}</span>
        </div>
      </template>
      <template v-if="detail.row">
        <div v-if="detail.row.current" class="muted small mb">{{ detail.row.current.format }}</div>
        <el-table v-loading="detail.loading" :data="detail.items" size="small" :empty-text="detail.error || t('common.empty')">
          <el-table-column :label="t('rules.sampleSn')" min-width="200">
            <template #default="{ row }"><span class="sn-mono" :class="{ max: row.max }">{{ row.sn }}</span></template>
          </el-table-column>
          <el-table-column :label="t('rules.prefix')" min-width="100" show-overflow-tooltip>
            <template #default="{ row }"><span class="sn-mono">{{ row.prefix }}</span></template>
          </el-table-column>
          <el-table-column :label="t('rules.seqNo')" min-width="100"><template #default="{ row }"><span class="sn-mono">{{ row.seq_text }}</span></template></el-table-column>
          <el-table-column :label="t('rules.seqDigits')" width="80"><template #default="{ row }">{{ row.seq_len }}</template></el-table-column>
          <el-table-column :label="t('rules.base')" width="64"><template #default="{ row }">{{ row.base }}</template></el-table-column>
          <el-table-column :label="t('common.length')" width="80"><template #default="{ row }">{{ row.length || "—" }}</template></el-table-column>
          <el-table-column :label="t('common.unit')" width="80"><template #default="{ row }">{{ row.unit || "—" }}</template></el-table-column>
          <el-table-column :label="t('rules.suffixCol')" min-width="90" show-overflow-tooltip>
            <template #default="{ row }"><span v-if="row.suffix" class="sn-mono">{{ row.suffix }}</span><span v-else class="muted">—</span></template>
          </el-table-column>
        </el-table>
        <div class="muted small mt">{{ t("rules.detailNote") }}</div>
      </template>
    </el-drawer>
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
import { useFactories } from "@/composables/useFactories";
import { useAuthStore } from "@/stores/auth";

const { t } = useI18n();
const store = useAuthStore();
const { factories } = useFactories();
const factoryName = (code: string) => {
  const f = factories.value.find((x) => x.factory_code === code);
  return f ? `${f.factory_name}（${code}）` : code;
};
const rows = ref<any[]>([]);
const loading = ref(false);
const q = ref("");
const piFilter = ref("");
const page = ref(1);
const pageSize = ref(20);
const dialog = ref(false);
const editing = ref<any>(null);
const saving = ref(false);
const sample = ref<any>(null);
const sampleError = ref("");
const sampleLength = ref("");
const sampleUnit = ref("");
/** 规则库流水位数默认 5（自定义规则默认 4，在生成页）。 */
const EMPTY = {
  rule_code: "", rule_name: "", factory_code: "", pi: "", prefix: "", suffix: "", base: 10, seq_len: 5, charset: "",
  with_length: true, length_pad: 0, sep_before: "", sep_after: "", with_unit: false,
};
const form = reactive({ ...EMPTY });
const defaultCharset = computed(() => CHARSETS[form.base]);
/** 规则绑定的 PI；早先按未分类 / 客户分类的规则没有 PI。 */
const piOf = (row: any): string => (row.rule.category_scope === "PI" ? row.rule.category_value : "");
const piValues = computed(() => [...new Set(rows.value.map(piOf))].filter(Boolean).sort());
const shown = computed(() => (piFilter.value ? rows.value.filter((r) => piOf(r) === piFilter.value) : rows.value));
/** 默认分页列出全部规则（前端分页）；改 PI 筛选回第一页，条数变少时页码不越界。 */
const paged = computed(() => shown.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value));
watch(piFilter, () => (page.value = 1));
watch(() => shown.value.length, (n) => (page.value = Math.min(page.value, Math.max(1, Math.ceil(n / pageSize.value)))));
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

function search() {
  page.value = 1;
  load();
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
    rule_name: row.rule.rule_name, pi: piOf(row),
    prefix: c.prefix, suffix: c.suffix, base: c.base, seq_len: c.seq_len, with_length: !!c.with_length,
    length_pad: c.length_pad || 0, sep_before: c.length_sep_before || "", sep_after: c.length_sep_after || "", with_unit: !!c.with_unit,
    charset: c.charset === CHARSETS[c.base] ? "" : c.charset,
  });
  dialog.value = true;
}

/** 版本的编码参数（与编辑表单提交的一致），用于取示例号。 */
const specOf = (c: any) => ({
  prefix: c.prefix, suffix: c.suffix, base: c.base, seq_len: c.seq_len, charset: c.charset, with_length: !!c.with_length,
  length_pad: c.length_pad || 0, sep_before: c.length_sep_before || "", sep_after: c.length_sep_after || "", with_unit: !!c.with_unit,
});

/** 点规则行：右侧抽屉列出当前版本的示例号（前 5 个和最大号），各行拆出前缀、流水号、米数、产品单位、后缀。 */
const detail = reactive({ row: null as any, loading: false, items: [] as any[], error: "" });
async function openDetail(row: any) {
  Object.assign(detail, { row, items: [], error: "" });
  const c = row.current;
  if (!c) return;
  detail.loading = true;
  try {
    const r: any = await api.previewRule(specOf(c));
    if (detail.row !== row) return; // 等结果时已改看别的规则
    const last = r.samples.length - 1;
    detail.items = r.samples.map((s: any, i: number) => ({
      ...s, prefix: c.prefix, suffix: c.suffix, base: c.base, seq_len: c.seq_len, max: i === last && r.max_seq > 5,
      length: c.with_length ? r.sample_length : "", unit: c.with_unit ? r.sample_unit : "",
    }));
  } catch (e: any) {
    if (detail.row === row) detail.error = e?.message || "";
  } finally {
    if (detail.row === row) detail.loading = false;
  }
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

const category = () => ({ category_scope: "PI", category_value: form.pi });

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
.mt { margin-top: 10px; }
.small { font-size: 12px; }
.dh { display: flex; align-items: center; gap: 8px; }
.max { color: var(--el-color-warning); }
:deep(.clickable) { cursor: pointer; }
.hint { width: 100%; line-height: 1.5; margin-top: 2px; }
.w160 { width: 160px; }
.samples { display: flex; flex-direction: column; gap: 4px; }
.sample-list { display: flex; flex-wrap: wrap; gap: 4px 12px; }
.sample-list .max { color: var(--el-color-warning); }
.err { color: var(--el-color-danger); font-size: 12px; }
</style>
