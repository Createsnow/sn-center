<template>
  <el-dialog v-model="bf.open" :title="t('transfer.newBill')" width="860px" top="5vh">
    <el-form label-width="120px" class="form">
      <el-form-item :label="t('transfer.fromBill')" required>
        <el-input v-model="bf.from_bill" class="w260" clearable @change="loadFromLines" />
        <span v-if="fromLines.length" class="muted ml">{{ t("transfer.linesInSnapshot", { n: fromLines.length }) }}</span>
        <span v-else-if="bf.from_bill" class="muted ml">{{ t("transfer.sourceNotInSnapshot") }}</span>
      </el-form-item>
      <el-form-item :label="t('transfer.mode')">
        <el-radio-group v-model="bf.mode" @change="resetPreview">
          <el-radio-button value="VOID">{{ t("transfer.billMode.VOID") }}</el-radio-button>
          <el-radio-button value="SPLIT">{{ t("transfer.billMode.SPLIT") }}</el-radio-button>
        </el-radio-group>
        <span class="muted ml">{{ t(`transfer.billModeTip.${bf.mode}`) }}</span>
      </el-form-item>
      <el-form-item :label="t('transfer.toBill')" required>
        <el-autocomplete v-model="bf.to_bill" class="w260" :fetch-suggestions="suggestBills" :trigger-on-focus="false" clearable
          @select="loadToLines" @change="loadToLines" />
        <span v-if="toBill" class="muted ml">{{ nameOf(toBill.org_code) }} · PI {{ toBill.pi }} · {{ t("common.customer") }} {{ toBill.customer_code }}</span>
      </el-form-item>
      <template v-if="bf.mode === 'SPLIT'">
        <el-form-item :label="t('transfer.fromLine')" required>
          <el-select v-if="fromLines.length" v-model="bf.from_seq" class="w260" @change="resetPreview">
            <el-option v-for="l in fromLines" :key="l.seq" :value="l.seq" :label="`${l.seq} · ${l.material_code || t('transfer.emptyMaterial')} · ${l.length}`" />
          </el-select>
          <el-input-number v-else v-model="bf.from_seq" :min="1" controls-position="right" @change="resetPreview" />
        </el-form-item>
        <el-form-item :label="t('transfer.splitScope')">
          <FactorySelect v-model="bf.factory_code" clearable :placeholder="t('common.factory')" @update:model-value="resetPreview" />
          <el-input v-model="bf.pi_no" class="w180 ml" placeholder="PI" clearable @change="resetPreview" />
        </el-form-item>
        <el-form-item :label="t('transfer.range')">
          <el-input v-model="bf.start_sn" class="w220" :placeholder="t('transfer.startSn')" clearable @change="resetPreview" />
          <span class="ml">~</span>
          <el-input v-model="bf.end_sn" class="w220 ml" :placeholder="t('transfer.endSn')" clearable @change="resetPreview" />
          <div class="muted hint">{{ t("transfer.rangeHint") }}</div>
        </el-form-item>
        <el-form-item :label="t('transfer.toLine')">
          <el-select v-model="bf.to_seq" class="w260" clearable :placeholder="t('transfer.autoByMaterial')" @change="resetPreview">
            <el-option v-for="l in toLines" :key="l.seq" :value="l.seq" :label="lineLabel(l)" />
          </el-select>
        </el-form-item>
      </template>
      <el-form-item v-else-if="mapRows.length" :label="t('transfer.mapping')">
        <div class="map">
          <div v-for="r in mapRows" :key="r.seq" class="map-row">
            <span>{{ t("transfer.sourceLine", { seq: r.seq, m: r.material || t("transfer.emptyMaterial") }) }}</span>
            <span>→</span>
            <el-select v-model="mapping[r.seq]" class="w260" clearable :placeholder="t('transfer.autoByMaterial')" @change="resetPreview">
              <el-option v-for="l in toLines" :key="l.seq" :value="l.seq" :label="lineLabel(l)" />
            </el-select>
          </div>
          <div class="muted">{{ t("transfer.mappingHint") }}</div>
        </div>
      </el-form-item>
      <el-form-item :label="t('common.reason')" required>
        <el-input v-model="bf.reason" type="textarea" :rows="2" maxlength="500" show-word-limit :placeholder="t('common.reasonPlaceholder')" />
      </el-form-item>
    </el-form>

    <div v-if="preview" class="preview">
      <div class="kpis">
        <span>{{ t("transfer.pvTotal") }} <b class="num">{{ preview.total }}</b></span>
        <span>{{ t("transfer.pvTransferable") }} <b class="num ok">{{ preview.transferable }}</b></span>
        <span>{{ t("transfer.pvDelivered") }} <b class="num">{{ preview.delivered }}</b></span>
        <span v-if="preview.other_org">{{ t("transfer.pvOtherOrg") }} <b class="num warn">{{ preview.other_org }}</b></span>
        <span class="muted">→ {{ nameOf(preview.to_org) }} · PI {{ preview.to_pi }} · {{ preview.scope }}</span>
      </div>
      <div class="tags">
        <el-tag v-for="(n, s) in preview.by_status" :key="s" size="small" effect="plain">{{ statusLabel(String(s)) }} {{ n }}</el-tag>
        <el-tag v-for="(n, r) in preview.blocked" :key="r" size="small" type="danger">{{ t(`transfer.blocked.${r}`) }} {{ n }}</el-tag>
      </div>
      <el-table :data="preview.generated_changes" size="small" class="mt">
        <el-table-column :label="t('common.billNo')" min-width="160"><template #default="{ row }">{{ row.bill_no }} / {{ row.seq }}</template></el-table-column>
        <el-table-column :label="t('transfer.genBefore')" align="right" width="110"><template #default="{ row }"><span class="num">{{ row.before }}</span></template></el-table-column>
        <el-table-column :label="t('transfer.genAfter')" align="right" width="110"><template #default="{ row }"><span class="num">{{ row.after }}</span></template></el-table-column>
      </el-table>
      <p class="muted">{{ t("transfer.quotaUnchanged") }}</p>
    </div>
    <template #footer>
      <el-button @click="bf.open = false">{{ t("common.cancel") }}</el-button>
      <el-button :loading="busy === 'preview'" :disabled="!bf.from_bill || !bf.to_bill" @click="previewBill">{{ t("transfer.preview") }}</el-button>
      <el-button type="primary" :loading="busy === 'submit'" :disabled="!preview || !preview.transferable || !bf.reason.trim()" @click="submitBill">{{ t("transfer.submitBill") }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/** 新建转单（F-15）：整单作废（VOID，来源各物料行按物料对到目标单据，对不上的手工选）或拆单（SPLIT，按物料行 + 号段）。
 *  先预演确认框（枚数、各状态、不可转原因、已生成数变化），再提交；完成后发出 done（转单单）。 */
import { reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "@/api";
import { ApiError, http } from "@/api/http";
import FactorySelect from "@/components/FactorySelect.vue";
import { useFactories } from "@/composables/useFactories";
import { statusLabel } from "@/constants";
import type { OrderLine } from "@/types/api";
import { type Summary, useTargets } from "./useTargets";

interface BillPreview extends Summary {
  generated_changes: { bill_no: string; seq: number; before: number; after: number }[];
  to_pi: string;
  to_org: string;
  scope: string;
  other_org: number;
}

const emit = defineEmits<{ done: [doc: any] }>();
const { t } = useI18n();
const { nameOf } = useFactories();
const { suggestBills } = useTargets();
const busy = ref("");

const bf = reactive({
  open: false, from_bill: "", to_bill: "", mode: "VOID" as "VOID" | "SPLIT", factory_code: "", pi_no: "",
  from_seq: undefined as number | undefined, start_sn: "", end_sn: "", to_seq: undefined as number | undefined, reason: "",
});
const fromLines = ref<OrderLine[]>([]);
const toLines = ref<OrderLine[]>([]);
const toBill = ref<{ org_code: string; pi: string; customer_code: string } | null>(null);
const mapping = reactive<Record<number, number | undefined>>({});
const mapRows = ref<{ seq: number; material: string }[]>([]);
const preview = ref<BillPreview | null>(null);

function resetPreview() {
  preview.value = null;
}
function lineLabel(l: OrderLine) {
  return `${l.seq} · ${l.material_code || t("transfer.emptyMaterial")} · ${t("common.length")} ${l.length || "—"}`;
}

function open() {
  Object.assign(bf, { open: true, from_bill: "", to_bill: "", mode: "VOID", factory_code: "", pi_no: "", from_seq: undefined, start_sn: "", end_sn: "", to_seq: undefined, reason: "" });
  fromLines.value = [];
  toLines.value = [];
  toBill.value = null;
  mapRows.value = [];
  Object.keys(mapping).forEach((k) => delete mapping[Number(k)]);
  resetPreview();
}

/** 单据可能已不在快照中：查不到时静默（不弹错误）。 */
async function linesOf(billNo: string): Promise<{ head: any; lines: OrderLine[] } | null> {
  if (!billNo.trim()) return null;
  try {
    const r = await http.get<any, any>(`/orders/${encodeURIComponent(billNo.trim())}/lines`, { silent: true });
    return { head: r, lines: r.lines || [] };
  } catch {
    return null;
  }
}

async function loadFromLines() {
  resetPreview();
  const r = await linesOf(bf.from_bill);
  fromLines.value = r?.lines ?? [];
  rebuildMapRows();
}
async function loadToLines() {
  resetPreview();
  const r = await linesOf(bf.to_bill);
  toLines.value = r?.lines ?? [];
  toBill.value = r ? { org_code: r.head.org_code, pi: r.head.pi, customer_code: r.head.customer_code } : null;
  rebuildMapRows();
}
/** 整单作废：来源各物料行在目标单据中没有或有多行同物料时，须手工选目标物料行。 */
function rebuildMapRows() {
  if (!fromLines.value.length || !toLines.value.length) return;
  const rows = fromLines.value
    .filter((l) => toLines.value.filter((x) => x.material_code === l.material_code).length !== 1)
    .map((l) => ({ seq: l.seq, material: l.material_code }));
  const extra = mapRows.value.filter((r) => !rows.some((x) => x.seq === r.seq));
  mapRows.value = [...rows, ...extra];
}

function body() {
  const map: Record<string, number> = {};
  Object.entries(mapping).forEach(([k, v]) => {
    if (v !== undefined && v !== null) map[k] = v;
  });
  return bf.mode === "VOID"
    ? { from_bill: bf.from_bill.trim(), to_bill: bf.to_bill.trim(), mode: "VOID", mapping: map, reason: bf.reason }
    : {
        from_bill: bf.from_bill.trim(), to_bill: bf.to_bill.trim(), mode: "SPLIT", factory_code: bf.factory_code || null,
        pi_no: bf.pi_no.trim() || null, from_seq: bf.from_seq ?? null, start_sn: bf.start_sn.trim() || null,
        end_sn: bf.end_sn.trim() || null, to_seq: bf.to_seq ?? null, reason: bf.reason,
      };
}

async function previewBill() {
  busy.value = "preview";
  try {
    preview.value = await api.billTransferPreview(body());
  } catch (e) {
    // 目标单据中没有或有多行对应：加一行让用户手工选目标物料行
    if (e instanceof ApiError && e.code === "TR_TARGET_LINE_REQUIRED" && bf.mode === "VOID") {
      const seq = Number(e.params?.seq);
      if (seq && !mapRows.value.some((r) => r.seq === seq)) mapRows.value.push({ seq, material: String(e.params?.material ?? "") });
      if (!toLines.value.length) await loadToLines();
    }
  } finally {
    busy.value = "";
  }
}

async function submitBill() {
  const p = preview.value!;
  await ElMessageBox.confirm(t("transfer.billConfirm", { n: p.transferable, to: bf.to_bill }), t("transfer.newBill"), { type: "warning" });
  busy.value = "submit";
  try {
    const r = await api.billTransfer(body());
    ElMessage.success(t("transfer.billDone", { no: r.transfer_no, n: r.qty }));
    bf.open = false;
    emit("done", r);
  } finally {
    busy.value = "";
  }
}

defineExpose({ open });
</script>

<style scoped>
.w180 { width: 180px; }
.w220 { width: 220px; }
.w260 { width: 260px; }
.ml { margin-left: 8px; }
.mt { margin-top: 12px; }
.hint { width: 100%; margin-top: 4px; }
.form :deep(.el-form-item) { margin-bottom: 14px; }
.map { display: flex; flex-direction: column; gap: 8px; }
.map-row { display: flex; align-items: center; gap: 8px; }
.preview { border: 1px solid var(--app-line); border-radius: 8px; padding: 12px 16px; background: var(--app-canvas); }
.kpis { display: flex; flex-wrap: wrap; gap: 18px; align-items: baseline; }
.tags { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.ok { color: var(--el-color-success); }
.warn { color: var(--el-color-danger); }
</style>
