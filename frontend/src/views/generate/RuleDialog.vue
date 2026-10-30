<template>
  <el-dialog :model-value="modelValue" :title="t('generate.rule.dialogTitle')" width="640px" @update:model-value="(v: boolean) => emit('update:modelValue', v)" @open="onOpen">
    <!-- 默认在自定义页签：前缀默认取 PI 第一张单据编码后 7 位，其他参数可选；不放说明文字 -->
    <el-radio-group v-model="mode" class="mb" @change="(v: any) => v === 'CUSTOM' && schedule()">
      <el-radio-button value="CUSTOM">{{ t("generate.rule.custom") }}</el-radio-button>
      <el-radio-button value="LIBRARY" :disabled="!options.length">{{ t("generate.rule.library") }}</el-radio-button>
    </el-radio-group>

    <template v-if="mode === 'LIBRARY'">
      <el-input v-model="kw" clearable prefix-icon="Search" class="mb" />
      <!-- 精简：每条只列「规则编码 v版本」（本 PI 用的版本）；格式悬停可看 -->
      <div class="rule-list">
        <div v-for="o in filtered" :key="o.rule_id" class="it" :class="{ on: picked?.rule_id === o.rule_id }" tabindex="0"
          :title="o.format || ''" @click="pick(o)" @keyup.enter="pick(o)">
          <span class="radio" />
          <span class="nm">
            <b>{{ optionTitle(o) }}</b>
            <el-tag v-if="o.recommended" size="small" type="success">{{ t("generate.rule.recommended") }}</el-tag>
          </span>
        </div>
        <div v-if="!filtered.length" class="empty">{{ t("common.empty") }}</div>
      </div>
      <el-form v-if="picked?.plan === 'PREFIX'" label-width="120px" class="mt" @submit.prevent>
        <el-form-item :label="t('generate.rule.newPrefix')" required>
          <el-input v-model="newPrefix" maxlength="32" class="w200" />
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
      <template v-if="custom.with_length">
        <el-form-item :label="t('generate.rule.sepBefore')">
          <el-input v-model="custom.sep_before" maxlength="16" class="w120" @input="schedule" />
        </el-form-item>
        <el-form-item :label="t('generate.rule.lengthPad')">
          <el-input-number v-model="custom.length_pad" :min="0" :max="32" controls-position="right" @change="schedule" />
        </el-form-item>
        <el-form-item :label="t('generate.rule.sepAfter')">
          <el-input v-model="custom.sep_after" maxlength="16" class="w120" @input="schedule" />
        </el-form-item>
      </template>
      <el-form-item :label="t('generate.rule.withUnit')">
        <el-switch v-model="custom.with_unit" @change="schedule" />
      </el-form-item>
      <el-form-item :label="t('generate.rule.suffix')">
        <el-input v-model="custom.suffix" maxlength="32" class="w200" @input="schedule" />
      </el-form-item>
      <el-form-item :label="t('generate.rule.sample')">
        <div class="sample">
          <span v-if="sample.error" class="err">{{ sample.error }}</span>
          <span v-else-if="sample.sn" class="sn-mono">{{ sample.sn }}</span>
          <span v-else class="muted">—</span>
        </div>
      </el-form-item>
    </el-form>

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
import type { CustomRule, OrderLine } from "@/types/api";

/** 「选规则」的默认前缀（ctx.rule_prefix）：prefix = PI 第一张单据编码后 7 位；custom = 本 PI 已用这个前缀的格式（没有为 null）。 */
type RulePrefix = { prefix: string; custom: CustomRule | null };
const props = defineProps<{ modelValue: boolean; pi: string; lines: OrderLine[]; options: any[]; rulePrefix?: RulePrefix }>();
const emit = defineEmits<{ (e: "update:modelValue", v: boolean): void; (e: "saved"): void }>();
const { t } = useI18n();

const mode = ref<"LIBRARY" | "CUSTOM">("LIBRARY");
const kw = ref("");
const picked = ref<any>(null);
const newPrefix = ref("");
/** 前缀默认取 PI 第一张单据编码后 7 位（同一 PI 的各张单据共用，生成时后台查全局唯一）；取不到时用所选第一行的单据。 */
const defaultPrefix = () => props.rulePrefix?.prefix || (props.lines[0]?.bill_no ?? "").slice(-7);
/** 自定义规则默认：流水 4 位，不带米数、不带单位。 */
const customDefault = (): CustomRule => ({
  prefix: defaultPrefix(), base: 10, seq_len: 4, with_length: false, length_pad: 0, sep_before: "", sep_after: "",
  with_unit: false, suffix: "",
});
const custom = reactive<CustomRule>(customDefault());
const sample = reactive({ sn: "", error: "" });
const busy = ref("");

/** 规则选择只列「规则编码 v版本」 */
const optionTitle = (o: any) => (o.version ? `${o.rule_code} v${o.version}` : o.rule_code);
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

/** 打开即在自定义页签：前缀为默认前缀；其他参数按本 PI 已用这个前缀的格式（保存时沿用那一版），
 *  没有时按所选物料行现有的自定义规则，再没有用默认值。 */
function onOpen() {
  kw.value = "";
  newPrefix.value = "";
  sample.error = "";
  sample.sn = "";
  const cur = props.lines.length === 1 ? props.lines[0].rule : null;
  const base = props.rulePrefix?.custom ?? (cur?.mode === "CUSTOM" ? cur.custom : null);
  Object.assign(custom, customDefault(), base ?? {}, { prefix: defaultPrefix() });
  mode.value = "CUSTOM";
  picked.value = cur?.rule_id ? props.options.find((o) => o.rule_id === cur.rule_id) ?? null : null;
  schedule();
}

function pick(o: any) {
  picked.value = o;
  // 须为本 PI 换前缀时同样默认 PI 第一张单据编码后 7 位
  newPrefix.value = o.plan === "PREFIX" ? defaultPrefix() : "";
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
    const l = props.lines[0];
    const r = await api.previewRule({ ...custom, prefix: custom.prefix.trim(), length: l?.length || undefined, unit: l?.unit || undefined });
    sample.sn = r.samples?.[0]?.sn || "";
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
      body.custom = {
        ...custom, prefix: custom.prefix.trim(), suffix: custom.suffix.trim(), sep_before: custom.sep_before.trim(), sep_after: custom.sep_after.trim(),
      };
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
.mb { margin-bottom: 12px; }
.mt { margin-top: 12px; }
.w120 { width: 120px; }
.w200 { width: 200px; }
.rule-list { border: 1px solid var(--el-border-color-lighter); border-radius: 8px; max-height: 280px; overflow: auto; }
.rule-list .it { display: grid; grid-template-columns: 16px minmax(0, 1fr); gap: 10px; align-items: center; padding: 8px 12px; cursor: pointer; border-bottom: 1px solid var(--el-border-color-lighter); outline: none; }
.rule-list .it:last-child { border-bottom: none; }
.rule-list .it:hover, .rule-list .it:focus-visible { background: var(--el-fill-color-light); }
.rule-list .it.on { background: var(--el-color-primary-light-9); }
.rule-list .radio { width: 14px; height: 14px; border-radius: 50%; border: 1.5px solid var(--el-border-color-darker); }
.rule-list .it.on .radio { border: 4px solid var(--el-color-primary); }
.rule-list .nm { display: flex; align-items: center; gap: 6px; min-width: 0; }
.rule-list .nm b { font-weight: 500; white-space: nowrap; }
.rule-list .empty { padding: 20px; text-align: center; color: var(--app-muted); }
.sample { display: flex; flex-direction: column; }
.err { color: var(--el-color-danger); font-size: 12px; }
</style>
