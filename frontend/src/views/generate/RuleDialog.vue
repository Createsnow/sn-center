<template>
  <el-dialog :model-value="modelValue" :title="t('generate.rule.dialogTitle')" width="960px" class="rule-dlg"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)" @open="onOpen">
    <!-- 默认在自定义页签：SN 由组件按顺序拼成，默认「单据编号后 7 位 + 流水」（本 PI 已生成的号还在时沿用已用的前缀）；不放说明文字 -->
    <div class="top">
      <el-radio-group v-model="mode" @change="(v: any) => v === 'CUSTOM' && schedule()">
        <el-radio-button value="CUSTOM">{{ t("generate.rule.custom") }}</el-radio-button>
        <el-radio-button value="LIBRARY" :disabled="!options.length">{{ t("generate.rule.library") }}</el-radio-button>
      </el-radio-group>
      <!-- 图例：组件按「保存时定值 / 流水 / 按物料行取值」着色 -->
      <div v-if="mode === 'CUSTOM'" class="legend">
        <span v-for="k in LEGEND" :key="k"><i class="sw" :class="`c-${k}`" />{{ t(`generate.rule.legend.${k}`) }}</span>
      </div>
    </div>

    <template v-if="mode === 'LIBRARY'">
      <el-input v-model="kw" clearable prefix-icon="Search" class="mb" />
      <!-- 每条列「规则编码 v版本」（本 PI 用的版本）、格式与本 PI 用它时的处理（须换前缀等） -->
      <div class="rule-list">
        <div v-for="o in filtered" :key="o.rule_id" class="it" :class="{ on: picked?.rule_id === o.rule_id }" tabindex="0"
          @click="pick(o)" @keyup.enter="pick(o)">
          <span class="radio" />
          <span class="nm">
            <b>{{ optionTitle(o) }}</b>
            <span v-if="o.format" class="fm">{{ o.format }}</span>
          </span>
          <span class="tags">
            <el-tag v-if="o.recommended" size="small" type="success">{{ t("generate.rule.recommended") }}</el-tag>
            <el-tag v-if="o.plan" size="small" :type="o.plan === 'PREFIX' ? 'warning' : 'info'">{{ t(`generate.plan.${o.plan}`) }}</el-tag>
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
         上方常用格式一键套用；示例按「前缀（流水之前）/ 流水 / 后缀（流水之后）」分段；
         左侧组件库添加到前缀或后缀，右侧组件清单按三段逐行改参数、上移 / 下移（越过流水即换段）、删除。
         弹窗只放控件与数据，不放说明文字 -->
    <div v-else class="parts-box">
      <div class="tpls">
        <span class="lb">{{ t("generate.rule.tpls") }}</span>
        <button v-for="k in TPL_KEYS" :key="k" type="button" class="tpl" :class="{ on: tplOn === k }" @click="applyTpl(k)">
          {{ t(`generate.rule.tpl.${k}`) }}
        </button>
      </div>

      <!-- 示例：上排为组成（各组件的取值，悬停清单行时高亮），下排为最小号 / 最大号（取自 /rules/preview） -->
      <div class="prev">
        <div class="big">
          <span v-for="g in bigGroups" :key="g.key" class="grp" :class="`g-${g.key}`">
            <span class="segs">
              <span v-for="s in g.segs" :key="s.id" class="bs" :class="[`c-${s.type}`, { hot: s.id === hot }]">
                <span class="n">{{ s.no }}</span><span class="v sn-mono">{{ s.text }}</span>
              </span>
            </span>
            <span class="br" />
            <span class="gt">{{ t(`generate.rule.sec.${g.key}`) }}</span>
          </span>
        </div>
        <div class="sample">
          <span class="lb">{{ t("generate.rule.sample") }}</span>
          <span v-if="errorText" class="err">{{ errorText }}</span>
          <span v-else-if="sample.error" class="err">{{ sample.error }}</span>
          <template v-else-if="sample.min">
            <span v-for="[label, s] in sampleRows" :key="label" class="rng">
              <span class="lb">{{ label === "sampleTop" ? t("generate.rule.sampleTop", { len: seqPart?.len }) : t(`generate.rule.${label}`) }}</span>
              <span class="sn sn-mono"><template v-if="s.segs"><span v-for="(g, j) in s.segs" :key="j" :class="g.cls && `s-${g.cls}`">{{ g.text }}</span></template><template v-else>{{ s.sn }}</template></span>
            </span>
          </template>
          <span v-else class="muted">—</span>
        </div>
      </div>

      <div class="cols">
        <!-- 组件库：固定字符、取单据·日期按「添加到」放进前缀或后缀；米数、单位只能在后缀；已有的置灰 -->
        <div class="lib">
          <span class="gl">{{ t("generate.rule.addTo") }}</span>
          <el-radio-group v-model="target" size="small" class="tg">
            <el-radio-button value="PRE">{{ t("generate.rule.sec.PRE") }}</el-radio-button>
            <el-radio-button value="SUF">{{ t("generate.rule.sec.SUF") }}</el-radio-button>
          </el-radio-group>
          <template v-for="g in GROUPS" :key="g.key">
            <span class="gl">{{ t(`generate.rule.group.${g.key}`) }}</span>
            <button v-for="k in g.kinds" :key="k" type="button" class="it" :disabled="!!addBlock[k]"
              :title="addBlock[k] ? reasonText(addBlock[k]) : undefined" @click="addPart(k)">
              <span>＋ {{ t(`generate.rule.part.${k}`) }}</span>
              <small v-if="addBlock[k] === 'TAKEN'">{{ t("generate.rule.taken") }}</small>
              <small v-else-if="g.key === 'LINE'">{{ t("generate.rule.toSuf") }}</small>
            </button>
          </template>
        </div>

        <!-- 组件清单：前缀 / 流水 / 后缀三段，序号与示例上的序号对应 -->
        <div class="list">
          <div class="lh">
            <span>{{ t("generate.rule.col.NO") }}</span><span>{{ t("generate.rule.col.PART") }}</span><span>{{ t("generate.rule.col.SET") }}</span>
            <span>{{ t("generate.rule.col.VAL") }}</span><span class="r">{{ t("generate.rule.col.OPS") }}</span>
          </div>
          <template v-for="sec in sections" :key="sec.key">
            <div class="sh" :class="`sh-${sec.key}`">
              <b>{{ t(`generate.rule.sec.${sec.key}`) }}</b>
              <span class="lb">{{ t(`generate.rule.secHint.${sec.key}`) }}</span>
              <span v-if="sec.key !== 'SEQ'" class="sv sn-mono">{{ sec.text || "—" }}</span>
              <span v-if="sec.key === 'PRE'" class="cnt" :class="{ bad: built.spec.prefix.length > PREFIX_MAX }">
                {{ t("generate.rule.prefixLen", { n: built.spec.prefix.length, max: PREFIX_MAX }) }}
              </span>
            </div>
            <div v-if="!sec.rows.length" class="empty-row">{{ t("generate.rule.none") }}</div>
            <div v-for="{ p, i } in sec.rows" :key="p.id" class="row" :class="{ hot: p.id === hot, 'is-seq': p.kind === 'SEQ' }"
              @mouseenter="hot = p.id" @mouseleave="hot = null">
              <span class="no">{{ i + 1 }}</span>
              <span class="kt" :class="`c-${chipType(p.kind)}`">{{ t(`generate.rule.part.${p.kind}`) }}</span>
              <span class="set">
                <template v-if="p.kind === 'TEXT'">
                  <el-input v-model="p.text" :maxlength="TEXT_MAX" size="small" class="w180 mono-in" :placeholder="t('generate.rule.text')" />
                </template>
                <template v-else-if="p.kind === 'SEP'">
                  <el-radio-group v-model="p.text" size="small" class="mono-seg">
                    <el-radio-button v-for="s in SEPS" :key="s" :value="s">{{ s }}</el-radio-button>
                  </el-radio-group>
                </template>
                <template v-else-if="p.kind === 'PI' || p.kind === 'BILL'">
                  <el-radio-group v-model="p.cut" size="small">
                    <el-radio-button v-for="c in CUTS" :key="c" :value="c">{{ t(`generate.rule.cut.${c}`) }}</el-radio-button>
                  </el-radio-group>
                  <span v-if="p.cut !== 'ALL'" class="fi">
                    {{ t("generate.rule.digits") }}
                    <el-input-number v-model="p.n" :min="1" :max="64" :step="1" step-strictly size="small" class="w100" />
                  </span>
                </template>
                <template v-else-if="p.kind === 'DATE'">
                  <span class="fi">
                    <el-date-picker v-model="p.date" type="date" value-format="YYYY-MM-DD" :clearable="false" size="small" style="width: 132px" />
                    <el-button link type="primary" size="small" @click="p.date = today()">{{ t("generate.rule.today") }}</el-button>
                  </span>
                  <el-radio-group v-model="p.fmt" size="small" class="mono-seg">
                    <el-radio-button v-for="f in DATE_FMTS" :key="f" :value="f" :title="t(`generate.rule.dateFmt.${f}`)">{{ f }}</el-radio-button>
                  </el-radio-group>
                </template>
                <template v-else-if="p.kind === 'SEQ'">
                  <!-- 前缀已归属本 PI（含号已全部删掉的）：进制、流水位数锁定为那个前缀的格式，置灰（悬停显示原因） -->
                  <span class="fi" :title="lockTitle">
                    {{ t("generate.rule.base") }}
                    <el-radio-group v-model="p.base" size="small" class="mono-seg" :disabled="!!locked">
                      <el-radio-button v-for="b in BASES" :key="b" :value="b">{{ b }}</el-radio-button>
                    </el-radio-group>
                  </span>
                  <span class="fi" :title="lockTitle">
                    {{ t("generate.rule.seqLen") }}
                    <el-input-number v-model="p.len" :min="1" :max="SEQ_LEN_MAX" :step="1" step-strictly size="small" class="w100" :disabled="!!locked" />
                  </span>
                  <!-- 上限自动突破（默认开启）：流水用完位数后自动加位（4 位用到 9999 后接 5 位 10000，以此类推）；
                       关闭时保持原逻辑（位数按订单数量定，用完报规则位数已用尽） -->
                  <span class="fi grow" :class="{ on: p.grow }">
                    <el-switch v-model="p.grow" size="small" />
                    {{ t("generate.rule.seqGrow") }}
                  </span>
                  <span v-if="seqShort(p)" class="fi seq-short">
                    {{ t("generate.rule.seqTooShort", { need, max: maxSeq(p), len: p.len, base: p.base }) }}
                  </span>
                  <!-- 起始号（十进制流水）：下限 startMin ～ 最大流水，默认为下限；等于下限时按未指定保存（start = null，请求体与原来相同）。
                       本 PI 已有号段预留时下限为预留起号（按预留号段生成），改大则所选物料行按预留号段整体平移，原预留留作空号段；
                       还没有预留时：新前缀下限为 1，本 PI 已用这个前缀时为接续的下一个号 -->
                  <span class="fi">
                    {{ t("generate.rule.seqStart") }}
                    <el-input-number :model-value="p.start ?? startMin" :min="startMin" :max="maxSeq(p)" value-on-clear="min" :step="1"
                      step-strictly size="small" class="w140"
                      @update:model-value="(v: any) => (p.start = Number(v) > startMin ? Number(v) : null)" />
                    <el-tooltip v-if="reserveStart" placement="top" :content="t('generate.rule.seqStartContinue', { next: startMin })">
                      <el-icon class="tip"><QuestionFilled /></el-icon>
                    </el-tooltip>
                  </span>
                </template>
                <template v-else-if="p.kind === 'LEN'">
                  <span class="fi">
                    {{ t("generate.rule.lengthPad") }}
                    <el-input-number v-model="p.pad" :min="0" :max="PAD_MAX" :step="1" step-strictly size="small" class="w100" />
                  </span>
                </template>
                <span v-else>{{ t("generate.rule.unitFromLine") }}</span>
              </span>
              <span class="rv sn-mono" :class="{ blank: !chipValue(p) }" :title="chipValue(p)">{{ chipValue(p) || t("generate.rule.blank") }}</span>
              <!-- 流水不能移动、删除（必须且只有一个）；上移 / 下移放不下时置灰 -->
              <span class="acts">
                <template v-if="p.kind !== 'SEQ'">
                  <el-button link :icon="Top" :disabled="!canMove(i, -1)" :aria-label="t('generate.rule.moveUp')" :title="t('generate.rule.moveUp')" @click="moveRow(i, -1)" />
                  <el-button link :icon="Bottom" :disabled="!canMove(i, 1)" :aria-label="t('generate.rule.moveDown')" :title="t('generate.rule.moveDown')" @click="moveRow(i, 1)" />
                  <el-button link type="danger" :icon="Delete" :aria-label="t('common.delete')" :title="t('common.delete')" @click="removeRow(i)" />
                </template>
              </span>
            </div>
          </template>
        </div>
      </div>
    </div>

    <!-- 底部：左「恢复默认」（自定义页签），右取消、确定；不提供「改回按 PI / 客户默认」（要撤回物料行选定的规则，用详情底部「更多 → 撤销配置」） -->
    <template #footer>
      <div class="foot">
        <span><el-button v-if="mode === 'CUSTOM'" link type="primary" @click="resetParts">{{ t("generate.rule.resetParts") }}</el-button></span>
        <span>
          <el-button @click="emit('update:modelValue', false)">{{ t("common.cancel") }}</el-button>
          <el-button type="primary" :loading="busy === 'save'" :disabled="!canSave" @click="save">{{ t("common.confirm") }}</el-button>
        </span>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, h, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { Bottom, Delete, QuestionFilled, Top } from "@element-plus/icons-vue";
import { api } from "@/api";
import { ApiError } from "@/api/http";
import { BASES, CHARSETS } from "@/constants";
import { useTime } from "@/composables/useTime";
import { translateApiError } from "@/i18n";
import {
  CUTS, DATE_FMTS, PAD_MAX, PREFIX_MAX, SEP_MAX, SEPS, SEQ_LEN_MAX, TEMPLATES, TEXT_MAX,
  SEQ_LEN_DEFAULT, addIndex, blockReason, fromSpec, insertAt, maxSeqOf, moveTo, newErrors, newPart, partValue, partsSig, sampleSegments,
  seqLenFor, seqNeed, seqText as seqTextOf, textPart, todayIn, toSpec,
} from "@/snParts.js";
import type { CustomRule, OrderLine } from "@/types/api";

/** ctx.rule_prefix（见 rules.pi_prefix）：custom = 本 PI 已生成的号还在时所用前缀的格式，prefix 为这个前缀；
 *  还没有生成的号（或已全部删掉）时 custom 为 null，prefix 为 PI 第一张单据编码后 7 位（没有点选的单据时才用）。 */
type RulePrefix = { prefix: string; custom: CustomRule | null };
/** SN 组成的一个组件（见 snParts.js） */
type Part = {
  id: number; kind: string; text?: string; cut?: string; n?: number; fmt?: string; date?: string;
  base?: number; len?: number; start?: number | null; grow?: boolean; pad?: number;
};
type Seg = { text: string; cls: string };
type Sample = { sn: string; segs: Seg[] | null };
/** 三段：前缀（流水之前）/ 流水 / 后缀（流水之后，含米数、单位） */
type SecKey = "PRE" | "SEQ" | "SUF";
/** prefixLocked：本 PI 已占用的前缀 → 锁定的格式（ctx.prefix_locked，见 rules.pi_prefix_locked；号全部删掉后仍锁定）；
 *  nextSeq：本 PI 接续的下一个号（ctx.counter.next_seq），已用前缀时起始号的下限；
 *  piQuota：本 PI 全部物料行还可生成的数量之和（各行订单数量 − 本单已生成），按它定流水位数的默认值 */
const props = defineProps<{
  modelValue: boolean; pi: string; lines: OrderLine[]; options: any[]; rulePrefix?: RulePrefix; nextSeq?: number; piQuota?: number;
  /** 本 PI 号段预留的预留起号（还没有预留为 null）：起始号的默认值 */
  reserveStart?: number | null;
  prefixLocked?: Record<string, CustomRule>;
}>();
const emit = defineEmits<{ (e: "update:modelValue", v: boolean): void; (e: "saved"): void }>();
const { t } = useI18n();
const { offset } = useTime();

/** 常用格式（顺序即按钮顺序） */
const TPL_KEYS = Object.keys(TEMPLATES) as (keyof typeof TEMPLATES)[];
/** 组件库分组（流水必须且只有一个，不在组件库里） */
const GROUPS = [
  { key: "FIX", kinds: ["TEXT", "SEP"] },
  { key: "DOC", kinds: ["PI", "BILL", "DATE"] },
  { key: "LINE", kinds: ["LEN", "UNIT"] },
];
const LEGEND = ["FIX", "SEQ", "LINE"];

const mode = ref<"LIBRARY" | "CUSTOM">("LIBRARY");
const kw = ref("");
const picked = ref<any>(null);
const newPrefix = ref("");
const parts = ref<Part[]>([]);
/** 组件库「添加到」：前缀 / 后缀 */
const target = ref<"PRE" | "SUF">("PRE");
/** 悬停的清单行（示例上同一组件高亮） */
const hot = ref<number | null>(null);
/** 示例：最小号、最大号；上限自动突破时 max 为规则位数的最大号，brk 为突破后的第一个号 */
const sample = reactive<{ min: Sample | null; max: Sample | null; brk: Sample | null; error: string }>({
  min: null, max: null, brk: null, error: "",
});
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
const tplOn = computed(() => {
  const cur = partsSig(parts.value);
  return TPL_KEYS.find((k) => partsSig(TEMPLATES[k](today())) === cur) ?? "";
});

/** 流水文本（与后台 encoding.encode_seq 相同的字符表；上限自动突破时装不下按实际位数） */
const seqText = (n: number, base: number, len: number, grow = false) => seqTextOf(n, base, len, CHARSETS[base] ?? CHARSETS[10], grow);
/** 本 PI 还要用到的最大流水：need 不留余量（位数不够的提示用），needDefault 留余量（定默认位数用） */
const need = computed(() => seqNeed(props.nextSeq, props.piQuota, 1));
const needDefault = computed(() => seqNeed(props.nextSeq, props.piQuota));
/** 新的流水组件：上限自动突破默认开启，位数默认 4 位（用完自动加位）；
 *  关闭时（原逻辑）位数按本 PI 订单数量取装得下的最少位数（不少于 4 位，订单数量小时与原来相同） */
const autoLen = (base: number, grow: boolean) => (grow ? SEQ_LEN_DEFAULT : seqLenFor(base, needDefault.value));
const autoSeq = (base = 10, grow = true): Part => ({ ...newPart("SEQ"), base, grow, len: autoLen(base, grow) });
/** 当前进制、位数下的最大流水（起始号上限）；上限自动突破时为加到 12 位的最大流水 */
const maxSeq = (p: Part) => maxSeqOf(p.base, p.len, p.grow);
/** 组件的取值：保存时定值的组件取值；流水取第一个号；米数、单位取第一个物料行的 */
function chipValue(p: Part) {
  const v = partValue(p, partCtx.value);
  if (v !== null) return v;
  const l = props.lines[0];
  if (p.kind === "SEQ") {
    const st = Number(p.start);
    return seqText(Number.isSafeInteger(st) && st > startMin.value ? st : startMin.value, Number(p.base) || 10, Number(p.len) || 4, !!p.grow);
  }
  if (p.kind === "LEN") return p.pad ? (l?.length ?? "").padStart(p.pad, "0") : l?.length ?? "";
  return l?.unit ?? "";
}
const chipType = (k: string) => (k === "SEQ" ? "SEQ" : k === "LEN" || k === "UNIT" ? "LINE" : k === "SEP" ? "SEP" : "FIX");

/** 流水的位置；没有流水时（不应出现）全部算前缀 */
const seqAt = computed(() => {
  const i = parts.value.findIndex((p) => p.kind === "SEQ");
  return i < 0 ? parts.value.length : i;
});
const secOf = (i: number): SecKey => (i < seqAt.value ? "PRE" : i === seqAt.value ? "SEQ" : "SUF");
/** 组件清单的三段（i 为在整条组成里的下标，序号 = i + 1） */
const sections = computed(() =>
  (["PRE", "SEQ", "SUF"] as SecKey[]).map((key) => {
    const rows = parts.value.map((p, i) => ({ p, i })).filter((r) => secOf(r.i) === key);
    return { key, rows, text: rows.map((r) => chipValue(r.p)).join("") };
  }),
);
/** 示例上排：有取值的组件按三段分组 */
const bigGroups = computed(() =>
  sections.value
    .map((s) => ({
      key: s.key,
      segs: s.rows.map(({ p, i }) => ({ id: p.id, no: i + 1, text: chipValue(p), type: chipType(p.kind) })).filter((g) => g.text),
    }))
    .filter((g) => g.segs.length),
);

const reasonText = (code: string) =>
  t(code === "TAKEN" ? "generate.rule.taken" : `generate.rule.err.${code}`, { max: code === "SEP_TOO_LONG" ? SEP_MAX : PREFIX_MAX });
/** 组件库各组件按「添加到」放不下的原因（空为放得下） */
const addBlock = computed<Record<string, string>>(() =>
  Object.fromEntries(GROUPS.flatMap((g) => g.kinds).map((k) => [
    k, blockReason(parts.value, k, addIndex(parts.value, k, target.value, partCtx.value), partCtx.value),
  ])),
);
function setParts(ps: Part[]) {
  parts.value = ps;
}
/** 提示里带「撤销」 */
function undoable(text: string, undo: () => void) {
  const m = ElMessage({
    type: "success",
    duration: 5000,
    message: h("span", [text, " ", h("a", { class: "undo", onClick: () => { undo(); m.close(); } }, t("generate.rule.undo"))]),
  });
}
function applyTpl(k: keyof typeof TEMPLATES) {
  const prev = parts.value;
  setParts(TEMPLATES[k](today()) as Part[]);
  undoable(t("generate.rule.tplApplied", { name: t(`generate.rule.tpl.${k}`) }), () => setParts(prev));
}
function addPart(kind: string) {
  if (addBlock.value[kind]) return;
  setParts(insertAt(parts.value, addIndex(parts.value, kind, target.value, partCtx.value), newPart(kind, today())));
}
/** 上移 / 下移第 i 个组件（d = -1 / 1）：越过流水即在前缀、后缀之间换段；放不下时按钮置灰 */
const moveTarget = (i: number, d: number) => (d < 0 ? i - 1 : i + 2);
function canMove(i: number, d: number) {
  const to = moveTarget(i, d);
  if (to < 0 || to > parts.value.length) return false;
  return !newErrors(parts.value, moveTo(parts.value, i, to), partCtx.value).length;
}
function moveRow(i: number, d: number) {
  if (canMove(i, d)) setParts(moveTo(parts.value, i, moveTarget(i, d)));
}
function removeRow(i: number) {
  const prev = parts.value, p = prev[i];
  if (!p || p.kind === "SEQ") return;
  hot.value = null;
  setParts(prev.filter((_, j) => j !== i));
  undoable(t("generate.rule.partRemoved", { name: t(`generate.rule.part.${p.kind}`) }), () => setParts(prev));
}
/** 示例行：最小号、最大号；上限自动突破时为最小号、规则位数的最大号、突破后的第一个号 */
const sampleRows = computed(() =>
  (sample.brk
    ? [["sampleMin", sample.min], ["sampleTop", sample.max], ["sampleBrk", sample.brk]]
    : [["sampleMin", sample.min], ["sampleMax", sample.max]]
  ).filter((r) => r[1]) as [string, Sample][],
);

/** 默认前缀组件：本 PI 已生成的号还在时沿用已用的前缀（之后各张单据都用它，流水接续）；否则取点选的第一个单据编码后 7 位，
 *  取不到时用 PI 第一张单据的（同 genScope.defaultRulePrefix）。生成时后台查前缀全局唯一。 */
function defaultHead(): Part[] {
  if (props.rulePrefix?.custom) return [textPart(props.rulePrefix.prefix)];
  if ((props.lines[0]?.bill_no ?? "").slice(-7)) return [newPart("BILL")];
  return [textPart(props.rulePrefix?.prefix ?? "")];
}
/** 前缀已归属本 PI 时锁定的格式（进制、流水位数不能改） */
const lockedOf = (prefix: string): CustomRule | null => props.prefixLocked?.[prefix] ?? null;
/** 默认组成：默认前缀 + 流水（上限自动突破默认开启、4 位；本 PI 已用前缀时按已用的格式；默认前缀已归属本 PI 时
 *  （号已全部删掉）按它锁定的格式），不带米数、不带单位 */
function defaultParts(grow = true): Part[] {
  const head = defaultHead();
  const used = props.rulePrefix?.custom ?? lockedOf(toSpec([...head, newPart("SEQ")], partCtx.value).spec.prefix);
  return used ? fromSpec({ ...used, seq_start: null, seq_grow: grow }, head) : [...head, autoSeq(10, grow)];
}
function resetParts() {
  const prev = parts.value;
  setParts(defaultParts());
  undoable(t("generate.rule.resetDone"), () => setParts(prev));
}

/** 规则选择列「规则编码 v版本」 */
const optionTitle = (o: any) => (o.version ? `${o.rule_code} v${o.version}` : o.rule_code);
const filtered = computed(() => {
  const k = kw.value.trim().toLowerCase();
  if (!k) return props.options;
  return props.options.filter((o) => `${o.rule_code} ${o.rule_name} ${o.category_value}`.toLowerCase().includes(k));
});
/** 当前前缀锁定的格式（已归属本 PI）；置灰进制、流水位数时的悬停说明 */
const locked = computed(() => lockedOf(built.value.spec.prefix));
const lockTitle = computed(() =>
  locked.value
    ? translateApiError(
      { code: "RULE_PREFIX_FORMAT_LOCKED", params: { prefix: built.value.spec.prefix, base: locked.value.base, seq_len: locked.value.seq_len } },
      "",
    )
    : undefined,
);
/** 本 PI 已用这个前缀（同格式沿用那一版、流水接续）：起始号下限为接续的下一个号，可改大往后跳。 */
const prefixUsed = computed(() => !!props.rulePrefix?.custom && built.value.spec.prefix === props.rulePrefix.prefix);
/** 本 PI 号段预留的预留起号（有预留时起始号默认为它，按预留号段生成；改大即平移） */
const reserveStart = computed(() => (Number(props.reserveStart) > 0 ? Number(props.reserveStart) : 0));
/** 起始号下限：有预留时为预留起号；没有时已用前缀为接续的下一个号，否则为 1 */
const startMin = computed(() => reserveStart.value || (prefixUsed.value ? Math.max(Number(props.nextSeq) || 1, 1) : 1));
/** 提交用的起始号：大于下限才提交（等于下限即接续 / 从 1 起，按未指定，请求体与原来相同） */
const seqStart = () => {
  const s = built.value.spec.seq_start;
  return s && s > startMin.value ? s : null;
};
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
  sample.brk = null;
  const cur = props.lines.length === 1 ? props.lines[0].rule : null;
  const base = props.rulePrefix?.custom ?? (cur?.mode === "CUSTOM" ? cur.custom : null);
  // 起始号带出所选物料行已存的：新前缀的自定义规则，或沿用本 PI 已用前缀时另记的（还没生成过）
  const own = props.rulePrefix?.custom ? cur?.prefix === props.rulePrefix.prefix : cur?.mode === "CUSTOM";
  const start = own ? cur?.seq_start ?? null : null;
  // 上限自动突破带出所选物料行已存的（同上），否则默认开启
  const grow = own ? cur?.seq_grow ?? true : true;
  parts.value = base ? fromSpec({ ...base, seq_start: start, seq_grow: grow }, defaultHead()) : defaultParts(grow);
  target.value = "PRE";
  hot.value = null;
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

/** 改进制时：位数还是上一个进制按订单数量给的默认值（没手动改过）就跟着换成新进制的默认值；本 PI 已用的前缀格式锁定，不动 */
const seqPart = computed(() => parts.value.find((p) => p.kind === "SEQ"));
watch(
  () => [seqPart.value?.id, seqPart.value?.base] as const,
  ([id, b], [oldId, old]) => {
    const p = seqPart.value;
    // 只管同一个流水组件上改进制；整体换组成（打开、套用常用格式、重置）时不动
    if (!p || id !== oldId || b === undefined || old === undefined || b === old || prefixUsed.value || locked.value || p.grow) return;
    if (p.len === seqLenFor(old, needDefault.value)) p.len = seqLenFor(b, needDefault.value);
  },
);
/** 开关上限自动突破时：位数还是默认值（没手动改过）就换成另一种的默认值（开启 4 位，关闭按订单数量）；本 PI 已用的前缀格式锁定，不动 */
watch(
  () => [seqPart.value?.id, seqPart.value?.grow] as const,
  ([id, g], [oldId, old]) => {
    const p = seqPart.value;
    if (!p || id !== oldId || g === undefined || old === undefined || g === old || prefixUsed.value || locked.value) return;
    const b = Number(p.base) || 10;
    if (p.len === autoLen(b, !!old)) p.len = autoLen(b, !!g);
  },
);
/** 前缀改成（或套用常用格式后成为）已归属本 PI 的前缀：进制、流水位数改为它锁定的格式（同一前缀下不能改） */
watch(
  () => [locked.value, seqPart.value?.id] as const,
  ([lk]) => {
    const p = seqPart.value;
    if (!lk || !p) return;
    if (p.base !== lk.base) p.base = lk.base;
    if (p.len !== lk.seq_len) p.len = lk.seq_len;
  },
);
/** 位数不够装下本 PI 还要的流水（不含余量）：提示改大位数或换进制 */
const seqShort = (p: Part) => !p.grow && !prefixUsed.value && !locked.value && maxSeq(p) < need.value;

async function previewCustom() {
  sample.min = null;
  sample.max = null;
  sample.brk = null;
  sample.error = "";
  const { spec, errors } = built.value;
  if (errors.length || !spec.prefix) return;
  try {
    const l = props.lines[0];
    // 后台返回从起始号起的前 5 个号与最大号：取第一个为最小、最后一个为最大
    const r = await api.previewRule({
      // 示例从实际的第一个号起：填了起始号按它，已用前缀没填按接续的下一个号
      ...spec, seq_start: seqStart() ?? (startMin.value > 1 ? startMin.value : undefined), length: l?.length || undefined, unit: l?.unit || undefined,
    });
    const list: any[] = r.samples ?? [];
    const len = spec.length_pad ? String(r.sample_length ?? "").padStart(spec.length_pad, "0") : String(r.sample_length ?? "");
    const toSample = (x: any): Sample | null =>
      x ? { sn: x.sn, segs: sampleSegments(parts.value, partCtx.value, x.sn, x.seq_text, len, r.sample_unit ?? "") } : null;
    sample.min = toSample(list[0]);
    // 上限自动突破：后台在规则位数的最大号之后另带突破后的第一个号（最大流水 + 1）
    const top = Number(r.max_seq);
    const brk = spec.seq_grow ? list.find((x) => Number(x.seq_dec) === top + 1) : undefined;
    sample.max = toSample(brk ? list.find((x) => Number(x.seq_dec) === top) : list[list.length - 1]);
    sample.brk = toSample(brk);
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
      // 请求体：prefix、base、seq_len、with_length、length_pad、sep_before、sep_after、with_unit、suffix、seq_start，
      // 另带 seq_grow（上限自动突破，后台不传按关闭）
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
.w100 { width: 100px; }
.w140 { width: 140px; }
.tip { margin-left: -6px; color: var(--el-text-color-secondary); cursor: help; }
.w180 { width: 180px; }
.w200 { width: 200px; }
.top { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.lb { font-size: 12px; color: var(--el-text-color-regular); }
.muted { color: var(--app-muted); }
.err { color: var(--el-color-danger); font-size: 12px; }
/* 着色：保存时定值 / 流水 / 按物料行取值（分隔符同定值，虚线） */
.legend { display: flex; gap: 12px; align-items: center; font-size: 12px; color: var(--el-text-color-regular); }
.legend > span { display: inline-flex; align-items: center; gap: 4px; }
.sw { width: 12px; height: 12px; border-radius: 3px; display: inline-block; border: 1px solid var(--el-border-color-darker); background: var(--el-fill-color); }
.sw.c-SEQ { background: var(--el-color-primary-light-9); border-color: var(--el-color-primary); }
.sw.c-LINE { background: var(--el-color-warning-light-9); border-color: var(--el-color-warning); }
/* 规则库 */
.rule-list { border: 1px solid var(--el-border-color-lighter); border-radius: 8px; max-height: 360px; overflow: auto; }
.rule-list .it { display: grid; grid-template-columns: 16px minmax(0, 1fr) auto; gap: 10px; align-items: center; padding: 8px 12px; cursor: pointer; border-bottom: 1px solid var(--el-border-color-lighter); outline: none; }
.rule-list .it:last-child { border-bottom: none; }
.rule-list .it:hover, .rule-list .it:focus-visible { background: var(--el-fill-color-light); }
.rule-list .it.on { background: var(--el-color-primary-light-9); }
.rule-list .radio { width: 14px; height: 14px; border-radius: 50%; border: 1.5px solid var(--el-border-color-darker); box-sizing: border-box; }
.rule-list .it.on .radio { border: 4px solid var(--el-color-primary); }
.rule-list .nm { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.rule-list .nm b { font-weight: 500; white-space: nowrap; }
.rule-list .fm { font-size: 12px; color: var(--el-text-color-regular); word-break: break-all; }
.rule-list .tags { display: flex; gap: 6px; }
.rule-list .empty { padding: 20px; text-align: center; color: var(--app-muted); }
.parts-box { display: flex; flex-direction: column; gap: 12px; }
/* 常用格式 */
.tpls { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.tpl { font: inherit; font-size: 12px; cursor: pointer; border: 1px solid var(--el-border-color-lighter); background: var(--el-fill-color-light); color: var(--el-text-color-regular); border-radius: 12px; padding: 3px 10px; }
.tpl:hover { border-color: var(--el-color-primary-light-5); color: var(--el-color-primary); }
.tpl.on { border-color: var(--el-color-primary-light-5); background: var(--el-color-primary-light-9); color: var(--el-color-primary); }
/* 示例：上排按前缀 / 流水 / 后缀分组，下排最小 / 最大号 */
.prev { border: 1px solid var(--el-border-color); border-radius: 8px; background: var(--el-fill-color-lighter); padding: 12px 16px 10px; display: flex; flex-direction: column; gap: 10px; }
.big { display: flex; flex-wrap: wrap; align-items: flex-start; gap: 8px 6px; min-height: 64px; }
.grp { display: flex; flex-direction: column; gap: 4px; min-width: 0; max-width: 100%; }
.grp .segs { display: flex; flex-wrap: wrap; align-items: flex-end; }
.grp .br { height: 7px; border: 1.5px solid var(--el-text-color-secondary); border-top: none; border-radius: 0 0 5px 5px; margin: 0 1px; }
.grp .gt { font-size: 12px; color: var(--el-text-color-regular); text-align: center; font-weight: 600; }
.grp.g-SEQ .br { border-color: var(--el-color-primary); }
.grp.g-SEQ .gt { color: var(--el-color-primary); }
.grp.g-SUF .br { border-style: dashed; }
.bs { display: flex; flex-direction: column; align-items: center; gap: 2px; }
.bs .n { font-size: 10px; line-height: 1; color: var(--el-text-color-regular); }
.bs .v { font-size: 22px; line-height: 1.3; padding: 0 2px; border-radius: 3px; border-bottom: 3px solid transparent; white-space: nowrap; }
.bs.c-SEQ .v { color: var(--el-color-primary); background: var(--el-color-primary-light-9); }
.bs.c-LINE .v { color: var(--el-color-warning-dark-2); background: var(--el-color-warning-light-9); }
.bs.hot .v { border-bottom-color: var(--el-color-primary); background: var(--el-color-primary-light-8); }
.bs.hot .n { color: var(--el-color-primary); font-weight: 700; }
.sample { display: flex; flex-wrap: wrap; align-items: baseline; gap: 6px 14px; }
.sample .rng { display: inline-flex; align-items: baseline; gap: 6px; min-width: 0; }
.sample .sn { font-size: 14px; word-break: break-all; }
.sample .s-SEQ { color: var(--el-color-primary); background: var(--el-color-primary-light-9); border-radius: 2px; }
.sample .s-LINE { color: var(--el-color-warning-dark-2); background: var(--el-color-warning-light-9); border-radius: 2px; }
/* 组件库 + 组件清单 */
.cols { display: flex; gap: 14px; align-items: flex-start; }
.lib { width: 160px; flex: none; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; padding: 10px; display: flex; flex-direction: column; gap: 8px; }
.lib .gl { font-size: 12px; color: var(--el-text-color-regular); margin-top: 2px; }
.lib .tg { display: flex; }
.lib .tg :deep(.el-radio-button) { flex: 1; }
.lib .tg :deep(.el-radio-button__inner) { width: 100%; }
.lib .it { font: inherit; font-size: 13px; text-align: left; cursor: pointer; border: 1px solid var(--el-border-color); background: var(--el-bg-color); border-radius: 4px; padding: 5px 10px; color: var(--el-text-color-primary); display: flex; justify-content: space-between; align-items: center; gap: 4px; }
.lib .it:hover:not(:disabled) { border-color: var(--el-color-primary); background: var(--el-color-primary-light-9); color: var(--el-color-primary); }
.lib .it:disabled { background: var(--el-fill-color-light); color: var(--el-text-color-placeholder); cursor: not-allowed; }
.lib .it small { font-size: 11px; color: var(--el-text-color-regular); white-space: nowrap; }
.lib .it:disabled small { color: var(--el-text-color-placeholder); }
.list { flex: 1; min-width: 0; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; overflow: hidden; }
.lh, .row { display: grid; grid-template-columns: 30px 80px minmax(0, 1fr) 112px 84px; align-items: center; gap: 8px; padding: 0 12px; }
.lh { height: 32px; background: var(--el-fill-color-light); font-size: 12px; color: var(--el-text-color-regular); border-bottom: 1px solid var(--el-border-color-lighter); }
.lh .r { text-align: right; }
.sh { display: flex; align-items: center; gap: 10px; padding: 6px 12px; border-bottom: 1px solid var(--el-border-color-lighter); background: var(--el-fill-color-lighter); min-width: 0; }
.sh b { font-size: 13px; font-weight: 600; white-space: nowrap; }
.sh .lb { white-space: nowrap; }
.sh .sv { font-size: 13px; background: var(--el-bg-color); border: 1px solid var(--el-border-color-lighter); border-radius: 3px; padding: 0 6px; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sh .cnt { font-size: 12px; color: var(--el-text-color-regular); margin-left: auto; white-space: nowrap; }
.sh .cnt.bad { color: var(--el-color-danger); }
.sh.sh-SEQ { background: var(--el-color-primary-light-9); }
.sh.sh-SEQ b { color: var(--el-color-primary); }
.empty-row { padding: 8px 12px 8px 50px; font-size: 12px; color: var(--el-text-color-regular); border-bottom: 1px solid var(--el-border-color-lighter); }
.row { min-height: 44px; padding-top: 6px; padding-bottom: 6px; border-bottom: 1px solid var(--el-border-color-lighter); }
.list > :last-child { border-bottom: none; }
.row:hover, .row.hot { background: var(--el-color-primary-light-9); }
.no { width: 20px; height: 20px; border-radius: 50%; background: var(--el-fill-color-dark); color: var(--el-text-color-regular); font-size: 11px; line-height: 20px; text-align: center; }
.row.hot .no { background: var(--el-color-primary); color: #fff; }
.kt { font-size: 12px; line-height: 1.4; padding: 1px 8px; border-radius: 3px; justify-self: start; max-width: 100%; box-sizing: border-box; overflow-wrap: anywhere; border: 1px solid var(--el-border-color-lighter); background: var(--el-fill-color-light); color: var(--el-text-color-regular); }
.kt.c-SEQ { background: var(--el-color-primary-light-9); color: var(--el-color-primary); border-color: var(--el-color-primary-light-5); }
.kt.c-LINE { background: var(--el-color-warning-light-9); color: var(--el-color-warning-dark-2); border-color: var(--el-color-warning-light-5); }
.set { display: flex; flex-wrap: wrap; gap: 6px 12px; align-items: center; font-size: 12px; color: var(--el-text-color-regular); min-width: 0; }
.set .fi { display: inline-flex; align-items: center; gap: 6px; }
.set .grow { padding: 0 8px; height: 24px; border-radius: 12px; background: var(--el-fill-color-light); }
.set .grow.on { color: var(--el-color-primary); background: var(--el-color-primary-light-9); }
.set .seq-short { color: var(--el-color-warning); font-size: 12px; }
.rv { font-size: 14px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rv.blank { color: var(--el-text-color-placeholder); font-family: inherit; font-size: 12px; }
.acts { display: flex; justify-content: flex-end; }
.acts .el-button + .el-button { margin-left: 8px; }
.mono-seg :deep(.el-radio-button__inner), .mono-in :deep(.el-input__inner) { font-family: "JetBrains Mono", Consolas, monospace; }
.foot { display: flex; justify-content: space-between; align-items: center; }
:global(.rule-dlg) { max-width: calc(100vw - 32px); }
:global(.el-message a.undo) { color: var(--el-color-primary); cursor: pointer; margin-left: 8px; text-decoration: underline; }
</style>
