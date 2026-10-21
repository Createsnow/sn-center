<template>
  <div class="page page--fill">
    <PageHead :title="t('generate.title')" :desc="t('generate.desc')">
      <span class="muted snap">
        {{ t("generate.snapshotAt", { time: fmtTime(list.synced_at) }) }}
        <router-link to="/orders">{{ t("generate.toOrders") }}</router-link>
      </span>
    </PageHead>

    <div class="surface fill">
      <div class="toolbar">
        <el-radio-group v-model="tab" @change="loadList()">
          <el-radio-button v-for="v in TABS" :key="v" :value="v">{{ t(`generate.tab.${v}`) }}</el-radio-button>
        </el-radio-group>
        <el-input v-model="q" class="kw" :placeholder="t('generate.search')" clearable prefix-icon="Search" @keyup.enter="loadList()" @clear="loadList()" />
        <span class="muted summary">
          {{ t("generate.summary", { quota: num(list.summary.quota) }) }}
          <router-link v-if="list.summary.pending_alloc" to="/allocate">{{ t("generate.summaryPending", { n: num(list.summary.pending_alloc) }) }}</router-link>
        </span>
      </div>

      <div class="fill-body">
        <el-table v-loading="listLoading" :data="paged" size="small" row-key="pi_no" height="100%" :empty-text="t('common.empty')"
          @row-click="(row: any) => openRow(row)">
          <el-table-column label="PI" min-width="160">
            <template #default="{ row }">
              <b>{{ row.pi_no }}</b>
              <el-tag v-if="row.frozen_bills" size="small" type="danger" class="ml">{{ t("generate.frozenBills", { n: row.frozen_bills }) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="customer_code" :label="t('common.customer')" min-width="90" show-overflow-tooltip />
          <el-table-column :label="t('generate.bills')" align="right" width="64">
            <template #default="{ row }"><span class="num">{{ row.bills }}</span></template>
          </el-table-column>
          <el-table-column :label="t('common.factory')" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ row.orgs.map((o: string) => nameOf(o)).join("、") }}</template>
          </el-table-column>
          <el-table-column :label="t('common.status')" min-width="200">
            <template #default="{ row }">
              <div class="row-state">
                <el-tag size="small" :type="STATUS_TAG[genStatus(row)] || 'info'" disable-transitions>{{ t(`generate.status.${genStatus(row)}`) }}</el-tag>
                <template v-if="row.running">
                  <span class="mini-bar"><i :style="{ width: pct(row.running) + '%' }" /></span>
                  <span class="muted num">{{ pct(row.running) }}%</span>
                </template>
                <span v-else-if="reasonText(row.reasons)" class="muted">{{ reasonText(row.reasons) }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="t('generate.orderTotal')" align="right" width="90">
            <template #default="{ row }"><span class="num">{{ num(row.total_qty) }}</span></template>
          </el-table-column>
          <el-table-column :label="t('generate.generated')" align="right" width="86">
            <template #default="{ row }"><span class="num">{{ num(row.generated_qty) }}</span></template>
          </el-table-column>
          <el-table-column :label="t('generate.transferredIn')" align="right" width="80">
            <template #default="{ row }"><span class="num" :class="{ zero: !row.transferred_in }">{{ num(row.transferred_in) }}</span></template>
          </el-table-column>
          <el-table-column :label="t('generate.quota')" align="right" width="86">
            <template #default="{ row }"><span class="num" :class="{ zero: !row.quota }">{{ num(row.quota) }}</span></template>
          </el-table-column>
          <el-table-column :label="t('generate.pendingAlloc')" align="right" width="90">
            <template #default="{ row }">
              <router-link v-if="row.pending_alloc" class="num" :to="{ path: '/allocate', query: { pi: row.pi_no } }" @click.stop>{{ num(row.pending_alloc) }}</router-link>
              <span v-else class="num zero">0</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('common.actions')" width="110" align="right">
            <template #default="{ row }">
              <el-button size="small" :type="ACTION_TYPE[actionOf(row)]" :plain="actionOf(row) !== 'gen'"
                :loading="actionOf(row) === 'running'" @click.stop="openRow(row)">
                {{ t(`generate.action.${actionOf(row)}`) }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <el-pagination class="pager" layout="total, prev, pager, next" :total="shown.length" :page-size="PAGE"
        :current-page="page" @current-change="(p: number) => (page = p)" />
      <p v-if="limited" class="muted">{{ t("generate.recentOnly", { n: RECENT_PAGES }) }}</p>
      <p v-if="list.capped" class="muted">{{ t("generate.capped", { n: LIST_LIMIT }) }}</p>
    </div>

    <!-- 详情：只管生成与撤销。头部（数字、去分配）→ 提示条 → 物料行 / 生成记录 → 底部操作栏（范围、批量、生成） -->
    <el-drawer :model-value="!!detailPi" size="min(1240px, 94vw)" class="gen-drawer" @close="closeDetail">
      <template #header>
        <div v-if="ctx" class="dh">
          <div class="dh-title">
            <b>PI {{ ctx.pi_no }}</b>
            <span class="muted">{{ t("common.customer") }} {{ ctx.customer_code || "—" }}</span>
            <span class="push" />
            <router-link v-if="ctx.summary.pending_alloc" class="go" :to="{ path: '/allocate', query: { pi: ctx.pi_no } }">
              {{ t("generate.toAllocate", { n: num(ctx.summary.pending_alloc) }) }}<el-icon><Right /></el-icon>
            </router-link>
            <el-button size="small" @click="piRuleOpen = true">{{ t("generate.piRuleBtn") }}</el-button>
          </div>
          <div class="kv">
            <span>{{ t("generate.orderTotal") }} <b class="num">{{ num(ctx.summary.total_qty) }}</b></span>
            <span>{{ t("generate.generated") }} <b class="num">{{ num(ctx.summary.generated_qty) }}</b></span>
            <span>{{ t("generate.quota") }} <b class="num">{{ num(ctx.summary.quota) }}</b></span>
            <span v-if="ctx.summary.transferred_in">{{ t("generate.transferredIn") }} <b class="num">{{ num(ctx.summary.transferred_in) }}</b></span>
            <span v-if="ctx.summary.suspended" class="warn">{{ t("generate.suspendedQty", { n: num(ctx.summary.suspended) }) }}</span>
            <span>{{ t("generate.nextSeq") }} <b class="num">{{ ctx.counter.next_seq }}</b></span>
          </div>
        </div>
      </template>

      <div v-if="ctx" v-loading="ctxLoading" class="drawer">
        <!-- 提示条：还有额度但不能生成的物料行，按原因给处理入口 -->
        <div v-if="todoGroups.length" class="banner danger">
          <b>{{ t("generate.todo") }}</b>
          <span v-for="g in todoGroups" :key="g.code" class="todo">
            <span>{{ issueText(g.code) }} {{ t("generate.issueLines", { n: g.lines.length }) }}</span>
            <el-button v-if="g.code === 'NO_RULE' || g.code === 'RULE_PREFIX'" link type="primary" @click="openRuleDialog(g.lines)">{{ t("generate.pickForNeeded") }}</el-button>
            <router-link v-else-if="g.code === 'PI_FROZEN' || g.code === 'MATERIAL_LOST'" :to="{ path: '/orders', query: { bill_no: g.lines[0].bill_no } }">{{ t("generate.toOrders") }}</router-link>
            <el-button v-else link type="primary" @click="showTodo">{{ t("generate.onlyThese") }}</el-button>
          </span>
          <span class="push muted small">{{ t("generate.todoNote") }}</span>
        </div>

        <!-- 生成中：进度、取消 -->
        <GenProgress v-if="running" :running="running" :canceling="busy === 'cancel'" @cancel="cancelGen" />

        <el-tabs v-model="dtab" class="dtabs">
          <el-tab-pane :label="t('generate.linesTitle')" name="lines">
            <div class="toolbar">
              <el-radio-group v-model="lineFilter" size="small">
                <el-radio-button v-for="f in FILTERS" :key="f" :value="f">{{ t(`generate.filter.${f}`, { n: filterCount[f] }) }}</el-radio-button>
              </el-radio-group>
              <span class="muted small">{{ t("generate.selHint") }}</span>
              <span class="push" />
              <el-button link type="primary" size="small" @click="foldAll(false)">{{ t("common.expandAll") }}</el-button>
              <el-button link type="primary" size="small" @click="foldAll(true)">{{ t("common.collapseAll") }}</el-button>
            </div>
            <div v-for="b in shownBills" :key="b.bill_no" class="bill">
              <!-- 点单据头展开 / 收起物料行；勾选框只管勾选 -->
              <div class="bill-h" :title="isFolded(b.bill_no) ? t('common.expand') : t('common.collapse')" @click="toggleFold(b.bill_no)">
                <el-icon class="fold" :class="{ open: !isFolded(b.bill_no) }"><ArrowRight /></el-icon>
                <span @click.stop><el-checkbox v-bind="billCheck(b.bill_no)" :disabled="!b.in_snapshot || !b.lines.length" @change="(v: any) => onBill(b.bill_no, !!v)" /></span>
                <span v-if="billCheck(b.bill_no).order >= 0" class="order">{{ billCheck(b.bill_no).order + 1 }}</span>
                <b>{{ b.bill_no }}</b>
                <span class="muted">PO {{ b.po || "—" }} · {{ b.org_name || nameOf(b.org_code) }}</span>
                <el-tag v-if="!b.in_snapshot" size="small" type="info">{{ t("generate.notInSnapshot") }}</el-tag>
                <el-tag v-if="b.state.suspend" size="small" type="warning">{{ suspendLabel(b.state.suspend) }}</el-tag>
                <el-tag v-if="b.state.leave_state" size="small" type="danger">{{ leaveStateLabel(b.state.leave_state) }}</el-tag>
                <el-tag v-if="b.state.pi_frozen" size="small" type="danger">{{ t("generate.piFrozen") }}</el-tag>
                <el-tag v-if="b.state.open_alerts" size="small" type="warning">{{ t("generate.openAlerts", { n: b.state.open_alerts }) }}</el-tag>
                <span class="push muted num">{{ t("generate.billCounts", { total: num(b.total_qty), gen: num(b.generated_qty), quota: num(b.quota) }) }}</span>
              </div>
              <el-table v-if="b.shown.length" v-show="!isFolded(b.bill_no)" :data="b.shown" size="small" class="lines" :row-class-name="lineClass" @row-click="onLineClick">
                <el-table-column width="52">
                  <template #default="{ row }">
                    <span class="sel" @click.stop>
                      <el-checkbox :model-value="isPicked(row)" @change="(v: any) => onLine(row, !!v)" />
                      <span v-if="lineIdx(row) >= 0" class="order">{{ lineIdx(row) + 1 }}</span>
                    </span>
                  </template>
                </el-table-column>
                <el-table-column :label="t('common.billSeq')" prop="seq" width="72" />
                <el-table-column :label="t('common.attachNo')" width="120">
                  <template #default="{ row }">
                    <span v-if="row.attach_source === 'K3'">{{ row.attach_no }}</span>
                    <el-tooltip v-else :disabled="!row.edit_lock" :content="lockText(row)">
                      <span class="cell-input" @click.stop>
                        <el-input :model-value="draftOf(row, 'attach')" size="small" maxlength="64" :disabled="!!row.edit_lock"
                          :class="{ missing: !row.edit_lock && !draftOf(row, 'attach') }" :placeholder="t('generate.attachRequired')"
                          @update:model-value="(v: string) => setDraft(row, 'attach', v)" @change="saveAttach(row)" />
                      </span>
                    </el-tooltip>
                  </template>
                </el-table-column>
                <el-table-column :label="t('common.material')" min-width="110" show-overflow-tooltip>
                  <template #default="{ row }">
                    <div>{{ row.material_code || t("generate.noMaterial") }}</div>
                    <div class="muted">{{ row.material_name }}</div>
                  </template>
                </el-table-column>
                <el-table-column :label="t('common.length')" width="96">
                  <template #default="{ row }">
                    <span v-if="row.length_source === 'K3'">{{ row.length }}</span>
                    <el-tooltip v-else :content="row.edit_lock ? lockText(row) : t('generate.lengthDefaultTip')">
                      <span class="cell-input" @click.stop>
                        <el-input :model-value="draftOf(row, 'length')" size="small" maxlength="64" :disabled="!!row.edit_lock"
                          :placeholder="DEFAULT_LENGTH" @update:model-value="(v: string) => setDraft(row, 'length', v)" @change="saveLength(row)" />
                      </span>
                    </el-tooltip>
                  </template>
                </el-table-column>
                <el-table-column :label="t('common.qty')" align="right" width="64">
                  <template #default="{ row }"><span class="num">{{ num(row.qty) }}</span></template>
                </el-table-column>
                <el-table-column :label="t('generate.generated')" align="right" width="74">
                  <template #default="{ row }">
                    <el-tooltip :content="t('generate.ownTip', { own: num(row.own_generated_qty), gen: num(row.generated_qty) })">
                      <span class="num">{{ num(row.generated_qty) }}</span>
                    </el-tooltip>
                  </template>
                </el-table-column>
                <el-table-column :label="t('generate.allocated')" align="right" width="74">
                  <template #default="{ row }"><span class="num">{{ num(row.allocated_qty) }}</span></template>
                </el-table-column>
                <el-table-column :label="t('generate.quota')" align="right" width="70">
                  <template #default="{ row }"><span class="num" :class="{ zero: !row.quota }">{{ num(row.quota) }}</span></template>
                </el-table-column>
                <el-table-column :label="t('common.status')" min-width="112">
                  <template #default="{ row }">
                    <div v-if="row.quota && row.issues?.length" class="issue-tags">
                      <el-tag v-for="i in row.issues" :key="i" size="small" type="danger" effect="plain">{{ issueText(i) }}</el-tag>
                    </div>
                    <el-tag v-else size="small" :type="LINE_STATE_TAG[lineState(row)]" disable-transitions>{{ t(`generate.lineState.${lineState(row)}`) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column :label="t('generate.snRange')" min-width="230">
                  <template #default="{ row }">
                    <div v-for="s in segsOf(row)" :key="s.gen_batch" class="sn-mono">{{ s.start_sn }} ~ {{ s.end_sn }}</div>
                    <div v-if="planOf(row)" class="plan">
                      <span class="sn-mono">{{ planOf(row).start_sn }} ~ {{ planOf(row).end_sn }}</span>
                      <el-tag size="small" disable-transitions>{{ t("generate.thisRun") }}</el-tag>
                    </div>
                    <span v-if="!segsOf(row).length && !planOf(row)" class="zero">—</span>
                  </template>
                </el-table-column>
                <el-table-column :label="t('common.actions')" width="112" align="right">
                  <template #default="{ row }">
                    <span class="ops" @click.stop>
                      <el-tooltip :content="row.rule ? t('generate.ruleNow', { rule: ruleTitle(row.rule), format: row.rule.format }) : t('generate.needRule')">
                        <el-button link :type="row.rule ? 'primary' : 'danger'" size="small" @click="openRuleDialog([row])">
                          {{ row.rule ? t("generate.changeRule") : t("generate.pickRule") }}
                        </el-button>
                      </el-tooltip>
                      <el-tooltip v-if="revokeOf([row])" :content="revokeTip(revokeOf([row])!)">
                        <span>
                          <el-button link type="danger" size="small" :disabled="!revokeOf([row])!.ok" @click="openRevoke([row])">{{ t("generate.revoke") }}</el-button>
                        </span>
                      </el-tooltip>
                    </span>
                  </template>
                </el-table-column>
              </el-table>
              <p v-else-if="!b.lines.length" v-show="!isFolded(b.bill_no)" class="muted no-lines">{{ t("generate.noLines") }}</p>
            </div>
            <p v-if="!shownBills.length" class="muted no-lines">{{ t("common.empty") }}</p>
          </el-tab-pane>

          <!-- 生成记录：只看；整次撤销 = 勾选这次的物料行后在底部撤销 -->
          <el-tab-pane :label="t('generate.tabJobs', { n: ctx.jobs.length })" name="jobs">
            <JobsTable :jobs="ctx.jobs" :segments="segments" @pick="pickJob" />
          </el-tab-pane>
        </el-tabs>
      </div>

      <!-- 底部操作栏：范围 → 批量（改规则、撤销、统一设置长度）→ 生成 -->
      <template #footer>
        <div v-if="ctx" class="bar">
          <div class="bar-scope">
            <span class="muted small">{{ t("generate.scope") }}</span>
            <span><b>{{ scopeText }}</b><el-button v-if="hasSel" link type="primary" class="ml" @click="clearSelection">{{ t("generate.clearSel") }}</el-button></span>
          </div>
          <el-button size="small" :disabled="!pickedRows.length" @click="openRuleDialog(pickedRows)">{{ t("generate.ruleForSelected") }}</el-button>
          <el-tooltip :content="batchRevoke ? revokeTip(batchRevoke) : t('generate.revokeSelHint')">
            <span>
              <el-button size="small" type="danger" plain :disabled="!batchRevoke?.ok" @click="openRevoke(pickedRows)">{{ t("generate.revokeSelected") }}</el-button>
            </span>
          </el-tooltip>
          <el-button size="small" :loading="busy === 'lengths'" @click="fillLengths">{{ t("generate.lengthBatch") }}</el-button>
          <span class="push" />
          <div v-loading="gen.loading" class="bar-gen">
            <span v-if="running" class="muted">{{ t("generate.runningHint") }}</span>
            <span v-else-if="gen.error" class="err">{{ gen.error }}</span>
            <span v-else-if="piDone" class="muted">{{ t("generate.nothingLeftPi") }}</span>
            <span v-else-if="nothingLeft" class="muted">{{ t("generate.nothingLeft") }}</span>
            <template v-else-if="gen.preview && !gen.preview.generatable">
              <el-tooltip placement="top">
                <template #content>
                  <div v-for="g in issueGroups" :key="g.code">
                    <b>{{ issueText(g.code) }}</b>
                    <template v-if="g.total">（{{ t("generate.issueLines", { n: num(g.total) }) }}）：{{ g.bills.map((b) => `${b.bill_no} #${b.seqs.join("、")}`).join("；") }}<span v-if="g.more"> …</span></template>
                  </div>
                </template>
                <span class="err">{{ t("generate.cannotGen", { n: badCount, why: issueGroups.map((g) => issueText(g.code)).join("、") }) }}</span>
              </el-tooltip>
              <el-button link type="primary" @click="showTodo">{{ t("generate.onlyThese") }}</el-button>
            </template>
            <template v-else-if="gen.preview">
              <span class="sn-mono">{{ gen.preview.start_sn }} ~ {{ gen.preview.end_sn }}</span>
              <span class="muted">{{ t("generate.wholeLines", { n: gen.preview.items.length }) }}</span>
              <el-checkbox v-if="quota > limits.default && quota <= limits.max" v-model="gen.unlocked" class="unlock">
                {{ t("generate.unlock", { limit: num(limits.default) }) }}
              </el-checkbox>
            </template>
          </div>
          <el-button type="primary" :disabled="!gen.preview?.generatable || gen.loading || !!limitState || !!running" @click="genDlg = true">
            {{ t("generate.genBtn", { n: num(gen.preview?.generatable ? gen.preview.qty : 0) }) }}
          </el-button>
        </div>
      </template>
    </el-drawer>

    <RuleDialog v-if="ctx" v-model="ruleDlg.open" :pi="ctx.pi_no" :lines="ruleDlg.lines" :options="ctx.rule_options" @saved="reloadCtx" />

    <GenConfirmDialog v-model="genDlg" :preview="gen.preview" :sync-threshold="limits.sync_threshold" :loading="busy === 'run'" @confirm="runGen" />

    <RevokeDialog v-if="ctx" ref="revokeDlg" :pi="ctx.pi_no" @done="afterRevoke" />

    <!-- PI / 客户规则：整张 PI 的默认规则，不常用 -->
    <PiRuleDialog v-if="ctx" v-model="piRuleOpen" :ctx="ctx" @saved="reloadCtx" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "@/api";
import { ApiError } from "@/api/http";
import { billMark, emptySel, genLimit, lineOrder, revokeRange, scopeBody, selectedLines, toggleBill, toggleLine } from "@/genScope.js";
import { useFactories } from "@/composables/useFactories";
import { useTime } from "@/composables/useTime";
import { BILL_FOLD_OVER, leaveStateLabel, suspendLabel } from "@/constants";
import type { OrderLine } from "@/types/api";
import PageHead from "@/components/PageHead.vue";
import RuleDialog from "@/views/generate/RuleDialog.vue";
import PiRuleDialog from "@/views/generate/PiRuleDialog.vue";
import RevokeDialog from "@/views/generate/RevokeDialog.vue";
import GenConfirmDialog from "@/views/generate/GenConfirmDialog.vue";
import GenProgress from "@/views/generate/GenProgress.vue";
import JobsTable from "@/views/generate/JobsTable.vue";
import { num, pct, ruleTitle as ruleTitleOf } from "@/views/generate/format";
import { useLineDrafts } from "@/views/generate/useLineDrafts";

type Tab = "TO_GENERATE" | "GENERATED";
type Action = "running" | "view" | "gen" | "pick";
type TagType = "" | "info" | "primary" | "success" | "warning" | "danger";
type LineRef = { bill_no: string; seq: number };
type Filter = "ALL" | "TODO" | "GEN" | "GENERATED";
type LineState = "TODO" | "GEN" | "GENERATED" | "DONE" | "NONE";
/** PI 流水上未撤销的物料行号段（ctx.segments，从流水最末往前）。 */
type Segment = LineRef & { gen_batch: string; qty: number; start_sn: string; end_sn: string; bad: number; ok: boolean };
type RevokeRange = { segs: Segment[]; ok: boolean; own: boolean };

const TABS: Tab[] = ["TO_GENERATE", "GENERATED"];
const FILTERS: Filter[] = ["ALL", "TODO", "GEN", "GENERATED"];
const PAGE = 20;
/** 各页签只列最近 3 页，更早的用搜索；生成中的 PI 排在「待生成」最前，不受此限（F-10 第 2 条）。 */
const RECENT_PAGES = 3;
const LIST_LIMIT = 500;
const STATUS_TAG: Record<string, TagType> = {
  GENERATING: "primary", CANNOT: "danger", NEED_RULE: "warning", TO_GENERATE: "primary", PARTIAL: "primary", GENERATED: "success",
};
const ACTION_TYPE: Record<Action, TagType> = { running: "primary", view: "", gen: "primary", pick: "primary" };
const LINE_STATE_TAG: Record<LineState, TagType> = { TODO: "danger", GEN: "primary", GENERATED: "success", DONE: "info", NONE: "info" };

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const { nameOf } = useFactories();
const { fmt: fmtTime } = useTime();
const issueText = (c: string) => t(`generate.issue.${c}`);
const sameLine = (a: LineRef, b: LineRef) => a.bill_no === b.bill_no && a.seq === b.seq;
const busy = ref("");

// ================================================================ PI 列表

const tab = ref<Tab>("TO_GENERATE");
const q = ref("");
const page = ref(1);
const listLoading = ref(false);
const list = ref<any>({ items: [], capped: false, summary: { quota: 0, pending_alloc: 0, unbatched: 0 }, synced_at: null });
const limited = computed(() => !q.value.trim() && list.value.items.length > PAGE * RECENT_PAGES);
const shown = computed<any[]>(() => (limited.value ? list.value.items.slice(0, PAGE * RECENT_PAGES) : list.value.items));
const paged = computed(() => shown.value.slice((page.value - 1) * PAGE, page.value * PAGE));
let listTimer: ReturnType<typeof setTimeout> | undefined;

async function loadList(silent = false) {
  clearTimeout(listTimer);
  if (!silent) listLoading.value = true;
  try {
    list.value = await api.genPis({ tab: tab.value, q: q.value.trim() });
    if (!silent) page.value = 1;
  } finally {
    listLoading.value = false;
  }
  // 有生成中的 PI 时定时刷新进度
  if (list.value.items.some((x: any) => x.running)) listTimer = setTimeout(() => loadList(true), 3000);
}

function reasonText(reasons: Record<string, number> | undefined) {
  const e = Object.entries(reasons || {});
  return e.map(([k, n]) => `${issueText(k)} ${n}`).join("、");
}

/** 生成页只看生成：没有额度、也不在生成中的 PI 一律为「已生成完」（分配、推送状态在分配页）。 */
function genStatus(row: any): string {
  if (row.status === "GENERATING" || row.status === "CANNOT" || row.status === "NEED_RULE") return row.status;
  if (row.quota > 0) return row.status === "PARTIAL" ? "PARTIAL" : "TO_GENERATE";
  return "GENERATED";
}

/** 行上按钮（F-10 第 3 条中与生成有关的部分，按顺序判断）。 */
function actionOf(row: any): Action {
  if (row.status === "GENERATING") return "running";
  if (row.status === "CANNOT") return "view";
  if (row.quota > 0) return row.status === "NEED_RULE" ? "pick" : "gen";
  return "view";
}

function openRow(row: any) {
  openDetail({ pi: row.pi_no }, actionOf(row) === "pick" ? "TODO" : "ALL");
}

// ================================================================ 详情

const detailPi = ref("");
const ctx = ref<any>(null);
const ctxLoading = ref(false);
const dtab = ref("lines");
const piRuleOpen = ref(false);

const allLines = computed<OrderLine[]>(() => (ctx.value ? ctx.value.bills.flatMap((b: any) => b.lines) : []));
/** 还有额度、但还不能生成的物料行（规则、附件项次、冻结等）按原因归并：每种原因列出涉及的物料行（一行可有多个原因）。 */
const todoGroups = computed(() => {
  const groups = new Map<string, OrderLine[]>();
  for (const l of allLines.value) {
    if (l.quota > 0) for (const code of l.issues || []) groups.set(code, [...(groups.get(code) || []), l]);
  }
  return [...groups.entries()].map(([code, lines]) => ({ code, lines }));
});
const limits = computed(() => ctx.value?.limits || { default: 10000, max: 500000, sync_threshold: 5000 });

async function openDetail(key: { pi?: string; bill_no?: string }, filter: Filter = "ALL") {
  detailPi.value = key.pi || key.bill_no || "";
  ctx.value = null;
  clearSelection();
  dtab.value = "lines";
  lineFilter.value = filter;
  foldAll(false);
  await loadCtx(key);
  if (!ctx.value) return;
  detailPi.value = ctx.value.pi_no;
  // 单据多时默认收起；从单据进来的展开该单
  if (shownBills.value.length > BILL_FOLD_OVER) foldAll(true);
  if (key.bill_no && isFolded(key.bill_no)) toggleFold(key.bill_no);
  if (route.query.pi !== ctx.value.pi_no) router.replace({ query: { pi: ctx.value.pi_no } });
  schedulePreview();
}

async function loadCtx(key: { pi?: string; bill_no?: string }) {
  ctxLoading.value = true;
  try {
    ctx.value = await api.genContext(key);
  } catch {
    if (!ctx.value) detailPi.value = "";
  } finally {
    ctxLoading.value = false;
  }
  const rj = ctx.value?.running_job;
  if (rj && rj.status === "RUNNING") startPoll(rj);
}

async function reloadCtx() {
  if (!detailPi.value) return;
  await loadCtx({ pi: detailPi.value });
  schedulePreview();
  loadList(true);
}

function closeDetail() {
  detailPi.value = "";
  ctx.value = null;
  stopPoll();
  if (route.query.pi || route.query.bill_no) router.replace({ query: {} });
}

const ruleTitle = (r: any) => ruleTitleOf(t, r);

// ---------------- 规则：物料行（PI 绑定 / 客户模板在 PiRuleDialog）

const ruleDlg = reactive({ open: false, lines: [] as OrderLine[] });

function openRuleDialog(lines: OrderLine[]) {
  ruleDlg.lines = lines;
  ruleDlg.open = true;
}

// ---------------- 范围：勾选单据（整单，按勾选顺序）或物料行（按点选顺序）；生成、批量改规则、批量撤销、统一设置长度共用

const sel = ref(emptySel());
const linesOf = (no: string): LineRef[] => (ctx.value?.bills.find((b: any) => b.bill_no === no)?.lines ?? []);
const hasSel = computed(() => !!(sel.value.bills.length || sel.value.lines.length));

function clearSelection() {
  sel.value = emptySel();
}
const onBill = (no: string, on: boolean) => (sel.value = toggleBill(sel.value, no, on, linesOf));
const onLine = (l: LineRef, on: boolean) => (sel.value = toggleLine(sel.value, l, on, linesOf));
const lineIdx = (l: LineRef) => lineOrder(sel.value, l);
const billCheck = (no: string) => billMark(sel.value, no, linesOf);
const isPicked = (l: LineRef) => lineIdx(l) >= 0 || billCheck(l.bill_no).order >= 0;
/** 选中的物料行（勾选的单据展开），按排号顺序。 */
const pickedRows = computed(() =>
  selectedLines(sel.value, linesOf)
    .map((r: LineRef) => allLines.value.find((l) => sameLine(l, r)))
    .filter(Boolean) as OrderLine[],
);
function onLineClick(row: OrderLine) {
  onLine(row, !isPicked(row));
}
function lineClass({ row }: { row: OrderLine }) {
  return isPicked(row) ? "picked" : "";
}
const scopeText = computed(() => {
  if (!hasSel.value) return t("generate.scopeWholePi");
  return sel.value.lines.length
    ? t("generate.scopeLinesN", { n: sel.value.lines.length })
    : t("generate.scopeBillsN", { n: sel.value.bills.length, bills: sel.value.bills.join("、") });
});
/** 未勾选时：生成取快照中还有额度的全部单据（按单据编号）。 */
const defaultBills = computed<string[]>(() => (ctx.value ? ctx.value.bills.filter((b: any) => b.in_snapshot && b.quota > 0).map((b: any) => b.bill_no) : []));
const genScope = computed(() => scopeBody(sel.value) ?? { scope: "BILLS", bills: defaultBills.value });
/** 未勾选且整张 PI 已没有额度：不用预演。 */
const piDone = computed(() => !!ctx.value && !hasSel.value && !defaultBills.value.length);
/** 统一设置长度的范围：所选单据 / 物料行；都没选 = 整张 PI。 */
const rangeBody = computed(() => {
  const b = scopeBody(sel.value);
  return b ? (b.scope === "LINES" ? { lines: b.lines } : { bills: b.bills }) : {};
});

// ---------------- 物料行：状态、筛选、号段；附件项次、长度的填写

function lineState(l: OrderLine): LineState {
  if (l.quota > 0) return (l.issues || []).length ? "TODO" : "GEN";
  if (l.generated_qty > l.allocated_qty) return "GENERATED";
  return l.generated_qty > 0 ? "DONE" : "NONE";
}
const lineFilter = ref<Filter>("ALL");
const FILTER_OK: Record<Filter, (l: OrderLine) => boolean> = {
  ALL: () => true,
  TODO: (l) => lineState(l) === "TODO",
  GEN: (l) => l.quota > 0,
  GENERATED: (l) => l.generated_qty > 0,
};
const filterCount = computed(() =>
  Object.fromEntries(FILTERS.map((f) => [f, allLines.value.filter(FILTER_OK[f]).length])) as Record<Filter, number>,
);
const shownBills = computed(() => {
  const ok = FILTER_OK[lineFilter.value];
  return (ctx.value?.bills ?? [])
    .map((b: any) => ({ ...b, shown: b.lines.filter(ok) }))
    .filter((b: any) => lineFilter.value === "ALL" || b.shown.length);
});
function showTodo() {
  lineFilter.value = "TODO";
  dtab.value = "lines";
  foldAll(false);
}

/** 收起的单据（只影响显示，不影响勾选）；打开 PI 时单据超过 BILL_FOLD_OVER 张默认全部收起，否则全部展开。 */
const folded = ref(new Set<string>());
const isFolded = (no: string) => folded.value.has(no);
function toggleFold(no: string) {
  const s = new Set(folded.value);
  if (!s.delete(no)) s.add(no);
  folded.value = s;
}
function foldAll(on: boolean) {
  folded.value = new Set(on ? shownBills.value.map((b: any) => b.bill_no as string) : []);
}

const segments = computed<Segment[]>(() => ctx.value?.segments ?? []);
/** 物料行已生成的号段（按流水先后）。 */
const segsOf = (l: LineRef) => segments.value.filter((s) => sameLine(s, l)).reverse();
/** 本次预演给该物料行排的号段。 */
const planOf = (l: LineRef) => (gen.preview?.generatable ? gen.preview.items.find((x: any) => sameLine(x, l)) : null);

const { DEFAULT_LENGTH, draftOf, setDraft, lockText, saveAttach, saveLength } = useLineDrafts(reloadCtx);
/** 统一设置长度：所选物料行 / 勾选的单据 / 都没选 = 整张 PI 内长度还是默认 000、还没有号的物料行一次设为同一个值。 */
async function fillLengths() {
  if (!ctx.value) return;
  const range = hasSel.value ? scopeText.value : t("generate.scopeWholePiLines", { pi: ctx.value.pi_no });
  let value: string;
  try {
    const r = await ElMessageBox.prompt(t("generate.lengthBatchHint", { range }), t("generate.lengthBatch"), {
      inputValue: DEFAULT_LENGTH,
      inputValidator: (v: string) => (!!(v || "").trim() && v.trim().length <= 64) || t("generate.lengthInvalid"),
    });
    value = (r as { value: string }).value.trim();
  } catch {
    return;
  }
  busy.value = "lengths";
  try {
    const r: any = await api.fillLengths({ pi_no: ctx.value.pi_no, value, ...rangeBody.value });
    ElMessage.success(t("generate.lengthBatchDone", { n: r.filled, v: r.value }));
    await reloadCtx();
  } finally {
    busy.value = "";
  }
}

// ---------------- 生成：勾选变化后自动预演（所选物料行整行生成）→ 确认 → 当场完成或后台进度 / 取消

const gen = reactive({ unlocked: false, loading: false, preview: null as any, error: "" });
const genDlg = ref(false);
const quota = computed(() => Number(gen.preview?.quota ?? 0));
/** 预演不能生成的原因按原因归并：每种原因一行，列出涉及的单据与序号（最多 ISSUE_SHOWN 个序号）。 */
const ISSUE_SHOWN = 20;
const issueGroups = computed(() => {
  const groups = new Map<string, { code: string; total: number; more: boolean; bills: { bill_no: string; seqs: number[] }[] }>();
  for (const x of gen.preview?.issues ?? []) {
    for (const code of x.issues as string[]) {
      let g = groups.get(code);
      if (!g) groups.set(code, (g = { code, total: 0, more: false, bills: [] }));
      if (!x.bill_no) continue;
      g.total += 1;
      if (g.total > ISSUE_SHOWN) {
        g.more = true;
        continue;
      }
      let b = g.bills.find((y) => y.bill_no === x.bill_no);
      if (!b) g.bills.push((b = { bill_no: x.bill_no, seqs: [] }));
      b.seqs.push(x.seq);
    }
  }
  return [...groups.values()];
});
const badCount = computed(() => (gen.preview?.issues ?? []).filter((x: any) => x.bill_no).length);
/** 所选范围都已生成完（只剩「没有可生成额度」）：不算错误。 */
const nothingLeft = computed(() => !!gen.preview && !gen.preview.generatable && issueGroups.value.every((g) => g.code === "QUOTA_EMPTY"));
const limitState = computed(() => (gen.preview?.generatable ? genLimit(gen.preview.qty, limits.value, gen.unlocked) : null));
let previewTimer: ReturnType<typeof setTimeout> | undefined;

function schedulePreview() {
  clearTimeout(previewTimer);
  previewTimer = setTimeout(runPreview, 400);
}

async function runPreview() {
  if (!ctx.value || running.value) return;
  if (piDone.value) return resetGen();
  gen.loading = true;
  gen.error = "";
  try {
    gen.preview = await api.genPreview({ pi_no: ctx.value.pi_no, ...genScope.value }, true);
  } catch (e) {
    gen.preview = null;
    gen.error = e instanceof ApiError ? e.message : String(e);
  } finally {
    gen.loading = false;
  }
}

function resetGen() {
  Object.assign(gen, { unlocked: false, preview: null, error: "" });
}

/** 生成完成：清空勾选、刷新详情；号到「SN 分配」页分配。 */
async function afterGenerated(qty: number) {
  ElMessage.success(t("generate.genDone", { n: num(qty) }));
  clearSelection();
  resetGen();
  await reloadCtx();
}

async function runGen() {
  const p = gen.preview;
  busy.value = "run";
  try {
    const job = await api.genRun(p.token);
    genDlg.value = false;
    if (job.status === "RUNNING") startPoll(job);
    else await afterGenerated(job.qty);
  } catch {
    // 预演失效等：重新预演
    genDlg.value = false;
    schedulePreview();
  } finally {
    busy.value = "";
  }
}

const running = ref<any>(null);
let pollTimer: ReturnType<typeof setInterval> | undefined;

function startPoll(job: any) {
  running.value = job;
  stopPoll(false);
  pollTimer = setInterval(async () => {
    try {
      const r = await api.genProgress(job.gen_batch);
      running.value = r;
      if (r.status !== "RUNNING") finishPoll(r);
    } catch {
      /* 下一次再试 */
    }
  }, 1500);
}

function stopPoll(clear = true) {
  if (pollTimer) clearInterval(pollTimer);
  pollTimer = undefined;
  if (clear) running.value = null;
}

async function finishPoll(r: any) {
  stopPoll();
  if (r.status === "SUCCESS") return afterGenerated(r.qty);
  if (r.status === "CANCELED") ElMessage.info(t("generate.genCanceled"));
  else ElMessage.error(r.error_msg || t("generate.job.FAILED"));
  resetGen();
  await reloadCtx();
}

async function cancelGen() {
  await ElMessageBox.confirm(t("generate.cancelConfirm"), t("generate.cancelGen"), { type: "warning" });
  busy.value = "cancel";
  try {
    const r = await api.cancelGen(running.value.gen_batch);
    running.value = { ...running.value, ...r, cancel_at: r.cancel_at || new Date().toISOString() };
  } finally {
    busy.value = "";
  }
}

// ---------------- 撤销：从流水最末往前撤到所选物料行为止（流水在其后的行一并撤），一个事务内完成

const revokeOf = (rows: LineRef[]): RevokeRange | null => revokeRange(segments.value, rows);
const batchRevoke = computed(() => revokeOf(pickedRows.value));
function revokeTip(r: RevokeRange) {
  if (running.value) return t("generate.revokeBusy");
  if (!r.ok) return r.own ? t("generate.revokeOwnBad") : t("generate.revokeLaterBad");
  return r.segs.length > 1 ? t("generate.revokeWithLater", { n: r.segs.length }) : t("generate.revokeTail");
}

const revokeDlg = ref<InstanceType<typeof RevokeDialog> | null>(null);

function openRevoke(rows: LineRef[]) {
  const r = revokeOf(rows);
  if (r?.ok) revokeDlg.value?.open(r.segs, rows);
}

async function afterRevoke() {
  clearSelection();
  await reloadCtx();
}

function pickJob(lines: LineRef[]) {
  sel.value = { bills: [], lines };
  lineFilter.value = "ALL";
  dtab.value = "lines";
  // 选中的物料行所在单据展开，免得勾上了看不见
  const s = new Set(folded.value);
  for (const l of lines) s.delete(l.bill_no);
  folded.value = s;
}

// ================================================================ 联动

watch(sel, () => {
  resetGen();
  schedulePreview();
});

watch(() => [route.query.pi, route.query.bill_no], ([pi, bill]) => {
  if (pi && pi !== detailPi.value) openDetail({ pi: String(pi) });
  else if (bill && !pi) openDetail({ bill_no: String(bill) });
});

onMounted(() => {
  loadList();
  const { pi, bill_no } = route.query;
  if (pi) openDetail({ pi: String(pi) });
  else if (bill_no) openDetail({ bill_no: String(bill_no) });
});

onUnmounted(() => {
  stopPoll();
  clearTimeout(listTimer);
  clearTimeout(previewTimer);
});
</script>

<style scoped>
.snap { display: inline-flex; gap: 8px; align-items: center; }
.kw { width: 260px; }
.summary { margin-left: auto; display: inline-flex; gap: 10px; align-items: center; }
.ml { margin-left: 6px; }
.small { font-size: 12px; }
.zero { color: var(--el-text-color-placeholder); }
.warn { color: var(--el-color-warning-dark-2); }
.err { color: var(--el-color-danger); }
.push { margin-left: auto; }
.row-state { display: inline-flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.mini-bar { width: 60px; height: 6px; border-radius: 3px; overflow: hidden; background: var(--el-fill-color-dark); display: inline-block; }
.mini-bar i { display: block; height: 100%; background: var(--el-color-primary); }
.dh { display: flex; flex-direction: column; gap: 6px; }
.dh-title { display: flex; gap: 12px; align-items: center; font-size: 16px; color: var(--app-text); }
.go { display: inline-flex; align-items: center; gap: 4px; padding: 3px 12px; border-radius: 14px; font-size: 13px; text-decoration: none;
  color: var(--el-color-success-dark-2); background: var(--el-color-success-light-9); border: 1px solid var(--el-color-success-light-5); }
.go:hover { background: var(--el-color-success-light-8); }
.kv { display: flex; flex-wrap: wrap; gap: 4px 18px; font-size: 12px; color: var(--app-muted); }
.kv b { color: var(--app-text); font-weight: 600; }
.drawer { display: flex; flex-direction: column; gap: 10px; }
.banner { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding: 8px 14px; border-radius: 8px; font-size: 13px; }
.banner.danger { background: var(--el-color-danger-light-9); color: var(--el-color-danger-dark-2); }
.todo { display: inline-flex; align-items: center; gap: 6px; }
.todo + .todo::before { content: "·"; color: var(--app-muted); margin-right: 4px; }
.dtabs :deep(.el-tabs__header) { margin-bottom: 8px; }
.toolbar { margin-bottom: 8px; }
.issue-tags, .plan, .ops { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.ops { justify-content: flex-end; flex-wrap: nowrap; }
.plan .sn-mono { color: var(--el-color-primary); }
.cell-input { display: block; }
.cell-input .missing :deep(.el-input__wrapper) { box-shadow: 0 0 0 1px var(--el-color-danger) inset; }
.bill { border: 1px solid var(--el-border-color-lighter); border-radius: 8px; margin-bottom: 10px; overflow: hidden; }
.bill-h { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 8px 12px; background: var(--el-fill-color-light); cursor: pointer; user-select: none; }
.bill-h:hover { background: var(--el-fill-color); }
.fold { color: var(--app-muted); transition: transform 0.15s; }
.fold.open { transform: rotate(90deg); }
.order { display: inline-flex; align-items: center; justify-content: center; min-width: 18px; height: 18px; padding: 0 4px; border-radius: 9px; font-size: 11px; color: #fff; background: var(--el-color-primary); }
.sel { display: inline-flex; align-items: center; gap: 4px; }
.lines :deep(tr.picked > td) { background: var(--el-color-primary-light-9); }
.lines :deep(.el-table__row) { cursor: pointer; }
.no-lines { padding: 8px 12px; margin: 0; }
.bar { display: flex; align-items: center; gap: 10px; text-align: left; }
.bar-scope { display: flex; flex-direction: column; gap: 2px; min-width: 160px; margin-right: 6px; font-size: 13px; }
.bar-gen { display: flex; flex-direction: column; align-items: flex-end; gap: 2px; font-size: 12px; max-width: 420px; min-height: 32px; justify-content: center; }
.unlock { height: 18px; }
.unlock :deep(.el-checkbox__label) { font-size: 12px; color: var(--el-color-warning-dark-2); }
</style>

<style>
.gen-drawer .el-drawer__header { margin-bottom: 8px; align-items: flex-start; }
.gen-drawer .el-drawer__footer { border-top: 1px solid var(--el-border-color); background: var(--el-fill-color-lighter); padding: 10px 20px; }
</style>
