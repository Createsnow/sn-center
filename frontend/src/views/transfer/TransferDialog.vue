<template>
  <el-dialog v-model="tf.open" :title="formTitle" width="860px" top="5vh">
    <el-form label-width="120px" class="form">
      <h4 class="sec">{{ t("transfer.source") }}</h4>
      <el-form-item v-if="tf.kind === 'ORG'" :label="t('transfer.fromOrg')" required>
        <FactorySelect v-model="tf.factory_code" :disabled="store.isFactory" @update:model-value="loadSources" />
      </el-form-item>
      <el-form-item label="PI" required>
        <el-input v-model="tf.pi_no" class="w260" clearable @change="loadSources" />
        <span v-if="tf.pi_no && sourcesLoaded && !srcBills.length" class="muted ml">{{ t("transfer.noSnInPi") }}</span>
      </el-form-item>
      <el-form-item :label="t('transfer.fromBill')" required>
        <el-select v-model="tf.bill_no" class="w260" filterable :disabled="!srcBills.length" :placeholder="t('transfer.pickBill')" @change="onSourceBill">
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
        <el-select v-model="tf.material_code" class="w260" filterable :disabled="!srcMaterials.length" :placeholder="t('transfer.pickMaterial')" @change="onSourceMaterial">
          <el-option v-for="mm in srcMaterials" :key="matKey(mm.material_code)" :value="matKey(mm.material_code)"
            :label="`${mm.material_code || t('transfer.emptyMaterial')} · ${t('transfer.snCount', { n: mm.qty })}`" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="tf.scope === 'RANGE'" :label="t('transfer.range')" required>
        <el-input v-model="tf.start_sn" class="w220 sn-mono" :placeholder="t('transfer.startSn')" clearable @change="resetPreview" />
        <span class="ml">~</span>
        <el-input v-model="tf.end_sn" class="w220 ml sn-mono" :placeholder="t('transfer.endSn')" clearable @change="resetPreview" />
        <div class="muted hint">{{ t("transfer.rangeHint") }}</div>
      </el-form-item>

      <el-divider />
      <h4 class="sec">{{ t("transfer.target") }}</h4>
      <el-form-item :label="t('transfer.toPi')" required>
        <el-autocomplete v-model="tf.to_pi" style="width: 260px" :fetch-suggestions="suggestPi" :trigger-on-focus="false" clearable
          @select="loadTargets" @change="loadTargets" />
      </el-form-item>
      <el-form-item :label="t('transfer.toBill')" required>
        <el-select v-model="tf.to_bill" class="w260" filterable :disabled="!toBills.length" :placeholder="t('transfer.pickBill')" @change="onTargetBill">
          <el-option v-for="b in toBills" :key="b.bill_no" :value="b.bill_no" :disabled="tf.kind === 'BILL' && b.bill_no === tf.bill_no"
            :label="`${b.bill_no} · ${nameOf(b.org_code)}`" />
        </el-select>
        <span v-if="toHead" class="muted ml">{{ t("common.customer") }} {{ toHead.customer_code || "—" }}</span>
        <span v-else-if="tf.to_pi && targetsLoaded && !toBills.length" class="muted ml">{{ t("transfer.noBillInPi") }}</span>
      </el-form-item>
      <el-form-item :label="t('transfer.toOrg')" required>
        <el-select v-model="tf.to_factory" filterable class="w260" :disabled="tf.kind === 'BILL'" @change="resetPreview">
          <el-option v-for="x in factories" :key="x.factory_code" :value="x.factory_code" :disabled="tf.kind === 'ORG' && !x.enabled"
            :label="`${x.factory_name}（${x.factory_code}）${x.mes_enabled ? ' · MES' : ''}${x.enabled ? '' : t('common.disabledSuffix')}`" />
          <el-option v-if="tf.kind === 'BILL' && tf.to_factory && !factories.some((x) => x.factory_code === tf.to_factory)" :value="tf.to_factory" :label="tf.to_factory" />
        </el-select>
        <span class="muted ml">{{ tf.kind === "BILL" ? t("transfer.orgOfBill") : t("transfer.orgFree") }}</span>
      </el-form-item>
      <el-form-item :label="t('transfer.toMaterial')" required>
        <el-select v-model="tf.to_seq" class="w260" :disabled="!toLines.length" :placeholder="t('transfer.pickTargetLine')" @change="resetPreview">
          <el-option v-for="l in toLines" :key="l.seq" :value="l.seq" :label="lineLabel(l)" />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('common.reason')" required>
        <el-input v-model="tf.reason" type="textarea" :rows="2" maxlength="500" show-word-limit :placeholder="t('common.reasonPlaceholder')" />
      </el-form-item>
    </el-form>

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
      <p v-if="tf.kind === 'ORG'" class="muted">{{ store.isAdmin ? t("transfer.directNote") : t("transfer.applyNote") }}</p>
    </div>
    <template #footer>
      <el-button @click="tf.open = false">{{ t("common.cancel") }}</el-button>
      <el-button :loading="busy === 'pv'" :disabled="!ready" @click="preview">{{ t("transfer.preview") }}</el-button>
      <el-button type="primary" :loading="busy === 'submit'" :disabled="!pv || !pv.transferable || !tf.reason.trim()" @click="submit">{{ submitLabel }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/** 新建转单（F-15，总部）/ 转组织（F-16，生产组织申请、总部直接转）共用一个表单：
 *  来源 PI → 单据 → 范围（整张单据 / 物料编码下全部号 / 物料编码下单枚或连续号段），转组织另选来源生产组织；
 *  目标 PI、单据、生产组织、物料行都必填（转单的生产组织取目标单据的）。完成后发出 done（种类、单据）。 */
import { computed, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "@/api";
import FactorySelect from "@/components/FactorySelect.vue";
import { useFactories } from "@/composables/useFactories";
import { scopeLabel, statusLabel } from "@/constants";
import { useAuthStore } from "@/stores/auth";
import type { Preview, SourceBill, Target } from "./types";

type Kind = "BILL" | "ORG";
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
let wantMaterial: string | undefined;

const srcMaterials = computed(() => srcBills.value.find((b) => b.bill_no === tf.bill_no)?.materials ?? []);
const toBills = computed(() => {
  const seen = new Map<string, Target>();
  toRows.value.forEach((r) => seen.has(r.bill_no) || seen.set(r.bill_no, r));
  return [...seen.values()];
});
const toHead = computed(() => toBills.value.find((b) => b.bill_no === tf.to_bill) ?? null);
const toLines = computed(() => toRows.value.filter((r) => r.bill_no === tf.to_bill));
const formTitle = computed(() => (tf.kind === "BILL" ? t("transfer.newBill") : store.isAdmin ? t("transfer.newDirect") : t("transfer.newApply")));
const submitLabel = computed(() => (tf.kind === "BILL" ? t("transfer.submitBill") : store.isAdmin ? t("transfer.submitDirect") : t("transfer.submitApply")));
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
  return `${l.seq} · ${l.material_code || t("transfer.emptyMaterial")} · ${t("common.length")} ${l.length || "—"}`;
}
function open(kind: Kind, prefill: { factory_code?: string; pi?: string; material?: string } = {}) {
  Object.assign(tf, {
    open: true, kind, factory_code: store.isFactory ? store.boundFactory : prefill.factory_code || "", pi_no: prefill.pi || "",
    bill_no: "", scope: "BILL", material_code: "", start_sn: "", end_sn: "", to_pi: prefill.pi || "", to_bill: "", to_factory: "",
    to_seq: undefined, reason: "",
  });
  wantMaterial = prefill.material;
  srcBills.value = [];
  toRows.value = [];
  sourcesLoaded.value = targetsLoaded.value = false;
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
  // 从领取页带物料过来：该物料只在一张单据下时直接选上
  if (wantMaterial !== undefined) {
    const hit = srcBills.value.filter((b) => b.materials.some((x) => x.material_code === wantMaterial));
    if (hit.length === 1) Object.assign(tf, { bill_no: hit[0].bill_no, scope: "MATERIAL", material_code: matKey(wantMaterial) });
    wantMaterial = undefined;
  }
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
  const args = { n: p.transferable, to: nameOf(p.to_org), pi: p.to_pi, bill: p.to_bill };
  const key = tf.kind === "BILL" ? "transfer.billConfirm" : store.isAdmin ? "transfer.directConfirm" : "transfer.applyConfirm";
  await ElMessageBox.confirm(t(key, args), formTitle.value, { type: "warning" });
  busy.value = "submit";
  try {
    const r = tf.kind === "BILL" ? await api.billTransfer(body()) : store.isAdmin ? await api.orgDirect(body()) : await api.orgApply(body());
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
.w220 { width: 220px; }
.w260 { width: 260px; }
.ml { margin-left: 8px; }
.mt { margin-top: 12px; }
.mt-s { margin-top: 6px; }
.hint { width: 100%; margin-top: 4px; }
.sec { margin: 0 0 10px; font-size: 14px; color: var(--app-text); }
.form :deep(.el-form-item) { margin-bottom: 14px; }
.preview { border: 1px solid var(--app-line); border-radius: 8px; padding: 12px 16px; background: var(--app-canvas); }
.kpis { display: flex; flex-wrap: wrap; gap: 18px; align-items: baseline; }
.tags { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.ok { color: var(--el-color-success); }
.warn { color: var(--el-color-danger); }
</style>
