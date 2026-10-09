<template>
  <div class="page">
    <PageHead :title="t('generate.title')" :desc="t('generate.desc')">
      <span class="muted snap">
        {{ t("generate.snapshotAt", { time: syncedAt || "—" }) }}
        <router-link to="/orders">{{ t("generate.toOrders") }}</router-link>
      </span>
    </PageHead>

    <div class="surface">
      <div class="toolbar">
        <el-radio-group v-model="view">
          <el-radio-button v-for="v in VIEWS" :key="v" :value="v">
            {{ t(`generate.view${v}`) }}<span v-if="v !== 'ALL'" class="cnt num">{{ rowsOf(v).length }}</span>
          </el-radio-button>
        </el-radio-group>
        <el-input v-model="kw" class="kw" :placeholder="t('generate.search')" clearable prefix-icon="Search" @keyup.enter="reload" @clear="reload" />
        <span class="muted summary">{{ t("generate.summary", { q: totals.quota, p: totals.pending }) }}</span>
      </div>

      <el-table v-loading="loading" :data="shown" size="small" row-key="pi" class="pis" :empty-text="t(`generate.empty${view}`)" @row-click="(row: any) => openDetail(row.pi)">
        <el-table-column label="PI" min-width="170">
          <template #default="{ row }">
            <b>{{ row.pi }}</b>
            <el-tag v-if="row.unmapped" size="small" type="danger" class="ml">{{ t("generate.unmappedTag") }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="customer_number" :label="t('common.customer')" min-width="100" />
        <el-table-column :label="t('generate.bills')" align="right" width="70">
          <template #default="{ row }"><span class="num">{{ row.bills }}</span></template>
        </el-table-column>
        <el-table-column :label="t('orders.org')" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.orgs.map((o: any) => o.name).join("、") }}</template>
        </el-table-column>
        <el-table-column :label="t('generate.progress')" min-width="150">
          <template #default="{ row }"><ProgressBar :row="row" /></template>
        </el-table-column>
        <el-table-column :label="t('orders.quota')" align="right" width="90">
          <template #default="{ row }"><span class="num" :class="{ zero: !row.quota }">{{ row.quota }}</span></template>
        </el-table-column>
        <el-table-column :label="t('generate.pendingAlloc')" align="right" width="90">
          <template #default="{ row }"><span class="num" :class="{ zero: !row.pending_alloc }">{{ row.pending_alloc }}</span></template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="150" align="right">
          <template #default="{ row }">
            <el-button v-if="row.pending_alloc" size="small" type="success" :loading="busy === `alloc:${row.pi}`" :disabled="!!busy && busy !== `alloc:${row.pi}`"
              @click.stop="allocatePi(row.pi, row.pending_alloc)">{{ t("generate.allocN", { n: row.pending_alloc }) }}</el-button>
            <el-button v-else-if="row.quota && canQuickGen(row)" size="small" type="primary" :loading="busy === `gen:${row.pi}`" :disabled="!!busy && busy !== `gen:${row.pi}`"
              @click.stop="quickGenerate(row)">{{ t("generate.genN", { n: defaultQty(row.quota) }) }}</el-button>
            <el-button v-else-if="row.quota" size="small" @click.stop="openDetail(row.pi)">{{ t("generate.genFirst") }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <p v-if="pis.length >= LIST_LIMIT" class="muted">{{ t("generate.more", { n: LIST_LIMIT }) }}</p>
    </div>

    <el-drawer :model-value="!!detailPi" size="780px" @close="closeDetail">
      <template #header>
        <div v-if="ctx" class="dh">
          <b>PI {{ ctx.pi_no }}</b>
          <el-tag effect="plain" size="small">{{ ctx.customer_code }} · {{ t("generate.billsN", { n: ctx.bills.length }) }}</el-tag>
        </div>
      </template>
      <div v-if="ctx" v-loading="ctxLoading" class="drawer">
        <el-alert v-for="msg in issueTexts" :key="msg" type="error" show-icon :closable="false" :title="msg" />

        <!-- PI 流水与规则：按 PI 记录，各订单共用 -->
        <dl class="kv">
          <dt>{{ t("generate.rule") }}</dt>
          <dd>
            <template v-if="ctx.rule">
              {{ ctx.rule.rule_code }} · {{ ctx.rule.rule_name }} · v{{ ctx.rule.version }}
              <el-tag size="small" :type="ctx.rule.source === 'CHOSEN' ? 'warning' : 'success'" class="ml">{{ t(`generate.ruleSrc${ctx.rule.source}`) }}</el-tag>
            </template>
            <template v-else-if="ctx.rule_options.length">
              <el-select v-model="ruleId" filterable size="small" :placeholder="t('generate.rulePick')" class="rule-picker">
                <el-option v-for="o in ctx.rule_options" :key="o.rule_id" :value="o.rule_id" :label="ruleLabel(o)">
                  <div class="opt"><span><b>{{ o.rule_code }}</b> · {{ o.rule_name }}</span><span class="muted sn-mono">{{ o.sample_sn }}</span></div>
                </el-option>
              </el-select>
              <div class="muted hint">{{ t("generate.rulePickHint") }}</div>
            </template>
            <span v-else class="muted">—</span>
          </dd>
          <dt>{{ t("generate.nextStart") }}</dt>
          <dd>
            <span class="sn-mono">{{ nextSn || "—" }}</span>
            <el-tooltip v-if="ctx.next?.adjusted" :content="t('generate.startAdjusted', { sn: ctx.next.by_sn, start: ctx.next.start_sn })">
              <el-tag size="small" type="warning" class="ml">{{ t("generate.adjustedTag") }}</el-tag>
            </el-tooltip>
            <span class="muted ml">
              {{ t("generate.lastSeq") }} <span class="num">{{ ctx.counter.last_seq_dec }}</span> ·
              {{ ctx.counter.start_locked ? t("generate.startLocked") : t("generate.startOpen") }}
            </span>
          </dd>
        </dl>

        <div class="totals">
          <ProgressBar :row="ctx" big />
          <div class="legend num">
            <span class="a">{{ t("generate.allocated") }} {{ ctx.allocated_qty }}</span>
            <span class="p">{{ t("generate.pendingAlloc") }} {{ ctx.pending_alloc }}</span>
            <span>{{ t("orders.quota") }} {{ ctx.quota }}</span>
            <span class="sp" />
            <span>{{ t("generate.total", { n: ctx.total_qty }) }}</span>
          </div>
        </div>

        <!-- 分配：整张 PI，各订单分到各自的生产组织 -->
        <div v-if="ctx.pending_alloc" class="card on">
          <h4><el-tag size="small" type="warning">{{ t("generate.current") }}</el-tag>{{ t("generate.allocTitle", { n: ctx.pending_alloc }) }}</h4>
          <div class="result num">
            <div v-for="g in allocGroups" :key="g.factory">
              {{ g.name }} <b>{{ g.qty }}</b>
              <span class="muted"> · {{ g.bills.map((b: any) => `${b.bill_no} ${b.pending_alloc}`).join(" · ") }}</span>
            </div>
          </div>
          <div class="row">
            <el-button type="success" :loading="busy === `alloc:${ctx.pi_no}`" :disabled="!!busy || !!running" @click="allocatePi(ctx.pi_no, ctx.pending_alloc)">
              {{ t("generate.allocConfirmBtn", { n: ctx.pending_alloc }) }}
            </el-button>
            <span class="muted small">{{ t("generate.allocNote") }}</span>
          </div>
        </div>

        <!-- 生成：数量按单据号顺序占用各订单额度，输入停顿后自动预演 -->
        <div v-if="ctx.quota || running" class="card" :class="{ on: !ctx.pending_alloc }">
          <h4>
            <el-tag size="small" :type="ctx.pending_alloc ? 'info' : 'warning'">{{ ctx.pending_alloc ? t("generate.also") : t("generate.current") }}</el-tag>
            {{ t("generate.genTitle", { n: ctx.quota }) }}
          </h4>
          <template v-if="!running">
            <div class="row">
              <span class="muted small">{{ t("generate.qty") }}</span>
              <el-input-number v-model="qty" :min="1" :max="Math.max(1, ctx.quota)" :step="1000" controls-position="right" />
              <template v-if="!ctx.counter.start_locked">
                <el-link v-if="!startOpen" type="primary" :underline="false" @click="startOpen = true">{{ t("generate.setStart") }}</el-link>
                <el-input v-else v-model="startText" clearable class="start" :placeholder="t('generate.startPlaceholder')" />
              </template>
            </div>
            <div v-if="startOpen && !ctx.counter.start_locked" class="muted small">{{ t("generate.startHint") }}</div>
            <div v-if="previewing" class="result muted">{{ t("generate.previewing") }}</div>
            <div v-else-if="previewError" class="result err">{{ previewError }}</div>
            <div v-else-if="preview" class="result">
              <el-alert v-if="preview.next?.adjusted && preview.start_override == null" type="warning" :closable="false" show-icon
                :title="t('generate.startAdjusted', { sn: preview.next.by_sn, start: preview.start_sn })" />
              <span class="sn-mono">{{ preview.start_sn }} ~ {{ preview.end_sn }}</span>
              <span class="muted num">
                {{ t("generate.byBill") }}{{ preview.items.map((it: any) => `${it.bill_no} ${it.qty}`).join(" · ") }}
              </span>
            </div>
            <div class="row">
              <el-button type="primary" :disabled="!preview || !!busy" :loading="busy === `gen:${ctx.pi_no}`" @click="runGenerate(preview)">
                {{ t("generate.genN", { n: preview?.qty ?? qty }) }}
              </el-button>
            </div>
          </template>
          <div v-else>
            <div class="muted small">{{ t("generate.runningPi", { n: running.bills, qty: running.qty }) }}</div>
            <el-progress :percentage="running.pct" :stroke-width="14" striped striped-flow />
          </div>
        </div>

        <el-table :data="ctx.bills" size="small" row-key="bill_no" class="bills">
          <el-table-column type="expand">
            <template #default="{ row }">
              <el-table v-if="row.lines.length" :data="row.lines" size="small" class="lines">
                <el-table-column prop="line_seq" label="#" width="48" />
                <el-table-column :label="t('common.material')"><template #default="{ row: l }">{{ l.material_number || t("orders.noMaterial") }}</template></el-table-column>
                <el-table-column prop="material_name" :label="t('orders.materialName')" show-overflow-tooltip />
                <el-table-column :label="t('common.qty')" align="right" width="90"><template #default="{ row: l }"><span class="num">{{ Math.floor(Number(l.qty)) }}</span></template></el-table-column>
              </el-table>
              <div v-else class="muted lines">{{ t("generate.leftSnapshot") }}</div>
            </template>
          </el-table-column>
          <el-table-column :label="t('common.billNo')" min-width="140">
            <template #default="{ row }">
              <b>{{ row.bill_no }}</b>
              <el-tag v-if="!row.in_snapshot" size="small" type="info" class="ml">{{ t("generate.leftSnapshot") }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('orders.org')" min-width="110">
            <template #default="{ row }">
              {{ row.factory_name || row.prd_org_name }}
              <el-tag v-if="!row.factory_code" size="small" type="danger" class="ml">{{ t("generate.unmappedTag") }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('orders.totalQty')" align="right" width="70"><template #default="{ row }"><span class="num">{{ row.total_qty }}</span></template></el-table-column>
          <el-table-column :label="t('generate.allocated')" align="right" width="70"><template #default="{ row }"><span class="num">{{ row.allocated_qty }}</span></template></el-table-column>
          <el-table-column :label="t('generate.pendingAlloc')" align="right" width="70"><template #default="{ row }"><span class="num">{{ row.pending_alloc }}</span></template></el-table-column>
          <el-table-column :label="t('orders.quota')" align="right" width="70"><template #default="{ row }"><span class="num">{{ row.quota }}</span></template></el-table-column>
          <el-table-column width="96" align="right">
            <template #default="{ row }">
              <el-button v-if="row.pending_alloc && allocGroups.reduce((n, g) => n + g.bills.length, 0) > 1" link type="primary" :disabled="!!busy || !!running" @click="allocateBill(row)">{{ t("generate.allocOne") }}</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-collapse>
          <el-collapse-item :title="t('generate.history', { j: ctx.jobs.length, a: ctx.allocations.length })" name="h">
            <el-tabs>
              <el-tab-pane :label="t('generate.jobs')">
                <el-table :data="ctx.jobs" size="small" :empty-text="t('common.empty')">
                  <el-table-column prop="bill_no" :label="t('common.billNo')" min-width="120" />
                  <el-table-column :label="t('generate.segment')" min-width="200">
                    <template #default="{ row }"><span class="sn-mono">{{ row.start_sn }} ~ {{ row.end_sn }}</span></template>
                  </el-table-column>
                  <el-table-column :label="t('common.qty')" align="right" width="70"><template #default="{ row }"><span class="num">{{ row.qty }}</span></template></el-table-column>
                  <el-table-column :label="t('common.status')" width="90">
                    <template #default="{ row }">
                      <el-tooltip v-if="row.error_msg" :content="row.error_msg"><el-tag size="small" type="danger">{{ t("generate.jobFAILED") }}</el-tag></el-tooltip>
                      <el-tag v-else size="small" :type="row.status === 'SUCCESS' ? 'success' : 'info'">{{ t(`generate.job${row.status}`) }}</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column prop="created_by" :label="t('common.operator')" width="80" />
                  <el-table-column prop="created_at" :label="t('common.time')" width="150" />
                </el-table>
              </el-tab-pane>
              <el-tab-pane :label="t('generate.allocations')">
                <el-table :data="ctx.allocations" size="small" :empty-text="t('common.empty')">
                  <el-table-column prop="bill_no" :label="t('common.billNo')" min-width="120" />
                  <el-table-column :label="t('common.factory')" width="100"><template #default="{ row }">{{ nameOf(row.factory_code) }}</template></el-table-column>
                  <el-table-column :label="t('generate.segment')" min-width="200">
                    <template #default="{ row }"><span class="sn-mono">{{ row.start_sn }} ~ {{ row.end_sn }}</span></template>
                  </el-table-column>
                  <el-table-column :label="t('common.qty')" align="right" width="70"><template #default="{ row }"><span class="num">{{ row.qty }}</span></template></el-table-column>
                  <el-table-column prop="created_by" :label="t('common.operator')" width="80" />
                  <el-table-column prop="created_at" :label="t('common.time')" width="150" />
                </el-table>
              </el-tab-pane>
            </el-tabs>
          </el-collapse-item>
        </el-collapse>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, onMounted, onUnmounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "@/api";
import { ApiError } from "@/api/http";
import { DEFAULT_PAGE_LIMIT } from "@/constants";
import { suggestQty } from "@/snList.js";
import { useFactories } from "@/composables/useFactories";
import type { GenJob } from "@/types/api";
import PageHead from "@/components/PageHead.vue";

type View = "TODO" | "TO_GEN" | "TO_ALLOC" | "ALL";
const VIEWS: View[] = ["TODO", "TO_GEN", "TO_ALLOC", "ALL"];
const LIST_LIMIT = 500;

/** 进度条：已分配 / 已生成待分配 / 未生成，占订单总数（已生成超过总数时以已生成为准）。 */
const ProgressBar = defineComponent({
  props: { row: { type: Object, required: true }, big: Boolean },
  setup(props) {
    return () => {
      const r: any = props.row;
      const whole = Math.max(r.total_qty, r.generated_qty, 1);
      const w = (n: number) => `${Math.min(100, (n * 100) / whole)}%`;
      return h("div", { class: ["bar", { big: props.big }] }, [
        h("i", { class: "a", style: { width: w(r.allocated_qty) } }),
        h("i", { class: "p", style: { width: w(r.pending_alloc) } }),
      ]);
    };
  },
});

const { t } = useI18n();
const route = useRoute();
const { nameOf } = useFactories();
const view = ref<View>("TODO");
const kw = ref("");
const pis = ref<any[]>([]);
const allPis = ref<any[]>([]);
const loading = ref(false);
const syncedAt = ref<string | null>(null);
const busy = ref("");

const detailPi = ref("");
const ctx = ref<any>(null);
const ctxLoading = ref(false);
const ruleId = ref<number | null>(null);
const qty = ref(1);
const startOpen = ref(false);
const startText = ref("");
const preview = ref<any>(null);
const previewing = ref(false);
const previewError = ref("");
/** 整张 PI 正在生成（含后台任务）：bills 张订单共 qty 枚。 */
const running = ref<{ pi: string; bills: number; qty: number; pct: number } | null>(null);
let previewTimer: number | undefined;
let previewSeq = 0;
let alive = true;

const defaultQty = (quota: number) => suggestQty(quota, DEFAULT_PAGE_LIMIT);
// 已生成过的 PI 规则与起始号都已确定，行上可直接生成；首次生成要在抽屉里选规则 / 指定起始号
const canQuickGen = (row: any) => row.start_locked && !row.unmapped;
const totals = computed(() => pis.value.reduce((s, r) => ({ quota: s.quota + r.quota, pending: s.pending + r.pending_alloc }), { quota: 0, pending: 0 }));
const shown = computed(() => rowsOf(view.value));
const ruleLabel = (o: any) => `${o.rule_code} · ${o.rule_name}（${t(`rules.scope${o.bind_scope}`)}${o.bind_value ? " " + o.bind_value : ""}）`;
const nextSn = computed(() => ctx.value?.next?.start_sn ?? ctx.value?.rule_options.find((o: any) => o.rule_id === ruleId.value)?.sample_sn ?? null);

function rowsOf(v: View) {
  if (v === "ALL") return allPis.value;
  if (v === "TO_GEN") return pis.value.filter((r) => r.quota > 0);
  if (v === "TO_ALLOC") return pis.value.filter((r) => r.pending_alloc > 0);
  return pis.value;
}

const issueTexts = computed(() => {
  const c = ctx.value;
  if (!c) return [];
  return c.issues
    .filter((code: string) => code !== "GEN_QUOTA_EMPTY")
    .map((code: string) => {
      if (code !== "FACTORY_UNMAPPED") return t(`generate.issue.${code}`);
      const orgs = [...new Set(c.bills.filter((b: any) => !b.factory_code && b.quota).map((b: any) => b.prd_org_name))];
      return t("generate.issue.FACTORY_UNMAPPED", { org: orgs.join("、") });
    });
});

/** 待分配按生产组织汇总，用于分配前核对。 */
const allocGroups = computed(() => {
  type Group = { factory: string; name: string; qty: number; bills: any[] };
  const groups = new Map<string, Group>();
  for (const b of (ctx.value?.bills ?? []) as any[]) {
    if (!b.pending_alloc) continue;
    const g: Group = groups.get(b.factory_code) ?? { factory: b.factory_code, name: b.factory_name || b.prd_org_name || b.factory_code, qty: 0, bills: [] };
    g.qty += b.pending_alloc;
    g.bills.push(b);
    groups.set(b.factory_code, g);
  }
  return [...groups.values()];
});

async function reload() {
  loading.value = true;
  try {
    const q = kw.value.trim();
    const [p, a] = await Promise.all([
      api.orderPis({ q, pending: 1, limit: LIST_LIMIT }),
      view.value === "ALL" ? api.orderPis({ q, limit: LIST_LIMIT }) : Promise.resolve(allPis.value),
    ]);
    pis.value = p;
    allPis.value = a;
  } finally {
    loading.value = false;
  }
}
watch(view, (v) => {
  if (v === "ALL") reload();
});

async function openDetail(pi: string) {
  detailPi.value = pi;
  await loadContext({ pi });
}

function closeDetail() {
  if (running.value) return;
  detailPi.value = "";
  ctx.value = null;
}

async function loadContext(params: { pi?: string; bill_no?: string }) {
  ctxLoading.value = true;
  try {
    const c = await api.genPiContext(params);
    const changedPi = ctx.value?.pi_no !== c.pi_no;
    ctx.value = c;
    detailPi.value = c.pi_no;
    if (!c.rule_options.some((o: any) => o.rule_id === ruleId.value)) {
      ruleId.value = c.rule_options.find((o: any) => o.bind_scope === "GENERAL")?.rule_id ?? null;
    }
    if (changedPi) {
      startOpen.value = false;
      startText.value = "";
    }
    qty.value = Math.max(1, defaultQty(c.quota));
    schedulePreview();
    // 打开时这张 PI 正在后台生成（如刷新过页面）：接着显示进度
    const busyJobs = c.jobs.filter((j: GenJob) => j.status === "RUNNING");
    if (busyJobs.length && !running.value) settle(c.pi_no, busyJobs);
  } finally {
    ctxLoading.value = false;
  }
}

const startSeq = () => {
  const s = startText.value.trim();
  return s ? Number(s) : null;
};

// 数量、起始号、所选规则停顿后自动预演；预演结果过时就丢弃
function schedulePreview() {
  window.clearTimeout(previewTimer);
  previewSeq++;
  previewing.value = false;
  preview.value = null;
  previewError.value = "";
  const c = ctx.value;
  if (!c || !c.quota || running.value || c.issues.includes("RULE_MISSING")) return;
  if (!c.rule && ruleId.value == null) {
    previewError.value = t("generate.rulePickRequired");
    return;
  }
  const s = startSeq();
  if (s !== null && (!Number.isInteger(s) || s < 1)) {
    previewError.value = t("generate.startInvalid");
    return;
  }
  previewTimer = window.setTimeout(doPreview, 400);
}
watch([qty, startText, ruleId], schedulePreview);

async function doPreview() {
  const c = ctx.value;
  const seq = ++previewSeq;
  previewing.value = true;
  try {
    const p = await api.genPiPreview({ pi_no: c.pi_no, qty: qty.value, start_seq: startSeq(), rule_id: c.rule ? null : ruleId.value }, true);
    if (seq === previewSeq) preview.value = p;
  } catch (e: any) {
    if (seq === previewSeq) previewError.value = e.message;
  } finally {
    if (seq === previewSeq) previewing.value = false;
  }
}

/** 行上直接生成：已生成过的 PI 规则已定，预演后确认即可；预演失败（如需选规则）转到抽屉。 */
async function quickGenerate(row: any) {
  busy.value = `gen:${row.pi}`;
  let p: any;
  try {
    p = await api.genPiPreview({ pi_no: row.pi, qty: defaultQty(row.quota) }, true);
  } catch (e: any) {
    busy.value = "";
    if (e instanceof ApiError && e.code === "RULE_CHOICE_REQUIRED") return openDetail(row.pi);
    ElMessage.error(e.message);
    return;
  }
  busy.value = "";
  await runGenerate(p);
}

/** 凭按 PI 预演的各订单令牌一次生成：后端在一个事务里依次生成各订单，任何一张失败整张 PI 回滚。 */
async function runGenerate(p: any) {
  await ElMessageBox.confirm(
    t("generate.confirm", { qty: p.qty, start: p.start_sn, end: p.end_sn, n: p.items.length }),
    t("generate.generate"),
    { type: "warning" },
  );
  busy.value = `gen:${p.pi_no}`;
  try {
    const items = p.items.map((it: any) => ({ preview_token: it.token, bill_no: it.bill_no, qty: it.qty, start_seq: it.start_override }));
    const r = await api.generatePi({ pi_no: p.pi_no, items });
    await settle(p.pi_no, r.jobs);
  } catch {
    // 错误已由请求层提示；预演可能已失效，刷新后重新预演
    await refresh(p.pi_no).catch(() => undefined);
  } finally {
    busy.value = "";
  }
}

/** 等整张 PI 的任务结束（大批量在后台执行，轮询进度），再提示结果并刷新。 */
async function settle(pi: string, jobs: GenJob[]) {
  const qty = jobs.reduce((s, j) => s + j.qty, 0);
  let js = jobs;
  running.value = { pi, bills: js.length, qty, pct: 0 };
  try {
    while (alive && js.some((j) => j.status === "RUNNING")) {
      await new Promise((r) => window.setTimeout(r, 1000));
      js = await Promise.all(js.map((j) => api.genJob(j.id)));
      if (running.value) running.value.pct = qty ? Math.min(100, Math.round((js.reduce((s, j) => s + j.done_qty, 0) * 100) / qty)) : 0;
    }
  } finally {
    running.value = null;
  }
  const failed = js.find((j) => j.status === "FAILED");
  if (failed) ElMessage.error({ message: t("generate.failed", { msg: failed.error_msg || "" }), duration: 8000, showClose: true });
  else if (js.length) ElMessage.success(t("generate.done", { qty }));
  await refresh(pi);
}

async function refresh(pi: string) {
  await Promise.all([reload(), detailPi.value === pi ? loadContext({ pi }) : Promise.resolve()]);
}

async function allocatePi(pi: string, n: number) {
  await ElMessageBox.confirm(t("generate.allocConfirm", { qty: n, pi }), t("generate.allocate"), { type: "warning" });
  busy.value = `alloc:${pi}`;
  try {
    const r = await api.allocatePi(pi);
    ElMessage.success(t("generate.allocDone", { qty: r.qty, n: r.items.length }));
    await refresh(pi);
  } finally {
    busy.value = "";
  }
}

async function allocateBill(b: any) {
  const factory = b.factory_name || b.factory_code;
  await ElMessageBox.confirm(t("generate.allocOneConfirm", { qty: b.pending_alloc, bill: b.bill_no, factory }), t("generate.allocOne"), { type: "warning" });
  busy.value = `alloc:${b.bill_no}`;
  try {
    const r = await api.allocate({ bill_no: b.bill_no, factory_code: b.factory_code });
    ElMessage.success(t("generate.allocOneDone", { qty: r.qty, start: r.start_sn, end: r.end_sn }));
    await refresh(ctx.value.pi_no);
  } finally {
    busy.value = "";
  }
}

onMounted(async () => {
  const pi = route.query.pi as string | undefined;
  const billNo = route.query.bill_no as string | undefined;
  await reload();
  if (pi || billNo) loadContext(pi ? { pi } : { bill_no: billNo });
  syncedAt.value = (await api.orderMeta()).synced_at;
});
onUnmounted(() => {
  alive = false;
  window.clearTimeout(previewTimer);
});
</script>

<style scoped>
.kw { width: 240px; }
.summary { margin-left: auto; }
.snap { font-size: 12px; }
.cnt { margin-left: 6px; font-size: 12px; opacity: 0.75; }
.pis :deep(.el-table__row) { cursor: pointer; }
.zero { color: var(--app-muted); }
.ml { margin-left: 6px; }
.small { font-size: 12px; }
.sp { flex: 1; }
.row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.dh { display: flex; align-items: center; gap: 10px; font-size: 15px; color: var(--app-text); }
.drawer { display: flex; flex-direction: column; gap: 14px; }
.kv { display: grid; grid-template-columns: auto 1fr; gap: 6px 16px; margin: 0; font-size: 13px; }
.kv dt { color: var(--app-muted); }
.kv dd { margin: 0; }
.rule-picker { width: 100%; max-width: 420px; }
.hint { margin-top: 4px; font-size: 12px; }
.opt { display: flex; gap: 10px; align-items: center; justify-content: space-between; }
.totals { display: flex; flex-direction: column; gap: 6px; }
.legend { display: flex; gap: 14px; font-size: 12px; color: var(--app-muted); flex-wrap: wrap; }
.legend span::before { content: ""; display: inline-block; width: 8px; height: 8px; border-radius: 2px; margin-right: 5px; background: var(--el-fill-color-dark); }
.legend .sp::before, .legend span:last-child::before { display: none; }
.legend .a::before { background: var(--el-color-success); }
.legend .p::before { background: var(--el-color-warning); }
.card { border: 1px solid var(--el-border-color); border-radius: 8px; padding: 12px 14px; display: flex; flex-direction: column; gap: 10px; }
.card.on { border-color: var(--el-color-primary); }
.card h4 { margin: 0; font-size: 14px; display: flex; align-items: center; gap: 8px; }
.result { background: var(--el-fill-color-light); border-radius: 6px; padding: 8px 10px; font-size: 13px; display: flex; flex-direction: column; gap: 4px; }
.result.err { color: var(--el-color-danger); }
.start { width: 220px; }
.lines { margin: 0 12px 0 48px; width: auto; }
:deep(.bar) { display: flex; height: 8px; border-radius: 4px; overflow: hidden; background: var(--el-fill-color-dark); min-width: 100px; }
:deep(.bar.big) { height: 10px; }
:deep(.bar i) { display: block; }
:deep(.bar .a) { background: var(--el-color-success); }
:deep(.bar .p) { background: var(--el-color-warning); }
</style>
