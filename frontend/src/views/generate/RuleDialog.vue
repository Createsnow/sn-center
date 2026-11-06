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

    <!-- 自定义：SN 组成（组件按顺序拼成；保存时换算成前缀 / 流水 / 米数 / 单位 / 后缀，见 snParts.js）。
         上方常用格式一键套用；结构条点选组件改参数、拖动调顺序、点「+」在该处插入；放不下的位置置灰或标红。
         弹窗只放控件与数据，不放说明文字 -->
    <div v-else class="parts-box">
      <div class="tpls">
        <span class="lb">{{ t("generate.rule.tpls") }}</span>
        <button v-for="k in TPL_KEYS" :key="k" type="button" class="tpl" :class="{ on: tplOn === k }" @click="applyTpl(k)">
          {{ t(`generate.rule.tpl.${k}`) }}
        </button>
      </div>
      <div class="parts-head">
        <b>{{ t("generate.rule.parts") }}</b>
        <el-button link type="primary" size="small" @click="resetParts">{{ t("generate.rule.resetParts") }}</el-button>
      </div>
      <div ref="barEl" class="bar" :class="{ 'is-drag': drag?.on }" @pointerdown="onDown" @pointermove="onMove"
        @pointerup="onUp(true)" @pointercancel="onUp(false)">
        <template v-for="(p, i) in parts" :key="p.id">
          <button v-if="i > 0" type="button" class="slot" :class="slotClass(i)" :aria-label="t('generate.rule.insertAt', { n: i + 1 })"
            @click="toggleIns(i)"><i>+</i></button>
          <button type="button" class="chip" :class="chipClass(p, i)" :aria-pressed="p.id === selId"
            @keydown.enter.prevent="select(p.id)" @keydown.space.prevent="select(p.id)">
            <span class="v sn-mono" :class="{ blank: !chipValue(p) }">{{ chipValue(p) || t("generate.rule.blank") }}</span>
            <span class="t">{{ t(`generate.rule.part.${p.kind}`) }}</span>
          </button>
        </template>
        <span class="slot last" :class="slotClass(parts.length)" />
        <button type="button" class="add-end" :class="{ open: ins === 'END' }" @click="toggleIns('END')">＋ {{ t("generate.rule.addPart") }}</button>
      </div>
      <div v-if="msgText" class="msg">{{ msgText }}</div>

      <!-- 插入：分三组（固定字符 / 取单据·日期 / 生成时取值）；放不下的置灰，悬停看原因 -->
      <div v-if="ins !== null" class="panel">
        <div class="panel-h">
          <b>{{ ins === "END" ? t("generate.rule.addAuto") : t("generate.rule.insertAt", { n: ins + 1 }) }}</b>
          <el-button link size="small" @click="ins = null">{{ t("common.cancel") }}</el-button>
        </div>
        <div class="pal">
          <div v-for="g in GROUPS" :key="g.key" class="items">
            <button v-for="k in g.kinds" :key="k" type="button" class="it" :disabled="!!palette[k]"
              :title="palette[k] ? reasonText(palette[k]) : undefined" @click="addPart(k)">
              {{ t(`generate.rule.part.${k}`) }}
            </button>
          </div>
        </div>
      </div>

      <!-- 选中组件的参数 -->
      <div v-else-if="sel" class="panel">
        <div class="panel-h">
          <b>{{ t(`generate.rule.part.${sel.kind}`) }}</b>
          <span class="ops">
            <el-button link type="primary" size="small" :disabled="!canMove(-1)" @click="moveSel(-1)">{{ t("generate.rule.moveLeft") }}</el-button>
            <el-button link type="primary" size="small" :disabled="!canMove(1)" @click="moveSel(1)">{{ t("generate.rule.moveRight") }}</el-button>
            <el-button link type="danger" size="small" @click="removeSel">{{ t("common.delete") }}</el-button>
          </span>
        </div>
        <!-- 单位没有参数：只有左移 / 右移 / 删除 -->
        <div v-if="sel.kind !== 'UNIT'" class="panel-b">
          <template v-if="sel.kind === 'TEXT'">
            <span class="k">{{ t("generate.rule.text") }}</span>
            <span class="val"><el-input v-model="sel.text" :maxlength="TEXT_MAX" size="small" class="w200" /></span>
          </template>
          <template v-else-if="sel.kind === 'SEP'">
            <span class="k">{{ t("generate.rule.part.SEP") }}</span>
            <span class="val">
              <el-radio-group v-model="sel.text" size="small" class="mono-seg">
                <el-radio-button v-for="s in SEPS" :key="s" :value="s">{{ s }}</el-radio-button>
              </el-radio-group>
            </span>
          </template>
          <template v-else-if="sel.kind === 'PI' || sel.kind === 'BILL'">
            <span class="k">{{ t("generate.rule.cutBy") }}</span>
            <span class="val">
              <el-radio-group v-model="sel.cut" size="small">
                <el-radio-button v-for="c in CUTS" :key="c" :value="c">{{ t(`generate.rule.cut.${c}`) }}</el-radio-button>
              </el-radio-group>
            </span>
            <template v-if="sel.cut !== 'ALL'">
              <span class="k">{{ t("generate.rule.digits") }}</span>
              <span class="val"><el-input-number v-model="sel.n" :min="1" :max="64" :step="1" step-strictly size="small" class="w120" /></span>
            </template>
          </template>
          <template v-else-if="sel.kind === 'DATE'">
            <span class="k">{{ t("generate.rule.part.DATE") }}</span>
            <span class="val">
              <el-date-picker v-model="sel.date" type="date" value-format="YYYY-MM-DD" :clearable="false" size="small" style="width: 140px" />
              <el-button link type="primary" size="small" @click="sel.date = today()">{{ t("generate.rule.today") }}</el-button>
            </span>
            <span class="k">{{ t("generate.rule.dateFmtLabel") }}</span>
            <span class="val">
              <el-radio-group v-model="sel.fmt" size="small" class="mono-seg">
                <el-radio-button v-for="f in DATE_FMTS" :key="f" :value="f" :title="t(`generate.rule.dateFmt.${f}`)">{{ f }}</el-radio-button>
              </el-radio-group>
            </span>
          </template>
          <template v-else-if="sel.kind === 'SEQ'">
            <span class="k">{{ t("generate.rule.base") }}</span>
            <span class="val">
              <el-radio-group v-model="sel.base" size="small" class="mono-seg">
                <el-radio-button v-for="b in BASES" :key="b" :value="b">{{ b }}</el-radio-button>
              </el-radio-group>
            </span>
            <span class="k">{{ t("generate.rule.seqLen") }}</span>
            <span class="val">
              <el-input-number v-model="sel.len" :min="1" :max="SEQ_LEN_MAX" :step="1" step-strictly size="small" class="w120" />
            </span>
            <!-- 起始号（十进制流水）：默认 1，1 ～ 最大流水；为 1 时按未指定保存（start = null，请求体与原来相同）。
                 本 PI 已用这个前缀时流水接续，置灰不显示 -->
            <span class="k">{{ t("generate.rule.seqStart") }}</span>
            <span class="val">
              <el-input-number :model-value="prefixUsed ? undefined : sel.start ?? 1" :min="1" :max="maxSeq(sel)" value-on-clear="min" :step="1"
                step-strictly size="small" :disabled="prefixUsed" class="w150"
                @update:model-value="(v: any) => sel && (sel.start = Number(v) > 1 ? Number(v) : null)" />
            </span>
          </template>
          <template v-else-if="sel.kind === 'LEN'">
            <span class="k">{{ t("generate.rule.lengthPad") }}</span>
            <span class="val">
              <el-input-number v-model="sel.pad" :min="0" :max="PAD_MAX" :step="1" step-strictly size="small" class="w120" />
            </span>
          </template>
        </div>
      </div>

      <!-- 示例：最小号（第一个号，从起始号起）与最大号，取自 /rules/preview 的第一个和最后一个 -->
      <div class="sample">
        <span class="lb">{{ t("generate.rule.sample") }}</span>
        <span v-if="errorText" class="err">{{ errorText }}</span>
        <span v-else-if="sample.error" class="err">{{ sample.error }}</span>
        <template v-else-if="sample.min">
          <span v-for="[label, s] in sampleRows" :key="label" class="rng">
            <span class="lb">{{ t(`generate.rule.${label}`) }}</span>
            <span class="sn sn-mono"><template v-if="s.segs"><span v-for="(g, j) in s.segs" :key="j" :class="g.cls && `s-${g.cls}`">{{ g.text }}</span></template><template v-else>{{ s.sn }}</template></span>
          </span>
        </template>
        <span v-else class="muted">—</span>
      </div>
    </div>

    <!-- 底部只有取消、确定：不提供「改回按 PI / 客户默认」（要撤回物料行选定的规则，用详情底部「更多 → 撤销配置」） -->
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" :loading="busy === 'save'" :disabled="!canSave" @click="save">{{ t("common.confirm") }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, h, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "@/api";
import { ApiError } from "@/api/http";
import { BASES, CHARSETS } from "@/constants";
import { useTime } from "@/composables/useTime";
import {
  CUTS, DATE_FMTS, PAD_MAX, PREFIX_MAX, SEP_MAX, SEPS, SEQ_LEN_MAX, TEMPLATES, TEXT_MAX,
  autoIndex, blockReason, fromSpec, insertAt, moveTo, newErrors, newPart, partValue, partsSig, sampleSegments, textPart, todayIn, toSpec,
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
type Seg = { text: string; cls: string };
type Sample = { sn: string; segs: Seg[] | null };
const props = defineProps<{ modelValue: boolean; pi: string; lines: OrderLine[]; options: any[]; rulePrefix?: RulePrefix }>();
const emit = defineEmits<{ (e: "update:modelValue", v: boolean): void; (e: "saved"): void }>();
const { t } = useI18n();
const { offset } = useTime();

/** 常用格式（顺序即按钮顺序） */
const TPL_KEYS = Object.keys(TEMPLATES) as (keyof typeof TEMPLATES)[];
/** 插入面板的分组 */
const GROUPS = [
  { key: "FIX", kinds: ["TEXT", "SEP"] },
  { key: "DOC", kinds: ["PI", "BILL", "DATE"] },
  { key: "GEN", kinds: ["SEQ", "LEN", "UNIT"] },
];

const mode = ref<"LIBRARY" | "CUSTOM">("LIBRARY");
const kw = ref("");
const picked = ref<any>(null);
const newPrefix = ref("");
const parts = ref<Part[]>([]);
/** 选中的组件 id；ins 为插入位置（"END" 为「+ 组件」，自动放到可用的位置），打开时下方显示插入面板 */
const selId = ref<number | null>(null);
const ins = ref<number | "END" | null>(null);
const sample = reactive<{ min: Sample | null; max: Sample | null; error: string }>({ min: null, max: null, error: "" });
const busy = ref("");

/** 组件取值用的上下文：PI、点选的第一个单据编号（lines 按点选顺序，行内按钮为本行） */
const partCtx = computed(() => ({ pi: props.pi, billNo: props.lines[0]?.bill_no ?? "" }));
/** 组件换算成的自定义规则字段与校验错误 */
const built = computed(() => toSpec(parts.value, partCtx.value));
const errorText = computed(() => {
  const e = built.value.errors[0];
  return e ? t(`generate.rule.err.${e.code}`, e) : "";
});
const today = () => todayIn(offset.value);
const sel = computed(() => parts.value.find((p) => p.id === selId.value) ?? null);
const selIndex = computed(() => parts.value.findIndex((p) => p.id === selId.value));
const tplOn = computed(() => {
  const cur = partsSig(parts.value);
  return TPL_KEYS.find((k) => partsSig(TEMPLATES[k](today())) === cur) ?? "";
});

/** 流水文本（与后台 encoding.encode_seq 相同的字符表） */
function seqText(n: number, base: number, len: number) {
  const cs = CHARSETS[base] ?? CHARSETS[10];
  let s = "";
  for (let i = 0, x = n; i < len; i++, x = Math.floor(x / base)) s = cs[x % base] + s;
  return s;
}
/** 当前进制、位数下的最大流水（起始号上限） */
const maxSeq = (p: Part) => Math.min((Number(p.base) || 10) ** (Number(p.len) || 4) - 1, Number.MAX_SAFE_INTEGER);
/** 结构条上组件显示的值：保存时定值的组件取值；流水取第一个号；米数、单位取第一个物料行的 */
function chipValue(p: Part) {
  const v = partValue(p, partCtx.value);
  if (v !== null) return v;
  const l = props.lines[0];
  if (p.kind === "SEQ") {
    const st = Number(p.start);
    return seqText(!prefixUsed.value && Number.isSafeInteger(st) && st > 0 ? st : 1, Number(p.base) || 10, Number(p.len) || 4);
  }
  if (p.kind === "LEN") return p.pad ? (l?.length ?? "").padStart(p.pad, "0") : l?.length ?? "";
  return l?.unit ?? "";
}
const chipType = (k: string) => (k === "SEQ" ? "SEQ" : k === "LEN" || k === "UNIT" ? "LINE" : k === "SEP" ? "SEP" : "FIX");
function chipClass(p: Part, i: number) {
  const d = drag.value?.on && drag.value.from === i;
  return [`c-${chipType(p.kind)}`, { sel: p.id === selId.value, dragging: d, bad: d && !drag.value?.ok }];
}
function slotClass(i: number) {
  const d = drag.value;
  return { open: ins.value === i, drop: !!d?.on && d.to === i, bad: !!d?.on && d.to === i && !d.ok };
}

const reasonText = (code: string) =>
  t(code === "TAKEN" ? "generate.rule.taken" : `generate.rule.err.${code}`, { max: code === "SEP_TOO_LONG" ? SEP_MAX : PREFIX_MAX });
/** 插入面板各组件放不下的原因（空为放得下） */
const palette = computed<Record<string, string>>(() => {
  const at = ins.value;
  if (at === null) return {};
  return Object.fromEntries(GROUPS.flatMap((g) => g.kinds).map((k) => {
    const i = at === "END" ? autoIndex(parts.value, k, partCtx.value) : at;
    return [k, blockReason(parts.value, k, i, partCtx.value)];
  }));
});
function select(id: number) {
  selId.value = id;
  ins.value = null;
}
function toggleIns(at: number | "END") {
  ins.value = ins.value === at ? null : at;
}
function setParts(ps: Part[], sel?: number | null) {
  parts.value = ps;
  ins.value = null;
  if (sel !== undefined) selId.value = sel;
}
/** 提示里带「撤销」 */
function undoable(text: string, undo: () => void) {
  const m = ElMessage({
    type: "success",
    duration: 5000,
    message: h("span", [text, " ", h("a", { class: "undo", onClick: () => { undo(); m.close(); } }, t("generate.rule.undo"))]),
  });
}
const seqId = (ps: Part[]) => (ps.find((p) => p.kind === "SEQ") ?? ps[0])?.id ?? null;
function applyTpl(k: keyof typeof TEMPLATES) {
  const prev = parts.value, prevSel = selId.value;
  const ps = TEMPLATES[k](today()) as Part[];
  setParts(ps, seqId(ps));
  undoable(t("generate.rule.tplApplied", { name: t(`generate.rule.tpl.${k}`) }), () => setParts(prev, prevSel));
}
function addPart(kind: string) {
  const at = ins.value === "END" ? autoIndex(parts.value, kind, partCtx.value) : (ins.value as number);
  const p = newPart(kind, today());
  setParts(insertAt(parts.value, at, p), p.id);
}
/** 左移 / 右移选中的组件（d = -1 / 1）：放不下时按钮置灰 */
const moveTarget = (d: number) => (d < 0 ? selIndex.value - 1 : selIndex.value + 2);
function canMove(d: number) {
  const i = selIndex.value, to = moveTarget(d);
  if (i < 0 || to < 0 || to > parts.value.length) return false;
  return !newErrors(parts.value, moveTo(parts.value, i, to), partCtx.value).length;
}
function moveSel(d: number) {
  if (canMove(d)) setParts(moveTo(parts.value, selIndex.value, moveTarget(d)));
}
function removeSel() {
  const i = selIndex.value;
  if (i < 0) return;
  const prev = parts.value, p = prev[i];
  const rest = prev.filter((_, j) => j !== i);
  setParts(rest, (rest[i] ?? rest[i - 1])?.id ?? null);
  undoable(t("generate.rule.partRemoved", { name: t(`generate.rule.part.${p.kind}`) }), () => setParts(prev, p.id));
}

/** 拖动：按住组件移动超过 6px 开始拖；落点为竖线，放不下时标红、松手还原。未移动即为点选。 */
const barEl = ref<HTMLElement>();
const drag = ref<{ from: number; id: number; x: number; y: number; on: boolean; to: number | null; ok: boolean; code: string } | null>(null);
function onDown(e: PointerEvent) {
  const c = (e.target as HTMLElement).closest(".chip");
  if (!c || e.button > 0 || !barEl.value) return;
  const chips = [...barEl.value.querySelectorAll(".chip")];
  const from = chips.indexOf(c);
  drag.value = { from, id: parts.value[from].id, x: e.clientX, y: e.clientY, on: false, to: null, ok: true, code: "" };
  barEl.value.setPointerCapture(e.pointerId);
}
function onMove(e: PointerEvent) {
  const d = drag.value;
  if (!d || !barEl.value) return;
  if (!d.on) {
    if (Math.hypot(e.clientX - d.x, e.clientY - d.y) < 6) return;
    d.on = true;
    ins.value = null;
  }
  e.preventDefault();
  const chips = [...barEl.value.querySelectorAll(".chip")];
  let to = chips.length;
  for (let i = 0; i < chips.length; i++) {
    const r = chips[i].getBoundingClientRect();
    if (e.clientY < r.top || (e.clientY <= r.bottom && e.clientX < r.left + r.width / 2)) {
      to = i;
      break;
    }
  }
  if (to === d.from || to === d.from + 1) {
    Object.assign(d, { to: null, ok: true, code: "" });
    return;
  }
  const errs = newErrors(parts.value, moveTo(parts.value, d.from, to), partCtx.value);
  Object.assign(d, { to, ok: !errs.length, code: errs[0] ?? "" });
}
function onUp(apply: boolean) {
  const d = drag.value;
  drag.value = null;
  if (!d) return;
  if (!d.on) {
    if (apply) select(d.id);
    return;
  }
  if (!apply || d.to === null) return;
  if (d.ok) setParts(moveTo(parts.value, d.from, d.to));
}
/** 结构条下方一行：拖到放不下的位置时显示原因，否则显示校验错误 */
const msgText = computed(() => {
  const d = drag.value;
  if (d?.on && d.to !== null && !d.ok) return t("generate.rule.dropBad", { msg: reasonText(d.code) });
  return errorText.value;
});
const sampleRows = computed(() => [["sampleMin", sample.min], ["sampleMax", sample.max]].filter((r) => r[1]) as [string, Sample][]);

/** 默认前缀组件：本 PI 已生成的号还在时沿用已用的前缀（之后各张单据都用它，流水接续）；否则取点选的第一个单据编码后 7 位，
 *  取不到时用 PI 第一张单据的（同 genScope.defaultRulePrefix）。生成时后台查前缀全局唯一。 */
function defaultHead(): Part[] {
  if (props.rulePrefix?.custom) return [textPart(props.rulePrefix.prefix)];
  if ((props.lines[0]?.bill_no ?? "").slice(-7)) return [newPart("BILL")];
  return [textPart(props.rulePrefix?.prefix ?? "")];
}
/** 默认组成：默认前缀 + 流水 4 位（本 PI 已用前缀时按已用的格式），不带米数、不带单位 */
function defaultParts(): Part[] {
  const used = props.rulePrefix?.custom;
  return used ? fromSpec({ ...used, seq_start: null }, defaultHead()) : [...defaultHead(), newPart("SEQ")];
}
function resetParts() {
  const prev = parts.value, prevSel = selId.value;
  const ps = defaultParts();
  setParts(ps, seqId(ps));
  undoable(t("generate.rule.resetDone"), () => setParts(prev, prevSel));
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
  sample.min = null;
  sample.max = null;
  const cur = props.lines.length === 1 ? props.lines[0].rule : null;
  const base = props.rulePrefix?.custom ?? (cur?.mode === "CUSTOM" ? cur.custom : null);
  // 起始号只在沿用所选物料行现有的自定义规则时带出
  const start = !props.rulePrefix?.custom && cur?.mode === "CUSTOM" ? cur.seq_start ?? null : null;
  parts.value = base ? fromSpec({ ...base, seq_start: start }, defaultHead()) : [...defaultHead(), newPart("SEQ")];
  selId.value = seqId(parts.value);
  ins.value = null;
  drag.value = null;
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
  sample.min = null;
  sample.max = null;
  sample.error = "";
  const { spec, errors } = built.value;
  if (errors.length || !spec.prefix) return;
  try {
    const l = props.lines[0];
    // 后台返回从起始号起的前 5 个号与最大号：取第一个为最小、最后一个为最大
    const r = await api.previewRule({
      ...spec, seq_start: seqStart() ?? undefined, length: l?.length || undefined, unit: l?.unit || undefined,
    });
    const list: any[] = r.samples ?? [];
    const len = spec.length_pad ? String(r.sample_length ?? "").padStart(spec.length_pad, "0") : String(r.sample_length ?? "");
    const toSample = (x: any): Sample | null =>
      x ? { sn: x.sn, segs: sampleSegments(parts.value, partCtx.value, x.sn, x.seq_text, len, r.sample_unit ?? "") } : null;
    sample.min = toSample(list[0]);
    sample.max = toSample(list[list.length - 1]);
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
</script>

<style scoped>
.mb { margin-bottom: 12px; }
.mt { margin-top: 12px; }
.w120 { width: 120px; }
.w150 { width: 150px; }
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
.parts-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: -4px; }
.parts-head b { font-weight: 500; }
.lb { font-size: 12px; color: var(--app-muted); }
/* 常用格式 */
.tpls { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.tpl { font: inherit; font-size: 12px; cursor: pointer; border: 1px solid var(--el-border-color-lighter); background: var(--el-fill-color-light); color: var(--el-text-color-regular); border-radius: 12px; padding: 3px 10px; }
.tpl:hover { border-color: var(--el-color-primary-light-5); color: var(--el-color-primary); }
.tpl.on { border-color: var(--el-color-primary-light-5); background: var(--el-color-primary-light-9); color: var(--el-color-primary); }
/* 结构条：组件按 SN 的顺序横排，块内为取值、块下为类型 */
.bar { border: 1px solid var(--el-border-color); border-radius: 8px; padding: 12px 10px 8px; display: flex; flex-wrap: wrap; align-items: flex-start; row-gap: 8px; user-select: none; }
.chip { font: inherit; display: flex; flex-direction: column; align-items: center; gap: 3px; border: none; background: none; padding: 0; cursor: grab; touch-action: none; max-width: 100%; color: inherit; }
.chip:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 2px; border-radius: 6px; }
.chip .v { font-size: 16px; padding: 4px 8px; border-radius: 5px; border: 1.5px solid transparent; background: var(--el-fill-color); min-width: 28px; text-align: center; white-space: nowrap; max-width: 260px; overflow: hidden; text-overflow: ellipsis; }
.chip .v.blank { color: var(--app-muted); font-family: inherit; font-size: 12px; padding: 7px 8px; }
.chip .t { font-size: 11px; color: var(--app-muted); white-space: nowrap; }
.chip.c-SEP .v { background: transparent; border: 1.5px dashed var(--el-border-color); color: var(--el-text-color-regular); padding: 4px 6px; }
.chip.c-SEQ .v { background: var(--el-color-primary-light-9); color: var(--el-color-primary); border-color: var(--el-color-primary-light-5); }
.chip.c-LINE .v { background: var(--el-color-warning-light-9); color: var(--el-color-warning-dark-2); border-color: var(--el-color-warning-light-5); }
.chip.sel .v { border-color: var(--el-color-primary); box-shadow: 0 0 0 3px var(--el-color-primary-light-8); }
.chip.sel .t { color: var(--el-color-primary); font-weight: 600; }
.chip.dragging { opacity: 0.4; cursor: grabbing; }
.chip.dragging.bad { opacity: 1; }
.chip.bad .v { border-color: var(--el-color-danger); background: var(--el-color-danger-light-9); color: var(--el-color-danger); }
.chip.bad .t { color: var(--el-color-danger); }
.slot { font: inherit; width: 16px; height: 32px; border: none; background: none; padding: 0; position: relative; display: flex; align-items: center; justify-content: center; color: transparent; flex: none; cursor: pointer; }
.slot.last { width: 8px; cursor: default; }
.slot i { font-style: normal; width: 16px; height: 16px; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-size: 13px; line-height: 1; border: 1.5px solid transparent; position: relative; z-index: 1; }
.bar:hover .slot i, .slot:focus-visible i { color: var(--el-color-primary); border-color: var(--el-color-primary-light-5); background: var(--el-bg-color); }
@media (hover: none) { .slot i { color: var(--el-color-primary); border-color: var(--el-color-primary-light-5); background: var(--el-bg-color); } }
.slot:hover i, .slot.open i { color: #fff; background: var(--el-color-primary); border-color: var(--el-color-primary); }
.slot.open::before, .slot.drop::before { content: ""; position: absolute; top: 0; bottom: 0; left: 6px; width: 3px; border-radius: 2px; background: var(--el-color-primary); }
.slot.last.drop::before { left: 2px; }
.slot.drop.bad::before { background: var(--el-color-danger); }
.bar.is-drag .slot i { opacity: 0; }
.add-end { font: inherit; cursor: pointer; margin-left: 6px; border: 1.5px dashed var(--el-color-primary-light-5); background: var(--el-bg-color); color: var(--el-color-primary); border-radius: 5px; padding: 5px 10px; }
.add-end.open { background: var(--el-color-primary-light-9); }
.msg { font-size: 12px; color: var(--el-color-danger); margin-top: -2px; }
/* 选中组件的参数 / 插入面板 */
.panel { border: 1px solid var(--el-color-primary-light-5); border-radius: 8px; }
.panel-h { display: flex; justify-content: space-between; align-items: center; gap: 8px; padding: 4px 12px; border-bottom: 1px solid var(--el-border-color-lighter); background: var(--el-color-primary-light-9); border-radius: 8px 8px 0 0; }
.panel-h b { color: var(--el-color-primary); font-weight: 600; }
.panel-h .ops { display: flex; }
.panel-h .ops .el-button + .el-button { margin-left: 8px; }
.panel-b { padding: 12px; display: grid; grid-template-columns: 96px minmax(0, 1fr); gap: 10px 8px; align-items: center; }
.panel-b .k { font-size: 12px; color: var(--el-text-color-regular); }
.panel-b .val { display: flex; flex-wrap: wrap; gap: 6px 10px; align-items: center; min-width: 0; }
.mono-seg :deep(.el-radio-button__inner) { font-family: "JetBrains Mono", Consolas, monospace; }
.pal { padding: 12px; display: flex; flex-direction: column; gap: 8px; }
.pal .items + .items { padding-top: 8px; border-top: 1px dashed var(--el-border-color-lighter); }
.pal .items { display: flex; flex-wrap: wrap; gap: 6px; }
.pal .it { font: inherit; font-size: 13px; cursor: pointer; color: inherit; border: 1px solid var(--el-border-color-lighter); background: var(--el-bg-color); border-radius: 6px; padding: 5px 10px; min-width: 72px; }
.pal .it:hover:not(:disabled) { border-color: var(--el-color-primary); background: var(--el-color-primary-light-9); }
.pal .it:disabled { background: var(--el-fill-color-light); color: var(--el-text-color-placeholder); cursor: not-allowed; }
/* 示例：最小 / 最大号，流水与米数、单位按结构条同色 */
.sample { display: flex; flex-wrap: wrap; align-items: baseline; gap: 6px 14px; padding-top: 8px; border-top: 1px solid var(--el-border-color-lighter); }
.sample .rng { display: inline-flex; align-items: baseline; gap: 6px; min-width: 0; }
.sample .sn { font-size: 15px; word-break: break-all; }
.sample .s-SEQ { color: var(--el-color-primary); background: var(--el-color-primary-light-9); border-radius: 2px; }
.sample .s-LINE { color: var(--el-color-warning-dark-2); background: var(--el-color-warning-light-9); border-radius: 2px; }
.muted { color: var(--app-muted); }
.err { color: var(--el-color-danger); font-size: 12px; }
:global(.el-message a.undo) { color: var(--el-color-primary); cursor: pointer; margin-left: 8px; text-decoration: underline; }
</style>
