<template>
  <div class="page">
    <PageHead :title="t('rules.title')" :desc="t('rules.desc')">
      <el-button type="primary" @click="openCreate"><el-icon><Plus /></el-icon><span>{{ t("rules.create") }}</span></el-button>
    </PageHead>
    <div class="surface">
      <div class="toolbar">
        <el-select v-model="scope" clearable :placeholder="t('rules.bind')" @change="load">
          <el-option v-for="s in SCOPES" :key="s" :value="s" :label="t(`rules.scope${s}`)" />
        </el-select>
        <el-input v-model="q" clearable :placeholder="t('rules.search')" prefix-icon="Search" @keyup.enter="load" @clear="load" />
      </div>
      <el-table v-loading="loading" :data="rows" size="small" :empty-text="t('common.empty')">
        <el-table-column :label="t('rules.code')" min-width="120"><template #default="{ row }"><b>{{ row.rule.rule_code }}</b></template></el-table-column>
        <el-table-column :label="t('rules.name')" min-width="140"><template #default="{ row }">{{ row.rule.rule_name }}</template></el-table-column>
        <el-table-column :label="t('rules.bind')" min-width="160">
          <template #default="{ row }">
            <el-tag size="small" :type="row.rule.bind_scope === 'PI' ? 'danger' : row.rule.bind_scope === 'CUSTOMER' ? 'warning' : 'info'">
              {{ t(`rules.scope${row.rule.bind_scope}`) }}
            </el-tag>
            <span class="ml">{{ row.rule.bind_value }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('rules.version')" width="70"><template #default="{ row }">v{{ row.rule.current_version }}</template></el-table-column>
        <el-table-column :label="t('rules.format')" min-width="170">
          <template #default="{ row }"><span class="sn-mono">{{ row.current.prefix }}<i>{{ "#".repeat(row.current.seq_len) }}</i>{{ row.current.suffix }}</span></template>
        </el-table-column>
        <el-table-column :label="t('rules.base')" width="70"><template #default="{ row }">{{ row.current.base }}</template></el-table-column>
        <el-table-column :label="t('rules.charset')" min-width="180" show-overflow-tooltip>
          <template #default="{ row }"><span class="sn-mono">{{ row.current.charset }}</span></template>
        </el-table-column>
        <el-table-column :label="t('rules.used')" width="80">
          <template #default="{ row }"><el-tag size="small" :type="row.current.used ? 'success' : 'info'">{{ row.current.used ? t("common.yes") : t("common.no") }}</el-tag></template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">{{ t("common.edit") }}</el-button>
            <el-button link @click="versions = row">{{ t("rules.versions") }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" :title="editing ? t('rules.editTitle', { code: editing.rule.rule_code }) : t('rules.create')" width="640px">
      <el-alert v-if="editing" type="info" :closable="false" show-icon class="mb"
        :title="editing.current.used ? t('rules.editUsed') : t('rules.editUnused')" />
      <el-form label-width="110px">
        <el-form-item v-if="!editing" :label="t('rules.code')" required><el-input v-model="form.rule_code" /></el-form-item>
        <el-form-item :label="t('rules.name')" required><el-input v-model="form.rule_name" /></el-form-item>
        <el-form-item :label="t('rules.bind')" required>
          <el-radio-group v-model="form.bind_scope">
            <el-radio-button v-for="s in SCOPES" :key="s" :value="s">{{ t(`rules.scope${s}`) }}</el-radio-button>
          </el-radio-group>
          <div class="muted">{{ t("rules.bindHint") }}</div>
        </el-form-item>
        <el-form-item v-if="form.bind_scope === 'CUSTOMER'" :label="t('common.customer')" required>
          <el-select v-model="form.bind_value" filterable allow-create remote :remote-method="searchCustomers" class="w100">
            <el-option v-for="c in customers" :key="c" :value="c" :label="c" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.bind_scope === 'PI'" label="PI" required><el-input v-model="form.bind_value" /></el-form-item>
        <el-form-item :label="t('rules.prefix')"><el-input v-model="form.prefix" /></el-form-item>
        <el-form-item :label="t('rules.suffix')"><el-input v-model="form.suffix" /></el-form-item>
        <el-form-item :label="t('rules.base')">
          <el-radio-group v-model="form.base" @change="form.charset = ''">
            <el-radio-button v-for="b in [10, 16, 32, 36]" :key="b" :value="b">{{ b }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="t('rules.seqLen')"><el-input-number v-model="form.seq_len" :min="1" :max="12" /></el-form-item>
        <el-form-item :label="t('rules.charset')">
          <el-input v-model="form.charset" :placeholder="defaultCharset" class="sn-mono" />
          <div class="muted">{{ t("rules.charsetHint") }}</div>
        </el-form-item>
        <el-form-item :label="t('rules.sample')">
          <div v-if="sample" class="samples">
            <span v-for="s in sample.samples" :key="s.seq_dec" class="sn-mono">{{ s.sn }}</span>
            <span class="muted">{{ t("rules.maxSeq") }} {{ sample.max_seq.toLocaleString() }}</span>
          </div>
          <span v-else class="muted">{{ sampleError }}</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ t("common.save") }}</el-button>
      </template>
    </el-dialog>

    <el-drawer :model-value="!!versions" :title="t('rules.versionsOf', { code: versions?.rule.rule_code })" size="560px" @close="versions = null">
      <el-timeline v-if="versions">
        <el-timeline-item v-for="v in versions.versions" :key="v.id" :timestamp="v.created_at" :type="v.version === versions.rule.current_version ? 'primary' : undefined">
          <b>v{{ v.version }}</b>
          <span class="sn-mono ml">{{ v.prefix }}<i>{{ "#".repeat(v.seq_len) }}</i>{{ v.suffix }}</span>
          <span class="muted ml">{{ t("rules.base") }} {{ v.base }} · {{ v.created_by }}</span>
          <el-tag v-if="v.used" size="small" type="success" class="ml">{{ t("rules.used") }}</el-tag>
        </el-timeline-item>
      </el-timeline>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import { api } from "@/api";
import PageHead from "@/components/PageHead.vue";

const { t } = useI18n();
const SCOPES = ["GENERAL", "CUSTOMER", "PI"];
const CHARSETS: Record<number, string> = {
  10: "0123456789",
  16: "0123456789ABCDEF",
  32: "0123456789ABCDEFGHIJKLMNOPQRSTUV",
  36: "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ",
};
const rows = ref<any[]>([]);
const loading = ref(false);
const scope = ref("");
const q = ref("");
const dialog = ref(false);
const editing = ref<any>(null);
const saving = ref(false);
const versions = ref<any>(null);
const customers = ref<string[]>([]);
const sample = ref<any>(null);
const sampleError = ref("");
const form = reactive({ rule_code: "", rule_name: "", bind_scope: "GENERAL", bind_value: "", prefix: "", suffix: "", base: 10, seq_len: 5, charset: "" });
const defaultCharset = computed(() => CHARSETS[form.base]);

async function load() {
  loading.value = true;
  try {
    rows.value = await api.rules({ scope: scope.value, q: q.value });
  } finally {
    loading.value = false;
  }
}

async function searchCustomers(text: string) {
  customers.value = await api.orderCustomers(text);
}

function openCreate() {
  editing.value = null;
  Object.assign(form, { rule_code: "", rule_name: "", bind_scope: "GENERAL", bind_value: "", prefix: "", suffix: "", base: 10, seq_len: 5, charset: "" });
  dialog.value = true;
}

function openEdit(row: any) {
  editing.value = row;
  const c = row.current;
  Object.assign(form, { rule_name: row.rule.rule_name, bind_scope: row.rule.bind_scope, bind_value: row.rule.bind_value, prefix: c.prefix, suffix: c.suffix, base: c.base, seq_len: c.seq_len,
    charset: c.charset === CHARSETS[c.base] ? "" : c.charset });
  dialog.value = true;
}

let sampleTimer: number | undefined;
watch(() => [form.prefix, form.suffix, form.base, form.seq_len, form.charset, dialog.value], () => {
  window.clearTimeout(sampleTimer);
  if (!dialog.value) return;
  sampleTimer = window.setTimeout(async () => {
    try {
      sample.value = await api.previewRule({ prefix: form.prefix, suffix: form.suffix, base: form.base, seq_len: form.seq_len, charset: form.charset, start: 1 });
      sampleError.value = "";
    } catch (e: any) {
      sample.value = null;
      sampleError.value = e?.message || "";
    }
  }, 250);
});

async function save() {
  saving.value = true;
  try {
    if (editing.value) {
      const r: any = await api.updateRule(editing.value.rule.id, { rule_name: form.rule_name, prefix: form.prefix, suffix: form.suffix,
        base: form.base, seq_len: form.seq_len, charset: form.charset,
        bind_scope: form.bind_scope, bind_value: form.bind_scope === "GENERAL" ? "" : form.bind_value });
      ElMessage.success(t(`rules.result${r.action}`));
    } else {
      await api.createRule({ ...form, bind_value: form.bind_scope === "GENERAL" ? "" : form.bind_value });
      ElMessage.success(t("common.saved"));
    }
    dialog.value = false;
    await load();
  } finally {
    saving.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.ml { margin-left: 6px; }
.mb { margin-bottom: 12px; }
.w100 { width: 100%; }
.samples { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
i { font-style: normal; color: var(--app-muted); }
</style>
