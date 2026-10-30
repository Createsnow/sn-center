<template>
  <el-dialog v-model="tf.open" :title="t('transfer.new')" width="1000px" top="4vh">
    <!-- ① 快速定位：完整 SN / 单据编号 / PI，带出来源 -->
    <div class="locate">
      <span class="locate__t">{{ t("transfer.locate") }}</span>
      <el-input v-model="loc.q" class="sn-mono" clearable :placeholder="t('transfer.locatePlaceholder')" @keyup.enter="locate" />
      <el-button :loading="busy === 'loc'" :disabled="!loc.q.trim()" @click="locate">{{ t("transfer.locateBtn") }}</el-button>
      <span v-if="loc.by" class="locate__ok"><el-icon><CircleCheck /></el-icon>{{ t(`transfer.located.${loc.by}`) }}</span>
    </div>

    <!-- ② 来源 → 转入目标：一个框只放一个值，其余信息在下方只读信息表 -->
    <div class="sides">
      <section class="side">
        <h5 class="side__t">{{ t("transfer.source") }}</h5>
        <el-form label-position="top" class="form">
          <el-form-item label="PI" required>
            <el-input v-model="tf.pi_no" class="sn-mono" clearable @change="loadSources()" />
            <div v-if="tf.pi_no && sourcesLoaded && !srcBills.length" class="hint warn-text">{{ t("transfer.noSnInPi") }}</div>
          </el-form-item>
          <el-form-item :label="t('common.billNo')" required>
            <el-select v-model="tf.bill_no" class="full sn-mono" filterable :disabled="!srcBills.length" @change="onSourceBill()">
              <el-option v-for="b in srcBills" :key="b.bill_no" :value="b.bill_no" :label="b.bill_no">
                <div class="opt"><span class="sn-mono">{{ b.bill_no }}</span><span class="muted">{{ t("transfer.snCount", { n: b.qty }) }}</span></div>
              </el-option>
            </el-select>
          </el-form-item>
          <el-form-item :label="t('transfer.scope')">
            <el-radio-group v-model="tf.scope" @change="onSourceScope">
              <el-radio-button v-for="sc in SCOPES" :key="sc" :value="sc">{{ scopeLabel(sc) }}</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item v-if="tf.scope !== 'BILL'" :label="t('common.material')" required>
            <el-select v-model="tf.material_code" class="full sn-mono" filterable :disabled="!srcMaterials.length" @change="onSourceMaterial">
              <el-option v-for="mm in srcMaterials" :key="matKey(mm.material_code)" :value="matKey(mm.material_code)" :label="matLabel(mm.material_code)">
                <div class="opt"><span class="sn-mono">{{ matLabel(mm.material_code) }}</span><span class="muted">{{ t("transfer.snCount", { n: mm.qty }) }}</span></div>
              </el-option>
            </el-select>
          </el-form-item>
          <!-- 号分在多个生产组织时才要选；只有一个时自动带出（见来源信息） -->
          <el-form-item v-if="srcOrgs.length > 1" :label="t('transfer.fromOrg')" required>
            <el-select v-model="tf.factory_code" class="full" @change="autoTarget">
              <el-option v-for="o in srcOrgs" :key="o.factory_code" :value="o.factory_code" :label="orgLabel(o.factory_code)">
                <div class="opt"><span>{{ orgLabel(o.factory_code) }}</span><span class="muted">{{ t("transfer.snCount", { n: o.qty }) }}</span></div>
              </el-option>
            </el-select>
          </el-form-item>
          <el-form-item v-if="tf.scope === 'RANGE'" :label="t('transfer.range')" required>
            <div class="pair">
              <el-input v-model="tf.start_sn" class="sn-mono" :placeholder="t('transfer.startSn')" clearable />
              <el-input v-model="tf.end_sn" class="sn-mono" :placeholder="t('transfer.endSn')" clearable />
            </div>
          </el-form-item>
        </el-form>
        <div class="info">
          <div class="info__cap">{{ t("transfer.srcInfo") }}</div>
          <dl>
            <dt>{{ t("common.factory") }}</dt>
            <dd>{{ tf.factory_code ? orgLabel(tf.factory_code) : "—" }}<el-tag v-if="isMes(tf.factory_code)" size="small" class="ml">MES</el-tag></dd>
            <dt>{{ t("common.customer") }}</dt><dd>{{ join(srcInfo.customers) }}</dd>
            <dt>{{ t("common.attachNo") }}</dt><dd>{{ join(srcInfo.attach_nos) }}</dd>
            <dt>{{ t("common.length") }}</dt><dd>{{ join(srcInfo.lengths) }}</dd>
            <dt>{{ t("transfer.srcQty") }}</dt><dd class="num">{{ srcQtyText }}</dd>
          </dl>
        </div>
      </section>

      <div class="sides__arrow"><el-icon><Right /></el-icon></div>

      <section class="side">
        <h5 class="side__t">{{ t("transfer.target") }}</h5>
        <el-form label-position="top" class="form">
          <el-form-item label="PI" required>
            <el-autocomplete v-model="tf.to_pi" class="full sn-mono" :fetch-suggestions="suggestPi" :trigger-on-focus="false" clearable
              @select="loadTargets" @change="loadTargets" />
            <div class="hint">{{ t("transfer.toPiDefault") }}</div>
          </el-form-item>
          <el-form-item :label="t('common.billNo')" required>
            <el-select v-model="tf.to_bill" class="full sn-mono" filterable :disabled="!toBills.length" @change="onTargetBill">
              <el-option v-for="b in toBills" :key="b.bill_no" :value="b.bill_no" :label="b.bill_no">
                <div class="opt"><span class="sn-mono">{{ b.bill_no }}</span><span class="muted">{{ nameOf(b.org_code) }}</span></div>
              </el-option>
            </el-select>
            <div v-if="tf.to_pi && targetsLoaded && !toBills.length" class="hint warn-text">{{ t("transfer.noBillInPi") }}</div>
          </el-form-item>
          <div class="pair2">
            <el-form-item :label="t('common.material')" required>
              <el-select v-model="tf.to_material" class="full sn-mono" filterable :disabled="!toMaterials.length" @change="onTargetMaterial">
                <el-option v-for="mc in toMaterials" :key="matKey(mc)" :value="matKey(mc)" :label="matLabel(mc)" />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('transfer.seq')" required>
              <el-select v-model="tf.to_seq" class="full" :disabled="!toSeqs.length">
                <el-option v-for="l in toSeqs" :key="l.seq" :value="l.seq" :label="String(l.seq)" :disabled="lengthBad(l)">
                  <div class="opt cols">
                    <span>{{ l.seq }}</span><span>{{ t("common.attachNo") }} {{ l.attach_no || "—" }}</span>
                    <span>{{ t("common.length") }} {{ l.length || "—" }}</span>
                    <span class="muted">{{ lengthBad(l) ? t("transfer.lengthDiff") : "" }}</span>
                  </div>
                </el-option>
              </el-select>
            </el-form-item>
          </div>
          <div class="hint up">{{ t("transfer.autoMatchHint") }}</div>
          <el-form-item :label="t('common.factory')" required>
            <el-select v-model="tf.to_factory" filterable class="full">
              <el-option v-for="x in factories" :key="x.factory_code" :value="x.factory_code" :label="orgLabel(x.factory_code)"
                :disabled="!x.enabled && x.factory_code !== toHead?.org_code">
                <div class="opt">
                  <span>{{ orgLabel(x.factory_code) }}</span>
                  <span class="muted">{{ x.mes_enabled ? "MES" : "" }}{{ x.enabled ? "" : t("common.disabledSuffix") }}</span>
                </div>
              </el-option>
            </el-select>
            <div v-if="orgDiff" class="hint wt">{{ t("transfer.orgDiff") }}</div>
            <div v-else class="hint">{{ t("transfer.orgDefault") }}</div>
            <div v-if="outOfScope" class="hint warn-text">{{ t("transfer.targetOutOfScope") }}</div>
          </el-form-item>
        </el-form>
        <div class="info">
          <div class="info__cap">{{ t("transfer.toInfo") }}</div>
          <dl>
            <dt>{{ t("transfer.billOrg") }}</dt><dd>{{ toHead ? orgLabel(toHead.org_code) : "—" }}</dd>
            <dt>{{ t("transfer.mesConnected") }}</dt><dd>{{ tf.to_factory ? t(isMes(tf.to_factory) ? "common.yes" : "common.no") : "—" }}</dd>
            <dt>{{ t("common.customer") }}</dt><dd>{{ toHead?.customer_code || "—" }}</dd>
            <dt>{{ t("common.attachNo") }}</dt><dd>{{ toLine?.attach_no || "—" }}</dd>
            <dt>{{ t("common.length") }}</dt>
            <dd>
              {{ toLine ? toLine.length || "—" : "—" }}
              <span v-if="toLine && srcInfo.lengths.length" :class="lengthBad(toLine) ? 'warn-text' : 'ok'">
                {{ lengthBad(toLine) ? t("transfer.lengthDiff") : t("transfer.lengthOk") }}
              </span>
            </dd>
          </dl>
        </div>
      </section>
    </div>

    <!-- ③ 转移原因：直接转移、立即生效，原因必填，记入转移单与操作痕迹 -->
    <section class="reason" :class="{ bad: reasonMissing }">
      <div class="reason__t"><span class="req">*</span>{{ t("transfer.reason") }}</div>
      <el-input v-model="tf.reason" type="textarea" :rows="2" maxlength="500" show-word-limit :placeholder="t('transfer.reasonPlaceholder')"
        @blur="reasonTouched = true" />
      <div v-if="reasonMissing" class="hint warn-text">{{ t("transfer.reasonRequired") }}</div>
    </section>

    <template #footer>
      <div class="foot">
        <template v-if="kind">
          <span class="muted">{{ t("transfer.kindIs") }}</span>
          <el-tag :type="kind === 'BILL' ? 'primary' : 'warning'" effect="plain">{{ t(`transfer.kindName.${kind}`) }}</el-tag>
        </template>
        <span class="grow" />
        <el-button @click="tf.open = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" :loading="busy === 'submit'" :disabled="!ready" @click="submit">{{ t("transfer.submit") }}</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/** 新建转移（F-15、F-16）：页面不区分转单、转组织，系统按目标判断——目标单据与来源不同、且生产组织就是目标单据的 → 转单，
 *  其余 → 转组织。系统管理员、普通操作员都直接转移、立即生效，原因必填（没有申请、审批）。
 *  来源：快速定位（完整 SN / 单据编号 / PI）或 PI → 单据 → 范围（整张单据 / 物料编码下全部号 / 单枚或连续号段），
 *  来源生产组织按号自动带出（号分在多个组织时才选）；目标：PI（默认同来源）→ 单据 → 物料编码 + 序号（按来源物料自动匹配）
 *  → 生产组织（默认目标单据的，可改）。确认框列出枚数、不可转、转出等，确认后执行；完成后发出 done（种类、单据）。 */
import { computed, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "@/api";
import { useFactories } from "@/composables/useFactories";
import { scopeLabel } from "@/constants";
import { useAuthStore } from "@/stores/auth";
import { type Kind, type Located, type Preview, type SourceBill, type Target } from "./types";
type Scope = "BILL" | "MATERIAL" | "RANGE";
const SCOPES: Scope[] = ["BILL", "MATERIAL", "RANGE"];
/** el-select 把空串当作未选：空物料用占位值表示。 */
const EMPTY_MAT = "\u0000";
const matKey = (v: string) => (v === "" ? EMPTY_MAT : v);
const matValue = (k: string) => (k === EMPTY_MAT ? "" : k);

const emit = defineEmits<{ done: [kind: Kind, doc: any] }>();
const { t } = useI18n();
const store = useAuthStore();
const { factories, nameOf } = useFactories();
const busy = ref("");

const tf = reactive({
  open: false, factory_code: "", pi_no: "", bill_no: "", scope: "BILL" as Scope, material_code: "",
  start_sn: "", end_sn: "", to_pi: "", to_bill: "", to_material: "", to_seq: undefined as number | undefined, to_factory: "", reason: "",
});
const loc = reactive({ q: "", by: "" as "" | Located["by"] });
const srcBills = ref<SourceBill[]>([]);
const sourcesLoaded = ref(false);
const toRows = ref<Target[]>([]);
const targetsLoaded = ref(false);
const reasonTouched = ref(false);

// ---------------- 来源
const srcBill = computed(() => srcBills.value.find((b) => b.bill_no === tf.bill_no) ?? null);
const srcMaterials = computed(() => srcBill.value?.materials ?? []);
/** 所选范围（整张单据，或单据 + 物料编码）在各生产组织的号。 */
const scopeRows = computed(() => (srcBill.value?.rows ?? []).filter((r) => tf.scope === "BILL" || matKey(r.material_code) === tf.material_code));
const srcOrgs = computed(() => {
  const by = new Map<string, number>();
  scopeRows.value.forEach((r) => by.set(r.factory_code, (by.get(r.factory_code) ?? 0) + r.qty));
  return [...by].map(([factory_code, qty]) => ({ factory_code, qty }));
});
/** 来源信息：所选范围、来源生产组织下号上的客户、附件项次、米数与枚数。 */
const srcInfo = computed(() => {
  const rows = scopeRows.value.filter((r) => r.factory_code === tf.factory_code);
  const uniq = (k: "customers" | "attach_nos" | "lengths") => [...new Set(rows.flatMap((r) => r[k]))];
  return { qty: rows.reduce((n, r) => n + r.qty, 0), customers: uniq("customers"), attach_nos: uniq("attach_nos"), lengths: uniq("lengths") };
});
/** 号段的枚数在确认框里给出；只填起号即 1 枚。 */
const srcQtyText = computed(() => {
  if (!tf.factory_code) return "—";
  if (tf.scope !== "RANGE") return t("transfer.snCount", { n: srcInfo.value.qty });
  return tf.start_sn.trim() && !tf.end_sn.trim() ? t("transfer.snCount", { n: 1 }) : "—";
});

// ---------------- 目标
const toBills = computed(() => {
  const seen = new Map<string, Target>();
  toRows.value.forEach((r) => seen.has(r.bill_no) || seen.set(r.bill_no, r));
  return [...seen.values()];
});
const toHead = computed(() => toBills.value.find((b) => b.bill_no === tf.to_bill) ?? null);
const toLines = computed(() => toRows.value.filter((r) => r.bill_no === tf.to_bill));
const toMaterials = computed(() => [...new Set(toLines.value.map((l) => l.material_code))]);
const toSeqs = computed(() => toLines.value.filter((l) => matKey(l.material_code) === tf.to_material));
const toLine = computed(() => toSeqs.value.find((l) => l.seq === tf.to_seq) ?? null);

/** 系统判断的类型：目标单据与来源不同、且生产组织就是目标单据的 → 转单；其余 → 转组织（与后台 decide_kind 一致）。 */
const kind = computed<Kind | null>(() => {
  if (!toHead.value || !tf.to_factory) return null;
  return tf.to_bill !== tf.bill_no && tf.to_factory === toHead.value.org_code ? "BILL" : "ORG";
});
const orgDiff = computed(() => !!toHead.value && !!tf.to_factory && tf.to_factory !== toHead.value.org_code);
const reasonMissing = computed(() => reasonTouched.value && !tf.reason.trim());
/** 目标组织不在本账户授权范围内：照常可转，提示转后本组织不能再操作这些号。 */
const outOfScope = computed(() => !!tf.to_factory && !store.canWrite(tf.to_factory));
const ready = computed(
  () =>
    !!tf.factory_code && !!tf.pi_no.trim() && !!tf.bill_no && (tf.scope === "BILL" || !!tf.material_code) &&
    (tf.scope !== "RANGE" || !!tf.start_sn.trim()) && !!tf.to_pi.trim() && !!tf.to_bill && !!tf.to_factory && tf.to_seq !== undefined,
);

function matLabel(v: string) {
  return v === "" ? t("transfer.emptyMaterial") : v;
}
function orgLabel(code: string) {
  return `${nameOf(code)}（${code}）`;
}
function isMes(code: string) {
  return !!code && !!factories.value.find((f) => f.factory_code === code)?.mes_enabled;
}
function join(vs: string[]) {
  return vs.length ? vs.join("、") : "—";
}
/** 目标物料行的米数与号上米数不同（SN 已含米数，后台会拒绝）。整张单据、按物料编码时范围内的号都要转，有一种米数不同即不行；
 *  单枚或号段只取其中一部分，页面不知道落在哪几行，目标米数是来源米数之一即可（不符时后台拒绝）。 */
function lengthBad(l: Target) {
  const ls = srcInfo.value.lengths;
  return tf.scope === "RANGE" ? ls.length > 0 && !ls.includes(l.length) : ls.some((x) => x !== l.length);
}

function open() {
  Object.assign(tf, {
    open: true, factory_code: "", pi_no: "", bill_no: "", scope: "BILL", material_code: "", start_sn: "", end_sn: "",
    to_pi: "", to_bill: "", to_material: "", to_seq: undefined, to_factory: "", reason: "",
  });
  Object.assign(loc, { q: "", by: "" });
  srcBills.value = [];
  toRows.value = [];
  sourcesLoaded.value = targetsLoaded.value = false;
  reasonTouched.value = false;
}

/** 快速定位：SN 带出 PI、单据、物料编码、生产组织与起号（范围切到单枚 / 号段）；单据编号带出 PI、单据；PI 带出 PI。 */
async function locate() {
  const q = loc.q.trim();
  if (!q) return;
  busy.value = "loc";
  loc.by = "";
  try {
    const r: Located = await api.transferLocate(q);
    tf.pi_no = r.pi;
    if (r.by === "SN") Object.assign(tf, { scope: "RANGE", start_sn: r.sn, end_sn: "" });
    await loadSources({ bill: r.bill_no, material: r.material_code, factory: r.factory_code });
    loc.by = r.by;
  } finally {
    busy.value = "";
  }
}

/** 来源候选：PI 下各单据、各物料、各生产组织的号数；preset 为快速定位带出的值。 */
async function loadSources(preset: { bill?: string; material?: string; factory?: string } = {}) {
  sourcesLoaded.value = false;
  const pi = tf.pi_no.trim();
  if (!pi) {
    srcBills.value = [];
    tf.bill_no = tf.material_code = tf.factory_code = "";
    return;
  }
  const r = await api.transferSources({ kind: "AUTO", pi });
  srcBills.value = r.bills;
  sourcesLoaded.value = true;
  // 目标 PI 未填时默认同来源 PI（拆单多在同一 PI 内）
  if (!tf.to_pi.trim()) {
    tf.to_pi = pi;
    loadTargets();
  }
  if (preset.bill !== undefined) tf.bill_no = preset.bill;
  if (!srcBills.value.some((b) => b.bill_no === tf.bill_no)) tf.bill_no = srcBills.value.length === 1 ? srcBills.value[0].bill_no : "";
  if (preset.material !== undefined) tf.material_code = matKey(preset.material);
  onSourceBill(preset.factory);
}
function onSourceBill(factory?: string) {
  const mats = srcMaterials.value;
  if (!mats.some((x) => matKey(x.material_code) === tf.material_code)) tf.material_code = mats.length === 1 ? matKey(mats[0].material_code) : "";
  syncSourceOrg(factory);
}
function onSourceScope() {
  syncSourceOrg();
}
function onSourceMaterial() {
  syncSourceOrg();
}
/** 来源生产组织：所选范围的号只在一个组织时自动带出；多个时保留已选的（或快速定位带出的），否则待选。 */
function syncSourceOrg(prefer?: string) {
  const orgs = srcOrgs.value.map((o) => o.factory_code);
  if (prefer && orgs.includes(prefer)) tf.factory_code = prefer;
  else if (!orgs.includes(tf.factory_code)) tf.factory_code = orgs.length === 1 ? orgs[0] : "";
  autoTarget();
}

async function suggestPi(q: string, cb: (x: { value: string }[]) => void) {
  if (!q.trim()) return cb([]);
  const rows: Target[] = await api.orderTargets(q.trim(), 50);
  cb([...new Set(rows.map((r) => r.pi).filter(Boolean))].map((v) => ({ value: v })));
}
/** 目标 PI 下快照中的单据与物料行；只有一张别的单据时自动选上。 */
async function loadTargets() {
  targetsLoaded.value = false;
  const pi = tf.to_pi.trim();
  toRows.value = pi ? await api.orderTargets(undefined, 200, pi) : [];
  targetsLoaded.value = !!pi;
  if (!toBills.value.some((b) => b.bill_no === tf.to_bill)) {
    const others = toBills.value.filter((b) => b.bill_no !== tf.bill_no);
    tf.to_bill = others.length === 1 ? others[0].bill_no : "";
  }
  onTargetBill();
}
/** 换目标单据：生产组织回到该单据的，物料行重新匹配。 */
function onTargetBill() {
  tf.to_factory = toHead.value?.org_code ?? "";
  autoTarget();
}
function onTargetMaterial() {
  tf.to_seq = undefined;
  autoTarget();
}
/** 目标物料编码按来源物料自动匹配（目标单据只有一种物料时也选上）；序号在米数相符的行里只有一行时自动选上。 */
function autoTarget() {
  const mats = toMaterials.value;
  if (!mats.some((mc) => matKey(mc) === tf.to_material)) {
    const src = tf.scope !== "BILL" ? tf.material_code : srcMaterials.value.length === 1 ? matKey(srcMaterials.value[0].material_code) : "";
    tf.to_material = mats.some((mc) => matKey(mc) === src) ? src : mats.length === 1 ? matKey(mats[0]) : "";
  }
  if (!toSeqs.value.some((l) => l.seq === tf.to_seq && !lengthBad(l))) {
    const fit = toSeqs.value.filter((l) => !lengthBad(l));
    tf.to_seq = fit.length === 1 ? fit[0].seq : undefined;
  }
}

function body() {
  return {
    factory_code: tf.factory_code, pi_no: tf.pi_no.trim(), bill_no: tf.bill_no, scope: tf.scope,
    material_code: tf.scope === "BILL" ? null : matValue(tf.material_code),
    start_sn: tf.scope === "RANGE" ? tf.start_sn.trim() : null, end_sn: tf.scope === "RANGE" ? tf.end_sn.trim() || null : null,
    to_pi: tf.to_pi.trim(), to_bill: tf.to_bill, to_factory: tf.to_factory, to_material: matValue(tf.to_material),
    to_seq: tf.to_seq ?? null, reason: tf.reason,
  };
}

/** 确认框：类型、可转枚数、目标、不可转及原因、向来源 MES 转出的枚数、原因；确认后直接执行。 */
async function submit() {
  reasonTouched.value = true;
  if (!tf.reason.trim()) return;
  busy.value = "submit";
  const p: Preview = await api.transferPreview(body()).finally(() => (busy.value = ""));
  const blocked = Object.entries(p.blocked).map(([r, n]) => `${t(`transfer.blocked.${r}`)} ${n}`).join("；");
  if (!p.transferable) {
    ElMessage.error(t("transfer.nothingTransferable", { detail: blocked || "—" }));
    return;
  }
  const msg = [
    t("transfer.confirm", {
      kind: t(`transfer.kindName.${p.kind}`), n: p.transferable, to: orgLabel(p.to_org), pi: p.to_pi, bill: p.to_bill, seq: p.to_seq,
    }),
    blocked && t("transfer.confirmBlocked", { n: p.total - p.transferable, detail: blocked }),
    p.xo_qty && t("transfer.confirmXo", { n: p.xo_qty }),
    t("transfer.confirmReason", { reason: tf.reason.trim() }),
  ].filter(Boolean).join("\n");
  await ElMessageBox.confirm(msg, t("transfer.new"), { type: "warning", customClass: "pre-line" });
  busy.value = "submit";
  try {
    const r = await api.transferCreate(body());
    ElMessage.success(t(r.kind === "BILL" ? "transfer.billDone" : "transfer.orgDone", { no: r.doc.transfer_no, n: r.doc.qty }));
    tf.open = false;
    emit("done", r.kind, r.doc);
  } finally {
    busy.value = "";
  }
}

defineExpose({ open });
</script>

<style scoped>
.full { width: 100%; }
.ml { margin-left: 6px; }
.grow { flex: 1; }
.hint { width: 100%; margin-top: 4px; line-height: 1.5; color: var(--app-muted); font-size: 12px; }
.hint.up { margin: -8px 0 12px; }
.warn-text { color: var(--el-color-danger); font-size: 12px; }
.wt { color: var(--el-color-warning-dark-2); }
.ok { color: var(--el-color-success); }
.locate { display: flex; align-items: center; gap: 10px; border: 1px solid var(--app-line); border-radius: 8px; padding: 10px 16px;
  margin-bottom: 12px; background: var(--app-canvas); }
.locate__t { font-weight: 600; color: var(--app-text); white-space: nowrap; }
.locate__ok { display: inline-flex; align-items: center; gap: 4px; color: var(--el-color-success); font-size: 12px; white-space: nowrap; }
.reason { margin-top: 12px; border: 1px solid var(--app-line); border-left: 3px solid var(--el-color-warning); border-radius: 8px;
  padding: 10px 16px 12px; background: var(--app-surface); }
.reason.bad { border-left-color: var(--el-color-danger); }
.reason__t { font-size: 13px; font-weight: 600; color: var(--app-text); margin-bottom: 6px; }
.reason .req { color: var(--el-color-danger); margin-right: 4px; }
.form :deep(.el-form-item) { margin-bottom: 14px; }
.form :deep(.el-form-item__label) { padding-bottom: 4px; line-height: 20px; }
.sides { display: grid; grid-template-columns: minmax(0, 1fr) 28px minmax(0, 1fr); gap: 8px; align-items: stretch; }
.side { border: 1px solid var(--app-line); border-radius: 8px; padding: 12px 16px; background: var(--app-surface); }
.side__t { margin: 0 0 10px; font-size: 13px; font-weight: 600; color: var(--app-text); }
.sides__arrow { display: flex; align-items: center; justify-content: center; color: var(--app-muted); font-size: 18px; }
.pair { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; width: 100%; }
.pair2 { display: grid; grid-template-columns: minmax(0, 1fr) 110px; gap: 8px; }
.opt { display: flex; justify-content: space-between; gap: 16px; }
.opt.cols { display: grid; grid-template-columns: 32px 110px 90px auto; gap: 8px; }
.info { border: 1px solid var(--app-line); border-radius: 6px; overflow: hidden; font-size: 12px; }
.info__cap { background: var(--app-canvas); color: var(--app-muted); padding: 4px 10px; border-bottom: 1px solid var(--app-line); }
.info dl { display: grid; grid-template-columns: 96px minmax(0, 1fr); margin: 0; }
.info dt, .info dd { margin: 0; padding: 4px 10px; border-bottom: 1px solid var(--app-line); }
.info dt { color: var(--app-muted); }
.info dl > :nth-last-child(-n + 2) { border-bottom: 0; }
.foot { display: flex; align-items: center; gap: 8px; }
@media (max-width: 900px) {
  .sides, .pair { grid-template-columns: 1fr; }
  .sides__arrow { transform: rotate(90deg); }
}
</style>

<style>
/* 确认框按行展示（类型与枚数、不可转、转出、原因） */
.pre-line .el-message-box__message p { white-space: pre-line; }
</style>
