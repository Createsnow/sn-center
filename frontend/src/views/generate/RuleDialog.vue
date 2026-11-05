<template>
  <el-dialog :model-value="modelValue" :title="t('generate.rule.dialogTitle')" width="760px" @update:model-value="(v: boolean) => emit('update:modelValue', v)" @open="onOpen">
    <!-- 默认在自定义页签：SN 由组件按顺序拼成，默认「单据编号后 7 位 + 流水」（本 PI 已生成的号还在时沿用已用的前缀）；不放说明文字 -->
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

    <!-- 自定义：SN 组成（组件按顺序拼成；保存时换算成前缀 / 流水 / 米数 / 单位 / 后缀，见 snParts.js） -->
    <div v-else class="parts-box">
      <div class="parts-head">
        <b>{{ t("generate.rule.parts") }}</b>
        <el-button link type="primary" size="small" @click="resetParts">{{ t("generate.rule.resetParts") }}</el-button>
      </div>
      <div class="parts">
        <div v-for="(p, i) in parts" :key="p.id" class="part" :class="`k-${p.kind}`">
          <span class="idx">{{ i + 1 }}</span>
          <span class="nm">{{ t(`generate.rule.part.${p.kind}`) }}</span>
          <div class="opts">
            <el-input v-if="p.kind === 'TEXT' || p.kind === 'DCODE'" v-model="p.text" :maxlength="TEXT_MAX" size="small" class="w160" />
            <el-select v-else-if="p.kind === 'SEP'" v-model="p.text" size="small" class="w80">
              <el-option v-for="s in SEPS" :key="s" :value="s" :label="s" />
            </el-select>
            <template v-else-if="p.kind === 'PI' || p.kind === 'BILL'">
              <el-select v-model="p.cut" size="small" class="w110">
                <el-option v-for="c in CUTS" :key="c" :value="c" :label="t(`generate.rule.cut.${c}`)" />
              </el-select>
              <el-input-number v-if="p.cut !== 'ALL'" v-model="p.n" :min="1" :max="64" :step="1" step-strictly size="small" controls-position="right" class="w90" />
            </template>
            <template v-else-if="p.kind === 'DATE'">
              <el-date-picker v-model="p.date" type="date" value-format="YYYY-MM-DD" :clearable="false" size="small" style="width: 140px" />
              <el-select v-model="p.fmt" size="small" class="w170">
                <el-option v-for="f in DATE_FMTS" :key="f" :value="f" :label="t(`generate.rule.dateFmt.${f}`)" />
              </el-select>
            </template>
            <template v-else-if="p.kind === 'SEQ'">
              <span class="lb">{{ t("generate.rule.base") }}</span>
              <el-select v-model="p.base" size="small" class="w70">
                <el-option v-for="b in BASES" :key="b" :value="b" :label="String(b)" />
              </el-select>
              <span class="lb">{{ t("generate.rule.seqLen") }}</span>
              <el-input-number v-model="p.len" :min="1" :max="SEQ_LEN_MAX" :step="1" step-strictly size="small" controls-position="right" class="w80" />
              <!-- 起始号（十进制流水，选填）：本 PI 已用这个前缀时流水接续，不能填 -->
              <span class="lb">{{ t("generate.rule.seqStart") }}</span>
              <el-input-number :model-value="prefixUsed ? undefined : p.start ?? undefined" :min="1" :step="1" step-strictly size="small"
                controls-position="right" :disabled="prefixUsed" class="w130" @update:model-value="(v: any) => (p.start = v ?? null)" />
            </template>
            <template v-else-if="p.kind === 'LEN'">
              <span class="lb">{{ t("generate.rule.lengthPad") }}</span>
              <el-input-number v-model="p.pad" :min="0" :max="PAD_MAX" :step="1" step-strictly size="small" controls-position="right" class="w80" />
            </template>
            <span v-if="valueOf(p)" class="val sn-mono">{{ valueOf(p) }}</span>
          </div>
          <span class="ops">
            <el-button link size="small" icon="ArrowUp" :disabled="i === 0" :title="t('generate.rule.moveUp')" @click="move(i, -1)" />
            <el-button link size="small" icon="ArrowDown" :disabled="i === parts.length - 1" :title="t('generate.rule.moveDown')" @click="move(i, 1)" />
            <el-button link size="small" icon="Close" :title="t('common.delete')" @click="remove(i)" />
          </span>
        </div>
      </div>
      <div class="add">
        <el-button v-for="k in KINDS" :key="k" size="small" icon="Plus" :disabled="isSingleTaken(k)" @click="add(k)">
          {{ t(`generate.rule.part.${k}`) }}
        </el-button>
      </div>
      <div class="sample">
        <span class="lb">{{ t("generate.rule.sample") }}</span>
        <span v-if="errorText" class="err">{{ errorText }}</span>
        <span v-else-if="sample.error" class="err">{{ sample.error }}</span>
        <span v-else-if="sample.sn" class="sn-mono">{{ sample.sn }}</span>
        <span v-else class="muted">—</span>
      </div>
    </div>

    <template #footer>
      <el-button v-if="anyLineRule" :loading="busy === 'clear'" @click="clear">{{ t("generate.rule.clear") }}</el-button>
      <el-button @click="emit('update:modelValue', false)">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" :loading="busy === 'save'" :disabled="!canSave" @click="save">{{ t("common.confirm") }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "@/api";
import { ApiError } from "@/api/http";
import { BASES } from "@/constants";
import { useTime } from "@/composables/useTime";
import {
  CUTS, DATE_FMTS, KINDS, PAD_MAX, SEPS, SEQ_LEN_MAX, SINGLE, TEXT_MAX,
  fromSpec, movePart, newPart, partValue, textPart, todayIn, toSpec,
} from "@/snParts.js";
import type { CustomRule, OrderLine } from "@/types/api";

/** ctx.rule_prefix（见 rules.pi_prefix）：custom = 本 PI 已生成的号还在时所用前缀的格式，prefix 为这个前缀；
 *  还没有生成的号（或已全部删掉）时 custom 为 null，prefix 为 PI 第一张单据编码后 7 位（没有点选的单据时才用）。 */
type RulePrefix = { prefix: string; custom: CustomRule | null };
/** SN 组成的一个组件（见 snParts.js） */
type Part = {
  id: number; kind: string; text?: string; cut?: string; n?: number; fmt?: string; date?: string;
  base?: number; len?: number; start?: number | null; pad?: number;
};
const props = defineProps<{ modelValue: boolean; pi: string; lines: OrderLine[]; options: any[]; rulePrefix?: RulePrefix }>();
const emit = defineEmits<{ (e: "update:modelValue", v: boolean): void; (e: "saved"): void }>();
const { t } = useI18n();
const { offset } = useTime();

const mode = ref<"LIBRARY" | "CUSTOM">("LIBRARY");
const kw = ref("");
const picked = ref<any>(null);
const newPrefix = ref("");
const parts = ref<Part[]>([]);
const sample = reactive({ sn: "", error: "" });
const busy = ref("");

/** 组件取值用的上下文：PI、点选的第一个单据编号（lines 按点选顺序，行内按钮为本行） */
const partCtx = computed(() => ({ pi: props.pi, billNo: props.lines[0]?.bill_no ?? "" }));
/** 组件换算成的自定义规则字段与校验错误 */
const built = computed(() => toSpec(parts.value, partCtx.value));
const errorText = computed(() => {
  const e = built.value.errors[0];
  return e ? t(`generate.rule.err.${e.code}`, e) : "";
});
const valueOf = (p: Part) => partValue(p, partCtx.value) ?? "";
const isSingleTaken = (k: string) => SINGLE.includes(k) && parts.value.some((p) => p.kind === k);

/** 默认前缀组件：本 PI 已生成的号还在时沿用已用的前缀（之后各张单据都用它，流水接续）；否则取点选的第一个单据编码后 7 位，
 *  取不到时用 PI 第一张单据的（同 genScope.defaultRulePrefix）。生成时后台查前缀全局唯一。 */
function defaultHead(): Part[] {
  if (props.rulePrefix?.custom) return [textPart(props.rulePrefix.prefix)];
  if ((props.lines[0]?.bill_no ?? "").slice(-7)) return [newPart("BILL")];
  return [textPart(props.rulePrefix?.prefix ?? "")];
}
/** 默认组成：默认前缀 + 流水 4 位（本 PI 已用前缀时按已用的格式），不带米数、不带单位 */
function resetParts() {
  const used = props.rulePrefix?.custom;
  parts.value = used ? fromSpec({ ...used, seq_start: null }, defaultHead()) : [...defaultHead(), newPart("SEQ")];
}
function add(kind: string) {
  parts.value = [...parts.value, newPart(kind, todayIn(offset.value))];
}
function move(i: number, d: number) {
  parts.value = movePart(parts.value, i, d);
}
function remove(i: number) {
  parts.value = parts.value.filter((_, j) => j !== i);
}

/** 规则选择只列「规则编码 v版本」 */
const optionTitle = (o: any) => (o.version ? `${o.rule_code} v${o.version}` : o.rule_code);
const filtered = computed(() => {
  const k = kw.value.trim().toLowerCase();
  if (!k) return props.options;
  return props.options.filter((o) => `${o.rule_code} ${o.rule_name} ${o.category_value}`.toLowerCase().includes(k));
});
/** 本 PI 已用这个前缀（同格式沿用那一版、流水接续）：起始号不起作用，不能填。 */
const prefixUsed = computed(() => !!props.rulePrefix?.custom && built.value.spec.prefix === props.rulePrefix.prefix);
/** 提交 / 示例用的起始号 */
const seqStart = () => (prefixUsed.value ? null : built.value.spec.seq_start || null);
const anyGenerated = computed(() => props.lines.some((l) => l.generated_qty > 0));
const anyLineRule = computed(() => props.lines.some((l) => l.rule?.source === "LINE"));
const canSave = computed(() => {
  if (!props.lines.length) return false;
  if (mode.value === "LIBRARY") return !!picked.value && (picked.value.plan !== "PREFIX" || !!newPrefix.value.trim());
  return !built.value.errors.length && !!built.value.spec.prefix && !sample.error;
});

/** 打开即在自定义页签：前缀为默认前缀；其他组件按本 PI 已用这个前缀的格式（保存时沿用那一版），
 *  没有时按所选物料行现有的自定义规则拆回组件，再没有用默认（流水 4 位）。 */
function onOpen() {
  kw.value = "";
  newPrefix.value = "";
  sample.error = "";
  sample.sn = "";
  const cur = props.lines.length === 1 ? props.lines[0].rule : null;
  const base = props.rulePrefix?.custom ?? (cur?.mode === "CUSTOM" ? cur.custom : null);
  // 起始号只在沿用所选物料行现有的自定义规则时带出
  const start = !props.rulePrefix?.custom && cur?.mode === "CUSTOM" ? cur.seq_start ?? null : null;
  parts.value = base ? fromSpec({ ...base, seq_start: start }, defaultHead()) : [...defaultHead(), newPart("SEQ")];
  mode.value = "CUSTOM";
  picked.value = cur?.rule_id ? props.options.find((o) => o.rule_id === cur.rule_id) ?? null : null;
  schedule();
}

function pick(o: any) {
  picked.value = o;
  // 须为本 PI 换前缀时同样用默认前缀（点选的第一个单据编码后 7 位 / 已用前缀）
  newPrefix.value = o.plan === "PREFIX" ? toSpec([...defaultHead(), newPart("SEQ")], partCtx.value).spec.prefix : "";
}

let timer: ReturnType<typeof setTimeout> | undefined;
function schedule() {
  clearTimeout(timer);
  timer = setTimeout(previewCustom, 300);
}
watch(parts, () => mode.value === "CUSTOM" && schedule(), { deep: true });

async function previewCustom() {
  sample.sn = "";
  sample.error = "";
  const { spec, errors } = built.value;
  if (errors.length || !spec.prefix) return;
  try {
    const l = props.lines[0];
    const r = await api.previewRule({
      ...spec, seq_start: seqStart() ?? undefined, length: l?.length || undefined, unit: l?.unit || undefined,
    });
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
      // 请求体与原来相同：prefix、base、seq_len、with_length、length_pad、sep_before、sep_after、with_unit、suffix、seq_start
      body.custom = { ...built.value.spec, seq_start: seqStart() };
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
.w70 { width: 70px; }
.w80 { width: 80px; }
.w90 { width: 90px; }
.w110 { width: 110px; }
.w130 { width: 130px; }
.w160 { width: 160px; }
.w170 { width: 170px; }
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
.parts-box { display: flex; flex-direction: column; gap: 10px; }
.parts-head { display: flex; justify-content: space-between; align-items: center; }
.parts-head b { font-weight: 500; }
.parts { display: flex; flex-direction: column; gap: 6px; max-height: 360px; overflow: auto; }
.part { display: grid; grid-template-columns: 18px 76px minmax(0, 1fr) auto; gap: 8px; align-items: center; padding: 4px 8px; border-radius: 6px; background: var(--el-fill-color-light); }
.part.k-SEQ { background: var(--el-color-primary-light-9); }
.part.k-LEN, .part.k-UNIT { background: var(--el-color-warning-light-9); }
.part .idx { font-size: 12px; color: var(--app-muted); text-align: center; }
.part .nm { font-size: 13px; }
.part .opts { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; min-width: 0; }
.part .ops { display: flex; }
.part .ops .el-button + .el-button { margin-left: 4px; }
.lb { font-size: 12px; color: var(--app-muted); }
.val { font-size: 12px; color: var(--app-muted); }
.add { display: flex; flex-wrap: wrap; gap: 6px; }
.add .el-button { margin-left: 0; }
.sample { display: flex; align-items: baseline; gap: 10px; padding-top: 6px; border-top: 1px solid var(--el-border-color-lighter); }
.sample .sn-mono { font-size: 15px; }
.err { color: var(--el-color-danger); font-size: 12px; }
</style>
