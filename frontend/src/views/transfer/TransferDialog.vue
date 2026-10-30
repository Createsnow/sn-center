<template>
  <el-dialog v-model="tf.open" :title="t('transfer.new')" width="1000px" top="4vh">
    <!-- ① 类型：转单或转组织（系统管理员、普通操作员都直接转移，立即生效） -->
    <div class="kinds">
      <div v-for="k in KINDS" :key="k" class="kind" :class="{ on: tf.kind === k }" @click="setKind(k)">
        <div class="kind__t"><el-icon><component :is="k === 'BILL' ? 'Document' : 'OfficeBuilding'" /></el-icon>{{ t(`transfer.kindTitle.${k}`) }}</div>
        <div class="muted">{{ t(`transfer.kindTip.${k}`) }}</div>
      </div>
    </div>

    <!-- ② 来源 → 转入目标 -->
    <div class="sides">
      <section class="side">
        <h5 class="side__t">{{ t("transfer.source") }}</h5>
        <el-form label-position="top" class="form">
          <el-form-item v-if="tf.kind === 'ORG'" :label="t('transfer.fromOrg')" required>
            <FactorySelect v-model="tf.factory_code" class="full" scoped :disabled="!!store.boundFactory" @update:model-value="loadSources" />
          </el-form-item>
          <el-form-item label="PI" required>
            <el-input v-model="tf.pi_no" clearable @change="loadSources" />
            <div v-if="tf.pi_no && sourcesLoaded && !srcBills.length" class="muted hint">{{ t("transfer.noSnInPi") }}</div>
          </el-form-item>
          <el-form-item :label="t('transfer.fromBill')" required>
            <el-select v-model="tf.bill_no" class="full" filterable :disabled="!srcBills.length" :placeholder="t('transfer.pickBill')" @change="onSourceBill">
              <el-option v-for="b in srcBills" :key="b.bill_no" :value="b.bill_no" :label="`${b.bill_no} · ${t('transfer.snCount', { n: b.qty })}`" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('transfer.scope')">
            <el-radio-group v-model="tf.scope" @change="resetPreview">
              <el-radio-button v-for="sc in SCOPES" :key="sc" :value="sc">{{ scopeLabel(sc) }}</el-radio-button>
            </el-radio-group>
            <div class="muted hint">{{ t(`transfer.scopeTip.${tf.scope}`) }}</div>
          </el-form-item>
          <el-form-item v-if="tf.scope !== 'BILL'" :label="t('common.material')" required>
            <el-select v-model="tf.material_code" class="full" filterable :disabled="!srcMaterials.length" :placeholder="t('transfer.pickMaterial')" @change="onSourceMaterial">
              <el-option v-for="mm in srcMaterials" :key="matKey(mm.material_code)" :value="matKey(mm.material_code)"
                :label="`${mm.material_code || t('transfer.emptyMaterial')} · ${t('transfer.snCount', { n: mm.qty })}`" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="tf.scope === 'RANGE'" :label="t('transfer.range')" required>
            <div class="pair">
              <el-input v-model="tf.start_sn" class="sn-mono" :placeholder="t('transfer.startSn')" clearable @change="resetPreview" />
              <el-input v-model="tf.end_sn" class="sn-mono" :placeholder="t('transfer.endSn')" clearable @change="resetPreview" />
            </div>
            <div class="muted hint">{{ t("transfer.rangeHint") }}</div>
          </el-form-item>
        </el-form>
      </section>

      <div class="sides__arrow"><el-icon><Right /></el-icon></div>

      <section class="side">
        <h5 class="side__t">{{ t("transfer.target") }}</h5>
        <el-form label-position="top" class="form">
          <el-form-item :label="t('transfer.toPi')" required>
            <el-autocomplete v-model="tf.to_pi" class="full" :fetch-suggestions="suggestPi" :trigger-on-focus="false" clearable
              @select="loadTargets" @change="loadTargets" />
          </el-form-item>
          <el-form-item :label="t('transfer.toBill')" required>
            <el-select v-model="tf.to_bill" class="full" filterable :disabled="!toBills.length" :placeholder="t('transfer.pickBill')" @change="onTargetBill">
              <el-option v-for="b in toBills" :key="b.bill_no" :value="b.bill_no" :disabled="tf.kind === 'BILL' && b.bill_no === tf.bill_no"
                :label="`${b.bill_no} · ${nameOf(b.org_code)}`" />
            </el-select>
            <div v-if="toHead" class="muted hint">{{ t("common.customer") }} {{ toHead.customer_code || "—" }}</div>
            <div v-else-if="tf.to_pi && targetsLoaded && !toBills.length" class="muted hint">{{ t("transfer.noBillInPi") }}</div>
          </el-form-item>
          <el-form-item :label="t('transfer.toOrg')" required>
            <el-select v-model="tf.to_factory" filterable class="full" :disabled="tf.kind === 'BILL'" @change="resetPreview">
              <el-option v-for="x in factories" :key="x.factory_code" :value="x.factory_code" :disabled="tf.kind === 'ORG' && !x.enabled"
                :label="`${x.factory_name}（${x.factory_code}）${x.mes_enabled ? ' · MES' : ''}${x.enabled ? '' : t('common.disabledSuffix')}`" />
              <el-option v-if="tf.kind === 'BILL' && tf.to_factory && !factories.some((x) => x.factory_code === tf.to_factory)" :value="tf.to_factory" :label="tf.to_factory" />
            </el-select>
            <div class="muted hint">{{ tf.kind === "BILL" ? t("transfer.orgOfBill") : t("transfer.orgFree") }}</div>
            <div v-if="outOfScope" class="hint warn-text">{{ t("transfer.targetOutOfScope") }}</div>
          </el-form-item>
          <el-form-item :label="t('transfer.toMaterial')" required>
            <el-select v-model="tf.to_seq" class="full" :disabled="!toLines.length" :placeholder="t('transfer.pickTargetLine')" @change="resetPreview">
              <el-option v-for="l in toLines" :key="l.seq" :value="l.seq" :label="lineLabel(l)" />
            </el-select>
          </el-form-item>
        </el-form>
      </section>
    </div>

    <!-- ③ 转移原因：直接转移、立即生效，原因必填，记入转移单与操作痕迹 -->
    <section class="reason" :class="{ bad: reasonMissing }">
      <div class="reason__t"><span class="req">*</span>{{ t("transfer.reason") }}</div>
      <el-input v-model="tf.reason" type="textarea" :rows="2" maxlength="500" show-word-limit :placeholder="t('transfer.reasonPlaceholder')"
        @blur="reasonTouched = true" />
      <div class="muted hint">{{ reasonMissing ? t("transfer.reasonRequired") : t("transfer.reasonTip") }}</div>
    </section>

    <div v-if="pv" class="preview">
      <div class="kpis">
        <span>{{ t("transfer.pvTotal") }} <b class="num">{{ pv.total }}</b></span>
        <span>{{ t("transfer.pvTransferable") }} <b class="num ok">{{ pv.transferable }}</b></span>
        <span>{{ t("transfer.pvDelivered") }} <b class="num">{{ pv.delivered }}</b></span>
        <span v-if="pv.other_org">{{ t("transfer.pvOtherOrg") }} <b class="num warn">{{ pv.other_org }}</b></span>
        <span v-if="pv.xo_qty">{{ t("transfer.pvXo") }} <b class="num warn">{{ pv.xo_qty }}</b></span>
      </div>
      <div class="muted mt-s">{{ pv.scope }} → {{ nameOf(pv.to_org) }} · PI {{ pv.to_pi }} · {{ pv.to_bill }} / {{ pv.to_seq }} · {{ pv.to_material || t("transfer.emptyMaterial") }}</div>
      <div class="tags">
        <el-tag v-for="(n, st) in pv.by_status" :key="st" size="small" effect="plain">{{ statusLabel(String(st)) }} {{ n }}</el-tag>
        <el-tag v-for="(n, r) in pv.blocked" :key="r" size="small" type="danger">{{ t(`transfer.blocked.${r}`) }} {{ n }}</el-tag>
      </div>
      <el-table v-if="pv.generated_changes.length" :data="pv.generated_changes" size="small" class="mt">
        <el-table-column :label="t('common.billNo')" min-width="160"><template #default="{ row }">{{ row.bill_no }} / {{ row.seq }}</template></el-table-column>
        <el-table-column :label="t('transfer.genBefore')" align="right" width="110"><template #default="{ row }"><span class="num">{{ row.before }}</span></template></el-table-column>
        <el-table-column :label="t('transfer.genAfter')" align="right" width="110"><template #default="{ row }"><span class="num">{{ row.after }}</span></template></el-table-column>
      </el-table>
      <p class="muted">{{ t("transfer.quotaUnchanged") }}</p>
      <p class="muted">{{ t("transfer.directNote") }}</p>
    </div>
    <template #footer>
      <div class="foot">
        <span v-if="!pv" class="muted">{{ t("transfer.previewFirst") }}</span>
        <span v-else-if="!tf.reason.trim()" class="warn-text">{{ t("transfer.reasonRequired") }}</span>
        <span class="grow" />
        <el-button @click="tf.open = false">{{ t("common.cancel") }}</el-button>
        <el-button :loading="busy === 'pv'" :disabled="!ready" @click="preview">{{ t("transfer.preview") }}</el-button>
        <el-button type="primary" :loading="busy === 'submit'" :disabled="!pv || !pv.transferable || !tf.reason.trim()" @click="submit">{{ t("transfer.submit") }}</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/** 新建转移：转单（F-15）/ 转组织（F-16）共用一个表单，顶部切换类型；系统管理员、普通操作员都直接转移、立即生效，
 *  原因必填（没有申请、审批）。来源 PI → 单据 → 范围（整张单据 / 物料编码下全部号 / 物料编码下单枚或连续号段），
 *  转组织另选来源生产组织（普通操作员限授权组织）；目标 PI、单据、生产组织、物料行都必填（转单的生产组织取目标单据的，
 *  目标组织不限授权范围）。完成后发出 done（种类、单据）。 */
import { computed, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "@/api";
import FactorySelect from "@/components/FactorySelect.vue";
import { useFactories } from "@/composables/useFactories";
import { scopeLabel, statusLabel } from "@/constants";
import { useAuthStore } from "@/stores/auth";
import { type Kind, KINDS, type Preview, type SourceBill, type Target } from "./types";
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
  open: false, kind: "BILL" as Kind, factory_code: "", pi_no: "", bill_no: "", scope: "BILL" as Scope, material_code: "",
  start_sn: "", end_sn: "", to_pi: "", to_bill: "", to_factory: "", to_seq: undefined as number | undefined, reason: "",
});
const srcBills = ref<SourceBill[]>([]);
const sourcesLoaded = ref(false);
const toRows = ref<Target[]>([]);
const targetsLoaded = ref(false);
const pv = ref<Preview | null>(null);
const reasonTouched = ref(false);

const srcMaterials = computed(() => srcBills.value.find((b) => b.bill_no === tf.bill_no)?.materials ?? []);
const toBills = computed(() => {
  const seen = new Map<string, Target>();
  toRows.value.forEach((r) => seen.has(r.bill_no) || seen.set(r.bill_no, r));
  return [...seen.values()];
});
const toHead = computed(() => toBills.value.find((b) => b.bill_no === tf.to_bill) ?? null);
const toLines = computed(() => toRows.value.filter((r) => r.bill_no === tf.to_bill));
const formTitle = computed(() => t(`transfer.kindName.${tf.kind}`));
const reasonMissing = computed(() => reasonTouched.value && !tf.reason.trim());
/** 目标组织不在本账户授权范围内：照常可转，提示转后本组织不能再操作这些号。 */
const outOfScope = computed(() => !!tf.to_factory && !store.canWrite(tf.to_factory));
const ready = computed(
  () =>
    (tf.kind === "BILL" || !!tf.factory_code) && !!tf.pi_no.trim() && !!tf.bill_no &&
    (tf.scope === "BILL" || !!tf.material_code) && (tf.scope !== "RANGE" || !!tf.start_sn.trim()) &&
    !!tf.to_pi.trim() && !!tf.to_bill && !!tf.to_factory && tf.to_seq !== undefined,
);

function resetPreview() {
  pv.value = null;
}
function lineLabel(l: Target) {
  return `${l.seq} · ${l.material_code || t("transfer.emptyMaterial")} · ${t("common.attachNo")} ${l.attach_no || "—"} · ${t("common.length")} ${l.length || "—"}`;
}
/** 切换类型：转单不限来源生产组织、目标生产组织取目标单据的，来源与目标候选都重新加载。 */
function setKind(k: Kind) {
  if (tf.kind === k) return;
  tf.kind = k;
  if (k === "BILL" && tf.to_bill === tf.bill_no) tf.to_bill = "";
  loadSources();
  loadTargets();
}
function open(kind: Kind) {
  Object.assign(tf, {
    open: true, kind, factory_code: store.boundFactory || "", pi_no: "",
    bill_no: "", scope: "BILL", material_code: "", start_sn: "", end_sn: "", to_pi: "", to_bill: "", to_factory: "",
    to_seq: undefined, reason: "",
  });
  srcBills.value = [];
  toRows.value = [];
  sourcesLoaded.value = targetsLoaded.value = false;
  reasonTouched.value = false;
  resetPreview();
  loadSources();
  loadTargets();
}

/** 来源候选：PI（转组织另按来源生产组织）下各单据、各物料的号数。 */
async function loadSources() {
  resetPreview();
  sourcesLoaded.value = false;
  const pi = tf.pi_no.trim();
  if (!pi || (tf.kind === "ORG" && !tf.factory_code)) {
    srcBills.value = [];
    tf.bill_no = tf.material_code = "";
    return;
  }
  const r = await api.transferSources({ kind: tf.kind, pi, factory_code: tf.kind === "ORG" ? tf.factory_code : undefined });
  srcBills.value = r.bills;
  sourcesLoaded.value = true;
  // 目标 PI 未填时默认同来源 PI（拆单多在同一 PI 内）
  if (!tf.to_pi.trim()) {
    tf.to_pi = pi;
    loadTargets();
  }
  if (!srcBills.value.some((b) => b.bill_no === tf.bill_no)) tf.bill_no = srcBills.value.length === 1 ? srcBills.value[0].bill_no : "";
  onSourceBill();
}
function onSourceBill() {
  resetPreview();
  const mats = srcMaterials.value;
  if (!mats.some((x) => matKey(x.material_code) === tf.material_code)) tf.material_code = mats.length === 1 ? matKey(mats[0].material_code) : "";
  autoTargetLine();
}
function onSourceMaterial() {
  resetPreview();
  autoTargetLine();
}

async function suggestPi(q: string, cb: (x: { value: string }[]) => void) {
  if (!q.trim()) return cb([]);
  const rows: Target[] = await api.orderTargets(q.trim(), 50);
  cb([...new Set(rows.map((r) => r.pi).filter(Boolean))].map((v) => ({ value: v })));
}
/** 目标 PI 下快照中的单据与物料行。 */
async function loadTargets() {
  resetPreview();
  targetsLoaded.value = false;
  const pi = tf.to_pi.trim();
  toRows.value = pi ? await api.orderTargets(undefined, 200, pi) : [];
  targetsLoaded.value = !!pi;
  if (!toBills.value.some((b) => b.bill_no === tf.to_bill)) {
    const cands = toBills.value.filter((b) => tf.kind === "ORG" || b.bill_no !== tf.bill_no);
    tf.to_bill = cands.length === 1 ? cands[0].bill_no : "";
  }
  onTargetBill();
}
function onTargetBill() {
  resetPreview();
  const head = toHead.value;
  // 转单：生产组织就是目标单据的；转组织：默认目标单据的，可改
  if (tf.kind === "BILL") tf.to_factory = head?.org_code ?? "";
  else if (!tf.to_factory && head) tf.to_factory = head.org_code;
  if (!toLines.value.some((l) => l.seq === tf.to_seq)) tf.to_seq = undefined;
  autoTargetLine();
}
/** 目标物料行：与来源物料编码相同的只有一行时自动选上；目标单据只有一行时也选上。 */
function autoTargetLine() {
  if (tf.to_seq !== undefined || !toLines.value.length) return;
  const mat = tf.scope === "BILL" ? srcMaterials.value.length === 1 ? srcMaterials.value[0].material_code : undefined : tf.material_code ? matValue(tf.material_code) : undefined;
  const same = mat === undefined ? [] : toLines.value.filter((l) => l.material_code === mat);
  if (same.length === 1) tf.to_seq = same[0].seq;
  else if (toLines.value.length === 1) tf.to_seq = toLines.value[0].seq;
}

function body() {
  const line = toLines.value.find((l) => l.seq === tf.to_seq);
  return {
    factory_code: tf.kind === "ORG" ? tf.factory_code : null, pi_no: tf.pi_no.trim(), bill_no: tf.bill_no, scope: tf.scope,
    material_code: tf.scope === "BILL" ? null : matValue(tf.material_code),
    start_sn: tf.scope === "RANGE" ? tf.start_sn.trim() : null, end_sn: tf.scope === "RANGE" ? tf.end_sn.trim() || null : null,
    to_pi: tf.to_pi.trim(), to_bill: tf.to_bill, to_factory: tf.to_factory, to_material: line?.material_code ?? null,
    to_seq: tf.to_seq ?? null, reason: tf.reason,
  };
}

async function preview() {
  busy.value = "pv";
  try {
    pv.value = tf.kind === "BILL" ? await api.billTransferPreview(body()) : await api.orgCandidates(body());
  } finally {
    busy.value = "";
  }
}

async function submit() {
  const p = pv.value!;
  reasonTouched.value = true;
  if (!tf.reason.trim()) return;
  const args = { n: p.transferable, to: nameOf(p.to_org), pi: p.to_pi, bill: p.to_bill, reason: tf.reason.trim() };
  await ElMessageBox.confirm(t(tf.kind === "BILL" ? "transfer.billConfirm" : "transfer.orgConfirm", args), formTitle.value, { type: "warning" });
  busy.value = "submit";
  try {
    const r = tf.kind === "BILL" ? await api.billTransfer(body()) : await api.orgDirect(body());
    ElMessage.success(t(tf.kind === "BILL" ? "transfer.billDone" : "transfer.orgDone", { no: r.transfer_no, n: r.qty }));
    tf.open = false;
    emit("done", tf.kind, r);
  } finally {
    busy.value = "";
  }
}

defineExpose({ open });
</script>

<style scoped>
.full { width: 100%; }
.mt { margin-top: 12px; }
.mt-s { margin-top: 6px; }
.grow { flex: 1; }
.hint { width: 100%; margin-top: 4px; line-height: 1.5; }
.warn-text { color: var(--el-color-danger); font-size: 12px; }
.reason { margin-top: 12px; border: 1px solid var(--app-line); border-left: 3px solid var(--el-color-warning); border-radius: 8px;
  padding: 10px 16px 12px; background: var(--app-surface); }
.reason.bad { border-left-color: var(--el-color-danger); }
.reason__t { font-size: 13px; font-weight: 600; color: var(--app-text); margin-bottom: 6px; }
.reason .req { color: var(--el-color-danger); margin-right: 4px; }
.form :deep(.el-form-item) { margin-bottom: 14px; }
.form :deep(.el-form-item__label) { padding-bottom: 4px; line-height: 20px; }
.kinds { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 16px; }
.kind { border: 1px solid var(--app-line); border-radius: 8px; padding: 10px 14px; cursor: pointer; transition: border-color .15s, background .15s; }
.kind:hover { border-color: var(--el-color-primary-light-5); }
.kind.on { border-color: var(--el-color-primary); background: var(--el-color-primary-light-9); }
.kind__t { display: flex; align-items: center; gap: 6px; font-weight: 600; color: var(--app-text); margin-bottom: 4px; }
.sides { display: grid; grid-template-columns: minmax(0, 1fr) 28px minmax(0, 1fr); gap: 8px; align-items: stretch; }
.side { border: 1px solid var(--app-line); border-radius: 8px; padding: 12px 16px 0; background: var(--app-surface); }
.side__t { margin: 0 0 10px; font-size: 13px; font-weight: 600; color: var(--app-text); }
.sides__arrow { display: flex; align-items: center; justify-content: center; color: var(--app-muted); font-size: 18px; }
.pair { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; width: 100%; }
.preview { margin-top: 12px; border: 1px solid var(--app-line); border-radius: 8px; padding: 12px 16px; background: var(--app-canvas); }
.kpis { display: flex; flex-wrap: wrap; gap: 18px; align-items: baseline; }
.tags { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.ok { color: var(--el-color-success); }
.warn { color: var(--el-color-danger); }
.foot { display: flex; align-items: center; gap: 8px; }
@media (max-width: 900px) {
  .kinds, .sides, .pair { grid-template-columns: 1fr; }
  .sides__arrow { transform: rotate(90deg); }
}
</style>
