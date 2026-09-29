<template>
  <div class="page">
    <PageHead :title="t('generate.title')" :desc="t('generate.desc')">
      <el-select v-model="billNo" filterable remote clearable :remote-method="searchBills" :loading="searching"
        :placeholder="t('generate.pickOrder')" class="picker" @change="loadContext" @focus="searchBills('')">
        <el-option v-for="o in options" :key="o.bill_no" :value="o.bill_no" :label="o.bill_no">
          <div class="opt">
            <b>{{ o.bill_no }}</b>
            <span class="muted">{{ o.pi }} · {{ o.prd_org_name }}</span>
            <el-tag size="small" :type="o.quota ? 'success' : 'info'">{{ t("orders.quota") }} {{ o.quota }}</el-tag>
          </div>
        </el-option>
      </el-select>
    </PageHead>

    <el-empty v-if="!ctx" :description="t('generate.empty')" />

    <template v-else>
      <el-alert v-for="code in ctx.issues" :key="code" type="error" show-icon :closable="false"
        :title="t(`generate.issue.${code}`, { org: ctx.bill.prd_org_name })" />

      <el-row :gutter="16">
        <el-col :lg="14" :xs="24">
          <div class="surface">
            <div class="surface__title">{{ t("generate.order") }}</div>
            <el-descriptions :column="3" size="small" border>
              <el-descriptions-item :label="t('common.billNo')">{{ ctx.bill.bill_no }}</el-descriptions-item>
              <el-descriptions-item label="PI"><b>{{ ctx.bill.pi }}</b></el-descriptions-item>
              <el-descriptions-item label="PO">{{ ctx.bill.po }}</el-descriptions-item>
              <el-descriptions-item :label="t('common.customer')">{{ ctx.bill.customer_code }}</el-descriptions-item>
              <el-descriptions-item :label="t('orders.org')" :span="2">
                {{ ctx.bill.prd_org_name }}（{{ ctx.bill.factory_code || t("orders.noFactory") }}）
              </el-descriptions-item>
            </el-descriptions>
            <div class="stats">
              <div><span class="muted">{{ t("orders.totalQty") }}</span><b class="num">{{ ctx.bill.total_qty }}</b></div>
              <div><span class="muted">{{ t("orders.generated") }}</span><b class="num">{{ ctx.generated_qty }}</b></div>
              <div><span class="muted">{{ t("generate.allocated") }}</span><b class="num">{{ ctx.allocated_qty }}</b></div>
              <div><span class="muted">{{ t("generate.pendingAlloc") }}</span><b class="num">{{ ctx.pending_alloc }}</b></div>
              <div><span class="muted">{{ t("orders.quota") }}</span><b class="num quota">{{ ctx.quota }}</b></div>
            </div>
            <el-table :data="ctx.bill.lines" size="small">
              <el-table-column prop="line_seq" label="#" width="48" />
              <el-table-column :label="t('common.material')">
                <template #default="{ row }">{{ row.material_number || t("orders.noMaterial") }}</template>
              </el-table-column>
              <el-table-column prop="material_name" :label="t('orders.materialName')" show-overflow-tooltip />
              <el-table-column :label="t('common.qty')" align="right" width="90">
                <template #default="{ row }"><span class="num">{{ Math.floor(Number(row.qty)) }}</span></template>
              </el-table-column>
            </el-table>
          </div>
        </el-col>
        <el-col :lg="10" :xs="24">
          <div class="surface">
            <div class="surface__title">{{ t("generate.rule") }}</div>
            <el-descriptions v-if="ctx.rule" :column="2" size="small" border>
              <el-descriptions-item :label="t('rules.code')">{{ ctx.rule.rule_code }} · v{{ ctx.rule.version }}</el-descriptions-item>
              <el-descriptions-item :label="t('rules.bind')">{{ t(`rules.scope${ctx.rule.bind_scope}`) }} {{ ctx.rule.bind_value }}</el-descriptions-item>
              <el-descriptions-item :label="t('rules.format')">
                <span class="sn-mono">{{ ctx.rule.prefix }}<i>{{ "#".repeat(ctx.rule.seq_len) }}</i>{{ ctx.rule.suffix }}</span>
              </el-descriptions-item>
              <el-descriptions-item :label="t('rules.base')">{{ ctx.rule.base }} / {{ ctx.rule.seq_len }}</el-descriptions-item>
              <el-descriptions-item :label="t('rules.maxSeq')"><span class="num">{{ ctx.rule.max_seq.toLocaleString() }}</span></el-descriptions-item>
              <el-descriptions-item :label="t('rules.sample')"><span class="sn-mono">{{ ctx.rule.sample_sn }}</span></el-descriptions-item>
            </el-descriptions>
            <el-empty v-else :image-size="60" :description="t('generate.issue.RULE_MISSING')" />
            <div class="surface__title mt">{{ t("generate.counter") }}</div>
            <el-descriptions :column="2" size="small" border>
              <el-descriptions-item :label="t('generate.lastSeq')"><b class="num">{{ ctx.counter.last_seq_dec }}</b></el-descriptions-item>
              <el-descriptions-item v-if="ctx.next" :label="t('generate.nextStart')">
                <span class="sn-mono">{{ ctx.next.start_sn }}</span>
                <el-tooltip v-if="ctx.next.adjusted" :content="t('generate.startAdjusted', { sn: ctx.next.by_sn, start: ctx.next.start_sn })">
                  <el-tag size="small" type="warning" class="ml">{{ t("generate.adjustedTag") }}</el-tag>
                </el-tooltip>
              </el-descriptions-item>
              <el-descriptions-item :label="t('generate.piGenerated')"><span class="num">{{ ctx.counter.generated_qty }}</span></el-descriptions-item>
              <el-descriptions-item :label="t('generate.piImported')"><span class="num">{{ ctx.counter.imported_qty }}</span></el-descriptions-item>
              <el-descriptions-item :label="t('generate.startState')">
                <el-tag v-if="ctx.counter.start_locked" size="small" type="info">{{ t("generate.startLocked") }}</el-tag>
                <el-tag v-else size="small" type="success">{{ t("generate.startOpen") }}</el-tag>
              </el-descriptions-item>
            </el-descriptions>
          </div>
        </el-col>
      </el-row>

      <div class="surface">
        <div class="surface__title">{{ t("generate.piSummary", { factory: ctx.bill.factory_code || ctx.bill.prd_org_name, pi: ctx.bill.pi }) }}</div>
        <el-table :data="ctx.pi_summary" size="small">
          <el-table-column prop="bill_no" :label="t('common.billNo')">
            <template #default="{ row }">
              <b v-if="row.bill_no === ctx.bill.bill_no">{{ row.bill_no }}</b><span v-else>{{ row.bill_no }}</span>
              <el-tag v-if="!row.in_snapshot" size="small" type="info" class="ml">{{ t("generate.leftSnapshot") }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('orders.totalQty')" align="right"><template #default="{ row }"><span class="num">{{ row.total_qty }}</span></template></el-table-column>
          <el-table-column :label="t('orders.generated')" align="right"><template #default="{ row }"><span class="num">{{ row.generated_qty }}</span></template></el-table-column>
          <el-table-column :label="t('generate.allocated')" align="right"><template #default="{ row }"><span class="num">{{ row.allocated_qty }}</span></template></el-table-column>
          <el-table-column :label="t('orders.quota')" align="right"><template #default="{ row }"><span class="num">{{ row.quota }}</span></template></el-table-column>
        </el-table>
      </div>

      <div class="surface">
        <el-steps :active="step" finish-status="success" simple class="steps">
          <el-step :title="t('generate.step1')" />
          <el-step :title="t('generate.step2')" />
        </el-steps>

        <el-row :gutter="24">
          <!-- 第一步：按额度准备号（预演 → 生成） -->
          <el-col :lg="12" :xs="24">
            <h4>{{ t("generate.step1") }}</h4>
            <el-form label-position="top" class="field-stack">
              <el-form-item :label="t('generate.limit')">
                <el-input-number v-model="limit" :min="1" :step="1000" controls-position="right" />
                <div class="muted">{{ t("generate.limitHint") }}</div>
              </el-form-item>
              <el-form-item :label="t('generate.qty')">
                <el-input-number v-model="qty" :min="1" :max="Math.max(1, Math.min(ctx.quota, limit))" controls-position="right" />
                <div class="muted">{{ t("generate.qtyHint", { quota: ctx.quota, limit }) }}</div>
              </el-form-item>
              <el-form-item v-if="!ctx.counter.start_locked" :label="t('generate.start')">
                <el-input v-model="startText" clearable :placeholder="t('generate.startPlaceholder')" />
                <div class="muted">{{ t("generate.startHint") }}</div>
              </el-form-item>
            </el-form>
            <div class="actions">
              <el-button :disabled="!canGenerate" :loading="previewing" @click="doPreview">
                <el-icon><View /></el-icon><span>{{ t("generate.preview") }}</span>
              </el-button>
              <el-button type="primary" :disabled="!preview || !!runningJob" :loading="generating" @click="doGenerate">
                <el-icon><Check /></el-icon><span>{{ t("generate.generate") }}</span>
              </el-button>
            </div>
            <el-alert v-if="!preview" type="info" :closable="false" class="mt" :title="t('generate.needPreview')" />
            <template v-if="preview">
              <el-alert v-if="preview.next?.adjusted && preview.start_override == null" type="warning" :closable="false" show-icon class="mt"
                :title="t('generate.startAdjusted', { sn: preview.next.by_sn, start: preview.start_sn })" />
              <el-descriptions :column="2" size="small" border class="mt">
                <el-descriptions-item :label="t('generate.startSn')"><span class="sn-mono">{{ preview.start_sn }}</span></el-descriptions-item>
                <el-descriptions-item :label="t('generate.endSn')"><span class="sn-mono">{{ preview.end_sn }}</span></el-descriptions-item>
                <el-descriptions-item :label="t('common.qty')"><b class="num">{{ preview.qty }}</b></el-descriptions-item>
                <el-descriptions-item :label="t('generate.baseLast')"><span class="num">{{ preview.base_last_seq }}</span></el-descriptions-item>
                <el-descriptions-item :label="t('generate.seqRange')"><span class="num">{{ preview.start_seq }} ~ {{ preview.end_seq }}</span></el-descriptions-item>
                <el-descriptions-item :label="t('generate.expires')">{{ preview.expires_at }}</el-descriptions-item>
              </el-descriptions>
              <el-table :data="preview.segments" size="small" class="mt">
                <el-table-column prop="line_seq" label="#" width="48" />
                <el-table-column :label="t('common.material')"><template #default="{ row }">{{ row.material_code || t("orders.noMaterial") }}</template></el-table-column>
                <el-table-column :label="t('generate.segment')" min-width="220">
                  <template #default="{ row }"><span class="sn-mono">{{ row.start_sn }} ~ {{ row.end_sn }}</span></template>
                </el-table-column>
                <el-table-column :label="t('common.qty')" align="right" width="80"><template #default="{ row }"><span class="num">{{ row.qty }}</span></template></el-table-column>
              </el-table>
            </template>
            <div v-if="runningJob" class="mt">
              <div class="muted">{{ t("generate.running", { id: runningJob.id }) }}</div>
              <el-progress :percentage="pct(runningJob)" :stroke-width="14" striped striped-flow />
            </div>
          </el-col>

          <!-- 第二步：确认分给该生产组织 -->
          <el-col :lg="12" :xs="24">
            <h4>{{ t("generate.step2") }}</h4>
            <el-form label-position="top" class="field-stack">
              <el-form-item :label="t('generate.allocQty')">
                <el-input-number v-model="allocQty" :min="1" :max="Math.max(1, Math.min(ctx.pending_alloc, limit))" controls-position="right" />
                <div class="muted">{{ t("generate.allocHint", { pending: ctx.pending_alloc }) }}</div>
              </el-form-item>
              <el-form-item :label="t('generate.allocFactory')">
                <el-input :model-value="ctx.bill.factory_code ? `${ctx.factory_name}（${ctx.bill.factory_code}）` : ''" disabled />
                <div class="muted">{{ t("generate.allocFactoryHint") }}</div>
              </el-form-item>
            </el-form>
            <el-button type="primary" :disabled="!ctx.pending_alloc" :loading="allocating" @click="doAllocate">
              <el-icon><Promotion /></el-icon><span>{{ t("generate.allocate") }}</span>
            </el-button>
            <el-alert type="info" :closable="false" class="mt" :title="t('generate.allocNote')" />
          </el-col>
        </el-row>
      </div>

      <div class="surface">
        <el-tabs>
          <el-tab-pane :label="t('generate.jobs')">
            <el-table :data="ctx.jobs" size="small" :empty-text="t('common.empty')">
              <el-table-column prop="id" label="#" width="60" />
              <el-table-column :label="t('generate.segment')" min-width="220">
                <template #default="{ row }"><span class="sn-mono">{{ row.start_sn }} ~ {{ row.end_sn }}</span></template>
              </el-table-column>
              <el-table-column :label="t('common.qty')" align="right" width="80"><template #default="{ row }"><span class="num">{{ row.qty }}</span></template></el-table-column>
              <el-table-column :label="t('generate.progress')" width="200">
                <template #default="{ row }">
                  <el-progress :percentage="pct(row)" :status="row.status === 'SUCCESS' ? 'success' : row.status === 'FAILED' ? 'exception' : undefined" />
                </template>
              </el-table-column>
              <el-table-column prop="error_msg" :label="t('generate.error')" show-overflow-tooltip />
              <el-table-column prop="created_by" :label="t('common.operator')" width="90" />
              <el-table-column prop="created_at" :label="t('common.time')" width="160" />
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="t('generate.allocations')">
            <el-table :data="allocations" size="small" :empty-text="t('common.empty')">
              <el-table-column prop="factory_code" :label="t('common.factory')" width="110" />
              <el-table-column :label="t('generate.segment')" min-width="220">
                <template #default="{ row }"><span class="sn-mono">{{ row.start_sn }} ~ {{ row.end_sn }}</span></template>
              </el-table-column>
              <el-table-column :label="t('common.qty')" align="right" width="80"><template #default="{ row }"><span class="num">{{ row.qty }}</span></template></el-table-column>
              <el-table-column prop="created_by" :label="t('common.operator')" width="90" />
              <el-table-column prop="created_at" :label="t('common.time')" width="160" />
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "@/api";
import { ApiError } from "@/api/http";
import { DEFAULT_PAGE_LIMIT } from "@/constants";
import { suggestQty } from "@/snList.js";
import type { GenJob } from "@/types/api";
import PageHead from "@/components/PageHead.vue";

const { t } = useI18n();
const route = useRoute();
const billNo = ref<string>((route.query.bill_no as string) || "");
const options = ref<any[]>([]);
const searching = ref(false);
const ctx = ref<any>(null);
const allocations = ref<any[]>([]);
const limit = ref(DEFAULT_PAGE_LIMIT);
const qty = ref(1);
const startText = ref("");
const preview = ref<any>(null);
const previewing = ref(false);
const generating = ref(false);
const runningJob = ref<GenJob | null>(null);
const lastGenerated = ref(0);
const allocQty = ref(1);
const allocating = ref(false);
let timer: number | undefined;

// 0 = 待生成；1 = 已生成待分配；2 = 本订单额度已全部生成并分配
const step = computed(() => {
  if (!ctx.value) return 0;
  if (ctx.value.pending_alloc > 0) return 1;
  return ctx.value.quota === 0 && ctx.value.allocated_qty > 0 ? 2 : 0;
});
const canGenerate = computed(() => !!ctx.value && !ctx.value.issues.length && !runningJob.value);
const startSeq = computed(() => {
  const s = startText.value.trim();
  return s ? Number(s) : null;
});

const pct = (j: GenJob) => (j.qty ? Math.min(100, Math.round((j.done_qty * 100) / j.qty)) : 0);

async function searchBills(q: string) {
  searching.value = true;
  try {
    options.value = (await api.orders({ q, page_size: 30 })).items;
  } finally {
    searching.value = false;
  }
}

async function loadContext() {
  preview.value = null;
  if (!billNo.value) {
    ctx.value = null;
    return;
  }
  ctx.value = await api.genContext(billNo.value);
  allocations.value = await api.allocations(billNo.value);
  qty.value = Math.max(1, suggestQty(ctx.value.quota, limit.value));
  const pending = ctx.value.pending_alloc;
  allocQty.value = Math.max(1, Math.min(lastGenerated.value || pending, pending, limit.value));
  if (ctx.value.running_job && !runningJob.value) poll(ctx.value.running_job);
}

// 预演条件改变 → 预演失效，必须重新预演
watch([qty, startText, billNo], () => {
  preview.value = null;
});
watch(limit, (l) => {
  if (ctx.value) qty.value = Math.max(1, Math.min(qty.value, suggestQty(ctx.value.quota, l) || 1));
});

async function doPreview() {
  if (startSeq.value !== null && (!Number.isInteger(startSeq.value) || startSeq.value < 1)) {
    ElMessage.error(t("generate.startInvalid"));
    return;
  }
  previewing.value = true;
  try {
    preview.value = await api.genPreview({ bill_no: billNo.value, qty: qty.value, start_seq: startSeq.value });
  } finally {
    previewing.value = false;
  }
}

async function doGenerate() {
  if (!preview.value) return;
  await ElMessageBox.confirm(
    t("generate.confirm", { qty: preview.value.qty, start: preview.value.start_sn, end: preview.value.end_sn }),
    t("generate.generate"),
    { type: "warning" },
  );
  generating.value = true;
  const p = preview.value;
  try {
    const job: GenJob = await api.generate({ preview_token: p.token, bill_no: p.bill_no, qty: p.qty, start_seq: p.start_override });
    lastGenerated.value = p.qty;
    preview.value = null;
    if (job.status === "RUNNING") {
      poll(job);
    } else {
      ElMessage.success(t("generate.done", { qty: job.qty }));
      await loadContext();
    }
  } catch (e) {
    // 预演失效（最大号已变 / 条件变化）：清掉旧预演，提示重新预演
    if (e instanceof ApiError && e.code?.startsWith("GEN_PREVIEW")) preview.value = null;
    await loadContext().catch(() => undefined);
  } finally {
    generating.value = false;
  }
}

function poll(job: GenJob) {
  runningJob.value = job;
  window.clearInterval(timer);
  timer = window.setInterval(async () => {
    const j: GenJob = await api.genJob(job.id);
    runningJob.value = j;
    if (j.status !== "RUNNING") {
      window.clearInterval(timer);
      runningJob.value = null;
      if (j.status === "SUCCESS") ElMessage.success(t("generate.done", { qty: j.qty }));
      else ElMessage.error({ message: t("generate.failed", { msg: j.error_msg || "" }), duration: 8000, showClose: true });
      await loadContext();
    }
  }, 1000);
}

async function doAllocate() {
  await ElMessageBox.confirm(
    t("generate.allocConfirm", { qty: allocQty.value, factory: ctx.value.factory_name || ctx.value.bill.factory_code }),
    t("generate.allocate"),
    { type: "warning" },
  );
  allocating.value = true;
  try {
    const r = await api.allocate({ bill_no: billNo.value, qty: allocQty.value, factory_code: ctx.value.bill.factory_code });
    ElMessage.success(t("generate.allocDone", { qty: r.qty, start: r.start_sn, end: r.end_sn }));
    lastGenerated.value = 0;
    await loadContext();
  } finally {
    allocating.value = false;
  }
}

onMounted(() => {
  if (billNo.value) loadContext();
});
onUnmounted(() => window.clearInterval(timer));
</script>

<style scoped>
.picker { width: 360px; }
.opt { display: flex; gap: 10px; align-items: center; justify-content: space-between; }
.stats { display: grid; grid-template-columns: repeat(5, 1fr); gap: 8px; margin: 12px 0; }
.stats > div { background: var(--el-color-primary-light-9); border-radius: 8px; padding: 8px 10px; display: flex; flex-direction: column; gap: 2px; }
.stats b { font-size: 18px; }
.stats .quota { color: var(--el-color-success); }
.steps { margin-bottom: 16px; }
h4 { margin: 4px 0 12px; }
.actions { display: flex; gap: 8px; }
.mt { margin-top: 12px; }
.ml { margin-left: 6px; }
.el-col { margin-bottom: 16px; }
i { font-style: normal; color: var(--app-muted); }
</style>
