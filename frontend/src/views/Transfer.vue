<template>
  <div class="page">
    <PageHead :title="t('transfer.title')" :desc="t('transfer.desc')">
      <el-button v-if="store.canAct" type="primary" @click="openForm()">
        <el-icon><Plus /></el-icon><span>{{ store.isAdmin ? t("transfer.directSubmit") : t("transfer.applySubmit") }}</span>
      </el-button>
    </PageHead>

    <div class="surface">
      <div class="toolbar">
        <el-radio-group v-model="rf.status" @change="loadRecords(1)">
          <el-radio-button v-for="s in STATUSES" :key="s" :value="s">
            {{ s ? transferStatusLabel(s) : t("common.all") }}<span v-if="s === 'PENDING' && pendingCount" class="cnt num">{{ pendingCount }}</span>
          </el-radio-button>
        </el-radio-group>
        <FactorySelect v-if="!store.boundFactory" v-model="rf.factory_code" clearable @update:model-value="loadRecords(1)" />
        <el-input v-model="rf.pi" class="pi" :placeholder="t('transfer.recordSearch')" clearable prefix-icon="Search" @keyup.enter="loadRecords(1)" @clear="loadRecords(1)" />
      </div>
      <el-table v-loading="recState.loading" :data="records" size="small" :empty-text="t('common.empty')">
        <el-table-column :label="t('transfer.no')" min-width="170">
          <template #default="{ row }">
            <div class="sn-mono">{{ row.transfer.transfer_no }}</div>
            <div class="tags">
              <el-tag size="small" :type="TRANSFER_STATUS_TYPE[row.transfer.status]">{{ transferStatusLabel(row.transfer.status) }}</el-tag>
              <el-tag size="small" effect="plain">{{ scopeLabel(row.transfer.scope) }}</el-tag>
              <el-tag v-if="row.transfer.mode === 'DIRECT'" size="small" effect="plain" type="info">{{ t("transfer.modeDIRECT") }}</el-tag>
              <el-tag v-if="row.direction" size="small" :type="row.direction === 'OUT' ? 'danger' : 'success'">{{ t(`transfer.dir${row.direction}`) }}</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('transfer.fromTo')" min-width="280">
          <template #default="{ row }">
            <div>
              {{ nameOf(row.transfer.from_factory) }} · <b>{{ row.transfer.from_pi }}</b>
              <span v-if="row.transfer.from_material || row.transfer.from_sn" class="muted ml">{{ row.transfer.from_material || row.transfer.from_sn }}</span>
            </div>
            <div>
              → {{ nameOf(row.transfer.to_factory) }} · <b>{{ row.transfer.to_pi }}</b>
              <el-tag v-if="row.transfer.target_source === 'MANUAL'" size="small" type="warning" class="ml">{{ t("transfer.srcMANUAL") }}</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.qty')" align="right" width="70"><template #default="{ row }"><b class="num">{{ row.transfer.qty }}</b></template></el-table-column>
        <el-table-column :label="t('transfer.reason')" min-width="160" show-overflow-tooltip><template #default="{ row }">{{ row.transfer.reason }}</template></el-table-column>
        <el-table-column :label="t('transfer.applied')" width="150">
          <template #default="{ row }"><div>{{ row.transfer.applied_by }}</div><div class="muted">{{ row.transfer.applied_at }}</div></template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="190" align="right" fixed="right">
          <template #default="{ row }">
            <template v-if="row.transfer.status === 'PENDING'">
              <template v-if="store.isAdmin">
                <el-button size="small" type="success" @click="decide(row.transfer, 'approve')">{{ t("transfer.approve") }}</el-button>
                <el-button size="small" @click="decide(row.transfer, 'reject')">{{ t("transfer.reject") }}</el-button>
              </template>
              <el-button v-if="store.isFactory && row.transfer.from_factory === store.boundFactory" size="small"
                @click="withdraw(row.transfer)">{{ t("transfer.withdraw") }}</el-button>
            </template>
            <el-button link type="primary" @click="openItems(row.transfer)">{{ t("common.detail") }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="recState.total" :page-size="recState.pageSize"
        :current-page="recState.page" :page-sizes="[20, 50, 100]" @current-change="loadRecords" @size-change="recSize" />
    </div>

    <!-- 新建转厂 -->
    <el-drawer v-model="formOpen" size="980px" :title="store.isAdmin ? t('transfer.directTab') : t('transfer.applyTab')">
      <div class="form">
        <div class="steps">
          <section>
            <h4><span class="n">1</span>{{ t("transfer.stepSource") }}</h4>
            <el-form label-position="top" class="grid2">
              <el-form-item :label="t('transfer.fromFactory')" required>
                <FactorySelect v-if="store.isAdmin" v-model="src.factory_code" />
                <el-input v-else :model-value="store.profile?.factory_name || store.boundFactory" disabled />
              </el-form-item>
              <el-form-item label="PI" required>
                <el-input v-model="src.pi_no" clearable />
              </el-form-item>
            </el-form>
            <el-form label-position="top">
              <el-form-item :label="t('transfer.scope')" required>
                <el-radio-group v-model="src.scope">
                  <el-radio-button v-for="s in ['PI', 'MATERIAL', 'SINGLE']" :key="s" :value="s">{{ scopeLabel(s) }}</el-radio-button>
                </el-radio-group>
              </el-form-item>
              <el-form-item v-if="src.scope === 'MATERIAL'" :label="t('common.material')" required>
                <el-radio-group v-if="materials.length" v-model="src.material_code" size="small" class="chips">
                  <el-radio-button v-for="mt in materials" :key="mt" :value="mt">{{ mt || t("orders.noMaterial") }}</el-radio-button>
                </el-radio-group>
                <span v-else class="muted">{{ t("transfer.noMaterials") }}</span>
              </el-form-item>
              <el-form-item v-if="src.scope === 'SINGLE'" :label="t('common.sn')" required>
                <el-input v-model="src.sn" clearable class="sn-mono" />
              </el-form-item>
            </el-form>
          </section>

          <section>
            <h4><span class="n">2</span>{{ t("transfer.stepTarget") }}</h4>
            <template v-if="!manualMode">
              <el-autocomplete v-model="targetQ" class="full" :fetch-suggestions="suggestTargets" :trigger-on-focus="true" clearable
                :placeholder="t('transfer.targetSearch')" value-key="pi" popper-class="target-pop" @select="useTarget">
                <template #default="{ item }">
                  <div class="opt">
                    <b>{{ item.pi }}</b>
                    <span>{{ item.factory_code ? nameOf(item.factory_code) : item.prd_org_name }}</span>
                    <span class="muted">{{ item.customer_code }} · {{ item.material_code || t("orders.noMaterial") }}<template v-if="item.material_name"> · {{ item.material_name }}</template></span>
                    <el-tag v-if="!item.factory_code" size="small" type="danger">{{ t("orders.noFactory") }}</el-tag>
                  </div>
                </template>
              </el-autocomplete>
              <div v-if="target.to_pi" class="picked">
                <el-tag type="success" size="small">{{ t("transfer.srcSNAPSHOT") }}</el-tag>
                <el-tag effect="plain" size="small">{{ t("transfer.toFactory") }} {{ nameOf(target.to_factory) || "—" }}</el-tag>
                <el-tag effect="plain" size="small">PI {{ target.to_pi }}</el-tag>
                <el-tag effect="plain" size="small">{{ t("common.customer") }} {{ target.to_customer || t("transfer.keepShort") }}</el-tag>
                <el-tag effect="plain" size="small">{{ t("common.material") }} {{ target.to_material || t("transfer.keepShort") }}</el-tag>
                <el-button link type="primary" @click="manualMode = true">{{ t("common.edit") }}</el-button>
              </div>
              <el-button link type="primary" class="mt start" @click="manualMode = true">{{ t("transfer.manualEntry") }}</el-button>
            </template>
            <template v-else>
              <div class="picked"><el-tag type="warning" size="small">{{ t("transfer.srcMANUAL") }}</el-tag>
                <el-button link type="primary" @click="manualMode = false">{{ t("transfer.backToSnapshot") }}</el-button></div>
              <el-form label-position="top" class="grid2">
                <el-form-item :label="t('transfer.toFactory')" required><FactorySelect v-model="target.to_factory" @update:model-value="manual" /></el-form-item>
                <el-form-item :label="t('transfer.toPi')" required><el-input v-model="target.to_pi" clearable @input="manual" /></el-form-item>
                <el-form-item :label="t('transfer.toCustomer')"><el-input v-model="target.to_customer" clearable :placeholder="t('transfer.keep')" @input="manual" /></el-form-item>
                <el-form-item :label="t('transfer.toMaterial')"><el-input v-model="target.to_material" clearable :placeholder="t('transfer.keep')" @input="manual" /></el-form-item>
              </el-form>
            </template>
          </section>

          <section>
            <h4><span class="n">3</span>{{ t("transfer.reason") }}</h4>
            <el-input v-model="reason" type="textarea" :rows="3" maxlength="500" show-word-limit />
          </section>
          <p class="muted">{{ t("transfer.rule") }}</p>
        </div>

        <aside class="summary">
          <b>{{ t("transfer.summaryTitle") }}</b>
          <div class="flow">
            <span class="big num">{{ t("transfer.pieces", { n: candidates?.transferable ?? 0 }) }}</span>
            <span>{{ srcFactoryName || "—" }} · {{ src.pi_no.trim() || "—" }}<template v-if="src.scope === 'MATERIAL' && src.material_code !== null"> · {{ src.material_code || t("orders.noMaterial") }}</template><template v-if="src.scope === 'SINGLE' && src.sn.trim()"> · {{ src.sn.trim() }}</template></span>
            <span class="muted">↓</span>
            <span>{{ nameOf(target.to_factory) || "—" }} · {{ target.to_pi.trim() || "—" }}</span>
          </div>
          <div v-loading="checking" class="checks">
            <div v-for="c in checks" :key="c.key" class="check">
              <span class="ic" :class="c.state">{{ c.state === "ok" ? "✓" : c.state === "warn" ? "!" : "✕" }}</span>
              <span>{{ c.text }}</span>
            </div>
          </div>
          <div v-if="candidates?.by_status && Object.keys(candidates.by_status).length" class="by-status">
            <span v-for="(n, s) in candidates.by_status" :key="s"><StatusTag :status="String(s)" /> <span class="num">{{ n }}</span></span>
          </div>
          <el-button type="primary" class="submit" :loading="submitting" :disabled="!canSubmit" @click="submit">
            {{ store.isAdmin ? t("transfer.directSubmit") : t("transfer.applySubmit") }}
          </el-button>
          <span class="muted">{{ store.isAdmin ? t("transfer.directNote") : t("transfer.applyNote") }}</span>
        </aside>
      </div>
    </el-drawer>

    <!-- 转厂单明细 -->
    <el-drawer :model-value="!!itemsOf" :title="t('transfer.itemsOf', { no: itemsOf?.transfer_no })" size="800px" @close="itemsOf = null">
      <el-descriptions v-if="itemsOf" :column="2" size="small" border class="mb">
        <el-descriptions-item :label="t('common.status')">
          <el-tag size="small" :type="TRANSFER_STATUS_TYPE[itemsOf.status]">{{ transferStatusLabel(itemsOf.status) }}</el-tag>
          <span class="ml">{{ t(`transfer.mode${itemsOf.mode}`) }}</span>
        </el-descriptions-item>
        <el-descriptions-item :label="t('common.qty')"><b class="num">{{ itemsOf.qty }}</b></el-descriptions-item>
        <el-descriptions-item :label="t('transfer.source')">
          {{ nameOf(itemsOf.from_factory) }} · {{ itemsOf.from_pi }} · {{ scopeLabel(itemsOf.scope) }}
          <template v-if="itemsOf.from_material"> · {{ itemsOf.from_material }}</template><template v-if="itemsOf.from_sn"> · {{ itemsOf.from_sn }}</template>
        </el-descriptions-item>
        <el-descriptions-item :label="t('transfer.target')">
          {{ nameOf(itemsOf.to_factory) }} · {{ itemsOf.to_pi }} ·
          {{ t("common.customer") }} {{ itemsOf.to_customer || t("transfer.keepShort") }} ·
          {{ t("common.material") }} {{ itemsOf.to_material || t("transfer.keepShort") }}
          <el-tag size="small" :type="itemsOf.target_source === 'MANUAL' ? 'warning' : 'success'" class="ml">{{ t(`transfer.src${itemsOf.target_source}`) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item :label="t('transfer.reason')" :span="2">{{ itemsOf.reason }}</el-descriptions-item>
        <el-descriptions-item :label="t('transfer.applied')">{{ itemsOf.applied_by }} {{ itemsOf.applied_at }}</el-descriptions-item>
        <el-descriptions-item :label="t('transfer.decided')">{{ itemsOf.decided_by }} {{ itemsOf.decided_at }}</el-descriptions-item>
        <el-descriptions-item v-if="itemsOf.decide_note" :label="t('transfer.note')" :span="2">{{ itemsOf.decide_note }}</el-descriptions-item>
      </el-descriptions>
      <el-table v-loading="itemState.loading" :data="trItems" size="small">
        <el-table-column :label="t('common.sn')" min-width="170"><template #default="{ row }"><span class="sn-mono">{{ row.sn }}</span></template></el-table-column>
        <el-table-column :label="t('common.seqText')" width="110"><template #default="{ row }"><span class="sn-mono">{{ row.seq_text }}</span></template></el-table-column>
        <el-table-column prop="seq_dec" :label="t('common.seqDec')" width="90" />
        <el-table-column :label="t('transfer.fromStatus')" width="100"><template #default="{ row }"><StatusTag :status="row.from_status" /></template></el-table-column>
        <el-table-column :label="t('transfer.nowStatus')" width="100"><template #default="{ row }"><StatusTag v-if="row.current_status" :status="row.current_status" /></template></el-table-column>
        <el-table-column :label="t('transfer.nowAt')" min-width="170">
          <template #default="{ row }">{{ nameOf(row.current_factory) }} · {{ row.current_pi }}</template>
        </el-table-column>
        <el-table-column prop="from_batch_no" :label="t('acquire.batchNo')" min-width="170" />
      </el-table>
      <el-pagination class="pager" layout="total, prev, pager, next" :total="itemState.total" :page-size="itemState.pageSize"
        :current-page="itemState.page" @current-change="loadItems" />
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "@/api";
import { useAuthStore } from "@/stores/auth";
import { usePaged } from "@/composables/usePaged";
import { useFactories } from "@/composables/useFactories";
import { TRANSFER_STATUS_TYPE, scopeLabel, transferStatusLabel } from "@/constants";
import PageHead from "@/components/PageHead.vue";
import FactorySelect from "@/components/FactorySelect.vue";
import StatusTag from "@/components/StatusTag.vue";

const STATUSES = ["", "PENDING", "APPROVED", "REJECTED", "WITHDRAWN"];

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const store = useAuthStore();
const { nameOf } = useFactories();

// ---------------------------------------------------------------- 记录
/** 总部默认停在「待处理」，其他角色默认看全部。 */
const rf = reactive({ status: store.isAdmin ? "PENDING" : "", pi: "", factory_code: "" });
const pendingCount = ref(0);
const itemsOf = ref<any>(null);
const { items: records, state: recState, load: loadRecordsPage, onSize: recSize } = usePaged<any>((q) => api.transfers({ ...rf, pi: rf.pi.trim(), ...q }));
const { items: trItems, state: itemState, load: loadItems } = usePaged<any>((q) => api.transferItems(itemsOf.value.id, q), 50);

async function loadRecords(page?: number) {
  const [, p] = await Promise.all([loadRecordsPage(page), api.transfers({ status: "PENDING", factory_code: rf.factory_code, page: 1, page_size: 1 })]);
  pendingCount.value = p.total;
}

// ---------------------------------------------------------------- 新建
const formOpen = ref(false);
const src = reactive({ factory_code: "", pi_no: "", scope: "PI", material_code: null as string | null, sn: "" });
const target = reactive({ to_factory: "", to_pi: "", to_customer: "", to_material: "", target_source: "SNAPSHOT" });
const manualMode = ref(false);
const targetQ = ref("");
const reason = ref("");
const materials = ref<string[]>([]);
const candidates = ref<any>(null);
const checking = ref(false);
const submitting = ref(false);

const srcFactory = computed(() => (store.isAdmin ? src.factory_code : store.boundFactory) || "");
const srcFactoryName = computed(() => (store.isAdmin ? nameOf(src.factory_code) : store.profile?.factory_name || store.boundFactory));
const scopeReady = computed(() =>
  src.scope === "PI" || (src.scope === "MATERIAL" && src.material_code !== null) || (src.scope === "SINGLE" && !!src.sn.trim()),
);
const sourceReady = computed(() => !!srcFactory.value && !!src.pi_no.trim() && scopeReady.value);
const targetReady = computed(() => !!target.to_factory && !!target.to_pi.trim());

type Check = { key: string; state: "ok" | "warn" | "bad"; text: string };
/** 右侧检查清单：全部不是 bad 才能提交；按钮不可用时，打 ✕ 的就是原因。 */
const checks = computed<Check[]>(() => {
  const c = candidates.value;
  const out: Check[] = [];
  if (!sourceReady.value) out.push({ key: "src", state: "bad", text: t("transfer.chkSource") });
  else if (c) {
    out.push({ key: "n", state: c.transferable > 0 ? "ok" : "bad", text: t("transfer.candidates", { ok: c.transferable, total: c.total }) });
  }
  if (!targetReady.value) out.push({ key: "tgt", state: "bad", text: t("transfer.chkTarget") });
  else if (c) {
    out.push(c.duplicates
      ? { key: "dup", state: "bad", text: t("transfer.dupWarn", { pi: target.to_pi.trim(), n: c.duplicates.count, sns: c.duplicates.samples.join("、") }) }
      : { key: "dup", state: "ok", text: t("transfer.chkNoDup") });
    if (c.target) out.push({ key: "jump", state: "warn", text: jumpText(c.target) });
  }
  out.push(reason.value.trim() ? { key: "reason", state: "ok", text: t("transfer.chkReason") } : { key: "reason", state: "bad", text: t("transfer.chkNoReason") });
  return out;
});
const canSubmit = computed(() => !checking.value && !!candidates.value && sourceReady.value && targetReady.value && checks.value.every((c) => c.state !== "bad"));

/** 转入后转入 PI 下一次生成的起点后移时的提示。 */
function jumpText(h: any): string {
  return t("transfer.jumpWarn", { pi: h.to_pi, before: h.before.start_sn, after: h.after.start_sn, unused: h.unused });
}

/** 输入停顿后再校验，避免每个按键都请求。只保留最后一次请求的结果。 */
let timer: ReturnType<typeof setTimeout> | undefined;
let seq = 0;
function scheduleCheck() {
  clearTimeout(timer);
  timer = setTimeout(runCheck, 350);
}

async function runCheck() {
  const my = ++seq;
  if (!srcFactory.value || !src.pi_no.trim()) {
    materials.value = [];
    candidates.value = null;
    return;
  }
  checking.value = true;
  try {
    const base = { factory_code: srcFactory.value, pi: src.pi_no.trim(), to_pi: target.to_pi.trim() || undefined, to_customer: target.to_customer.trim() || undefined };
    const scoped = scopeReady.value
      ? { scope: src.scope, material_code: src.scope === "MATERIAL" ? src.material_code : undefined, sn: src.scope === "SINGLE" ? src.sn.trim() : undefined }
      : { scope: "PI" };
    const r = await api.transferCandidates({ ...base, ...scoped });
    if (my !== seq) return;
    materials.value = r.materials || [];
    if (src.scope === "MATERIAL" && src.material_code !== null && !materials.value.includes(src.material_code)) src.material_code = null;
    candidates.value = scopeReady.value ? r : null;
  } catch {
    if (my === seq) candidates.value = null;
  } finally {
    if (my === seq) checking.value = false;
  }
}

watch(() => [srcFactory.value, src.pi_no, src.scope, src.material_code, src.sn, target.to_pi, target.to_customer], scheduleCheck);

function manual() {
  target.target_source = "MANUAL";
}

async function suggestTargets(q: string, cb: (rows: any[]) => void) {
  try {
    cb(await api.transferTargets(q.trim()));
  } catch {
    cb([]);
  }
}

function useTarget(row: any) {
  target.to_factory = row.factory_code || "";
  target.to_pi = row.pi;
  target.to_customer = row.customer_code || "";
  target.to_material = row.material_code || "";
  target.target_source = "SNAPSHOT";
  targetQ.value = row.pi;
}

/** prefill：从领取页「转厂」「转走此物料」带来的来源。 */
function openForm(prefill?: { factory?: string; pi?: string; material?: string }) {
  src.factory_code = store.isAdmin ? prefill?.factory || "" : "";
  src.pi_no = prefill?.pi || "";
  src.scope = prefill?.material ? "MATERIAL" : "PI";
  src.material_code = prefill?.material ?? null;
  src.sn = "";
  Object.assign(target, { to_factory: "", to_pi: "", to_customer: "", to_material: "", target_source: "SNAPSHOT" });
  manualMode.value = false;
  targetQ.value = "";
  reason.value = "";
  candidates.value = null;
  formOpen.value = true;
}

async function submit() {
  const body = {
    factory_code: srcFactory.value,
    pi_no: src.pi_no.trim(), scope: src.scope,
    material_code: src.scope === "MATERIAL" ? src.material_code : null,
    sn: src.scope === "SINGLE" ? src.sn.trim() : null,
    to_factory: target.to_factory, to_pi: target.to_pi.trim(),
    to_customer: target.to_customer.trim(), to_material: target.to_material.trim(),
    target_source: target.target_source, reason: reason.value.trim(),
  };
  await ElMessageBox.confirm(
    t(store.isAdmin ? "transfer.directConfirm" : "transfer.applyConfirm", { n: candidates.value?.transferable, pi: body.to_pi, factory: nameOf(body.to_factory) }),
    store.isAdmin ? t("transfer.directSubmit") : t("transfer.applySubmit"),
    { type: "warning" },
  );
  submitting.value = true;
  try {
    const r = store.isAdmin ? await api.directTransfer(body) : await api.applyTransfer(body);
    ElMessage.success(t("transfer.submitted", { no: r.transfer_no, n: r.qty }));
    formOpen.value = false;
    rf.status = store.isAdmin ? "" : "PENDING";
    await loadRecords(1);
  } finally {
    submitting.value = false;
  }
}

// ---------------------------------------------------------------- 处理
async function decide(tr: any, action: "approve" | "reject") {
  // 确认前取最新的转入后起点提示（此刻计算，反映转入 PI 当前状态）
  const hint = action === "approve" ? (await api.transfer(tr.id)).target_hint : null;
  const { value } = await ElMessageBox.prompt(
    t(action === "approve" ? "transfer.approveConfirm" : "transfer.rejectConfirm", { no: tr.transfer_no, n: tr.qty }) +
      (hint ? ` ${jumpText(hint)}` : ""),
    action === "approve" ? t("transfer.approve") : t("transfer.reject"),
    { inputPlaceholder: t("transfer.note"), type: action === "approve" ? "success" : "warning" },
  );
  if (action === "approve") await api.approveTransfer(tr.id, value);
  else await api.rejectTransfer(tr.id, value);
  ElMessage.success(t("common.done"));
  await loadRecords();
}

async function withdraw(tr: any) {
  await ElMessageBox.confirm(t("transfer.withdrawConfirm", { no: tr.transfer_no }), t("transfer.withdraw"), { type: "warning" });
  await api.withdrawTransfer(tr.id);
  ElMessage.success(t("common.done"));
  await loadRecords();
}

function openItems(tr: any) {
  itemsOf.value = tr;
  loadItems(1);
}

onMounted(() => {
  loadRecords(1);
  const { factory, pi, material } = route.query;
  if (store.canAct && typeof pi === "string" && pi) {
    openForm({ factory: typeof factory === "string" ? factory : undefined, pi, material: typeof material === "string" ? material : undefined });
    router.replace({ path: route.path });
  }
});
</script>

<style scoped>
.pi { width: 220px; }
.cnt { margin-left: 6px; padding: 0 6px; border-radius: 9px; font-size: 11px; background: var(--el-color-danger); color: #fff; }
.tags { display: flex; gap: 4px; flex-wrap: wrap; margin-top: 4px; }
.ml { margin-left: 6px; }
.mt { margin-top: 8px; }
.start { justify-self: start; }
.mb { margin-bottom: 14px; }
.form { display: grid; grid-template-columns: minmax(0, 1fr) 300px; gap: 24px; align-items: start; }
.steps { display: grid; gap: 22px; }
.steps section { display: grid; gap: 4px; }
h4 { margin: 0 0 8px; display: flex; align-items: center; gap: 8px; font-size: 14px; }
.n { width: 20px; height: 20px; border-radius: 50%; border: 1.5px solid var(--el-color-primary); color: var(--el-color-primary);
  display: inline-grid; place-items: center; font-size: 11px; }
.grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 0 14px; }
.chips { flex-wrap: wrap; }
.full { width: 100%; }
.picked { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-top: 10px; }
.opt { display: flex; align-items: center; gap: 10px; line-height: 1.5; }
.summary { position: sticky; top: 0; display: grid; gap: 12px; padding: 16px; border: 1px solid var(--app-line); border-radius: 10px; background: var(--app-canvas); }
.flow { display: grid; gap: 2px; padding: 10px 12px; border: 1px solid var(--app-line); border-radius: 8px; background: var(--app-surface); }
.big { font-size: 22px; font-weight: 700; }
.checks { display: grid; gap: 6px; min-height: 24px; }
.check { display: flex; gap: 8px; font-size: 12.5px; line-height: 1.5; }
.ic { flex: none; width: 14px; font-weight: 700; }
.ic.ok { color: var(--el-color-success); }
.ic.warn { color: var(--el-color-warning); }
.ic.bad { color: var(--el-color-danger); }
.by-status { display: flex; gap: 10px; flex-wrap: wrap; font-size: 12px; }
.submit { width: 100%; }
</style>
