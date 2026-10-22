<template>
  <el-dialog :model-value="modelValue" :title="t('generate.rule.dialogTitle')" width="640px" @update:model-value="(v: boolean) => emit('update:modelValue', v)" @open="onOpen">
    <div class="targets muted">
      {{ t("generate.rule.targets", { n: lines.length }) }}
      <span v-for="l in lines.slice(0, 6)" :key="`${l.bill_no}#${l.seq}`" class="tg">{{ l.bill_no }} / {{ l.seq }} · {{ l.material_code || "—" }}</span>
      <span v-if="lines.length > 6">…</span>
    </div>
    <el-radio-group v-model="mode" class="mb" @change="(v: any) => v === 'CUSTOM' && schedule()">
      <el-radio-button value="LIBRARY" :disabled="!options.length">{{ t("generate.rule.library") }}</el-radio-button>
      <el-radio-button value="CUSTOM">{{ t("generate.rule.custom") }}</el-radio-button>
    </el-radio-group>

    <template v-if="mode === 'LIBRARY'">
      <el-input v-model="kw" :placeholder="t('generate.rule.searchLib')" clearable prefix-icon="Search" class="mb" />
      <div class="rule-list">
        <div v-for="o in filtered" :key="o.rule_id" class="it" :class="{ on: picked?.rule_id === o.rule_id }" tabindex="0"
          @click="pick(o)" @keyup.enter="pick(o)">
          <span class="radio" />
          <span class="nm">
            <el-tag v-if="o.recommended" size="small" type="success" effect="dark">{{ t("generate.rule.recommended") }}</el-tag>
            <b>{{ o.rule_code }}</b><span class="muted">{{ o.rule_name }}</span>
          </span>
          <span class="meta">
            <span class="sn-mono">{{ o.format || "—" }}</span>
            <el-tag v-if="o.plan" size="small" :type="o.plan === 'PREFIX' ? 'warning' : 'info'" class="ml">{{ planText(o.plan) }}</el-tag>
          </span>
        </div>
        <div v-if="!filtered.length" class="empty">{{ t("common.empty") }}</div>
      </div>
      <el-form v-if="picked?.plan === 'PREFIX'" label-width="120px" class="mt" @submit.prevent>
        <el-alert type="warning" :closable="false" show-icon class="mb" :title="t('generate.rule.prefixNeeded', { pi: picked.other_pi || '—' })" />
        <el-form-item :label="t('generate.rule.newPrefix')" required>
          <el-input v-model="newPrefix" maxlength="32" class="w200" :placeholder="picked.current_prefix || ''" />
        </el-form-item>
      </el-form>
    </template>

    <el-form v-else label-width="120px" @submit.prevent>
      <el-form-item :label="t('generate.rule.prefix')" required>
        <el-input v-model="custom.prefix" maxlength="32" class="w200" @input="schedule" />
      </el-form-item>
      <el-form-item :label="t('generate.rule.base')">
        <el-select v-model="custom.base" class="w120" @change="schedule">
          <el-option v-for="b in BASES" :key="b" :value="b" :label="String(b)" />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('generate.rule.seqLen')">
        <el-input-number v-model="custom.seq_len" :min="1" :max="12" controls-position="right" @change="schedule" />
      </el-form-item>
      <el-form-item :label="t('generate.rule.withLength')">
        <el-switch v-model="custom.with_length" @change="schedule" />
      </el-form-item>
      <el-form-item :label="t('generate.rule.suffix')">
        <el-input v-model="custom.suffix" maxlength="32" class="w200" :placeholder="t('common.optional')" @input="schedule" />
      </el-form-item>
      <el-form-item :label="t('generate.rule.sample')">
        <div class="sample">
          <span v-if="sample.error" class="err">{{ sample.error }}</span>
          <template v-else-if="sample.sn">
            <span class="sn-mono">{{ sample.sn }}</span>
            <span class="muted">{{ sample.format }} · {{ t("generate.rule.maxSeq", { n: sample.max }) }}</span>
          </template>
          <span v-else class="muted">—</span>
        </div>
      </el-form-item>
      <p class="muted">{{ t("generate.rule.customNote") }}</p>
    </el-form>

    <el-alert v-if="anyGenerated" type="info" :closable="false" show-icon :title="t('generate.rule.changeNote')" />
    <template #footer>
      <el-button v-if="anyLineRule" :loading="busy === 'clear'" @click="clear">{{ t("generate.rule.clear") }}</el-button>
      <el-button @click="emit('update:modelValue', false)">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" :loading="busy === 'save'" :disabled="!canSave" @click="save">{{ t("common.confirm") }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "@/api";
import { ApiError } from "@/api/http";
import { BASES } from "@/constants";
import type { OrderLine } from "@/types/api";

const props = defineProps<{ modelValue: boolean; pi: string; lines: OrderLine[]; options: any[] }>();
const emit = defineEmits<{ (e: "update:modelValue", v: boolean): void; (e: "saved"): void }>();
const { t } = useI18n();

const mode = ref<"LIBRARY" | "CUSTOM">("LIBRARY");
const kw = ref("");
const picked = ref<any>(null);
const newPrefix = ref("");
/** 自定义规则默认：前缀取单据号后 7 位（转大写、去掉前缀不允许的字符），流水 4 位，不带米标。 */
const defaultPrefix = (bill = "") => bill.toUpperCase().replace(/[^A-Z0-9_-]/g, "").slice(-7);
const customDefault = () => ({ prefix: defaultPrefix(props.lines[0]?.bill_no), base: 10, seq_len: 4, with_length: false, suffix: "" });
const custom = reactive(customDefault());
const sample = reactive({ sn: "", format: "", max: 0, error: "" });
const busy = ref("");

const planText = (p: string) => t(`generate.plan.${p}`);
const filtered = computed(() => {
  const k = kw.value.trim().toLowerCase();
  if (!k) return props.options;
  return props.options.filter((o) => `${o.rule_code} ${o.rule_name} ${o.category_value}`.toLowerCase().includes(k));
});
const anyGenerated = computed(() => props.lines.some((l) => l.generated_qty > 0));
const anyLineRule = computed(() => props.lines.some((l) => l.rule?.source === "LINE"));
const canSave = computed(() => {
  if (!props.lines.length) return false;
  if (mode.value === "LIBRARY") return !!picked.value && (picked.value.plan !== "PREFIX" || !!newPrefix.value.trim());
  return !!custom.prefix.trim() && !sample.error;
});

function onOpen() {
  kw.value = "";
  newPrefix.value = "";
  sample.error = "";
  sample.sn = "";
  const cur = props.lines.length === 1 ? props.lines[0].rule : null;
  if (cur?.mode === "CUSTOM" && cur.custom) {
    mode.value = "CUSTOM";
    Object.assign(custom, cur.custom);
  } else {
    mode.value = props.options.length ? "LIBRARY" : "CUSTOM";
    Object.assign(custom, customDefault());
  }
  picked.value = cur?.rule_id ? props.options.find((o) => o.rule_id === cur.rule_id) ?? null : null;
  if (mode.value === "CUSTOM") schedule();
}

function pick(o: any) {
  picked.value = o;
  newPrefix.value = "";
}

let timer: ReturnType<typeof setTimeout> | undefined;
function schedule() {
  clearTimeout(timer);
  timer = setTimeout(previewCustom, 300);
}
async function previewCustom() {
  sample.sn = "";
  sample.error = "";
  if (!custom.prefix.trim()) return;
  try {
    const r = await api.previewRule({ ...custom, prefix: custom.prefix.trim(), length: props.lines[0]?.length || undefined });
    sample.sn = r.samples?.[0]?.sn || "";
    sample.format = r.format;
    sample.max = r.max_seq;
  } catch (e) {
    sample.error = e instanceof ApiError ? e.message : String(e);
  }
}

const refs = () => props.lines.map((l) => ({ bill_no: l.bill_no, seq: l.seq }));

async function confirmChange() {
  if (!anyGenerated.value) return true;
  try {
    await ElMessageBox.confirm(t("generate.rule.changeConfirm"), t("generate.rule.dialogTitle"), { type: "warning" });
    return true;
  } catch {
    return false;
  }
}

async function save() {
  if (!(await confirmChange())) return;
  busy.value = "save";
  try {
    const body: Record<string, unknown> = { pi_no: props.pi, lines: refs() };
    if (mode.value === "LIBRARY") {
      body.rule_id = picked.value.rule_id;
      if (picked.value.plan === "PREFIX") body.new_prefix = newPrefix.value.trim();
    } else {
      body.custom = { ...custom, prefix: custom.prefix.trim(), suffix: custom.suffix.trim() };
    }
    await api.selectLineRule(body);
    ElMessage.success(t("common.saved"));
    emit("saved");
    emit("update:modelValue", false);
  } finally {
    busy.value = "";
  }
}

async function clear() {
  if (!(await confirmChange())) return;
  busy.value = "clear";
  try {
    await api.clearLineRule({ pi_no: props.pi, lines: refs() });
    ElMessage.success(t("common.done"));
    emit("saved");
    emit("update:modelValue", false);
  } finally {
    busy.value = "";
  }
}
</script>

<style scoped>
.targets { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; margin-bottom: 12px; }
.tg { background: var(--el-fill-color); border-radius: 4px; padding: 0 6px; }
.mb { margin-bottom: 12px; }
.mt { margin-top: 12px; }
.ml { margin-left: 6px; }
.w120 { width: 120px; }
.w200 { width: 200px; }
.rule-list { border: 1px solid var(--el-border-color-lighter); border-radius: 8px; max-height: 280px; overflow: auto; }
.rule-list .it { display: grid; grid-template-columns: 16px minmax(0, 1fr) auto; gap: 10px; align-items: center; padding: 8px 12px; cursor: pointer; border-bottom: 1px solid var(--el-border-color-lighter); outline: none; }
.rule-list .it:last-child { border-bottom: none; }
.rule-list .it:hover, .rule-list .it:focus-visible { background: var(--el-fill-color-light); }
.rule-list .it.on { background: var(--el-color-primary-light-9); }
.rule-list .radio { width: 14px; height: 14px; border-radius: 50%; border: 1.5px solid var(--el-border-color-darker); }
.rule-list .it.on .radio { border: 4px solid var(--el-color-primary); }
.rule-list .nm { display: flex; align-items: center; gap: 6px; min-width: 0; }
.rule-list .nm b { font-weight: 500; white-space: nowrap; }
.rule-list .meta { font-size: 12px; color: var(--app-muted); white-space: nowrap; }
.rule-list .empty { padding: 20px; text-align: center; color: var(--app-muted); }
.sample { display: flex; flex-direction: column; gap: 2px; }
.err { color: var(--el-color-danger); font-size: 12px; }
</style>
