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
          <!-- PI 下单据的最新修改日期；列表与订单页同一排序（新的在前） -->
          <el-table-column :label="t('orders.modifyDate')" width="160">
            <template #default="{ row }"><span class="num">{{ row.modify_time || "—" }}</span></template>
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
    <el-drawer :model-value="!!detailPi" size="min(1240px, 96vw)" class="gen-drawer" @close="closeDetail">
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
                <span class="muted">PO {{ b.po || "—" }} · {{ b.org_name || nameOf(b.org_code) }}<template v-if="b.modify_time"> · {{ t("orders.modifyDate") }} {{ b.modify_time }}</template></span>
                <el-tag v-if="!b.in_snapshot" size="small" type="info">{{ t("generate.notInSnapshot") }}</el-tag>
                <el-tag v-if="b.state.suspend" size="small" type="warning">{{ suspendLabel(b.state.suspend) }}</el-tag>
                <el-tag v-if="b.state.leave_state" size="small" type="danger">{{ leaveStateLabel(b.state.leave_state) }}</el-tag>
                <el-tag v-if="b.state.pi_frozen" size="small" type="danger">{{ t("generate.piFrozen") }}</el-tag>
                <el-tag v-if="b.state.open_alerts" size="small" type="warning">{{ t("generate.openAlerts", { n: b.state.open_alerts }) }}</el-tag>
                <span class="push muted num">{{ t("generate.billCounts", { total: num(b.total_qty), gen: num(b.generated_qty), quota: num(b.quota) }) }}</span>
              </div>
              <!-- 列宽按抽屉宽度排满、不出横向滚动条：数字列收窄，号段起止分两行 -->
              <el-table v-if="b.shown.length" v-show="!isFolded(b.bill_no)" :data="b.shown" size="small" class="lines" :row-class-name="lineClass" @row-click="onLineClick">
                <el-table-column width="44">
                  <template #default="{ row }">
                    <span class="sel" @click.stop>
                      <el-checkbox :model-value="isPicked(row)" @change="(v: any) => onLine(row, !!v)" />
                      <span v-if="lineIdx(row) >= 0" class="order">{{ lineIdx(row) + 1 }}</span>
                    </span>
                  </template>
                </el-table-column>
                <el-table-column :label="t('common.billSeq')" prop="seq" width="48" />
                <el-table-column :label="t('common.attachNo')" width="88">
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
                <el-table-column :label="t('common.material')" min-width="88" show-overflow-tooltip>
                  <template #default="{ row }">
                    {{ row.material_code || t("generate.noMaterial") }}
                  </template>
                </el-table-column>
                <el-table-column :label="t('common.length')" width="76">
                  <template #default="{ row }">
                    <span v-if="row.length_source === 'K3'">{{ row.length }}</span>
                    <!-- 金蝶米数含中文：须手动改成不含中文的米数才能生成（标红，占位显示金蝶原值） -->
                    <el-tooltip v-else :content="row.edit_lock ? lockText(row) : lengthTip(row)">
                      <span class="cell-input" @click.stop>
                        <el-input :model-value="draftOf(row, 'length')" size="small" maxlength="64" :disabled="!!row.edit_lock"
                          :class="{ missing: !row.edit_lock && lengthMissing(row) }" :placeholder="lengthPlaceholder(row)"
                          @update:model-value="(v: string) => setDraft(row, 'length', v)" @change="saveLength(row)" />
                      </span>
                    </el-tooltip>
                  </template>
                </el-table-column>
                <el-table-column :label="t('common.unit')" width="52" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.unit || "—" }}</template>
                </el-table-column>
                <el-table-column :label="t('common.qty')" align="right" width="58">
                  <template #default="{ row }"><span class="num">{{ num(row.qty) }}</span></template>
                </el-table-column>
                <el-table-column :label="t('generate.generated')" align="right" width="58">
                  <template #default="{ row }">
                    <el-tooltip :content="t('generate.ownTip', { own: num(row.own_generated_qty), gen: num(row.generated_qty) })">
                      <span class="num">{{ num(row.generated_qty) }}</span>
                    </el-tooltip>
                  </template>
                </el-table-column>
                <el-table-column :label="t('generate.allocated')" align="right" width="58">
                  <template #default="{ row }"><span class="num">{{ num(row.allocated_qty) }}</span></template>
                </el-table-column>
                <el-table-column :label="t('generate.quota')" align="right" width="58">
                  <template #default="{ row }"><span class="num" :class="{ zero: !row.quota }">{{ num(row.quota) }}</span></template>
                </el-table-column>
                <el-table-column :label="t('common.status')" min-width="88">
                  <template #default="{ row }">
                    <div v-if="row.quota && row.issues?.length" class="issue-tags">
                      <el-tag v-for="i in row.issues" :key="i" size="small" type="danger" effect="plain">{{ issueText(i) }}</el-tag>
                    </div>
                    <el-tag v-else size="small" :type="LINE_STATE_TAG[lineState(row)]" disable-transitions>{{ t(`generate.lineState.${lineState(row)}`) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column :label="t('generate.snRange')" min-width="140">
                  <template #default="{ row }">
                    <div v-for="s in segsOf(row)" :key="s.gen_batch" class="sn-mono seg">{{ s.start_sn }}<br />~ {{ s.end_sn }}</div>
                    <div v-if="planOf(row)" class="sn-mono seg plan">{{ planOf(row).start_sn }}<br />~ {{ planOf(row).end_sn }}</div>
                    <span v-if="!segsOf(row).length && !planOf(row)" class="zero">—</span>
                  </template>
                </el-table-column>
                <el-table-column :label="t('common.actions')" width="80" align="right">
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
                      <!-- 待处理页签中还没有号的行：撤销配置 -->
                      <el-tooltip v-else-if="lineFilter === 'TODO' && !row.edit_lock" :content="hasConfig(row) ? t('generate.revokeConfigTip') : t('generate.revokeConfigNone')">
                        <span>
                          <el-button link type="danger" size="small" :disabled="!hasConfig(row)" @click="revokeConfig([row])">{{ t("generate.revoke") }}</el-button>
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

      <!-- 底部操作栏：范围 → 批量（改规则、撤销）→ 生成；三个页签按钮相同，「撤销（所选）」在待处理页签撤销配置、其他页签撤销生成 -->
      <template #footer>
        <div v-if="ctx" class="bar">
          <div class="bar-scope">
            <span class="muted small">{{ t("generate.scope") }}</span>
            <span><b>{{ scopeText }}</b><el-button v-if="hasSel" link type="primary" class="ml" @click="clearSelection">{{ t("generate.clearSel") }}</el-button></span>
          </div>
          <el-button size="small" :disabled="!pickedRows.length" @click="openRuleDialog(pickedRows)">{{ t("generate.ruleForSelected") }}</el-button>
          <el-tooltip :content="selRevoke.tip">
            <span>
              <el-button size="small" type="danger" plain :disabled="!selRevoke.ok" :loading="busy === 'config'" @click="revokeSelected">{{ t("generate.revokeSelected") }}</el-button>
            </span>
          </el-tooltip>
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
                <span class="err">{{ t("generate.cannotGen", { n: badCount }) }}</span>
              </el-tooltip>
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
import { leaveStateLabel, suspendLabel } from "@/constants";
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
/** 物料行页签按处理步骤：待处理 → 可生成 → 已生成 */
type Filter = "TODO" | "GEN" | "GENERATED";
type LineState = "TODO" | "GEN" | "GENERATED" | "DONE" | "NONE";
/** PI 流水上未撤销的物料行号段（ctx.segments，从流水最末往前）。 */
type Segment = LineRef & { gen_batch: string; qty: number; start_sn: string; end_sn: string; bad: number; ok: boolean };
type RevokeRange = { segs: Segment[]; ok: boolean; own: boolean };

const TABS: Tab[] = ["TO_GENERATE", "GENERATED"];
const FILTERS: Filter[] = ["TODO", "GEN", "GENERATED"];
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
  openDetail({ pi: row.pi_no });
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

async function openDetail(key: { pi?: string; bill_no?: string }) {
  detailPi.value = key.pi || key.bill_no || "";
  ctx.value = null;
  clearSelection();
  dtab.value = "lines";
  foldAll(false);
  await loadCtx(key);
  if (!ctx.value) return;
  // 打开时停在第一个还有物料行的步骤
  lineFilter.value = FILTERS.find((f) => filterCount.value[f] > 0) ?? "TODO";
  detailPi.value = ctx.value.pi_no;
  // 单据默认全部收起；从单据进来的展开该单
  foldAll(true);
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
  const before = lineTabs();
  await loadCtx({ pi: detailPi.value });
  nextStep(before);
  schedulePreview();
  loadList(true);
}

const lineKey = (l: LineRef) => `${l.bill_no}#${l.seq}`;
/** 各物料行当前落在哪些页签（刷新前记下，刷新后比较哪些行离开了当前页签）。 */
function lineTabs(): Map<string, Filter[]> {
  return new Map(allLines.value.map((l) => [lineKey(l), FILTERS.filter((f) => FILTER_OK[f](l))]));
}

/** 页签跟着数据走：每次操作刷新后，
 *  1. 有物料行离开了当前页签（如补齐规则 / 附件项次 / 米数后变为可生成、生成后变为已生成、撤销后回到可生成），
 *     转到这些行现在所在的、排在当前页签之后的第一个页签（待处理 → 可生成 → 已生成，到头再从前面找）；
 *  2. 没有行移动但当前页签已空，转到下一个还有物料行的页签。
 *  转过去时提示，并展开这些行所在的单据、回到顶部。 */
function nextStep(before: Map<string, Filter[]>) {
  if (!ctx.value || dtab.value !== "lines") return;
  const cur = lineFilter.value;
  const i = FILTERS.indexOf(cur);
  const order = [...FILTERS.slice(i + 1), ...FILTERS.slice(0, i)];
  const moved = allLines.value.filter((l) => before.get(lineKey(l))?.includes(cur) && !FILTER_OK[cur](l));
  const to = moved.length
    ? order.find((f) => moved.some((l) => FILTER_OK[f](l)))
    : filterCount.value[cur] > 0
      ? undefined
      : order.find((f) => filterCount.value[f] > 0);
  if (!to) return;
  const toName = t(`generate.lineState.${to}`);
  ElMessage.info(
    moved.length
      ? t("generate.autoFollow", { n: moved.length, to: toName })
      : t("generate.autoNext", { from: t(`generate.lineState.${cur}`), to: toName }),
  );
  lineFilter.value = to;
  if (moved.length) {
    const s = new Set(folded.value);
    for (const l of moved) s.delete(l.bill_no);
    folded.value = s;
  }
  document.querySelector(".gen-drawer .el-drawer__body")?.scrollTo({ top: 0 });
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

// ---------------- 范围：勾选单据（整单，按勾选顺序）或物料行（按点选顺序）；生成、批量改规则、批量撤销、统一设置米数共用

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
/** 未勾选时：生成取快照中还有额度的全部单据，排号仍按单据编号从小到大（页面显示按修改日期排，不影响排号）。 */
const defaultBills = computed<string[]>(() =>
  ctx.value ? ctx.value.bills.filter((b: any) => b.in_snapshot && b.quota > 0).map((b: any) => b.bill_no as string).sort() : [],
);
const genScope = computed(() => scopeBody(sel.value) ?? { scope: "BILLS", bills: defaultBills.value });
/** 未勾选且整张 PI 已没有额度：不用预演。 */
const piDone = computed(() => !!ctx.value && !hasSel.value && !defaultBills.value.length);

// ---------------- 物料行：状态、筛选、号段；附件项次、米数的填写

function lineState(l: OrderLine): LineState {
  if (l.quota > 0) return (l.issues || []).length ? "TODO" : "GEN";
  if (l.generated_qty > l.allocated_qty) return "GENERATED";
  return l.generated_qty > 0 ? "DONE" : "NONE";
}
const lineFilter = ref<Filter>("TODO");
/** 每条物料行至少落在一个页签：还有额度的按能否生成分到待处理 / 可生成；有号或已没有额度的在已生成（部分生成的行两边都有）。 */
const FILTER_OK: Record<Filter, (l: OrderLine) => boolean> = {
  TODO: (l) => lineState(l) === "TODO",
  GEN: (l) => lineState(l) === "GEN",
  GENERATED: (l) => l.generated_qty > 0 || l.quota <= 0,
};
const filterCount = computed(() =>
  Object.fromEntries(FILTERS.map((f) => [f, allLines.value.filter(FILTER_OK[f]).length])) as Record<Filter, number>,
);
const shownBills = computed(() => {
  const ok = FILTER_OK[lineFilter.value];
  return (ctx.value?.bills ?? [])
    .map((b: any) => ({ ...b, shown: b.lines.filter(ok) }))
    // 已不在快照的单据（没有物料行、只有号）列在「已生成」
    .filter((b: any) => b.shown.length || (!b.lines.length && lineFilter.value === "GENERATED"));
});
function showTodo() {
  lineFilter.value = "TODO";
  dtab.value = "lines";
  foldAll(false);
}

/** 收起的单据（只影响显示，不影响勾选）；每次打开 PI 时默认全部收起（各页签的单据都收起）。 */
const folded = ref(new Set<string>());
const isFolded = (no: string) => folded.value.has(no);
function toggleFold(no: string) {
  const s = new Set(folded.value);
  if (!s.delete(no)) s.add(no);
  folded.value = s;
}
function foldAll(on: boolean) {
  folded.value = new Set(on ? (ctx.value?.bills ?? []).map((b: any) => b.bill_no as string) : []);
}

const segments = computed<Segment[]>(() => ctx.value?.segments ?? []);
/** 物料行已生成的号段（按流水先后）。 */
const segsOf = (l: LineRef) => segments.value.filter((s) => sameLine(s, l)).reverse();
/** 本次预演给该物料行排的号段。 */
const planOf = (l: LineRef) => (gen.preview?.generatable ? gen.preview.items.find((x: any) => sameLine(x, l)) : null);

const { draftOf, setDraft, lockText, saveAttach, saveLength, lengthTip, lengthMissing, lengthPlaceholder } =
  useLineDrafts(reloadCtx);
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

// ---------------- 撤销配置（待处理页签）：还没有号的物料行，物料行选定的规则、手填的附件项次和米数恢复为未配置

/** 物料行在本系统做过配置：物料行选定了规则，或手填了附件项次 / 米数。 */
const hasConfig = (l: OrderLine) => l.rule?.source === "LINE" || l.attach_manual != null || l.length_manual != null;
/** 底部批量撤销配置的对象：所选物料行中在待处理页签、还没有号、做过配置的。 */
const configRows = computed(() => pickedRows.value.filter((l) => FILTER_OK.TODO(l) && !l.edit_lock && hasConfig(l)));

/** 底部「撤销（所选）」：按钮名称各页签相同；待处理页签撤销所选行的配置，可生成 / 已生成页签撤销生成。 */
const selRevoke = computed(() => {
  if (lineFilter.value === "TODO") {
    const n = configRows.value.length;
    return { ok: n > 0, tip: n ? t("generate.revokeConfigSelN", { n }) : t("generate.revokeConfigSelHint") };
  }
  const r = batchRevoke.value;
  return { ok: !!r?.ok, tip: r ? revokeTip(r) : t("generate.revokeSelHint") };
});
function revokeSelected() {
  if (lineFilter.value === "TODO") revokeConfig(configRows.value);
  else openRevoke(pickedRows.value);
}

async function revokeConfig(rows: OrderLine[]) {
  if (!ctx.value || !rows.length) return;
  try {
    await ElMessageBox.confirm(t("generate.revokeConfigConfirm", { n: rows.length }), t("generate.revokeConfigTitle"), { type: "warning" });
  } catch {
    return;
  }
  busy.value = "config";
  try {
    const r: any = await api.revokeConfig(ctx.value.pi_no, rows.map((l) => ({ bill_no: l.bill_no, seq: l.seq })));
    ElMessage.success(t("generate.revokeConfigDone", { n: r.revoked }));
    clearSelection();
    await reloadCtx();
  } finally {
    busy.value = "";
  }
}

function pickJob(lines: LineRef[]) {
  sel.value = { bills: [], lines };
  lineFilter.value = "GENERATED";
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
.issue-tags, .ops { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.ops { justify-content: flex-end; gap: 2px 6px; }
.ops :deep(.el-button + .el-button) { margin-left: 0; }
/* 号段起止分两行，SN 很长时也在格内折行，不撑出横向滚动条 */
.seg { word-break: break-all; line-height: 1.4; }
.plan { color: var(--el-color-primary); }
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
