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
        <!-- 生产组织筛选：系统管理员、授权多个组织的账户才有；只在页面上按 PI 的生产组织筛（不改接口），详情仍列出本 PI 授权范围内的全部单据 -->
        <FactorySelect v-if="store.multiScope" v-model="factory" scoped clearable :placeholder="t('app.allFactories')" @update:model-value="page = 1" />
        <span class="muted summary">
          {{ t("generate.summary", { quota: num(list.summary.quota) }) }}
          <router-link v-if="list.summary.pending_alloc" to="/allocate">{{ t("generate.summaryPending", { n: num(list.summary.pending_alloc) }) }}</router-link>
        </span>
      </div>

      <div class="fill-body">
        <!-- 列宽按内容自适应（listW），单元格不截断、不折行；超出容器时横向滚动；点行打开 PI 详情 -->
        <el-table v-loading="listLoading" :data="paged" size="small" row-key="pi_no" height="100%" class="pi-list" :empty-text="t('common.empty')"
          @row-click="(row: any) => openRow(row)">
          <el-table-column label="PI" :min-width="listW.pi" fixed>
            <template #default="{ row }">
              <b>{{ row.pi_no }}</b>
              <el-tag v-if="row.frozen_bills" size="small" type="danger" class="ml">{{ frozenText(row) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('common.customer')" :min-width="listW.customer">
            <template #default="{ row }">{{ row.customer_code || "—" }}</template>
          </el-table-column>
          <el-table-column :label="t('generate.bills')" align="right" :min-width="listW.bills">
            <template #default="{ row }"><span class="num">{{ num(row.bills) }}</span></template>
          </el-table-column>
          <el-table-column :label="t('generate.orderTotal')" align="right" :min-width="listW.total">
            <template #default="{ row }"><span class="num">{{ num(row.total_qty) }}</span></template>
          </el-table-column>
          <el-table-column :label="t('generate.generated')" align="right" :min-width="listW.generated">
            <template #default="{ row }"><span class="num">{{ num(row.generated_qty) }}</span></template>
          </el-table-column>
          <el-table-column :label="t('generate.transferredIn')" align="right" :min-width="listW.transferredIn">
            <template #default="{ row }"><span class="num" :class="{ zero: !row.transferred_in }">{{ num(row.transferred_in) }}</span></template>
          </el-table-column>
          <el-table-column :label="t('generate.quota')" align="right" :min-width="listW.quota">
            <template #default="{ row }"><span class="num" :class="{ zero: !row.quota }">{{ num(row.quota) }}</span></template>
          </el-table-column>
          <el-table-column :label="t('generate.pendingAlloc')" align="right" :min-width="listW.pendingAlloc">
            <template #default="{ row }">
              <router-link v-if="row.pending_alloc" class="num" :to="{ path: '/allocate', query: { pi: row.pi_no } }" @click.stop>{{ num(row.pending_alloc) }}</router-link>
              <span v-else class="num zero">0</span>
            </template>
          </el-table-column>
          <!-- 一张 PI 可有多个生产组织：各组织名称整段不断开，放不下时在组织之间换行 -->
          <el-table-column :label="t('common.factory')" :min-width="listW.factory" class-name="orgs-cell">
            <template #default="{ row }">
              <span v-for="(o, i) in orgNames(row)" :key="i" class="org">{{ o }}<template v-if="i < row.orgs.length - 1">、</template></span>
            </template>
          </el-table-column>
          <!-- PI 下单据的最新修改日期；列表与订单页同一排序（新的在前） -->
          <el-table-column :label="t('orders.modifyDate')" :min-width="listW.modify">
            <template #default="{ row }"><span class="num">{{ row.modify_time || "—" }}</span></template>
          </el-table-column>
          <!-- 不设状态列：生成中在按钮上显示进度（PRD F-10 第 2 条「行上显示进度」） -->
          <el-table-column :label="t('common.actions')" :min-width="listW.action" align="right" fixed="right">
            <template #default="{ row }">
              <el-button size="small" :type="ACTION_TYPE[actionOf(row)]" :plain="actionOf(row) !== 'gen'"
                :loading="actionOf(row) === 'running'" @click.stop="openRow(row)">
                {{ actionText(row) }}
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

    <!-- 详情：只管生成与删号。头部（数字、去分配）→ 物料行 / 生成记录 → 底部操作栏（范围、批量、生成）；不能生成的原因看物料行状态列 -->
    <el-drawer :model-value="!!detailPi" size="min(1240px, 96vw)" class="gen-drawer" @close="closeDetail">
      <template #header>
        <div v-if="ctx" class="dh">
          <div class="dh-title">
            <b>PI {{ ctx.pi_no }}</b>
            <span class="muted">{{ t("common.customer") }} {{ ctx.customer_code || "—" }}</span>
            <span class="push" />
            <el-button size="small" type="success" plain :disabled="!ctx.summary.pending_alloc" @click="goAllocate">
              {{ t("generate.goAllocate") }}<el-icon class="el-icon--right"><Right /></el-icon>
            </el-button>
          </div>
          <div class="kv">
            <span>{{ t("generate.orderTotal") }} <b class="num">{{ num(ctx.summary.total_qty) }}</b></span>
            <span>{{ t("generate.generated") }} <b class="num">{{ num(ctx.summary.generated_qty) }}</b></span>
            <span>{{ t("generate.quota") }} <b class="num">{{ num(ctx.summary.quota) }}</b></span>
            <span v-if="ctx.summary.transferred_in">{{ t("generate.transferredIn") }} <b class="num">{{ num(ctx.summary.transferred_in) }}</b></span>
            <span v-if="ctx.summary.suspended" class="warn">{{ t("generate.suspendedQty", { n: num(ctx.summary.suspended) }) }}</span>
            <span>{{ t("generate.nextSeq") }} <b class="num">{{ ctx.counter.next_seq }}</b></span>
            <el-button v-if="ctx.gaps?.count" link type="warning" size="small" @click="segDlg?.open({})">
              {{ t("generate.gapsLink", { n: num(ctx.gaps.count), qty: num(ctx.gaps.total) }) }}
            </el-button>
            <el-button v-if="ctx.gaps?.reserved_total" link type="primary" size="small" @click="segDlg?.open({ tab: 'reserved' })">
              {{ t("generate.reservedLink", { qty: num(ctx.gaps.reserved_total) }) }}
            </el-button>
          </div>
        </div>
      </template>

      <div v-if="ctx" v-loading="ctxLoading" class="drawer">
        <!-- 生成中：进度、取消 -->
        <GenProgress v-if="running" :running="running" :canceling="busy === 'cancel'" @cancel="cancelGen" />

        <!-- 只有三个页签：待处理 / 可生成 / 已生成（按处理步骤筛物料行）；页签名后不带数字；
             全部展开 / 全部收起与页签同一行（叠在页签头右端，页签头底线仍贯通整行） -->
        <div class="tabs-row">
          <el-tabs v-model="lineFilter" class="dtabs">
            <el-tab-pane v-for="f in FILTERS" :key="f" :label="t(`generate.filter.${f}`)" :name="f" />
          </el-tabs>
          <span class="fold-ops">
            <!-- 全选：勾选当前页签列出的全部可勾选单据（与逐张勾选单据头相同，整单），再点取消这些单据的勾选 -->
            <el-checkbox v-bind="allBillsMark" :disabled="!pickableBills.length" class="all-bills" @change="(v: any) => onAllBills(!!v)">{{ t("generate.selectAllBills") }}</el-checkbox>
            <el-button link type="primary" size="small" @click="foldAll(false)">{{ t("common.expandAll") }}</el-button>
            <el-button link type="primary" size="small" @click="foldAll(true)">{{ t("common.collapseAll") }}</el-button>
          </span>
        </div>
        <!-- 已分配的单据删号后（或数量增加后）还有额度的，照常勾选生成 -->
        <div v-for="b in shownBills" :key="b.bill_no" class="bill">
          <!-- 点单据头展开 / 收起物料行；勾选框只管勾选 -->
          <div class="bill-h" @click="toggleFold(b.bill_no)">
            <el-icon class="fold" :class="{ open: !isFolded(b.bill_no) }"><ArrowRight /></el-icon>
            <!-- 勾选框跟随勾选状态（model-value 受控；Element Plus 的 checked 只是初始值，清空 / 全选后会对不上） -->
            <span @click.stop><el-checkbox :model-value="billCheck(b.bill_no).checked" :indeterminate="billCheck(b.bill_no).indeterminate" :disabled="!billPickable(b)" @change="(v: any) => onBill(b.bill_no, !!v)" /></span>
            <span v-if="billIdx(b.bill_no) >= 0" class="order">{{ billIdx(b.bill_no) + 1 }}</span>
            <b>{{ b.bill_no }}</b>
            <!-- 单据所属生产组织名称（小号加粗），在号段标签之前 -->
            <b v-if="billOrg(b)" class="bill-org">{{ billOrg(b) }}</b>
            <!-- 单据号段：已用号段（本单据现有的号）、空闲号段（本单据删号腾出），没有时不显示，点开看明细 -->
            <span v-if="b.segments?.use_count" class="chip use" @click.stop="segDlg?.open({ bill: b.bill_no, tab: 'use' })">
              {{ t("generate.segUse", { n: num(b.segments.use_count), qty: num(b.segments.use_qty) }) }}
            </span>
            <span v-if="b.segments?.free_count" class="chip free" @click.stop="segDlg?.open({ bill: b.bill_no, tab: 'free' })">
              {{ t("generate.segFree", { n: num(b.segments.free_count), qty: num(b.segments.free_qty) }) }}
            </span>
            <el-tag v-if="b.alloc_state === 'ALLOCATED'" size="small" type="info">{{ t("allocate.state.ALLOCATED") }}</el-tag>
            <el-tag v-if="!b.in_snapshot" size="small" type="info">{{ t("generate.notInSnapshot") }}</el-tag>
            <el-tag v-if="b.state.suspend" size="small" type="warning">{{ suspendLabel(b.state.suspend) }}</el-tag>
            <el-tag v-if="b.state.leave_state" size="small" type="danger">{{ leaveStateLabel(b.state.leave_state) }}</el-tag>
            <el-tag v-if="b.state.pi_frozen" size="small" type="danger">{{ t("generate.piFrozen") }}</el-tag>
            <el-tag v-if="b.state.open_alerts" size="small" type="warning">{{ t("generate.openAlerts", { n: b.state.open_alerts }) }}</el-tag>
            <span class="push muted num">{{ t("generate.billCounts", { total: num(b.total_qty), gen: num(b.generated_qty), quota: num(b.quota) }) }}</span>
          </div>
          <!-- 列依次为附件项次、物料编码、ERP行号、客户 P/N、米数、产品单位、数量、已生成、已分配、可生成、状态、开始 / 结束 SN 号、操作；
               数字列收窄；行按附件项次从小到大（shownBills） -->
          <el-table v-if="b.shown.length" v-show="!isFolded(b.bill_no)" :data="b.shown" size="small" class="lines" :row-class-name="lineClass" @row-click="onLineClick">
            <el-table-column width="44">
              <template #default="{ row }">
                <span class="sel" @click.stop>
                  <el-checkbox :model-value="isPicked(row)" @change="(v: any) => pickLine(row, !!v)" />
                  <span v-if="genIdx(row) >= 0" class="order">{{ genIdx(row) + 1 }}</span>
                </span>
              </template>
            </el-table-column>
            <el-table-column :label="t('common.attachNo')" width="88">
              <template #default="{ row }">
                <!-- 金蝶有值也可改（页面值优先，留空即改回金蝶值）；生成时所选物料行不分单据按附件项次从小到大排号 -->
                <span class="cell-input attach-cell" @click.stop>
                  <el-input :model-value="draftOf(row, 'attach')" size="small" maxlength="64" :disabled="!!row.edit_lock"
                    :class="{ missing: !row.edit_lock && !draftOf(row, 'attach') && !row.attach_k3 }"
                    :placeholder="row.attach_k3 ? t('generate.attachK3Placeholder', { v: row.attach_k3 }) : t('generate.attachRequired')"
                    @update:model-value="(v: string) => setDraft(row, 'attach', v)" @change="saveAttach(row)" />
                </span>
              </template>
            </el-table-column>
            <el-table-column :label="t('common.material')" min-width="88" show-overflow-tooltip>
              <template #default="{ row }">
                {{ row.material_code || t("generate.noMaterial") }}
              </template>
            </el-table-column>
            <el-table-column :label="t('common.lineNo')" prop="seq" width="72" />
            <!-- 客户 P/N：金蝶只显示字段，不参与生成；完整显示、单行不折行（不截断、无省略号），
                 列宽按本 PI 全部物料行的值量出（pnW），放不下时单据物料行表格左右滑动 -->
            <el-table-column :label="t('orders.customerPn')" :min-width="pnW" class-name="nowrap-cell" label-class-name="nowrap-cell">
              <template #default="{ row }">{{ row.customer_pn || "—" }}</template>
            </el-table-column>
            <el-table-column :label="t('common.length')" width="76">
              <template #default="{ row }">
                <!-- 金蝶米数原样显示、原样使用，可改（留空即改回金蝶值）；金蝶为空时规则带米数须填写（标红） -->
                <span class="cell-input" @click.stop>
                  <el-input :model-value="draftOf(row, 'length')" size="small" maxlength="64" :disabled="!!row.edit_lock"
                    :class="{ missing: !row.edit_lock && lengthMissing(row) }" :placeholder="lengthPlaceholder(row)"
                    @update:model-value="(v: string) => setDraft(row, 'length', v)" @change="saveLength(row)" />
                </span>
              </template>
            </el-table-column>
            <el-table-column :label="t('common.unit')" width="64">
              <template #default="{ row }">
                <!-- 金蝶产品单位原样显示、原样使用（含中文也照用），可改（留空即改回金蝶值）；金蝶为空时规则带单位须填写（标红） -->
                <span class="cell-input" @click.stop>
                  <el-input :model-value="draftOf(row, 'unit')" size="small" maxlength="64" :disabled="!!row.edit_lock"
                    :class="{ missing: !row.edit_lock && unitMissing(row) }" :placeholder="unitPlaceholder(row)"
                    @update:model-value="(v: string) => setDraft(row, 'unit', v)" @change="saveUnit(row)" />
                </span>
              </template>
            </el-table-column>
            <el-table-column :label="t('common.qty')" align="right" width="58">
              <template #default="{ row }"><span class="num">{{ num(row.qty) }}</span></template>
            </el-table-column>
            <el-table-column :label="t('generate.generated')" align="right" width="58">
              <template #default="{ row }"><span class="num">{{ num(row.generated_qty) }}</span></template>
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
            <!-- 开始 / 结束 SN 号：物料行现有号中最小、最大的完整 SN（与分配页物料行同一口径）；还没有号为「—」 -->
            <el-table-column :label="t('generate.startSn')" :min-width="snW">
              <template #default="{ row }"><span class="sn-mono">{{ row.start_sn || "—" }}</span></template>
            </el-table-column>
            <el-table-column :label="t('generate.endSn')" :min-width="snW">
              <template #default="{ row }"><span class="sn-mono">{{ row.end_sn || "—" }}</span></template>
            </el-table-column>
            <!-- 操作列按页签：待处理只有选规则；可生成有选规则和（有号时）删号；已生成只有删号（状态不限，已分配的也可删） -->
            <el-table-column :label="t('common.actions')" width="80" align="right">
              <template #default="{ row }">
                <span class="ops" @click.stop>
                  <el-button v-if="lineFilter !== 'GENERATED'" link :type="row.rule ? 'primary' : 'danger'" size="small" @click="openRuleDialog([row])">
                    {{ row.rule ? t("generate.changeRule") : t("generate.pickRule") }}
                  </el-button>
                  <el-button v-if="lineFilter !== 'TODO' && row.generated_qty > 0" link type="danger" size="small"
                    :disabled="!!running" @click="openDelete([row])">{{ t("generate.delete") }}</el-button>
                </span>
              </template>
            </el-table-column>
          </el-table>
          <p v-else-if="!b.lines.length" v-show="!isFolded(b.bill_no)" class="muted no-lines">{{ t("generate.noLines") }}</p>
        </div>
        <p v-if="!shownBills.length" class="muted no-lines">{{ t("common.empty") }}</p>
      </div>

      <!-- 底部操作栏（三个页签相同）：左侧对勾选的行操作 —— 已选信息（清空）、选规则、更多（撤销配置 / 删号 /
           删号，菜单项后写出作用的行数、枚数；撤销配置没有可作用的行时置灰，删号没有勾选时按 SN 起止号）；右侧生成 —— 生成范围、
           流水来源（弹层：从最大号之后 / 指定起止号、空闲号段）、生成 N 枚（唯一主按钮） -->
      <template #footer>
        <div v-if="ctx" class="bar">
          <span class="sel-info" :class="{ none: !pickedRows.length }">
            <template v-if="pickedRows.length">
              {{ t("generate.selInfo", { bills: pickedBills, lines: pickedRows.length }) }}
              <el-button link type="primary" @click="clearSelection">{{ t("generate.clearSel") }}</el-button>
            </template>
            <template v-else>{{ t("generate.selNone") }}</template>
          </span>
          <el-button :disabled="!pickedRows.length" :loading="busy === 'rule'" @click="openRuleDialog(pickedRows)">{{ t("generate.libraryRuleBtn") }}</el-button>
          <el-dropdown trigger="click" placement="top-start" @command="onMore">
            <el-button :loading="busy === 'config'">{{ t("generate.more") }}<el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
            <template #dropdown>
              <el-dropdown-menu class="more-menu">
                <el-dropdown-item command="config" :disabled="!configRows.length">
                  <span class="mi">{{ t("generate.revokeConfig") }}<span class="k">{{ t("generate.nLines", { n: configRows.length }) }}</span></span>
                </el-dropdown-item>
                <!-- 删号：有勾选的已出号行时按所选行删（弹窗里可改为按 SN 起止号），没有时直接按 SN 起止号 -->
                <el-dropdown-item command="delete" :disabled="!!running" :class="{ danger: !running }">
                  <span class="mi">{{ t("generate.deleteMenu") }}<span v-if="deleteRows.length" class="k">{{ t("generate.nLinesQty", { n: deleteRows.length, qty: num(deleteQty) }) }}</span></span>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <span class="push" />
          <span class="scope">
            {{ t("generate.scopeLabel") }}<b>{{ pickedRows.length ? t("generate.scopeLines", { n: pickedRows.length }) : t("generate.scopeNone") }}</b><br>
            {{ pickedRows.length ? t("generate.scopeSelHint") : t("generate.scopeNoneHint") }}
          </span>
          <span class="vsep" />
          <SeqSource v-model="seq" :gaps="ctx.gaps" :own="ownReserved" :first="gen.preview?.generatable ? gen.preview.start_seq : null" />
          <el-button type="primary" :loading="gen.loading" :disabled="!canGen" @click="genDlg = true">
            {{ t("generate.genBtn", { n: num(gen.preview?.generatable ? gen.preview.qty : 0) }) }}
          </el-button>
        </div>
      </template>
    </el-drawer>

    <RuleDialog v-if="ctx" v-model="ruleDlg.open" :pi="ctx.pi_no" :lines="ruleDlg.lines" :options="ctx.rule_options" :rule-prefix="ctx.rule_prefix" :prefix-locked="ctx.prefix_locked" :next-seq="ctx.counter?.next_seq" :reserve-start="ctx.counter?.reserve_start" :pi-quota="piQuota" @saved="reloadCtx" />

    <GenConfirmDialog v-model="genDlg" :preview="gen.preview" :unlock-limit="unlockLimit"
      :loading="busy === 'run'" @confirm="runGen" />

    <DeleteDialog v-if="ctx" ref="deleteDlg" :pi="ctx.pi_no" @done="afterDelete" />

    <SegmentsDialog v-if="ctx" ref="segDlg" :pi="ctx.pi_no" :gaps="ctx.gaps" @use="useRange" />

    <!-- PI / 客户规则：整张 PI 的默认规则，不常用 -->
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { usePageRoute } from "@/composables/usePageRoute";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "@/api";
import { ApiError } from "@/api/http";
import {
  billMark, dupAttach, emptySel, genLimit, genOrder, lineOrder, lineSort, scopeBody, selectedLines, seqSource, toggleBill, toggleLine,
} from "@/genScope.js";
import { useFactories } from "@/composables/useFactories";
import { useTime } from "@/composables/useTime";
import { leaveStateLabel, suspendLabel } from "@/constants";
import { useAuthStore } from "@/stores/auth";
import type { OrderLine } from "@/types/api";
import PageHead from "@/components/PageHead.vue";
import FactorySelect from "@/components/FactorySelect.vue";
import RuleDialog from "@/views/generate/RuleDialog.vue";
import DeleteDialog from "@/views/generate/DeleteDialog.vue";
import SeqSource, { type SeqValue } from "@/views/generate/SeqSource.vue";
import GenConfirmDialog from "@/views/generate/GenConfirmDialog.vue";
import GenProgress from "@/views/generate/GenProgress.vue";
import SegmentsDialog from "@/views/generate/SegmentsDialog.vue";
import { num, pct } from "@/views/generate/format";
import { buttonWidth, colWidth, tagWidth, textWidth } from "@/colWidth.js";
import { useLineDrafts } from "@/views/generate/useLineDrafts";

type Tab = "TO_GENERATE" | "GENERATED";
type Action = "running" | "view" | "gen" | "pick";
type TagType = "" | "info" | "primary" | "success" | "warning" | "danger";
type LineRef = { bill_no: string; seq: number };
/** 物料行页签按处理步骤：待处理 → 可生成 → 已生成 */
type Filter = "TODO" | "GEN" | "GENERATED";
type LineState = "TODO" | "GEN" | "GENERATED" | "DONE" | "NONE";

const TABS: Tab[] = ["TO_GENERATE", "GENERATED"];
const FILTERS: Filter[] = ["TODO", "GEN", "GENERATED"];
const PAGE = 20;
/** 各页签只列最近 3 页，更早的用搜索；生成中的 PI 排在「待生成」最前，不受此限（F-10 第 2 条）。 */
const RECENT_PAGES = 3;
const LIST_LIMIT = 500;
const ACTION_TYPE: Record<Action, TagType> = { running: "primary", view: "", gen: "primary", pick: "primary" };
const LINE_STATE_TAG: Record<LineState, TagType> = { TODO: "danger", GEN: "primary", GENERATED: "success", DONE: "info", NONE: "info" };

const { t } = useI18n();
// 页签缓存下本页在后台时不跟随别的页面的地址参数，也不改写地址
const route = usePageRoute();
const router = useRouter();
const { factories, nameOf } = useFactories();
const store = useAuthStore();
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
/** 生产组织筛选（空 = 全部）：按 PI 的生产组织（orgs，一张 PI 可有多个组织）在页面上筛，接口不变。 */
const factory = ref("");
const filtered = computed<any[]>(() =>
  factory.value ? list.value.items.filter((x: any) => (x.orgs || []).includes(factory.value)) : list.value.items,
);
const limited = computed(() => !q.value.trim() && filtered.value.length > PAGE * RECENT_PAGES);
const shown = computed<any[]>(() => (limited.value ? filtered.value.slice(0, PAGE * RECENT_PAGES) : filtered.value));
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

/** 行上按钮（F-10 第 3 条中与生成有关的部分，按顺序判断）。 */
function actionOf(row: any): Action {
  if (row.status === "GENERATING") return "running";
  if (row.status === "CANNOT") return "view";
  if (row.quota > 0) return row.status === "NEED_RULE" ? "pick" : "gen";
  return "view";
}

/** 按钮文字；生成中带进度百分比（列表不设状态列，进度显示在按钮上）。 */
function actionText(row: any): string {
  const a = actionOf(row);
  const label = t(`generate.action.${a}`);
  return a === "running" && row.running ? `${label} ${pct(row.running)}%` : label;
}

const frozenText = (row: any) => t("generate.frozenBills", { n: row.frozen_bills });
const orgNames = (row: any): string[] => (row.orgs || []).map((o: string) => nameOf(o));
/** 详情单据头的生产组织名称：与 PI 列表生产组织列同一来源（生产组织列表），取不到时用详情带的名称、编号。 */
const billOrg = (b: any): string =>
  factories.value.find((f) => f.factory_code === b.org_code)?.factory_name || b.org_name || b.org_code || "";

/** 生产组织列放不下时在组织之间换行，列宽不超过此值（单个组织名称更长时按最长的名称）。 */
const ORGS_MAX_W = 240;
/** 按钮加载图标（生成中）占的宽度：图标 14px + 间距 6px。 */
const LOADING_ICON_W = 20;

/** PI 列表各列宽度：按本页签全部行（shown）的内容量出，翻页时列宽不跳动。 */
const listW = computed(() => {
  const rows = shown.value;
  const nums = (f: (r: any) => unknown) => rows.map((r) => textWidth(num(f(r) as number)));
  const orgW = rows.map((r) => {
    const names = orgNames(r);
    const longest = Math.max(0, ...names.map((n) => textWidth(n + "、")));
    return Math.max(longest, Math.min(ORGS_MAX_W, textWidth(names.join("、"))));
  });
  return {
    pi: colWidth("PI", rows.map((r) => textWidth(r.pi_no, { bold: true }) + (r.frozen_bills ? 6 + tagWidth(frozenText(r)) : 0)), { min: 120 }),
    customer: colWidth(t("common.customer"), rows.map((r) => textWidth(r.customer_code || "—"))),
    bills: colWidth(t("generate.bills"), nums((r) => r.bills)),
    total: colWidth(t("generate.orderTotal"), nums((r) => r.total_qty)),
    generated: colWidth(t("generate.generated"), nums((r) => r.generated_qty)),
    transferredIn: colWidth(t("generate.transferredIn"), nums((r) => r.transferred_in)),
    quota: colWidth(t("generate.quota"), nums((r) => r.quota)),
    pendingAlloc: colWidth(t("generate.pendingAlloc"), nums((r) => r.pending_alloc)),
    factory: colWidth(t("common.factory"), orgW),
    modify: colWidth(t("orders.modifyDate"), rows.map((r) => textWidth(r.modify_time || "—"))),
    action: colWidth(t("common.actions"), rows.map((r) => buttonWidth(actionText(r)) + (actionOf(r) === "running" ? LOADING_ICON_W : 0))),
  };
});

function openRow(row: any) {
  openDetail({ pi: row.pi_no });
}

// ================================================================ 详情

const detailPi = ref("");
const ctx = ref<any>(null);
const ctxLoading = ref(false);

const allLines = computed<OrderLine[]>(() => (ctx.value ? ctx.value.bills.flatMap((b: any) => b.lines) : []));
/** 本 PI 全部物料行还可生成的数量之和：各物料行共用 PI 的一套流水，规则对话框按它给流水位数的默认值 */
const piQuota = computed(() => allLines.value.reduce((n, l) => n + (Number(l.quota) || 0), 0));
const limits = computed(() => ctx.value?.limits || { default: 10000, max: 500000, sync_threshold: 5000 });

async function openDetail(key: { pi?: string; bill_no?: string }) {
  detailPi.value = key.pi || key.bill_no || "";
  ctx.value = null;
  clearSelection();
  // 流水来源每张 PI 重新选：默认从最大号之后
  seq.value = { mode: "MAX", start: null, end: null, gaps: [] };
  foldAll(false);
  await loadCtx(key);
  if (!ctx.value) return;
  // 打开时停在第一个还有物料行的步骤
  lineFilter.value = FILTERS.find((f) => filterCount.value[f] > 0) ?? "TODO";
  detailPi.value = ctx.value.pi_no;
  // 单据默认全部收起；从单据进来的展开该单
  foldAll(true);
  if (key.bill_no && isFolded(key.bill_no)) toggleFold(key.bill_no);
  if (route.active && route.query.pi !== ctx.value.pi_no) router.replace({ query: { pi: ctx.value.pi_no } });
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
 *  转过去时不提示，展开这些行所在的单据、回到顶部。 */
function nextStep(before: Map<string, Filter[]>) {
  if (!ctx.value) return;
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
  lineFilter.value = to;
  if (moved.length) {
    const s = new Set(folded.value);
    for (const l of moved) s.delete(l.bill_no);
    folded.value = s;
  }
  document.querySelector(".gen-drawer .el-drawer__body")?.scrollTo({ top: 0 });
}

function closeDetail() {
  // 点选时自动填、还没保存的附件项次先保存
  flushAutoAttach();
  detailPi.value = "";
  ctx.value = null;
  stopPoll();
  if (route.active && (route.query.pi || route.query.bill_no)) router.replace({ query: {} });
}

// ---------------- 规则：物料行

const ruleDlg = reactive({ open: false, lines: [] as OrderLine[] });

/** 底部「选规则」与行内「选规则 / 改规则」打开同一个规则对话框（默认自定义页签，前缀默认点选的第一个单据编码后 7 位，本 PI 已生成的号还在时沿用已用的前缀）。
 *  打开前先查附件项次（只查还有额度、要生成的行；生成时所选物料行不分单据按附件项次从小到大排号）：
 *  1. 有没填的：跳到第一条没填的行（按排号顺序），提示补全，不打开；
 *  2. 有相同的（不分单据）：跳到这些行，提示将按行号（单据序号）从小到大生成，可继续选规则或回去修改。 */
async function openRuleDialog(lines: OrderLine[]) {
  const keys = lines.map(lineKey);
  busy.value = "rule";
  try {
    // 点选时自动填、还没保存的附件项次先保存（会刷新详情）
    await flushAutoAttach();
  } finally {
    busy.value = "";
  }
  const rows = keys.map((k) => allLines.value.find((l) => lineKey(l) === k)).filter(Boolean) as OrderLine[];
  if (!rows.length) return;
  const todo = genOrder(rows.filter((l) => l.quota > 0), attachOf);
  const missing = todo.filter((l) => !attachOf(l));
  if (missing.length) {
    jumpTo(missing.slice(0, 1), true);
    ElMessage.warning(t("generate.attachMissingJump", { bill: missing[0].bill_no, seq: missing[0].seq, n: missing.length }));
    return;
  }
  const dups = dupAttach(todo, attachOf);
  if (dups.length) {
    jumpTo(dups.flatMap((g: any) => g.lines));
    const text = [
      t("generate.attachDupHint"),
      ...dups.map((g: any) => t("generate.attachDupItem", { attach: g.attach, lines: dupLines(g.lines) })),
    ].join("\n");
    try {
      await ElMessageBox.confirm(text, t("generate.attachDupTitle"), {
        type: "warning",
        confirmButtonText: t("generate.attachDupGoOn"),
        cancelButtonText: t("generate.attachDupEdit"),
        customStyle: { whiteSpace: "pre-line" },
      });
    } catch {
      return;
    }
  }
  ruleDlg.lines = rows;
  ruleDlg.open = true;
}

/** 相同附件项次提示里一组物料行的文字（按生成顺序）：单据 X ERP行号 n、…… */
const dupLines = (ls: OrderLine[]) => ls.map((l) => t("generate.attachDupLine", { bill: l.bill_no, seq: l.seq })).join("、");

/** 物料行当前的附件项次（含页面上还没保存的填写）。 */
const attachOf = (l: OrderLine) => (draftOf(l, "attach") ?? "").trim();

/** 跳到物料行：切到它所在的页签、展开所在单据、滚动到第一行并高亮这些行；focus 时把光标放进第一行的附件项次。 */
const flash = ref(new Set<string>());
let flashTimer: ReturnType<typeof setTimeout> | undefined;
async function jumpTo(rows: OrderLine[], focus = false) {
  if (!rows.length) return;
  const first = rows[0];
  const tab = FILTER_OK[lineFilter.value](first) ? lineFilter.value : FILTERS.find((f) => FILTER_OK[f](first));
  if (tab) lineFilter.value = tab;
  const s = new Set(folded.value);
  for (const l of rows) s.delete(l.bill_no);
  folded.value = s;
  flash.value = new Set(rows.map(lineKey));
  flashFirst.value = lineKey(first);
  clearTimeout(flashTimer);
  flashTimer = setTimeout(() => {
    flash.value = new Set();
    flashFirst.value = "";
  }, 4000);
  await nextTick();
  const el = document.querySelector<HTMLElement>(".gen-drawer .lines .el-table__row.flash-first");
  el?.scrollIntoView({ block: "center", behavior: "smooth" });
  if (focus) el?.querySelector<HTMLInputElement>(".attach-cell input")?.focus();
}
const flashFirst = ref("");

/** 去 SN 分配页（本 PI）；没有待分配的号时按钮置灰。 */
function goAllocate() {
  if (ctx.value) router.push({ path: "/allocate", query: { pi: ctx.value.pi_no } });
}

// ---------------- 单据号段：单据头标签与号段弹窗；空闲号段「用这段」填进底部流水来源

const segDlg = ref<InstanceType<typeof SegmentsDialog> | null>(null);
function useRange(r: { start: number; end: number }) {
  seq.value = { mode: "RANGE", start: r.start, end: r.end, gaps: [] };
}

// ---------------- 范围：勾选单据（整单，按勾选顺序）或物料行（按点选顺序）；生成、规则配置、撤销共用

const sel = ref(emptySel());
const linesOf = (no: string): LineRef[] => (ctx.value?.bills.find((b: any) => b.bill_no === no)?.lines ?? []);
const hasSel = computed(() => !!(sel.value.bills.length || sel.value.lines.length));

function clearSelection() {
  sel.value = emptySel();
  dropAutoAttach();
}
const onBill = (no: string, on: boolean) => (sel.value = toggleBill(sel.value, no, on, linesOf));
const onLine = (l: LineRef, on: boolean) => (sel.value = toggleLine(sel.value, l, on, linesOf));
const lineIdx = (l: LineRef) => lineOrder(sel.value, l);
const billCheck = (no: string) => billMark(sel.value, no, linesOf);
const isPicked = (l: LineRef) => lineIdx(l) >= 0 || billCheck(l.bill_no).order >= 0;
/** 单据可勾选（与单据头勾选框置灰同一条件）：在快照中且有物料行。 */
const billPickable = (b: any) => !!b.in_snapshot && !!b.lines.length;
/** 「全选」作用的单据：当前页签列出的、可勾选的单据（按列出顺序）。 */
const pickableBills = computed<string[]>(() => shownBills.value.filter(billPickable).map((b: any) => b.bill_no as string));
/** 全选框：这些单据都整单勾选为选中，部分勾选（含单据内部分物料行）为半选。 */
const allBillsMark = computed(() => {
  const marks = pickableBills.value.map(billCheck);
  const checked = marks.length > 0 && marks.every((m) => m.checked);
  return { modelValue: checked, indeterminate: !checked && marks.some((m) => m.checked || m.indeterminate) };
});
/** 全选 / 取消全选：逐张走单据头勾选（toggleBill），与一张张勾选单据相同；取消后什么都没选时等同「清空」。 */
function onAllBills(on: boolean) {
  let s = sel.value;
  for (const no of pickableBills.value) {
    if (on && billMark(s, no, linesOf).checked) continue;
    s = toggleBill(s, no, on, linesOf);
  }
  if (!on && !s.bills.length && !s.lines.length) return clearSelection();
  sel.value = s;
}
/** 选中的物料行（勾选的单据展开），按排号顺序。 */
/** 所选物料行自己预留里空着的号段（指定起止号时这些行放弃预留，可勾选作剩下的号的去处） */
const ownReserved = computed<any[]>(() => {
  const keys = new Set(pickedRows.value.map((l: any) => `${l.bill_no}#${l.seq}`));
  return (ctx.value?.gaps?.reserved ?? []).filter((x: any) => keys.has(`${x.bill_no}#${x.seq}`));
});
const pickedRows = computed(() =>
  selectedLines(sel.value, linesOf)
    .map((r: LineRef) => allLines.value.find((l) => sameLine(l, r)))
    .filter(Boolean) as OrderLine[],
);
/** 点选 / 取消点选物料行（点行或勾选框）：附件项次为空的行自动填上点选顺序（可改），取消点选时撤掉还没保存的自动填值。 */
function pickLine(row: OrderLine, on: boolean) {
  onLine(row, on);
  if (!on) return dropAutoAttach(row);
  const i = lineIdx(row);
  if (i >= 0) autoFillAttach(row, i + 1);
}
function onLineClick(row: OrderLine) {
  pickLine(row, !isPicked(row));
}
function lineClass({ row }: { row: OrderLine }) {
  const k = lineKey(row);
  return [isPicked(row) ? "picked" : "", flash.value.has(k) ? "flash" : "", flashFirst.value === k ? "flash-first" : ""]
    .filter(Boolean)
    .join(" ");
}
/** 生成时的排号顺序（与后台一致）：所选物料行不分单据按附件项次从小到大（按数字比），项次相同按行号从小到大，
 *  行号也相同按单据勾选 / 点选先后。勾选框旁的数字按这个顺序标（单据头标单据第一次出现的次序，物料行标排号次序）。 */
const genRows = computed(() => genOrder(pickedRows.value, attachOf) as OrderLine[]);
const genIdx = (l: LineRef) => genRows.value.findIndex((x) => sameLine(x, l));
const billIdx = (no: string) => [...new Set(genRows.value.map((x) => x.bill_no))].indexOf(no);
/** 生成范围：勾选的单据或物料行；必须勾选，都不勾选为 null（不预演、不能生成）。
 *  已分配的单据删号后（或数量增加后）还有额度的照常勾选生成。 */
const genScope = computed(() => (hasSel.value ? scopeBody(sel.value) : null));
/** 流水来源：MAX 从最大号之后（默认）/ RANGE 手动起止号（空号段与未使用的号，不够时从最大号之后接着编）。 */
const seq = ref<SeqValue>({ mode: "MAX", start: null, end: null, gaps: [] });

// ---------------- 物料行：状态、筛选、号段；附件项次、米数、产品单位的填写

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
/** 各页签的物料行数：只用来决定打开时停在哪个页签、当前页签空了转到哪个（页签名不显示数字）。 */
const filterCount = computed(() =>
  Object.fromEntries(FILTERS.map((f) => [f, allLines.value.filter(FILTER_OK[f]).length])) as Record<Filter, number>,
);
const shownBills = computed(() => {
  const ok = FILTER_OK[lineFilter.value];
  return (ctx.value?.bills ?? [])
    // 单据内物料行按附件项次从小到大列出（已保存的附件项次，与生成排号同一比较；未填的排最后，项次相同按行号）
    .map((b: any) => ({ ...b, shown: lineSort(b.lines.filter(ok)) }))
    // 已不在快照的单据（没有物料行、只有号）列在「已生成」
    .filter((b: any) => b.shown.length || (!b.lines.length && lineFilter.value === "GENERATED"));
});
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

/** 物料行客户 P/N 列宽：按本 PI 全部物料行的值量出、不设上限（切页签时不跳动，各单据的表同宽、列对齐），
 *  完整单行显示；总宽超出详情宽度时单据物料行表格左右滑动。 */
const pnW = computed(() => colWidth(t("orders.customerPn"), allLines.value.map((l) => textWidth(l.customer_pn || "—")), { min: 80 }));

/** 物料行开始 / 结束 SN 号列宽：按本 PI 全部物料行的完整 SN 量出（切页签时不跳动），SN 完整显示。 */
const snW = computed(() =>
  Math.max(
    colWidth(t("generate.startSn"), allLines.value.map((l) => textWidth(l.start_sn || "—", { mono: true }))),
    colWidth(t("generate.endSn"), allLines.value.map((l) => textWidth(l.end_sn || "—", { mono: true }))),
  ),
);

const {
  draftOf, setDraft, saveAttach, saveLength, saveUnit,
  lengthMissing, lengthPlaceholder, unitMissing, unitPlaceholder,
  autoFillAttach, dropAutoAttach, flushAutoAttach,
} = useLineDrafts(reloadCtx);
// ---------------- 生成：勾选变化后自动预演（所选物料行整行生成）→ 确认 → 当场完成或后台进度 / 取消

const gen = reactive({ loading: false, preview: null as any });
const genDlg = ref(false);
/** 可生成：预演可生成、不在生成中、未超过单次最大上限（超过默认上限时在确认框里解除限制）。 */
const canGen = computed(() => !!gen.preview?.generatable && !running.value && genLimit(gen.preview.qty, limits.value, true) === null);
/** 超过单次默认上限时，确认框里须勾选解除限制；未超过为 0。 */
const unlockLimit = computed(() =>
  gen.preview?.generatable && genLimit(gen.preview.qty, limits.value, false) === "UNLOCK" ? Number(limits.value.default) : 0,
);
let previewTimer: ReturnType<typeof setTimeout> | undefined;

function schedulePreview() {
  clearTimeout(previewTimer);
  previewTimer = setTimeout(runPreview, 400);
}

async function runPreview() {
  if (!ctx.value || running.value) return;
  const scope = genScope.value;
  const src = seqSource(seq.value.mode, seq.value.start, seq.value.end, seq.value.gaps);
  // 手动起止号还没填好：不预演
  if (!scope || !src.body) return resetGen();
  gen.loading = true;
  try {
    gen.preview = await api.genPreview({ pi_no: ctx.value.pi_no, ...scope, ...src.body }, true);
  } catch (e) {
    // 预演失败（超过单次最大上限、规则流水用尽等）：底部栏不放说明文字，弹出提示
    gen.preview = null;
    ElMessage.error(e instanceof ApiError ? e.message : String(e));
  } finally {
    gen.loading = false;
  }
}

function resetGen() {
  Object.assign(gen, { preview: null });
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

// ---------------- 删号：所选物料行的号（状态不限），或按 SN 起止号；最大流水号不退回，删掉的号留下空号段

/** 底部「删号」的对象：所选物料行中挂着号的。 */
const deleteRows = computed(() => pickedRows.value.filter((l) => l.generated_qty > 0));
const deleteQty = computed(() => deleteRows.value.reduce((a, l) => a + l.generated_qty, 0));
/** 已选物料行涉及的单据数（底部已选信息）。 */
const pickedBills = computed(() => new Set(pickedRows.value.map((l) => l.bill_no)).size);

/** 底部「更多」菜单。 */
function onMore(cmd: string) {
  if (cmd === "config") revokeConfig(configRows.value);
  else if (cmd === "delete") deleteDlg.value?.open({ lines: deleteRows.value });
}
const deleteDlg = ref<InstanceType<typeof DeleteDialog> | null>(null);

function openDelete(rows: LineRef[]) {
  if (rows.length) deleteDlg.value?.open({ lines: rows });
}

async function afterDelete() {
  clearSelection();
  await reloadCtx();
}

// ---------------- 撤销配置（待处理、可生成页签）：还没有号的物料行，物料行选定的规则、手填的附件项次、米数和产品单位恢复为未配置

/** 物料行在本系统做过配置：物料行选定了规则，或手填了附件项次 / 米数 / 产品单位。 */
const hasConfig = (l: OrderLine) =>
  l.rule?.source === "LINE" || l.attach_manual != null || l.length_manual != null || l.unit_manual != null;
/** 底部批量撤销配置的对象：待处理 / 可生成页签中所选、还没有号、做过配置的物料行。 */
const configRows = computed(() => {
  const f = lineFilter.value;
  return f === "GENERATED" ? [] : pickedRows.value.filter((l) => FILTER_OK[f](l) && !l.edit_lock && hasConfig(l));
});

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

// ================================================================ 联动

watch([sel, seq], () => {
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
  clearTimeout(flashTimer);
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
/* PI 列表：列宽按内容算好（listW），单元格单行完整显示；生产组织列在组织之间换行，组织名称本身不断开 */
.pi-list :deep(.cell) { white-space: nowrap; text-overflow: clip; }
.pi-list :deep(.orgs-cell .cell) { white-space: normal; }
.pi-list .org { white-space: nowrap; }
.pi-list :deep(.el-table__row) { cursor: pointer; }
.dh { display: flex; flex-direction: column; gap: 6px; }
.dh-title { display: flex; gap: 12px; align-items: center; font-size: 16px; color: var(--app-text); }
.kv { display: flex; flex-wrap: wrap; gap: 4px 18px; font-size: 12px; color: var(--app-muted); }
.kv b { color: var(--app-text); font-weight: 600; }
.drawer { display: flex; flex-direction: column; gap: 10px; }
.dtabs :deep(.el-tabs__header) { margin-bottom: 8px; }
.tabs-row { position: relative; }
/* 高度与页签头（40px）一致，按钮与页签文字垂直居中；左侧留白避免与页签重叠 */
.dtabs :deep(.el-tabs__nav-wrap) { padding-right: 300px; }
.fold-ops { position: absolute; top: 0; right: 0; height: 40px; display: inline-flex; align-items: center; gap: 4px; }
.all-bills { margin-right: 8px; height: auto; }
.fold-ops :deep(.el-button + .el-button) { margin-left: 0; }
.toolbar { margin-bottom: 8px; }
.issue-tags, .ops { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.ops { justify-content: flex-end; gap: 2px 6px; }
.ops :deep(.el-button + .el-button) { margin-left: 0; }
.cell-input { display: block; }
.cell-input .missing :deep(.el-input__wrapper) { box-shadow: 0 0 0 1px var(--el-color-danger) inset; }
.bill { border: 1px solid var(--el-border-color-lighter); border-radius: 8px; margin-bottom: 10px; overflow: hidden; }
.bill-h { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 8px 12px; background: var(--el-fill-color-light); cursor: pointer; user-select: none; }
.bill-h:hover { background: var(--el-fill-color); }
.fold { color: var(--app-muted); transition: transform 0.15s; }
.fold.open { transform: rotate(90deg); }
.bill-org { font-size: 12px; font-weight: 700; color: var(--app-text); white-space: nowrap; }
.order { display: inline-flex; align-items: center; justify-content: center; min-width: 18px; height: 18px; padding: 0 4px; border-radius: 9px; font-size: 11px; color: #fff; background: var(--el-color-primary); }
.sel { display: inline-flex; align-items: center; gap: 4px; }
.lines :deep(tr.picked > td) { background: var(--el-color-primary-light-9); }
.lines :deep(tr.flash > td) { background: var(--el-color-warning-light-8) !important; transition: background 0.3s; }
.lines :deep(.el-table__row) { cursor: pointer; }
/* 客户 P/N：表头与数据单行显示、不折行（列宽已按内容量出，放不下时表格左右滑动） */
.lines :deep(.nowrap-cell .cell) { white-space: nowrap; text-overflow: clip; }
.no-lines { padding: 8px 12px; margin: 0; }
.chip { display: inline-flex; align-items: center; height: 22px; padding: 0 9px; border-radius: 11px; font-size: 12px; border: 1px solid; white-space: nowrap; cursor: pointer; }
.chip:hover { text-decoration: underline; }
.chip.use { color: var(--el-color-primary); background: var(--el-color-primary-light-9); border-color: var(--el-color-primary-light-8); }
.chip.free { color: var(--el-color-warning-dark-2); background: var(--el-color-warning-light-9); border-color: var(--el-color-warning-light-5); }
.bar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; text-align: left; }
.bar :deep(.el-button + .el-button) { margin-left: 0; }
.sel-info { display: inline-flex; align-items: center; gap: 8px; height: 32px; padding: 0 10px; border-radius: 4px; white-space: nowrap;
  color: var(--el-color-primary); background: var(--el-color-primary-light-9); }
.sel-info.none { color: var(--app-muted); background: var(--el-fill-color); }
.scope { font-size: 12px; line-height: 1.35; text-align: right; white-space: nowrap; color: var(--app-muted); }
.scope b { color: var(--app-text); }
.vsep { width: 1px; height: 22px; background: var(--el-border-color); }
</style>

<style>
.gen-drawer .el-drawer__header { margin-bottom: 8px; align-items: flex-start; }
.gen-drawer .el-drawer__footer { border-top: 1px solid var(--el-border-color); background: var(--el-fill-color-lighter); padding: 10px 20px; }
/* 底部「更多」菜单：菜单项后写出作用的行数 / 枚数；删号为红色 */
.more-menu .mi { display: flex; align-items: center; gap: 16px; min-width: 240px; }
.more-menu .mi .k { margin-left: auto; font-size: 12px; color: var(--app-muted); }
.more-menu .danger:not(.is-disabled) { color: var(--el-color-danger); }
</style>
