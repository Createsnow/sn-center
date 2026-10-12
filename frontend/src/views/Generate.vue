<template>
  <div class="page page--fill">
    <PageHead :title="t('generate.title')" :desc="t('generate.desc')">
      <span class="muted snap">
        {{ t("generate.snapshotAt", { time: syncedAt || "—" }) }}
        <router-link to="/orders">{{ t("generate.toOrders") }}</router-link>
      </span>
    </PageHead>

    <div class="surface fill">
      <div class="toolbar">
        <el-radio-group v-model="view">
          <el-radio-button v-for="v in VIEWS" :key="v" :value="v">
            {{ t(`generate.view${v}`) }}<span class="cnt num">{{ countText(v) }}</span>
          </el-radio-button>
        </el-radio-group>
        <el-input v-model="kw" class="kw" :placeholder="t('generate.search')" clearable prefix-icon="Search" @keyup.enter="reload" @clear="reload" />
        <span class="muted summary">{{ t("generate.summary", { q: fmt(totals.quota), p: fmt(totals.pending) }) }}</span>
      </div>

      <div class="fill-body">
      <el-table v-loading="loading" :data="paged" size="small" row-key="pi" class="pis" height="100%" :empty-text="t(`generate.empty${view}`)" @row-click="(row: any) => openDetail(row.pi)">
        <el-table-column label="PI" min-width="170">
          <template #default="{ row }">
            <b>{{ row.pi }}</b>
            <el-tag v-if="row.unmapped && row.quota" size="small" type="danger" class="ml">{{ t("generate.unmappedTag") }}</el-tag>
            <el-tag v-else-if="row.disabled && (row.quota || row.pending_alloc)" size="small" type="danger" class="ml">{{ t("generate.disabledTag") }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="customer_number" :label="t('common.customer')" min-width="100" />
        <el-table-column :label="t('generate.bills')" align="right" width="64">
          <template #default="{ row }"><span class="num">{{ row.bills }}</span></template>
        </el-table-column>
        <el-table-column :label="t('orders.org')" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.orgs.map((o: any) => o.name).join("、") }}</template>
        </el-table-column>
        <el-table-column :label="t('common.status')" min-width="210">
          <template #default="{ row }"><RowState :row="row" :prog="progressOf(row)" /></template>
        </el-table-column>
        <el-table-column :label="t('generate.orderTotal')" align="right" width="90">
          <template #default="{ row }"><span class="num">{{ fmt(row.total_qty) }}</span></template>
        </el-table-column>
        <el-table-column :label="t('generate.generated')" align="right" width="90">
          <template #default="{ row }"><span class="num" :class="{ zero: !row.generated_qty }">{{ progressOf(row) ? "—" : fmt(row.generated_qty) }}</span></template>
        </el-table-column>
        <el-table-column :label="t('generate.allocated')" align="right" width="90">
          <template #default="{ row }"><span class="num" :class="{ zero: !row.allocated_qty }">{{ fmt(row.allocated_qty) }}</span></template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="110" align="right" class-name="acts">
          <template #default="{ row }">
            <el-button v-if="actionOf(row) === 'gen'" size="small" type="primary" :disabled="!!busy" @click.stop="openGen(row.pi)">{{ t("generate.btnGen") }}</el-button>
            <el-button v-else-if="actionOf(row) === 'pick'" size="small" type="primary" plain @click.stop="openDetail(row.pi)">{{ t("generate.btnPick") }}</el-button>
            <el-button v-else-if="actionOf(row) === 'alloc'" size="small" type="success" :loading="busy === `alloc:${row.pi}`" :disabled="!!busy && busy !== `alloc:${row.pi}`"
              @click.stop="allocatePi(row.pi, row.pending_alloc)">{{ t("generate.btnAlloc") }}</el-button>
            <el-button v-else size="small" @click.stop="openDetail(row.pi)">{{ t("generate.btnView") }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      </div>
      <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="shown.length" :page-size="pageSize"
        :current-page="page" :page-sizes="[20, 50, 100]" @current-change="(p: number) => (page = p)" @size-change="onSize" />
      <p v-if="capped(view) && fullRows(view).length > listCap" class="muted">{{ t("generate.recentOnly", { n: GEN_PAGES }) }}</p>
      <p v-else-if="!capped(view) && fullRows(view).length >= LIST_LIMIT" class="muted">{{ t("generate.more", { n: LIST_LIMIT }) }}</p>
    </div>

    <!-- 详情：按状态只显示一个主操作（生成中 / 生成 / 分配） -->
    <el-drawer :model-value="!!detailPi" size="780px" @close="closeDetail">
      <template #header>
        <div v-if="ctx" class="dh">
          <b>PI {{ ctx.pi_no }}</b>
          <el-tag effect="plain" size="small">{{ ctx.customer_code }} · {{ t("generate.billsN", { n: ctx.bills.length }) }}</el-tag>
        </div>
      </template>
      <div v-if="ctx" v-loading="ctxLoading" class="drawer">
        <el-alert v-for="msg in issueTexts" :key="msg" type="error" show-icon :closable="false" :title="msg" />

        <dl class="kv">
          <dt>{{ t("generate.rule") }}</dt>
          <dd>
            <template v-if="ctx.rule">
              {{ ctx.rule.rule_code }} · {{ ctx.rule.rule_name }} · v{{ ctx.rule.version }}
              <el-tag size="small" :type="ctx.rule.source === 'CHOSEN' ? 'warning' : 'success'" class="ml">{{ t(`generate.ruleSrc${ctx.rule.source}`) }}</el-tag>
              <el-button v-if="ctx.rule.source === 'CHOSEN' && !detailRun" link type="primary" size="small" class="ml" :loading="ruleBusy" @click="unbindRule">{{ t("generate.ruleUnbind") }}</el-button>
              <div v-else-if="ctx.rule.source !== 'CHOSEN'" class="muted hint">{{ t("generate.ruleBoundHint") }}</div>
            </template>
            <template v-else-if="ctx.rule_options.length">
              <el-select v-model="ruleId" filterable size="small" :placeholder="t('generate.rulePick')" class="rule-picker">
                <el-option v-for="o in ctx.rule_options" :key="o.rule_id" :value="o.rule_id" :label="ruleLabel(o)">
                  <div class="opt"><span><b>{{ o.rule_code }}</b> · {{ o.rule_name }}</span><span class="muted sn-mono">{{ o.sample_sn }}</span></div>
                </el-option>
              </el-select>
              <el-button size="small" class="ml" :disabled="ruleId == null" :loading="ruleBusy" @click="bindRule">{{ t("generate.ruleAssign") }}</el-button>
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
            <span class="muted ml">{{ t("generate.lastSeq") }} <span class="num">{{ ctx.counter.last_seq_dec }}</span></span>
          </dd>
        </dl>

        <div class="stats" :class="{ dim: !!detailRun }">
          <div class="stat"><span>{{ t("generate.orderTotal") }}</span><b class="num">{{ fmt(ctx.total_qty) }}</b></div>
          <div class="stat"><span>{{ t("generate.generated") }}</span><b class="num">{{ detailRun ? "—" : fmt(ctx.generated_qty) }}</b></div>
          <div class="stat"><span>{{ t("generate.allocated") }}</span><b class="num">{{ fmt(ctx.allocated_qty) }}</b></div>
        </div>

        <!-- 生成中：整张 PI 一个实时进度，可取消 -->
        <div v-if="detailRun" class="card on">
          <div class="run-head">
            <h4>{{ detailRun.cancel_at ? t("generate.stCANCELING") : t("generate.runTitle") }}</h4>
            <span class="muted small">{{ t("generate.runMeta", { time: detailRun.created_at, who: detailRun.created_by }) }}</span>
          </div>
          <div class="run-num">
            <b class="pct num">{{ pctText(detailRun) }}%</b>
            <span class="num">{{ t("generate.runCount", { done: fmt(detailRun.done_qty), qty: fmt(detailRun.qty) }) }}</span>
          </div>
          <el-progress :percentage="pctOf(detailRun)" :stroke-width="14" :show-text="false" :status="detailRun.cancel_at ? 'warning' : undefined" striped striped-flow />
          <div class="run-grid">
            <div><span class="muted small">{{ t("generate.writtenTo") }}</span><span class="sn-mono">{{ detailRun.current_sn || "—" }}</span></div>
            <div><span class="muted small">{{ t("generate.speed") }}</span><span>{{ speedOf(detailRun) ? t("generate.speedVal", { n: fmt(speedOf(detailRun)) }) : "—" }}</span></div>
            <div><span class="muted small">{{ t("generate.eta") }}</span><span>{{ etaOf(detailRun) != null ? t("generate.etaVal", { n: etaOf(detailRun) }) : "—" }}</span></div>
          </div>
          <div class="result muted">{{ t("generate.runNote") }}</div>
          <div class="row">
            <el-button type="danger" plain :disabled="!!detailRun.cancel_at" :loading="busy === `cancel:${detailRun.batch}`" @click="cancelGen(detailRun)">{{ t("generate.cancelGen") }}</el-button>
          </div>
        </div>

        <template v-else>
          <!-- 生成：数量在弹窗里填 -->
          <div v-if="ctx.quota && canGenerate" class="card" :class="{ on: !ctx.pending_alloc }">
            <h4>{{ t("generate.genCardTitle") }}<span class="muted light">{{ t("generate.quotaN", { n: fmt(ctx.quota) }) }}</span></h4>
            <div class="result">
              <span v-if="nextSn">{{ t("generate.genCardHint", { sn: nextSn }) }}</span>
              <span v-if="ctx.quota > ctx.limits.default" class="muted">{{ t("generate.genCardLimit", { n: fmt(ctx.limits.default) }) }}</span>
            </div>
            <div class="row">
              <el-button type="primary" :disabled="!!busy || (!ctx.rule && ruleId == null)" @click="openGen(ctx.pi_no)">{{ t("generate.btnGenOpen") }}</el-button>
              <span v-if="!ctx.rule && ruleId == null" class="muted small">{{ t("generate.rulePickRequired") }}</span>
            </div>
          </div>

          <!-- 分配：整张 PI 一次分完 -->
          <div v-if="ctx.pending_alloc" class="card ok on">
            <h4>{{ t("generate.allocCardTitle") }}</h4>
            <div class="result num">
              <div v-for="g in allocGroups" :key="g.factory" class="alloc-row">
                <span><b>{{ g.name }}</b> <span class="muted">{{ g.bills.map((b: any) => b.bill_no).join(" · ") }}</span></span>
                <b>{{ fmt(g.qty) }}</b>
              </div>
            </div>
            <div class="row">
              <el-button type="success" :loading="busy === `alloc:${ctx.pi_no}`" :disabled="!!busy" @click="allocatePi(ctx.pi_no, ctx.pending_alloc)">{{ t("generate.allocate") }}</el-button>
              <span class="muted small">{{ t("generate.allocNote") }}</span>
            </div>
          </div>

          <!-- 最近一次生成点错了：在分配之前可整次撤销 -->
          <div v-if="ctx.revocable_batch" class="revoke-bar">
            <span class="small">
              {{ t("generate.lastGen", { time: ctx.revocable_batch.created_at, who: ctx.revocable_batch.created_by, n: ctx.revocable_batch.bills.length, qty: fmt(ctx.revocable_batch.qty) }) }}
              <span class="sn-mono">{{ ctx.revocable_batch.start_sn }} ~ {{ ctx.revocable_batch.end_sn }}</span>
            </span>
            <el-button size="small" type="danger" plain :disabled="!!busy" :loading="busy === `revoke:${ctx.revocable_batch.batch}`" @click="revokeBatch(ctx.revocable_batch)">
              {{ t("generate.revokeLast") }}
            </el-button>
          </div>
        </template>

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
              <el-tag v-else-if="row.factory_disabled" size="small" type="danger" class="ml">{{ t("generate.disabledTag") }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('orders.totalQty')" align="right" width="90"><template #default="{ row }"><span class="num">{{ fmt(row.total_qty) }}</span></template></el-table-column>
          <el-table-column :label="t('generate.generated')" align="right" width="90"><template #default="{ row }"><span class="num">{{ detailRun ? "—" : fmt(row.generated_qty) }}</span></template></el-table-column>
          <el-table-column :label="t('generate.allocated')" align="right" width="90"><template #default="{ row }"><span class="num">{{ fmt(row.allocated_qty) }}</span></template></el-table-column>
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
                      <el-tooltip v-if="(row.status === 'FAILED' || row.status === 'CANCELED') && row.error_msg" :content="row.error_msg">
                        <el-tag size="small" :type="row.status === 'FAILED' ? 'danger' : 'info'">{{ t(`generate.job${row.status}`) }}</el-tag>
                      </el-tooltip>
                      <el-tag v-else size="small" :type="row.status === 'SUCCESS' ? 'success' : row.status === 'REVOKED' ? 'warning' : 'info'">{{ t(`generate.job${row.status}`) }}</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column prop="created_by" :label="t('common.operator')" width="80" />
                  <el-table-column prop="created_at" :label="t('common.time')" width="150" />
                </el-table>
                <div class="muted hint">{{ t("generate.revokeHint") }}</div>
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

    <!-- 生成弹窗：数量默认填满额度；额度超过默认上限时先按上限，可解除限制；超过上限自动纠正 -->
    <el-dialog v-model="gen.open" :title="gen.ctx ? t('generate.dlgTitle', { pi: gen.ctx.pi_no }) : ''" width="520px" :close-on-click-modal="false" @closed="resetGen">
      <div v-if="gen.ctx" class="gen-dlg">
        <div class="field">
          <label class="muted" for="gen-qty">{{ t("generate.qtyLabel", { quota: fmt(gen.ctx.quota) }) }}</label>
          <div class="row">
            <el-input-number id="gen-qty" v-model="gen.qty" :min="1" :step="1000" :precision="0" controls-position="right" class="qty" @change="onQty" />
            <span class="muted">{{ t("generate.capOf", { n: fmt(genCap) }) }}</span>
          </div>
          <div v-if="gen.corrected" class="warn small">{{ gen.corrected }}</div>
          <el-alert v-if="limited && !gen.unlocked" type="warning" :closable="false" show-icon :title="t('generate.limitHint', { n: fmt(gen.ctx.limits.default) })" />
          <el-checkbox v-if="limited" v-model="gen.unlocked" @change="onUnlock">{{ t("generate.unlock", { n: fmt(Math.min(gen.ctx.quota, gen.ctx.limits.max)) }) }}</el-checkbox>
          <div v-if="gen.unlocked && gen.ctx.quota > gen.ctx.limits.max" class="muted small">{{ t("generate.maxHint", { n: fmt(gen.ctx.limits.max) }) }}</div>
        </div>

        <div v-if="gen.previewing" class="result muted">{{ t("generate.previewing") }}</div>
        <div v-else-if="gen.error" class="result err">{{ gen.error }}</div>
        <dl v-else-if="gen.preview" class="result kv">
          <dt>{{ t("generate.rule") }}</dt>
          <dd>{{ gen.preview.rule.rule_code }} · {{ gen.preview.rule.rule_name }} · v{{ gen.preview.rule.version }}</dd>
          <dt>{{ t("generate.range") }}</dt>
          <dd>
            <span class="sn-mono">{{ gen.preview.start_sn }} ~ {{ gen.preview.end_sn }}</span>
            <el-tooltip v-if="gen.preview.next?.adjusted" :content="t('generate.startAdjusted', { sn: gen.preview.next.by_sn, start: gen.preview.start_sn })">
              <el-tag size="small" type="warning" class="ml">{{ t("generate.adjustedTag") }}</el-tag>
            </el-tooltip>
          </dd>
          <dt>{{ t("generate.byBills") }}</dt>
          <dd class="bill-list">
            <span v-for="it in gen.preview.items" :key="it.bill_no">{{ it.bill_no }}　{{ nameOf(it.factory_code) }}　<span class="num">{{ fmt(it.qty) }}</span></span>
          </dd>
          <dt>{{ t("generate.after") }}</dt>
          <dd>{{ gen.ctx.quota - gen.preview.qty > 0 ? t("generate.afterLeft", { n: fmt(gen.ctx.quota - gen.preview.qty) }) : t("generate.afterDone") }}</dd>
        </dl>
      </div>
      <template #footer>
        <el-button @click="gen.open = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" :disabled="!gen.preview || gen.previewing || !!busy" :loading="busy === 'gen'" @click="runGenerate">
          {{ t("generate.confirmBtn", { n: fmt(gen.qty || 0) }) }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox, ElTag } from "element-plus";
import { api } from "@/api";
import { clampQty, genQtyCap } from "@/snList.js";
import { useFactories } from "@/composables/useFactories";
import type { GenBatch } from "@/types/api";
import PageHead from "@/components/PageHead.vue";

type View = "ALL" | "TO_GEN" | "RUNNING" | "TO_ALLOC" | "DONE";
const VIEWS: View[] = ["ALL", "TO_GEN", "RUNNING", "TO_ALLOC", "DONE"];
const LIST_LIMIT = 500;
/** 各页签只列最近下单的几页 PI（「生成中」除外），更早的用搜索找；搜索时不受页数限制。 */
const GEN_PAGES = 3;
type TagType = "info" | "primary" | "success" | "warning" | "danger";

const { t } = useI18n();
const route = useRoute();
const { nameOf } = useFactories();
const fmt = (n: number | null | undefined) => (n == null ? "—" : Number(n).toLocaleString());

/** 跟踪中的生成：PI → 最新整体进度，加首个采样点（算速度与预计剩余，不受服务器时钟影响）。 */
type Tracked = GenBatch & { t0: number; d0: number; t1: number };
const tracked = reactive<Record<string, Tracked>>({});

const pctOf = (b: { qty: number; done_qty: number }) => (b.qty ? Math.min(100, (b.done_qty * 100) / b.qty) : 0);
const pctText = (b: { qty: number; done_qty: number }) => pctOf(b).toFixed(1).replace(/\.0$/, "");
function speedOf(b: Tracked) {
  const s = (b.t1 - b.t0) / 1000;
  return s >= 1 && b.done_qty > b.d0 ? Math.round((b.done_qty - b.d0) / s) : 0;
}
function etaOf(b: Tracked) {
  const v = speedOf(b);
  return v ? Math.max(0, Math.ceil((b.qty - b.done_qty) / v)) : null;
}

/** 正在生成的 PI 的进度：本页跟踪中的优先，否则用列表接口带回的。 */
const progressOf = (row: any): any => tracked[row.pi] ?? row.running ?? null;

/** 一行的状态：生成中 / 不能生成 / 需先选规则 / 还可生成 / 待生成 / 待分配 / 已完成。 */
function stateOf(row: any): string {
  if (progressOf(row)) return "RUNNING";
  if (row.quota > 0) {
    if (row.unmapped || row.disabled) return "BLOCKED";
    if (row.generated_qty > 0) return "MORE";
    return row.rule_fixed ? "TO_GEN" : "PICK";
  }
  if (row.pending_alloc > 0) return "TO_ALLOC";
  return "DONE";
}
const STATE_TYPE: Record<string, TagType> = {
  TO_GEN: "primary", PICK: "info", BLOCKED: "danger", MORE: "primary", TO_ALLOC: "warning", DONE: "success",
};

/** 行上按钮（下一步）：生成 / 选规则 / 分配 / 查看。 */
function actionOf(row: any): "gen" | "pick" | "alloc" | "view" {
  const st = stateOf(row);
  if (st === "RUNNING" || st === "BLOCKED") return "view";
  if (view.value === "TO_ALLOC" && row.pending_alloc > 0) return "alloc";
  if (st === "TO_GEN" || st === "MORE") return "gen";
  if (st === "PICK") return "pick";
  return st === "TO_ALLOC" ? "alloc" : "view";
}

const RowState = defineComponent({
  props: { row: { type: Object, required: true }, prog: { type: Object, default: null } },
  setup(props) {
    return () => {
      const r: any = props.row;
      const p: any = props.prog;
      if (p) {
        const label = p.cancel_at ? t("generate.stCANCELING") : t("generate.stRUNNING", { pct: pctText(p) });
        return h("div", { class: "run-cell" }, [
          h("div", { class: "run-cell__top" }, [h("span", { class: "run-cell__label" }, label), h("span", { class: "muted num" }, `${fmt(p.done_qty)} / ${fmt(p.qty)}`)]),
          h("div", { class: "mini-bar" }, [h("i", { style: { width: `${pctOf(p)}%` }, class: { warn: !!p.cancel_at } })]),
        ]);
      }
      const st = stateOf(r);
      const kids = [h(ElTag, { size: "small", type: STATE_TYPE[st], disableTransitions: true }, () => t(`generate.st${st}`))];
      if (st === "MORE") kids.push(h("span", { class: "row-sub num" }, fmt(r.quota)));
      if ((st === "MORE" || st === "TO_GEN") && r.pending_alloc > 0) kids.push(h("span", { class: "row-sub" }, t("generate.alsoPending", { n: fmt(r.pending_alloc) })));
      return h("span", { class: "row-state" }, kids);
    };
  },
});

const view = ref<View>("ALL");
const page = ref(1);
const pageSize = ref(20);
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
const ruleBusy = ref(false);
let alive = true;

const ruleLabel = (o: any) => `${o.rule_code} · ${o.rule_name}`;
const nextSn = computed(() => ctx.value?.next?.start_sn ?? ctx.value?.rule_options.find((o: any) => o.rule_id === ruleId.value)?.sample_sn ?? null);
const detailRun = computed<Tracked | null>(() => (detailPi.value ? tracked[detailPi.value] ?? null : null));
const canGenerate = computed(() => !!ctx.value && !ctx.value.issues.some((c: string) => ["FACTORY_UNMAPPED", "FACTORY_DISABLED", "RULE_MISSING"].includes(c)));
const totals = computed(() => pis.value.reduce((s, r) => ({ quota: s.quota + r.quota, pending: s.pending + r.pending_alloc }), { quota: 0, pending: 0 }));
const listCap = computed(() => GEN_PAGES * pageSize.value);
/** 本次列表是否按关键字搜索得到（搜索结果不受页数限制）。 */
const searched = ref("");
const capped = (v: View) => v !== "RUNNING" && !searched.value;
const shown = computed(() => rowsOf(view.value));
const paged = computed(() => shown.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value));

/** 该页签的全部行（未截页）。 */
function fullRows(v: View) {
  if (v === "ALL") return allPis.value;
  if (v === "DONE") return allPis.value.filter((r) => stateOf(r) === "DONE" && r.generated_qty > 0);
  if (v === "RUNNING") return pis.value.filter((r) => !!progressOf(r));
  if (v === "TO_GEN") return pis.value.filter((r) => r.quota > 0 && !progressOf(r));
  return pis.value.filter((r) => r.pending_alloc > 0);
}
function rowsOf(v: View) {
  const rows = fullRows(v);
  return capped(v) ? rows.slice(0, listCap.value) : rows;
}
/** 页签上的总数：列表取满上限时写成「500+」，表示还有更多。 */
function countText(v: View) {
  const n = fullRows(v).length;
  const src = v === "ALL" || v === "DONE" ? allPis.value : pis.value;
  return src.length >= LIST_LIMIT ? `${n}+` : String(n);
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
      api.orderPis({ q, limit: LIST_LIMIT }),
    ]);
    searched.value = q;
    pis.value = p;
    allPis.value = a;
    // 打开页面前就在生成的 PI（别人发起的、或刷新过页面）：接着跟踪进度
    for (const r of [...p, ...a]) if (r.running && !tracked[r.pi]) track(r.pi, r.running.batch);
  } finally {
    loading.value = false;
  }
}
watch(view, () => {
  page.value = 1;
});
watch(kw, () => (page.value = 1));
watch(() => shown.value.length, (n) => {
  page.value = Math.min(page.value, Math.max(1, Math.ceil(n / pageSize.value)));
});

function onSize(s: number) {
  pageSize.value = s;
  page.value = 1;
}

async function openDetail(pi: string) {
  detailPi.value = pi;
  await loadContext({ pi });
}

function closeDetail() {
  detailPi.value = "";
  ctx.value = null;
}

async function loadContext(params: { pi?: string; bill_no?: string }) {
  ctxLoading.value = true;
  try {
    const c = await api.genPiContext(params);
    ctx.value = c;
    detailPi.value = c.pi_no;
    if (!c.rule_options.some((o: any) => o.rule_id === ruleId.value)) ruleId.value = c.rule_options[0]?.rule_id ?? null;
    if (c.running_batch && !tracked[c.pi_no]) track(c.pi_no, c.running_batch.batch);
  } finally {
    ctxLoading.value = false;
  }
}

async function bindRule() {
  const c = ctx.value;
  if (!c || ruleId.value == null) return;
  ruleBusy.value = true;
  try {
    await api.bindPiRule(c.pi_no, ruleId.value);
    ElMessage.success(t("generate.ruleAssigned"));
    await refresh(c.pi_no);
  } finally {
    ruleBusy.value = false;
  }
}

async function unbindRule() {
  const c = ctx.value;
  if (!c?.rule) return;
  try {
    await ElMessageBox.confirm(t("generate.ruleUnbindConfirm", { pi: c.pi_no, code: c.rule.rule_code }), t("generate.ruleUnbind"), { type: "warning" });
  } catch {
    return;
  }
  ruleBusy.value = true;
  try {
    await api.unbindPiRule(c.pi_no);
    ElMessage.success(t("generate.ruleUnbound"));
    await refresh(c.pi_no);
  } finally {
    ruleBusy.value = false;
  }
}

// ------------------------------------------------------------------ 生成弹窗

const gen = reactive<{
  open: boolean;
  ctx: any;
  ruleId: number | null;
  qty: number | undefined;
  unlocked: boolean;
  corrected: string;
  preview: any;
  previewing: boolean;
  error: string;
}>({ open: false, ctx: null, ruleId: null, qty: undefined, unlocked: false, corrected: "", preview: null, previewing: false, error: "" });
const genCap = computed(() => (gen.ctx ? genQtyCap(gen.ctx.quota, gen.ctx.limits, gen.unlocked) : 0));
const limited = computed(() => !!gen.ctx && gen.ctx.quota > gen.ctx.limits.default);
let previewTimer: number | undefined;
let previewSeq = 0;

/** 打开生成弹窗；规则还没确定时先打开详情选规则。 */
async function openGen(pi: string) {
  const c = ctx.value?.pi_no === pi ? ctx.value : await api.genPiContext({ pi });
  const picked = ctx.value?.pi_no === pi ? ruleId.value : null;
  if (!c.rule && picked == null) {
    await openDetail(pi);
    return;
  }
  gen.ctx = c;
  gen.ruleId = c.rule ? null : picked;
  gen.unlocked = false;
  gen.corrected = "";
  gen.qty = genQtyCap(c.quota, c.limits, false);
  gen.open = true;
  schedulePreview();
}

function resetGen() {
  window.clearTimeout(previewTimer);
  previewSeq++;
  Object.assign(gen, { ctx: null, preview: null, previewing: false, error: "", corrected: "" });
}

/** 数量改动：超过上限自动改为上限并提示。 */
function onQty(v: number | undefined) {
  const r = clampQty(v, genCap.value);
  gen.corrected = r.corrected ? t("generate.qtyCorrected", { n: fmt(Math.floor(Number(v))), cap: fmt(r.qty) }) : "";
  gen.qty = r.qty;
  schedulePreview();
}

function onUnlock() {
  gen.corrected = "";
  if ((gen.qty ?? 0) > genCap.value) gen.qty = genCap.value;
  schedulePreview();
}

// 数量停顿后自动计算号段（后台预演，不写号）；结果过时就丢弃
function schedulePreview() {
  window.clearTimeout(previewTimer);
  previewSeq++;
  gen.preview = null;
  gen.error = "";
  gen.previewing = true;
  previewTimer = window.setTimeout(doPreview, 400);
}

async function doPreview() {
  const seq = ++previewSeq;
  try {
    const p = await api.genPiPreview({ pi_no: gen.ctx.pi_no, qty: gen.qty, rule_id: gen.ruleId }, true);
    if (seq === previewSeq) gen.preview = p;
  } catch (e: any) {
    if (seq === previewSeq) gen.error = e.message;
  } finally {
    if (seq === previewSeq) gen.previewing = false;
  }
}

/** 凭预演令牌一次生成整张 PI：后台在一个事务里依次生成各订单，失败或取消整批回滚。 */
async function runGenerate() {
  const p = gen.preview;
  if (!p) return;
  busy.value = "gen";
  try {
    const items = p.items.map((it: any) => ({ preview_token: it.token, bill_no: it.bill_no, qty: it.qty }));
    const r = await api.generatePi({ pi_no: p.pi_no, items });
    gen.open = false;
    if (r.jobs.some((j: any) => j.status === "RUNNING")) track(p.pi_no, r.batch);
    else ElMessage.success(t("generate.done", { qty: fmt(r.qty) }));
    await refresh(p.pi_no);
  } catch {
    // 错误已由请求层提示；预演可能已失效，重新计算号段
    if (gen.open) schedulePreview();
    await refresh(p.pi_no).catch(() => undefined);
  } finally {
    busy.value = "";
  }
}

// ------------------------------------------------------------------ 进度 / 取消

/** 轮询一次生成的整体进度；结束时提示结果并刷新。 */
async function track(pi: string, batch: string) {
  if (tracked[pi]) return;
  let b: GenBatch;
  try {
    b = await api.genBatch(batch);
  } catch {
    return;
  }
  const now = Date.now();
  tracked[pi] = { ...b, t0: now, d0: b.done_qty, t1: now };
  try {
    while (alive && b.status === "RUNNING") {
      await new Promise((r) => window.setTimeout(r, 1000));
      b = await api.genBatch(batch);
      const cur = tracked[pi];
      tracked[pi] = { ...b, t0: cur?.t0 ?? now, d0: cur?.d0 ?? 0, t1: Date.now() };
    }
  } catch {
    // 网络中断：下次刷新列表时会重新接上
  } finally {
    delete tracked[pi];
    // 列表行上带回的进度已过时：等下面刷新列表拿最新状态
    for (const r of [...pis.value, ...allPis.value]) if (r.pi === pi) r.running = null;
  }
  if (!alive) return;
  if (b.status === "SUCCESS") ElMessage.success(t("generate.done", { qty: fmt(b.qty) }));
  else if (b.status === "CANCELED") ElMessage.warning({ message: t("generate.canceled"), duration: 6000, showClose: true });
  else if (b.status === "FAILED") ElMessage.error({ message: t("generate.failed", { msg: b.error_msg || "" }), duration: 8000, showClose: true });
  await refresh(pi);
}

/** 取消正在进行的生成：整批回滚，已写入的号全部删除；取消留痕。 */
async function cancelGen(b: Tracked) {
  try {
    await ElMessageBox.confirm(t("generate.cancelConfirm"), t("generate.cancelGen"), { type: "warning" });
  } catch {
    return;
  }
  busy.value = `cancel:${b.batch}`;
  try {
    const r = await api.cancelGen(b.batch);
    const cur = tracked[b.pi_no];
    if (cur) tracked[b.pi_no] = { ...cur, ...r };
    ElMessage.info(t("generate.cancelRequested"));
  } catch {
    // 已结束等错误已由请求层提示；进度轮询会带回最终结果
  } finally {
    busy.value = "";
  }
}

async function refresh(pi: string) {
  await Promise.all([reload(), detailPi.value === pi ? loadContext({ pi }) : Promise.resolve()]);
}

async function allocatePi(pi: string, n: number) {
  await ElMessageBox.confirm(t("generate.allocConfirm", { qty: fmt(n), pi }), t("generate.allocate"), { type: "warning" });
  busy.value = `alloc:${pi}`;
  try {
    const r = await api.allocatePi(pi);
    ElMessage.success(t("generate.allocDone", { qty: fmt(r.qty), n: r.items.length }));
    await refresh(pi);
  } finally {
    busy.value = "";
  }
}

/** 整次撤销最近一次生成（点错了重来）：只对 PI 流水排在最后、号全部仍待分配的那次开放，原因必填。 */
async function revokeBatch(rb: any) {
  let reason: string;
  try {
    const { value } = await ElMessageBox.prompt(
      t("generate.revokeConfirm", { qty: fmt(rb.qty), start: rb.start_sn, end: rb.end_sn, n: rb.bills.length }),
      t("generate.revokeLast"),
      { type: "warning", inputPlaceholder: t("generate.revokeReason"), inputValidator: (v: string) => !!v?.trim() || t("generate.revokeReason") },
    );
    reason = value.trim();
  } catch {
    return;
  }
  busy.value = `revoke:${rb.batch}`;
  try {
    await api.revokeBatch(rb.batch, reason);
    ElMessage.success(t("generate.revoked", { qty: fmt(rb.qty) }));
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
.pis :deep(.acts .el-button) { min-width: 76px; }
.zero { color: var(--app-muted); }
.ml { margin-left: 6px; }
.small { font-size: 12px; }
.light { font-weight: 400; margin-left: 8px; font-size: 13px; }
.row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.dh { display: flex; align-items: center; gap: 10px; font-size: 15px; color: var(--app-text); }
.drawer { display: flex; flex-direction: column; gap: 14px; }
.kv { display: grid; grid-template-columns: auto 1fr; gap: 6px 16px; margin: 0; font-size: 13px; }
.kv dt { color: var(--app-muted); }
.kv dd { margin: 0; }
.rule-picker { width: 100%; max-width: 420px; }
.hint { margin-top: 4px; font-size: 12px; }
.opt { display: flex; gap: 10px; align-items: center; justify-content: space-between; }
.stats { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.stats.dim { opacity: 0.6; }
.stat { border: 1px solid var(--el-border-color-lighter); border-radius: 6px; padding: 8px 12px; display: flex; flex-direction: column; gap: 2px; }
.stat span { color: var(--app-muted); font-size: 12px; }
.stat b { font-size: 20px; }
.card { border: 1px solid var(--el-border-color); border-radius: 8px; padding: 12px 14px; display: flex; flex-direction: column; gap: 10px; }
.card.on { border-color: var(--el-color-primary); }
.card.ok.on { border-color: var(--el-color-success); }
.card h4 { margin: 0; font-size: 14px; display: flex; align-items: baseline; gap: 8px; }
.result { background: var(--el-fill-color-light); border-radius: 6px; padding: 8px 10px; font-size: 13px; display: flex; flex-direction: column; gap: 4px; }
.result.kv { display: grid; }
.result.err { color: var(--el-color-danger); }
.alloc-row { display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.run-head { display: flex; justify-content: space-between; align-items: baseline; gap: 10px; flex-wrap: wrap; }
.run-num { display: flex; align-items: baseline; gap: 10px; }
.run-num .pct { font-size: 28px; color: var(--el-color-primary); }
.run-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.run-grid > div { display: flex; flex-direction: column; gap: 2px; }
.revoke-bar { display: flex; align-items: center; justify-content: space-between; gap: 10px; flex-wrap: wrap; padding: 8px 12px; border: 1px dashed var(--el-color-danger-light-5); border-radius: 6px; background: var(--el-color-danger-light-9); }
.lines { margin: 0 12px 0 48px; width: auto; }
.gen-dlg { display: flex; flex-direction: column; gap: 14px; }
.field { display: flex; flex-direction: column; gap: 8px; }
.qty { width: 180px; }
.warn { color: var(--el-color-warning-dark-2); }
.bill-list { display: flex; flex-direction: column; gap: 2px; }
:deep(.row-state) { display: inline-flex; align-items: center; gap: 8px; flex-wrap: wrap; }
:deep(.row-sub) { color: var(--app-muted); font-size: 12px; }
:deep(.run-cell) { display: flex; flex-direction: column; gap: 4px; }
:deep(.run-cell__top) { display: flex; justify-content: space-between; gap: 8px; font-size: 12px; }
:deep(.run-cell__label) { color: var(--el-color-primary); font-weight: 500; }
:deep(.mini-bar) { height: 6px; border-radius: 3px; overflow: hidden; background: var(--el-fill-color-dark); }
:deep(.mini-bar i) { display: block; height: 100%; background: var(--el-color-primary); }
:deep(.mini-bar i.warn) { background: var(--el-color-warning); }
</style>
