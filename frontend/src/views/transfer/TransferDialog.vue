<template>
  <el-dialog v-model="tf.open" :title="t('transfer.new')" width="1240px" top="4vh">
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
            <el-radio-group v-model="tf.scope" @change="syncLines">
              <el-radio-button v-for="sc in SCOPES" :key="sc" :value="sc">{{ scopeLabel(sc) }}</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <!-- 号分在多个生产组织时才要选；只有一个时自动带出（见来源信息） -->
          <el-form-item v-if="srcOrgs.length > 1" :label="t('transfer.fromOrg')" required>
            <el-select v-model="tf.factory_code" class="full" @change="syncLines">
              <el-option v-for="o in srcOrgs" :key="o.factory_code" :value="o.factory_code" :label="orgLabel(o.factory_code)">
                <div class="opt"><span>{{ orgLabel(o.factory_code) }}</span><span class="muted">{{ t("transfer.snCount", { n: o.qty }) }}</span></div>
              </el-option>
            </el-select>
          </el-form-item>
        </el-form>
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
      </section>
    </div>

    <!-- ③ 料号明细：料号与附件项次同级，来源、目标两侧都列附件项次与料号逐行对照（附件项次在料号前，目标侧料号后为目标ERP行号）。
         整单转移列出全部；按料号转移勾选一个或多个，各自全部、号段或指定数量；目标ERP行号只列目标单据中同料号、同附件项次的行
         （米数相符的只有一行时自动选上） -->
    <section class="lines">
      <div class="lines__t">
        <span>{{ t("transfer.linesTitle") }}</span>
        <span class="hint inline">{{ t("transfer.sameKeyHint") }}</span>
      </div>
      <el-table :data="groups" size="small" border :empty-text="t('transfer.noLines')" max-height="340" :row-class-name="rowClass">
        <el-table-column v-if="tf.scope === 'MATERIAL'" width="40" align="center">
          <template #header>
            <el-checkbox :model-value="allChecked" :indeterminate="someChecked && !allChecked" :disabled="!groups.length" @change="checkAll" />
          </template>
          <template #default="{ row }"><el-checkbox v-model="cfgs[row.key].checked" @change="refreshSeq(row)" /></template>
        </el-table-column>
        <el-table-column :label="t('transfer.source')" align="center" class-name="grp-src">
          <el-table-column :label="t('common.attachNo')" width="80">
            <template #default="{ row }"><span class="key">{{ row.attach_no || "—" }}</span></template>
          </el-table-column>
          <el-table-column :label="t('transfer.partNo')" min-width="96">
            <template #default="{ row }"><span class="key">{{ matLabel(row.material_code) }}</span></template>
          </el-table-column>
          <el-table-column :label="t('common.lineNo')" width="72"><template #default="{ row }">{{ join(row.seqs) }}</template></el-table-column>
          <el-table-column :label="t('common.length')" width="60"><template #default="{ row }">{{ join(row.lengths) }}</template></el-table-column>
          <el-table-column :label="t('transfer.groupQty')" align="right" width="52"><template #default="{ row }"><span class="num">{{ row.qty }}</span></template></el-table-column>
        </el-table-column>
        <el-table-column :label="t('transfer.way')" align="center">
          <el-table-column :label="t('transfer.wayShort')" width="106">
            <template #default="{ row }">
              <span v-if="tf.scope === 'BILL'" class="muted">{{ t("transfer.lineMode.ALL") }}</span>
              <el-select v-else v-model="cfgs[row.key].mode" size="small" :disabled="!cfgs[row.key].checked" @change="refreshSeq(row)">
                <el-option v-for="md in LINE_MODES" :key="md" :value="md" :label="t(`transfer.lineMode.${md}`)" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column :label="t('transfer.configure')" min-width="210">
            <template #default="{ row }">
              <template v-if="tf.scope === 'MATERIAL' && cfgs[row.key].checked">
                <div v-if="cfgs[row.key].mode === 'RANGE'" class="pair">
                  <el-input v-model="cfgs[row.key].start_sn" size="small" class="sn-mono" :placeholder="t('transfer.startSn')" clearable />
                  <el-input v-model="cfgs[row.key].end_sn" size="small" class="sn-mono" :placeholder="t('transfer.endSn')" clearable />
                </div>
                <el-input-number v-else-if="cfgs[row.key].mode === 'QTY'" v-model="cfgs[row.key].qty" size="small" :min="1" :max="row.qty"
                  :step="1" step-strictly :placeholder="t('transfer.qtyPlaceholder', { n: row.qty })" />
                <span v-else class="muted">{{ t("transfer.snCount", { n: row.qty }) }}</span>
              </template>
              <span v-else-if="tf.scope === 'BILL'" class="muted">{{ t("transfer.snCount", { n: row.qty }) }}</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
        </el-table-column>
        <el-table-column :label="t('transfer.target')" align="center" class-name="grp-dst">
          <el-table-column :label="t('common.attachNo')" width="80">
            <template #default="{ row }">
              <span v-if="isActive(row) && toLineOf(row)" class="key">{{ toLineOf(row)!.attach_no || "—" }}</span><span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('transfer.partNo')" min-width="96">
            <template #default="{ row }">
              <span v-if="isActive(row) && toLineOf(row)" class="key">{{ matLabel(toLineOf(row)!.material_code) }}</span><span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('transfer.toLine')" width="120">
            <template #default="{ row }">
              <el-select v-if="isActive(row) && cands(row).length" v-model="cfgs[row.key].to_seq" size="small" class="full" clearable
                :placeholder="sameBill ? t('transfer.keepLine') : t('transfer.pickLine')">
                <el-option v-for="l in cands(row)" :key="l.seq" :value="l.seq" :label="t('transfer.lineNo', { n: l.seq })" :disabled="lineLengthBad(l, row)">
                  <span>{{ t("transfer.lineOption", { seq: l.seq, material: matLabel(l.material_code), attach: l.attach_no || "—", length: l.length || "—" }) }}</span>
                  <span v-if="lineLengthBad(l, row)" class="muted"> · {{ t("transfer.lengthDiff") }}</span>
                </el-option>
              </el-select>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('transfer.match')" width="150">
            <template #default="{ row }">
              <template v-if="isActive(row) && tf.to_bill">
                <el-tag v-if="matchState(row) === 'OK'" type="success" size="small">{{ t("transfer.matchOk") }}</el-tag>
                <el-tag v-else-if="matchState(row) === 'PICK_SEQ'" type="warning" size="small">{{ t("transfer.lineIssue.PICK_SEQ", { n: cands(row).length }) }}</el-tag>
                <el-tag v-else type="danger" size="small">{{ t(`transfer.lineIssue.${matchState(row)}`) }}</el-tag>
              </template>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
        </el-table-column>
      </el-table>
    </section>

    <!-- ④ 转移原因：直接转移、立即生效，原因必填，记入转移单与操作痕迹 -->
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
 *  来源：快速定位（完整 SN / 单据编号 / PI）或 PI → 单据 → 范围：整单转移，或按料号转移（勾选一个或多个「料号 + 附件项次」，
 *  料号即物料编码、与附件项次同级；每行可转全部、号段或指定数量）；来源生产组织按号自动带出（号分在多个组织时才选）。
 *  目标：PI（默认同来源）→ 单据 → 生产组织（默认目标单据的，可改）；只有同料号、同附件项次才能转移——每行只能转到目标单据中
 *  料号、附件项次都相同的物料行（米数相符的只有一行时自动选上，多行时选行号）。确认框列出枚数、各行对应、不可转、转出等，
 *  确认后执行；完成后发出 done（种类、单据）。 */
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "@/api";
import { useFactories } from "@/composables/useFactories";
import { scopeLabel } from "@/constants";
import { useAuthStore } from "@/stores/auth";
import {
  LINE_MODES, activeGroups, autoSeq, candidates, defaultCfg, groupKey, groupsOf, lengthBad, lineIssue, linesBody,
} from "@/transferLines.js";
import { type Kind, type Located, type Preview, type SourceBill, type SourceGroup, type Target } from "./types";
type Scope = "BILL" | "MATERIAL";
type Cfg = ReturnType<typeof defaultCfg>;
const SCOPES: Scope[] = ["BILL", "MATERIAL"];

const emit = defineEmits<{ done: [kind: Kind, doc: any] }>();
const { t } = useI18n();
const store = useAuthStore();
const { factories, nameOf } = useFactories();
const busy = ref("");

const tf = reactive({
  open: false, factory_code: "", pi_no: "", bill_no: "", scope: "BILL" as Scope,
  to_pi: "", to_bill: "", to_factory: "", reason: "",
});
/** 各行（料号 + 附件项次）的勾选、转移方式、号段或数量、目标行号；换单据、换组织时保留同一行的配置。 */
const cfgs = reactive<Record<string, Cfg>>({});
const loc = reactive({ q: "", by: "" as "" | Located["by"] });
const srcBills = ref<SourceBill[]>([]);
const sourcesLoaded = ref(false);
const toRows = ref<Target[]>([]);
const targetsLoaded = ref(false);
const reasonTouched = ref(false);

// ---------------- 来源
const srcBill = computed(() => srcBills.value.find((b) => b.bill_no === tf.bill_no) ?? null);
/** 来源单据的号在各生产组织的枚数。 */
const srcOrgs = computed(() => {
  const by = new Map<string, number>();
  (srcBill.value?.rows ?? []).forEach((r) => by.set(r.factory_code, (by.get(r.factory_code) ?? 0) + r.qty));
  return [...by].map(([factory_code, qty]) => ({ factory_code, qty }));
});
/** 来源单据在来源生产组织下的各行：料号 + 附件项次。 */
const groups = computed<SourceGroup[]>(() => groupsOf(srcBill.value?.rows ?? [], tf.factory_code));
const active = computed<SourceGroup[]>(() => activeGroups(tf.scope, groups.value, cfgs));
const allChecked = computed(() => groups.value.length > 0 && groups.value.every((g) => cfgs[g.key]?.checked));
const someChecked = computed(() => groups.value.some((g) => cfgs[g.key]?.checked));
// ---------------- 目标
const toBills = computed(() => {
  const seen = new Map<string, Target>();
  toRows.value.forEach((r) => seen.has(r.bill_no) || seen.set(r.bill_no, r));
  return [...seen.values()];
});
const toHead = computed(() => toBills.value.find((b) => b.bill_no === tf.to_bill) ?? null);
const toLines = computed(() => toRows.value.filter((r) => r.bill_no === tf.to_bill));
/** 目标单据就是来源单据（同一单据内转组织）：不选行号即留在原行。 */
const sameBill = computed(() => !!tf.to_bill && tf.to_bill === tf.bill_no);

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
    !!tf.factory_code && !!tf.pi_no.trim() && !!tf.bill_no && !!tf.to_pi.trim() && !!tf.to_bill && !!tf.to_factory &&
    active.value.length > 0 && active.value.every((g) => !issue(g)),
);

function matLabel(v: string) {
  return v === "" ? t("transfer.emptyMaterial") : v;
}
function orgLabel(code: string) {
  return `${nameOf(code)}（${code}）`;
}
function join(vs: (string | number)[] = []) {
  return vs.length ? vs.join("、") : "—";
}
function isActive(g: SourceGroup) {
  return tf.scope === "BILL" || !!cfgs[g.key]?.checked;
}
function modeOf(g: SourceGroup) {
  return tf.scope === "BILL" ? "ALL" : cfgs[g.key]?.mode ?? "ALL";
}
/** 目标单据中料号、附件项次都与该行相同的物料行。 */
function cands(g: SourceGroup): Target[] {
  return candidates(toLines.value, g);
}
function lineLengthBad(l: Target, g: SourceGroup) {
  return lengthBad(l, g, modeOf(g));
}
/** 该行当前对应的目标物料行：已选行号的那一行；同一单据内未选时即原行（同料号、同附件项次）。 */
function toLineOf(g: SourceGroup): Target | null {
  const c = cfgs[g.key];
  const cs = cands(g);
  if (c?.to_seq !== undefined) return cs.find((l) => l.seq === c.to_seq) ?? null;
  return sameBill.value && cs.length ? cs[0] : null;
}
/** 对照：OK 同料号同项次 / PICK_SEQ 有多行须选行号 / NO_MATCH 目标没有同料号同项次的行 / LENGTH 米数都不同。
 *  号段未填、数量越界不影响对照（在号段 / 数量列里填好即可）。 */
function matchState(g: SourceGroup) {
  const i = issue(g);
  return i === "NO_MATCH" || i === "LENGTH" || i === "PICK_SEQ" ? i : "OK";
}
/** 目标没有同料号同项次、或米数都不同的行整行标红。 */
function rowClass({ row }: { row: SourceGroup }) {
  if (!isActive(row) || !tf.to_bill) return "";
  const m = matchState(row);
  return m === "NO_MATCH" || m === "LENGTH" ? "row-bad" : "";
}
function issue(g: SourceGroup) {
  return lineIssue(tf.scope, g, { ...cfgs[g.key], mode: modeOf(g) }, toLines.value, sameBill.value);
}

function open() {
  Object.assign(tf, { open: true, factory_code: "", pi_no: "", bill_no: "", scope: "BILL", to_pi: "", to_bill: "", to_factory: "", reason: "" });
  Object.keys(cfgs).forEach((k) => delete cfgs[k]);
  Object.assign(loc, { q: "", by: "" });
  srcBills.value = [];
  toRows.value = [];
  sourcesLoaded.value = targetsLoaded.value = false;
  reasonTouched.value = false;
}

/** 快速定位：SN 带出 PI、单据、料号 + 附件项次、生产组织与起号（按料号转移、该行按号段）；单据编号带出 PI、单据；PI 带出 PI。 */
async function locate() {
  const q = loc.q.trim();
  if (!q) return;
  busy.value = "loc";
  loc.by = "";
  try {
    const r: Located = await api.transferLocate(q);
    tf.pi_no = r.pi;
    await loadSources({ bill: r.bill_no, factory: r.factory_code });
    if (r.by === "SN") {
      tf.scope = "MATERIAL";
      Object.values(cfgs).forEach((c) => (c.checked = false));
      const c = cfgs[groupKey(r.material_code, r.attach_no ?? "")];
      if (c) Object.assign(c, { checked: true, mode: "RANGE", start_sn: r.sn, end_sn: "" });
      syncLines();
    }
    loc.by = r.by;
  } finally {
    busy.value = "";
  }
}

/** 来源候选：PI 下各单据、各料号 + 附件项次、各生产组织的号数；preset 为快速定位带出的值。 */
async function loadSources(preset: { bill?: string; factory?: string } = {}) {
  sourcesLoaded.value = false;
  const pi = tf.pi_no.trim();
  if (!pi) {
    srcBills.value = [];
    tf.bill_no = tf.factory_code = "";
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
  onSourceBill(preset.factory);
}
/** 来源生产组织：来源单据的号只在一个组织时自动带出；多个时保留已选的（或快速定位带出的），否则待选。 */
function onSourceBill(prefer?: string) {
  const orgs = srcOrgs.value.map((o) => o.factory_code);
  if (prefer && orgs.includes(prefer)) tf.factory_code = prefer;
  else if (!orgs.includes(tf.factory_code)) tf.factory_code = orgs.length === 1 ? orgs[0] : "";
  syncLines();
}
/** 各行配置：新出现的行补默认值（只有一行时默认勾选），并重新匹配目标行号。 */
function syncLines() {
  for (const g of groups.value) if (!cfgs[g.key]) cfgs[g.key] = { ...defaultCfg(), checked: groups.value.length === 1 };
  groups.value.forEach(refreshSeq);
}
/** 目标行号按同料号、同附件项次自动匹配（米数相符的只有一行时选上）；同一单据内默认留在原行（不选）。 */
function refreshSeq(g: SourceGroup) {
  const c = cfgs[g.key];
  if (!c) return;
  if (sameBill.value && !cands(g).some((l) => l.seq === c.to_seq)) c.to_seq = undefined;
  else if (!sameBill.value) c.to_seq = autoSeq(toLines.value, g, { ...c, mode: modeOf(g) });
}
// 来源行或目标物料行变化时（换单据、组织、目标单据）补配置并重新匹配目标行号；在渲染前执行
watch([groups, toLines, sameBill], syncLines);

function checkAll(on: boolean | string | number) {
  groups.value.forEach((g) => {
    cfgs[g.key].checked = !!on;
    refreshSeq(g);
  });
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
/** 换目标单据：生产组织回到该单据的，各行目标行号重新匹配。 */
function onTargetBill() {
  tf.to_factory = toHead.value?.org_code ?? "";
  syncLines();
}

function body() {
  return {
    factory_code: tf.factory_code, pi_no: tf.pi_no.trim(), bill_no: tf.bill_no, scope: tf.scope,
    lines: linesBody(tf.scope, groups.value, cfgs),
    to_pi: tf.to_pi.trim(), to_bill: tf.to_bill, to_factory: tf.to_factory, reason: tf.reason,
  };
}

/** 确认框：类型、可转枚数、目标、各行对应的目标行号、不可转及原因、向来源 MES 转出的枚数、原因；确认后直接执行。 */
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
    t("transfer.confirm", { kind: t(`transfer.kindName.${p.kind}`), n: p.transferable, to: orgLabel(p.to_org), pi: p.to_pi, bill: p.to_bill }),
    ...p.lines.filter((x) => x.transferable).map((x) =>
      t("transfer.confirmLine", { material: matLabel(x.material_code), attach: x.attach_no || "—", from: x.from_seq, seq: x.to_seq, n: x.transferable }),
    ),
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
.hint.inline { width: auto; margin: 0 0 0 12px; font-weight: 400; }
.lines { margin-top: 12px; border: 1px solid var(--app-line); border-radius: 8px; padding: 10px 16px 12px; background: var(--app-surface); }
.lines__t { display: flex; align-items: baseline; flex-wrap: wrap; font-size: 13px; font-weight: 600; color: var(--app-text); margin-bottom: 6px; }
.lines :deep(.el-input-number) { width: 130px; }
.key { font-family: var(--sn-mono, ui-monospace, monospace); font-weight: 600; }
.lines :deep(th.grp-src) { background: var(--el-color-primary-light-9) !important; }
.lines :deep(th.grp-dst) { background: var(--el-color-success-light-9) !important; }
.lines :deep(.row-bad td) { background: var(--el-color-danger-light-9) !important; }
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
.opt { display: flex; justify-content: space-between; gap: 16px; }
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
