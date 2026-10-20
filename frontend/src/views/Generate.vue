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
          {{ t("generate.summary", { quota: num(list.summary.quota), pending: num(list.summary.pending_alloc), unbatched: num(list.summary.unbatched) }) }}
        </span>
      </div>

      <div class="fill-body">
        <el-table v-loading="listLoading" :data="paged" size="small" row-key="pi_no" height="100%" :empty-text="t('common.empty')"
          @row-click="(row: any) => openRow(row)">
          <el-table-column label="PI" min-width="160">
            <template #default="{ row }">
              <b>{{ row.pi_no }}</b>
              <el-tag v-if="row.frozen_bills" size="small" type="danger" class="ml">{{ t("generate.frozenBills", { n: row.frozen_bills }) }}</el-tag>
              <el-tag v-if="row.suspended" size="small" type="warning" class="ml">{{ t("generate.suspendedQty", { n: num(row.suspended) }) }}</el-tag>
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
                <el-tag size="small" :type="STATUS_TAG[row.status] || 'info'" disable-transitions>{{ t(`generate.status.${row.status}`) }}</el-tag>
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
          <el-table-column :label="t('generate.allocated')" align="right" width="86">
            <template #default="{ row }"><span class="num">{{ num(row.allocated_qty) }}</span></template>
          </el-table-column>
          <el-table-column :label="t('common.actions')" width="130" align="right">
            <template #default="{ row }">
              <el-button size="small" :type="ACTION_TYPE[actionOf(row)]" :plain="actionOf(row) === 'running' || actionOf(row) === 'pick'"
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

    <!-- 详情：步骤条「SN 规则 → 生成 → 分配」+ 当前步骤面板 + 订单 / 生成任务 / 分配记录 -->
    <el-drawer :model-value="!!detailPi" size="980px" class="gen-drawer" @close="closeDetail">
      <template #header>
        <div v-if="ctx" class="dh">
          <div class="dh-title"><b>PI {{ ctx.pi_no }}</b><span class="muted">{{ t("common.customer") }} {{ ctx.customer_code || "—" }}</span></div>
          <div class="kv">
            <span>{{ t("generate.orderTotal") }} <b class="num">{{ num(ctx.summary.total_qty) }}</b></span>
            <span>{{ t("generate.generated") }} <b class="num">{{ num(ctx.summary.generated_qty) }}</b></span>
            <span>{{ t("generate.quota") }} <b class="num">{{ num(ctx.summary.quota) }}</b></span>
            <span>{{ t("generate.transferredIn") }} <b class="num">{{ num(ctx.summary.transferred_in) }}</b></span>
            <span>{{ t("generate.pendingAlloc") }} <b class="num">{{ num(ctx.summary.pending_alloc) }}</b></span>
            <span>{{ t("generate.allocated") }} <b class="num">{{ num(ctx.summary.allocated_qty) }}</b></span>
            <span v-if="ctx.summary.suspended" class="warn">{{ t("generate.suspendedQty", { n: num(ctx.summary.suspended) }) }}</span>
            <span>{{ t("generate.nextSeq") }} <b class="num">{{ ctx.counter.next_seq }}</b></span>
          </div>
        </div>
      </template>

      <div v-if="ctx" v-loading="ctxLoading" class="drawer">
        <div class="steps">
          <template v-for="(s, i) in STEPS" :key="s">
            <i v-if="i" class="line" :class="{ ok: stepDone[STEPS[i - 1]] }" />
            <button type="button" class="step" :class="{ ok: stepDone[s], cur: step === s }" @click="step = s">
              <span class="dot"><el-icon v-if="stepDone[s]"><Check /></el-icon><template v-else>{{ i + 1 }}</template></span>
              <span>{{ t(`generate.step.${s}`) }}</span>
            </button>
          </template>
          <el-tag v-if="allDone" type="success" class="done-tag">{{ t("generate.allDone") }}</el-tag>
        </div>

        <!-- ① SN 规则：PI 绑定、客户规则；物料行的规则在下方订单中点物料行选 -->
        <div v-if="step === 'rule'" class="panel">
          <div class="prow">
            <div class="k">{{ t("generate.piRule") }}</div>
            <div class="v">
              <div v-if="ctx.pi_rule" class="rule-now">
                <el-tag size="small" :type="ctx.pi_rule.source === 'PI' ? 'primary' : 'warning'">{{ t(`generate.source.${ctx.pi_rule.source}`) }}</el-tag>
                <b>{{ ruleTitle(ctx.pi_rule) }}</b>
                <span class="sn-mono">{{ ctx.pi_rule.format }}</span>
                <el-tag size="small" :type="ctx.pi_rule.plan === 'PREFIX' ? 'danger' : 'info'">{{ t(`generate.plan.${ctx.pi_rule.plan}`) }}</el-tag>
                <el-button v-if="ctx.pi_rule.source === 'PI'" link type="danger" :loading="busy === 'unbindPi'" @click="unbindPi">{{ t("generate.unbindPi") }}</el-button>
              </div>
              <span v-else class="muted">{{ t("generate.noPiRule") }}</span>
              <div class="bind">
                <el-select v-model="bind.rule_id" filterable clearable :placeholder="t('generate.pickLibRule')" class="w320" @change="bind.new_prefix = ''">
                  <el-option v-for="o in ctx.rule_options" :key="o.rule_id" :value="o.rule_id" :label="`${o.rule_code} · ${o.rule_name}`">
                    <span>{{ o.rule_code }} · {{ o.rule_name }}</span>
                    <el-tag v-if="o.recommended" size="small" type="success" class="ml">{{ t("generate.rule.recommended") }}</el-tag>
                    <span class="muted ml">{{ o.format }}</span>
                  </el-option>
                </el-select>
                <el-input v-if="bindOption?.plan === 'PREFIX'" v-model="bind.new_prefix" class="w160" maxlength="32"
                  :placeholder="t('generate.rule.newPrefix')" />
                <el-button type="primary" :disabled="!bind.rule_id || (bindOption?.plan === 'PREFIX' && !bind.new_prefix.trim())"
                  :loading="busy === 'bindPi'" @click="bindPi">{{ t("generate.bindPi") }}</el-button>
              </div>
              <span v-if="bindOption?.plan === 'PREFIX'" class="muted warn">{{ t("generate.rule.prefixNeeded", { pi: bindOption.other_pi || "—" }) }}</span>
            </div>
          </div>
          <div class="prow">
            <div class="k">{{ t("generate.customerRule") }}</div>
            <div class="v">
              <template v-if="ctx.customer_rule">
                <div class="rule-now">
                  <b>{{ ctx.customer_rule.rule_code }}</b><span class="muted">{{ ctx.customer_rule.rule_name }}</span>
                  <el-button link type="danger" :loading="busy === 'unbindCustomer'" @click="unbindCustomer">{{ t("generate.unbindCustomer") }}</el-button>
                </div>
                <div v-if="ctx.pi_rule?.source === 'CUSTOMER'" class="bind">
                  <el-input v-model="tpl.new_prefix" class="w160" maxlength="32" :placeholder="t('generate.rule.newPrefix')" />
                  <el-button type="primary" :disabled="!tpl.new_prefix.trim()" :loading="busy === 'template'" @click="adoptTemplate">{{ t("generate.useTemplate") }}</el-button>
                  <span class="muted">{{ t("generate.templateNote") }}</span>
                </div>
              </template>
              <span v-else class="muted">{{ t("generate.noCustomerRule") }}</span>
            </div>
          </div>
          <div class="act">
            <span class="msg" :class="{ err: needRuleLines.length }">
              {{ needRuleLines.length ? t("generate.needRuleLines", { n: needRuleLines.length }) : t("generate.rulesReady") }}
            </span>
            <el-button v-if="needRuleLines.length" @click="openRuleDialog(needRuleLines)">{{ t("generate.pickForNeeded") }}</el-button>
            <el-button type="primary" plain @click="step = 'gen'">{{ t("generate.nextStep") }}</el-button>
          </div>
        </div>

        <!-- ② 生成：范围 + 数量 → 自动预演 → 确认生成；生成中显示进度，可取消 -->
        <div v-else-if="step === 'gen'" class="panel">
          <template v-if="running">
            <div class="prog" :class="{ cx: !!running.cancel_at }">
              <div class="prog-h">
                <b class="pct num">{{ running.percent ?? pct(running) }}%</b>
                <span class="muted num">{{ t("generate.runCount", { done: num(running.done_qty), qty: num(running.qty) }) }}</span>
                <span class="muted push">{{ t("generate.runMeta", { time: fmtTime(running.created_at), who: running.created_by }) }}</span>
              </div>
              <el-progress :percentage="Number(running.percent ?? pct(running))" :stroke-width="10" :show-text="false" striped striped-flow
                :status="running.cancel_at ? 'warning' : undefined" />
              <div class="run-grid">
                <div><span class="muted">{{ t("generate.writtenTo") }}</span><span class="sn-mono">{{ running.current_sn || "—" }}</span></div>
                <div><span class="muted">{{ t("generate.speed") }}</span><span>{{ running.speed_per_sec ? t("generate.speedVal", { n: num(running.speed_per_sec) }) : "—" }}</span></div>
                <div><span class="muted">{{ t("generate.eta") }}</span><span>{{ formatDuration(running.eta_seconds) }}</span></div>
              </div>
            </div>
            <div class="act">
              <span class="msg">{{ running.cancel_at ? t("generate.canceling") : t("generate.runNote") }}</span>
              <el-button type="danger" plain :disabled="!!running.cancel_at" :loading="busy === 'cancel'" @click="cancelGen">{{ t("generate.cancelGen") }}</el-button>
            </div>
          </template>
          <template v-else>
            <div class="prow">
              <div class="k">{{ t("generate.scope") }}</div>
              <div class="v">
                <span>{{ scopeText }}</span>
                <span class="muted">{{ t("generate.scopeHint") }}</span>
              </div>
            </div>
            <div class="prow">
              <div class="k">{{ t("common.qty") }}</div>
              <div class="v">
                <div class="qty-row">
                  <el-input-number v-model="gen.qty" :min="1" :max="Math.max(1, cap)" :disabled="!quota" step-strictly controls-position="right" class="qty" @change="onQty" />
                  <span class="muted">{{ t("generate.quotaOf", { quota: num(quota), cap: num(cap) }) }}</span>
                  <el-checkbox v-if="quota > limits.default" v-model="gen.unlocked" @change="onUnlock">{{ t("generate.unlock") }}</el-checkbox>
                </div>
                <span v-if="quota > limits.default && !gen.unlocked" class="muted warn">{{ t("generate.limitHint", { limit: num(limits.default) }) }}</span>
                <span v-if="gen.corrected" class="muted warn">{{ t("generate.corrected", { cap: num(cap) }) }}</span>
              </div>
            </div>
            <div class="prow">
              <div class="k">{{ t("generate.preview") }}</div>
              <div v-loading="gen.loading" class="v">
                <span v-if="gen.error" class="err">{{ gen.error }}</span>
                <template v-else-if="gen.preview && !gen.preview.generatable">
                  <span class="err">{{ t("generate.cannotGen") }}</span>
                  <ul class="issues">
                    <li v-for="g in issueGroups" :key="g.code">
                      <b>{{ issueText(g.code) }}</b>
                      <template v-if="g.total">
                        <span class="muted">（{{ t("generate.issueLines", { n: num(g.total) }) }}）：</span>
                        <span>{{ g.bills.map((b) => `${b.bill_no} #${b.seqs.join("、")}`).join("；") }}</span>
                        <span v-if="g.more" class="muted"> …</span>
                      </template>
                    </li>
                  </ul>
                </template>
                <template v-else-if="gen.preview">
                  <div class="range">
                    <span class="sn-mono">{{ gen.preview.start_sn }}</span><span class="muted">~</span><span class="sn-mono">{{ gen.preview.end_sn }}</span>
                    <span class="muted num">{{ t("generate.previewSeq", { a: gen.preview.start_seq, b: gen.preview.end_seq, n: num(gen.preview.qty) }) }}</span>
                  </div>
                  <el-table :data="gen.preview.items" size="small" max-height="240">
                    <el-table-column :label="t('common.billNo')" prop="bill_no" min-width="110" />
                    <el-table-column :label="t('common.billSeq')" prop="seq" width="60" />
                    <el-table-column :label="t('common.material')" prop="material_code" min-width="90" />
                    <el-table-column :label="t('common.length')" prop="length" width="64" />
                    <el-table-column :label="t('common.qty')" align="right" width="70">
                      <template #default="{ row }"><span class="num">{{ num(row.qty) }}</span></template>
                    </el-table-column>
                    <el-table-column :label="t('generate.segment')" min-width="250">
                      <template #default="{ row }"><span class="sn-mono">{{ row.start_sn }} ~ {{ row.end_sn }}</span></template>
                    </el-table-column>
                    <el-table-column :label="t('generate.ruleCol')" prop="rule_label" min-width="180" show-overflow-tooltip />
                  </el-table>
                  <span class="muted">{{ t("generate.previewNote", { time: fmtTime(gen.preview.expires_at) }) }}</span>
                </template>
                <span v-else class="muted">—</span>
              </div>
            </div>
            <div class="act">
              <span class="msg">{{ t("generate.genNote", { n: num(limits.sync_threshold) }) }}</span>
              <el-button :loading="gen.loading" @click="runPreview">{{ t("generate.rePreview") }}</el-button>
              <el-button type="primary" :disabled="!gen.preview?.generatable || gen.loading" :loading="busy === 'run'" @click="runGen">
                {{ t("generate.genBtn", { n: num(gen.preview?.generatable ? gen.preview.qty : 0) }) }}
              </el-button>
            </div>
          </template>
        </div>

        <!-- ③ 分配：按生产组织汇总；对接 MES 的自动分批推送 -->
        <div v-else-if="step === 'alloc'" class="panel">
          <div class="prow">
            <div class="k">{{ t("generate.scope") }}</div>
            <div class="v"><span>{{ allocScopeText }}</span></div>
          </div>
          <div class="prow">
            <div class="k">{{ t("generate.allocPanel") }}</div>
            <div v-loading="alloc.loading" class="v">
              <el-table v-if="alloc.data?.orgs.length" :data="alloc.data.orgs" size="small">
                <el-table-column :label="t('common.factory')" min-width="140">
                  <template #default="{ row }">{{ row.org_name || nameOf(row.org_code) }}<span class="muted">（{{ row.org_code }}）</span></template>
                </el-table-column>
                <el-table-column :label="t('common.mes')" width="90">
                  <template #default="{ row }"><el-tag size="small" :type="row.mes_enabled ? 'success' : 'info'">{{ row.mes_enabled ? t("common.yes") : t("common.no") }}</el-tag></template>
                </el-table-column>
                <el-table-column :label="t('common.qty')" align="right" width="90">
                  <template #default="{ row }"><span class="num">{{ num(row.qty) }}</span></template>
                </el-table-column>
                <el-table-column :label="t('generate.pushes')" align="right" width="80">
                  <template #default="{ row }"><span class="num">{{ row.mes_enabled ? row.pushes : "—" }}</span></template>
                </el-table-column>
                <el-table-column :label="t('generate.billsCol')" min-width="140" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.bills.join("、") }}</template>
                </el-table-column>
                <el-table-column :label="t('common.material')" min-width="120" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.materials.join("、") || "—" }}</template>
                </el-table-column>
              </el-table>
              <span v-else-if="!alloc.loading" class="muted">{{ t("generate.nothingToAlloc") }}</span>
              <div v-if="alloc.data?.skipped.length" class="skipped">
                <span class="warn">{{ t("generate.skippedTitle") }}</span>
                <span v-for="s in alloc.data.skipped" :key="s.bill_no" class="muted">{{ s.bill_no }}（{{ num(s.qty) }}）：{{ t(`generate.skip.${s.reason}`) }}</span>
              </div>
            </div>
          </div>
          <div v-if="ctx.push.length" class="prow">
            <div class="k">{{ t("generate.pushStatus") }}</div>
            <div class="v">
              <div v-for="p in ctx.push" :key="p.factory_code" class="push-row">
                <div class="push-h">
                  <b>{{ nameOf(p.factory_code) }}</b>
                  <el-tag v-if="p.task" size="small" :type="TASK_TAG[p.task.state] || 'info'">{{ taskStateLabel(p.task.state) }}</el-tag>
                  <span class="muted num">{{ t("generate.pushProgress", { i: p.done_batches, n: p.total_batches, qty: num(p.pushed_qty) }) }}</span>
                  <el-button v-if="canResume(p)" size="small" type="warning" :loading="busy === `resume:${p.factory_code}`" @click="resume(p.factory_code)">
                    {{ t("generate.resumeBtn", { n: p.remaining.pushes }) }}
                  </el-button>
                </div>
                <span v-if="p.task?.message" class="muted">{{ p.task.message }}</span>
                <span v-if="p.last_problem" class="err">
                  {{ p.last_problem.batch_no }}：{{ p.last_problem.fail_reason || pushStateLabel(p.last_problem.push_state) }}
                </span>
              </div>
            </div>
          </div>
          <div class="act">
            <span class="msg">{{ t("generate.allocNote") }}</span>
            <el-button type="success" :disabled="!alloc.data?.qty" :loading="busy === 'alloc'" @click="doAllocate">
              {{ alloc.data?.pushes ? t("generate.allocBtnPush", { n: num(alloc.data?.qty || 0), m: alloc.data.pushes }) : t("generate.allocBtn", { n: num(alloc.data?.qty || 0) }) }}
            </el-button>
          </div>
        </div>

        <el-tabs v-model="dtab" class="dtabs">
          <!-- 订单：单据 → 物料行；勾选范围、点物料行配置 SN 规则 -->
          <el-tab-pane :label="t('generate.tabOrders')" name="orders">
            <div class="toolbar">
              <el-radio-group v-model="scope" size="small" @change="clearSelection">
                <el-radio-button value="BILLS">{{ t("generate.scopeBills") }}</el-radio-button>
                <el-radio-button value="LINES">{{ t("generate.scopeLines") }}</el-radio-button>
              </el-radio-group>
              <span class="muted">{{ scope === "BILLS" ? t("generate.scopeBillsHint") : t("generate.scopeLinesHint") }}</span>
              <el-button v-if="scope === 'LINES' && selectedLineRows.length" size="small" type="primary" plain @click="openRuleDialog(selectedLineRows)">
                {{ t("generate.ruleForSelected", { n: selectedLineRows.length }) }}
              </el-button>
              <el-button v-if="selBills.length || selLines.length" size="small" link @click="clearSelection">{{ t("generate.clearSel") }}</el-button>
            </div>
            <div v-for="b in ctx.bills" :key="b.bill_no" class="bill">
              <div class="bill-h">
                <el-checkbox v-if="scope === 'BILLS'" :model-value="selBills.includes(b.bill_no)" :disabled="!b.in_snapshot"
                  @change="(v: any) => toggleBill(b.bill_no, !!v)" />
                <span v-if="scope === 'BILLS' && selBills.includes(b.bill_no)" class="order">{{ selBills.indexOf(b.bill_no) + 1 }}</span>
                <b>{{ b.bill_no }}</b>
                <span class="muted">PO {{ b.po || "—" }} · {{ b.org_name || nameOf(b.org_code) }}</span>
                <el-tag v-if="!b.in_snapshot" size="small" type="info">{{ t("generate.notInSnapshot") }}</el-tag>
                <el-tag v-if="b.state.suspend" size="small" type="warning">{{ suspendLabel(b.state.suspend) }}</el-tag>
                <el-tag v-if="b.state.leave_state" size="small" type="danger">{{ leaveStateLabel(b.state.leave_state) }}</el-tag>
                <el-tag v-if="b.state.pi_frozen" size="small" type="danger">{{ t("generate.piFrozen") }}</el-tag>
                <el-tag v-if="b.state.open_alerts" size="small" type="warning">{{ t("generate.openAlerts", { n: b.state.open_alerts }) }}</el-tag>
                <span class="push muted num">{{ t("generate.billCounts", { total: num(b.total_qty), gen: num(b.generated_qty), quota: num(b.quota), pending: num(b.pending_alloc) }) }}</span>
              </div>
              <el-table v-if="b.lines.length" :data="b.lines" size="small" class="lines" :row-class-name="lineClass" @row-click="onLineClick">
                <el-table-column v-if="scope === 'LINES'" width="56">
                  <template #default="{ row }">
                    <span class="sel" @click.stop>
                      <el-checkbox :model-value="lineIdx(row) >= 0" @change="(v: any) => toggleLine(row, !!v)" />
                      <span v-if="lineIdx(row) >= 0" class="order">{{ lineIdx(row) + 1 }}</span>
                    </span>
                  </template>
                </el-table-column>
                <el-table-column :label="t('common.billSeq')" prop="seq" width="56" />
                <el-table-column :label="t('common.attachNo')" width="130">
                  <template #default="{ row }">
                    <span class="cell-edit">
                      <span v-if="row.attach_source === 'EMPTY'" class="err">{{ t("generate.attachEmpty") }}</span>
                      <span v-else>{{ row.attach_no }}<el-tag v-if="row.attach_source === 'MANUAL'" size="small" class="ml">{{ t("generate.attachManual") }}</el-tag></span>
                      <el-button v-if="row.attach_source !== 'K3'" link type="primary" size="small" @click.stop="fillAttach(row)">
                        <el-icon><EditPen /></el-icon>
                      </el-button>
                    </span>
                  </template>
                </el-table-column>
                <el-table-column :label="t('common.material')" min-width="130" show-overflow-tooltip>
                  <template #default="{ row }">
                    <div>{{ row.material_code || t("generate.noMaterial") }}</div>
                    <div class="muted">{{ row.material_name }}</div>
                  </template>
                </el-table-column>
                <el-table-column :label="t('common.length')" width="130">
                  <template #default="{ row }">
                    <span class="cell-edit">
                      <span v-if="row.length === null" class="err">{{ row.length_raw }}</span>
                      <span v-else>{{ row.length }}</span>
                      <el-tag v-if="row.length_source === 'DEFAULT'" size="small" type="warning">{{ t("generate.lengthDefault") }}</el-tag>
                      <el-tag v-else-if="row.length_source === 'MANUAL'" size="small">{{ t("generate.lengthManual") }}</el-tag>
                      <el-button v-if="row.length_source !== 'K3' || row.length === null" link type="primary" size="small" @click.stop="fillLength(row)">
                        <el-icon><EditPen /></el-icon>
                      </el-button>
                    </span>
                  </template>
                </el-table-column>
                <el-table-column :label="t('common.qty')" align="right" width="66">
                  <template #default="{ row }"><span class="num">{{ num(row.qty) }}</span></template>
                </el-table-column>
                <el-table-column :label="t('generate.generated')" align="right" width="70">
                  <template #default="{ row }">
                    <el-tooltip :content="t('generate.ownTip', { own: num(row.own_generated_qty), gen: num(row.generated_qty) })">
                      <span class="num">{{ num(row.generated_qty) }}</span>
                    </el-tooltip>
                  </template>
                </el-table-column>
                <el-table-column :label="t('generate.allocated')" align="right" width="70">
                  <template #default="{ row }"><span class="num">{{ num(row.allocated_qty) }}</span></template>
                </el-table-column>
                <el-table-column :label="t('generate.quota')" align="right" width="66">
                  <template #default="{ row }"><span class="num" :class="{ zero: !row.quota }">{{ num(row.quota) }}</span></template>
                </el-table-column>
                <el-table-column :label="t('generate.ruleCol')" min-width="220">
                  <template #default="{ row }">
                    <div v-if="row.rule" class="line-rule">
                      <el-tag size="small" :type="SOURCE_TAG[row.rule.source]">{{ t(`generate.source.${row.rule.source}`) }}</el-tag>
                      <span class="sn-mono">{{ row.rule.format }}</span>
                      <el-tag v-if="row.rule.plan === 'PREFIX'" size="small" type="danger">{{ t("generate.plan.PREFIX") }}</el-tag>
                    </div>
                    <div v-if="row.rule?.first_sn" class="muted sn-mono">{{ t("generate.firstSn", { sn: row.rule.first_sn }) }}</div>
                    <el-button v-if="!row.rule" link type="primary" @click.stop="openRuleDialog([row])">{{ t("generate.needRule") }}</el-button>
                    <div v-if="row.issues?.length" class="issue-tags">
                      <el-tag v-for="i in row.issues" :key="i" size="small" type="danger" effect="plain">{{ issueText(i) }}</el-tag>
                    </div>
                  </template>
                </el-table-column>
              </el-table>
              <p v-else class="muted no-lines">{{ t("generate.noLines") }}</p>
            </div>
          </el-tab-pane>

          <!-- 生成任务：最近 20 条，可撤销最后一次 -->
          <el-tab-pane :label="t('generate.tabJobs')" name="jobs">
            <el-table :data="ctx.jobs" size="small" :empty-text="t('common.empty')">
              <el-table-column type="expand">
                <template #default="{ row }">
                  <el-table :data="row.lines" size="small" class="job-lines">
                    <el-table-column :label="t('common.billNo')" prop="bill_no" min-width="110" />
                    <el-table-column :label="t('common.billSeq')" prop="seq" width="60" />
                    <el-table-column :label="t('common.material')" prop="material_code" min-width="90" />
                    <el-table-column :label="t('common.length')" prop="length" width="64" />
                    <el-table-column :label="t('common.qty')" prop="qty" align="right" width="70" />
                    <el-table-column :label="t('generate.segment')" min-width="240">
                      <template #default="{ row: l }"><span class="sn-mono">{{ l.start_sn }} ~ {{ l.end_sn }}</span></template>
                    </el-table-column>
                    <el-table-column :label="t('generate.ruleCol')" prop="rule_label" min-width="180" show-overflow-tooltip />
                  </el-table>
                </template>
              </el-table-column>
              <el-table-column :label="t('generate.genBatch')" prop="gen_batch" min-width="190" show-overflow-tooltip />
              <el-table-column :label="t('generate.scope')" width="80">
                <template #default="{ row }">{{ t(`generate.scopeShort.${row.scope}`) }}</template>
              </el-table-column>
              <el-table-column :label="t('common.qty')" align="right" width="80">
                <template #default="{ row }"><span class="num">{{ num(row.qty) }}</span></template>
              </el-table-column>
              <el-table-column :label="t('generate.segment')" min-width="250">
                <template #default="{ row }"><span class="sn-mono">{{ row.start_sn }} ~ {{ row.end_sn }}</span></template>
              </el-table-column>
              <el-table-column :label="t('common.status')" width="100">
                <template #default="{ row }">
                  <el-tooltip :disabled="!row.error_msg && !row.revoke_reason" :content="row.error_msg || row.revoke_reason || ''">
                    <el-tag size="small" :type="JOB_TAG[row.status] || 'info'">{{ t(`generate.job.${row.status}`) }}</el-tag>
                  </el-tooltip>
                </template>
              </el-table-column>
              <el-table-column :label="t('common.operator')" prop="created_by" width="90" />
              <el-table-column :label="t('common.time')" width="150">
                <template #default="{ row }">{{ fmtTime(row.created_at) }}</template>
              </el-table-column>
              <el-table-column :label="t('common.actions')" width="80" align="right">
                <template #default="{ row }">
                  <el-button v-if="row.revocable" link type="danger" :loading="busy === `revoke:${row.gen_batch}`" @click="revoke(row)">{{ t("generate.revoke") }}</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <!-- 分配记录：推送与拉取批次（最近 100 条）；未确认、失败的可人工确认或作废 -->
          <el-tab-pane :label="t('generate.tabBatches')" name="batches">
            <el-table :data="ctx.batches" size="small" :empty-text="t('common.empty')">
              <el-table-column :label="t('common.batchNo')" min-width="200">
                <template #default="{ row }">
                  <router-link :to="{ path: '/query', query: { tab: 'delivery', batch_no: row.batch_no } }" class="sn-mono">{{ row.batch_no }}</router-link>
                </template>
              </el-table-column>
              <el-table-column :label="t('common.factory')" min-width="110" show-overflow-tooltip>
                <template #default="{ row }">{{ nameOf(row.factory_code) }}</template>
              </el-table-column>
              <el-table-column :label="t('common.qty')" align="right" width="70">
                <template #default="{ row }"><span class="num">{{ num(row.qty) }}</span></template>
              </el-table-column>
              <el-table-column :label="t('generate.batchSource')" width="70">
                <template #default="{ row }">{{ batchSourceLabel(row.source) }}</template>
              </el-table-column>
              <el-table-column :label="t('generate.batchResult')" width="130">
                <template #default="{ row }">
                  <el-tooltip :disabled="!row.fail_reason" :content="row.fail_reason || ''">
                    <el-tag size="small" :type="BATCH_RESULT_TYPE[row.result] || 'info'">{{ batchResultLabel(row.result) }}</el-tag>
                  </el-tooltip>
                  <div v-if="row.push_state && row.push_state !== 'IDLE'" class="muted">{{ pushStateLabel(row.push_state) }}</div>
                </template>
              </el-table-column>
              <el-table-column :label="t('generate.confirmMethod')" width="100">
                <template #default="{ row }">{{ confirmMethodLabel(row.confirm_method) || "—" }}</template>
              </el-table-column>
              <el-table-column :label="t('common.operator')" prop="created_by" width="90" show-overflow-tooltip />
              <el-table-column :label="t('common.time')" width="150">
                <template #default="{ row }">{{ fmtTime(row.created_at) }}</template>
              </el-table-column>
              <el-table-column :label="t('common.actions')" width="130" align="right">
                <template #default="{ row }">
                  <template v-if="row.result === 'UNCONFIRMED' || row.result === 'FAILED'">
                    <el-button link type="primary" @click="openManual(row, 'confirm')">{{ t("generate.manualConfirm") }}</el-button>
                    <el-button v-if="row.voidable || row.result === 'UNCONFIRMED'" link type="danger" @click="openManual(row, 'void')">{{ t("generate.void") }}</el-button>
                  </template>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </div>
    </el-drawer>

    <RuleDialog v-if="ctx" v-model="ruleDlg.open" :pi="ctx.pi_no" :lines="ruleDlg.lines" :options="ctx.rule_options" @saved="reloadCtx" />

    <el-dialog v-model="manual.open" :title="manual.kind === 'void' ? t('generate.voidTitle') : t('generate.manualTitle')" width="520px">
      <p class="muted">{{ manual.kind === "void" ? t("generate.voidNote", { batch: manual.batch }) : t("generate.manualNote", { batch: manual.batch }) }}</p>
      <el-form label-width="80px" @submit.prevent>
        <el-form-item :label="t('common.reason')" required>
          <el-input v-model="manual.reason" type="textarea" :rows="2" maxlength="500" show-word-limit :placeholder="t('common.reasonPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('generate.basis')" required>
          <el-input v-model="manual.basis" type="textarea" :rows="2" maxlength="500" show-word-limit :placeholder="t('generate.basisPlaceholder')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="manual.open = false">{{ t("common.cancel") }}</el-button>
        <el-button :type="manual.kind === 'void' ? 'danger' : 'primary'" :disabled="!manual.reason.trim() || !manual.basis.trim()"
          :loading="busy === 'manual'" @click="submitManual">{{ t("common.confirm") }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "@/api";
import { ApiError } from "@/api/http";
import { clampQty, genQtyCap } from "@/snList.js";
import { useFactories } from "@/composables/useFactories";
import { useTime } from "@/composables/useTime";
import { formatDuration } from "@/utils/time";
import {
  BATCH_RESULT_TYPE, batchResultLabel, batchSourceLabel, confirmMethodLabel, leaveStateLabel, pushStateLabel, suspendLabel, taskStateLabel,
} from "@/constants";
import type { OrderLine } from "@/types/api";
import PageHead from "@/components/PageHead.vue";
import RuleDialog from "@/views/generate/RuleDialog.vue";

type Tab = "ALL" | "TO_GENERATE" | "GENERATING" | "TO_ALLOCATE" | "DONE";
type Step = "rule" | "gen" | "alloc";
type Action = "running" | "view" | "resume" | "alloc" | "gen" | "pick";
type TagType = "" | "info" | "primary" | "success" | "warning" | "danger";
type LineRef = { bill_no: string; seq: number };

const TABS: Tab[] = ["ALL", "TO_GENERATE", "GENERATING", "TO_ALLOCATE", "DONE"];
const STEPS: Step[] = ["rule", "gen", "alloc"];
const PAGE = 20;
/** 除「生成中」外各页签只列最近 3 页，更早的用搜索（F-10 第 2 条）。 */
const RECENT_PAGES = 3;
const LIST_LIMIT = 500;
const STATUS_TAG: Record<string, TagType> = {
  GENERATING: "primary", CANNOT: "danger", NEED_RULE: "warning", TO_GENERATE: "primary", PARTIAL: "primary",
  TO_ALLOCATE: "success", PUSH_INTERRUPTED: "danger", DONE: "info",
};
const ACTION_TYPE: Record<Action, TagType> = { running: "primary", view: "", resume: "warning", alloc: "success", gen: "primary", pick: "primary" };
const JOB_TAG: Record<string, TagType> = { RUNNING: "primary", SUCCESS: "success", FAILED: "danger", CANCELED: "info", REVOKED: "info" };

const TASK_TAG: Record<string, TagType> = { QUEUED: "primary", RUNNING: "primary", WAITING: "warning", STOPPED: "danger", DONE: "success" };
const SOURCE_TAG: Record<string, TagType> = { LINE: "success", PI: "primary", CUSTOMER: "warning" };
const ACTIVE_TASK = ["QUEUED", "RUNNING", "WAITING"];

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const { nameOf } = useFactories();
const { fmt: fmtTime } = useTime();
const num = (n: number | string | null | undefined) => (n == null ? "—" : Number(n).toLocaleString());
const pct = (b: { qty: number; done_qty: number }) => (b.qty ? Math.min(100, Math.floor((b.done_qty * 100) / b.qty)) : 0);
const issueText = (c: string) => t(`generate.issue.${c}`);
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
const busy = ref("");

// ================================================================ PI 列表

const tab = ref<Tab>("ALL");
const q = ref("");
const page = ref(1);
const listLoading = ref(false);
const list = ref<any>({ items: [], capped: false, summary: { quota: 0, pending_alloc: 0, unbatched: 0 }, synced_at: null });
const limited = computed(() => !q.value.trim() && tab.value !== "GENERATING" && list.value.items.length > PAGE * RECENT_PAGES);
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

/** 行上按钮（F-10 第 3 条，按顺序判断）。 */
function actionOf(row: any): Action {
  if (row.status === "GENERATING") return "running";
  if (row.status === "CANNOT") return "view";
  if (row.push_interrupted) return "resume";
  if (tab.value === "TO_ALLOCATE" && row.pending_alloc > 0) return "alloc";
  if (row.quota > 0) return row.status === "NEED_RULE" ? "pick" : "gen";
  if (row.pending_alloc > 0) return "alloc";
  return "view";
}

const ACTION_STEP: Partial<Record<Action, Step>> = { running: "gen", resume: "alloc", alloc: "alloc", gen: "gen", pick: "rule" };
function openRow(row: any) {
  openDetail({ pi: row.pi_no }, ACTION_STEP[actionOf(row)]);
}

// ================================================================ 详情

const detailPi = ref("");
const ctx = ref<any>(null);
const ctxLoading = ref(false);
const step = ref<Step>("rule");
const dtab = ref("orders");
let ctxTimer: ReturnType<typeof setTimeout> | undefined;

const allLines = computed<OrderLine[]>(() => (ctx.value ? ctx.value.bills.flatMap((b: any) => b.lines) : []));
const needRuleLines = computed(() => allLines.value.filter((l) => l.quota > 0 && (l.issues || []).some((i) => i === "NO_RULE" || i === "RULE_PREFIX")));
const limits = computed(() => ctx.value?.limits || { default: 10000, max: 500000, sync_threshold: 5000 });
const stepDone = computed<Record<Step, boolean>>(() => {
  const c = ctx.value;
  if (!c) return { rule: false, gen: false, alloc: false };
  const pushLeft = c.push.some((p: any) => p.remaining?.pushes > 0);
  return {
    rule: needRuleLines.value.length === 0,
    gen: c.summary.quota === 0 && !c.running_job,
    alloc: c.summary.pending_alloc === 0 && !pushLeft && c.summary.generated_qty > 0,
  };
});
const allDone = computed(() => stepDone.value.rule && stepDone.value.gen && stepDone.value.alloc);

function autoStep(): Step {
  const c = ctx.value;
  if (c.running_job) return "gen";
  if (c.summary.quota > 0) return needRuleLines.value.length ? "rule" : "gen";
  return "alloc";
}

async function openDetail(key: { pi?: string; bill_no?: string }, want?: Step) {
  detailPi.value = key.pi || key.bill_no || "";
  ctx.value = null;
  clearSelection();
  dtab.value = "orders";
  await loadCtx(key);
  if (!ctx.value) return;
  detailPi.value = ctx.value.pi_no;
  step.value = want ?? autoStep();
  if (route.query.pi !== ctx.value.pi_no) router.replace({ query: { pi: ctx.value.pi_no } });
  afterStepChange();
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
  scheduleCtxRefresh();
}

async function reloadCtx() {
  if (!detailPi.value) return;
  await loadCtx({ pi: detailPi.value });
  afterStepChange();
  loadList(true);
}

/** 有推送任务在跑时，定时刷新详情里的推送状态。 */
function scheduleCtxRefresh() {
  clearTimeout(ctxTimer);
  if (ctx.value?.push.some((p: any) => p.task && ACTIVE_TASK.includes(p.task.state))) {
    ctxTimer = setTimeout(() => detailPi.value && loadCtx({ pi: detailPi.value }), 5000);
  }
}

function closeDetail() {
  detailPi.value = "";
  ctx.value = null;
  stopPoll();
  clearTimeout(ctxTimer);
  if (route.query.pi || route.query.bill_no) router.replace({ query: {} });
}

function ruleTitle(r: any) {
  if (r.mode === "CUSTOM") return t("generate.rule.custom");
  return `${r.rule_code} v${r.version}`;
}

// ---------------- 规则：PI 绑定 / 客户模板 / 物料行

const bind = reactive({ rule_id: undefined as number | undefined, new_prefix: "" });
const tpl = reactive({ new_prefix: "" });
const bindOption = computed(() => ctx.value?.rule_options.find((o: any) => o.rule_id === bind.rule_id));
const ruleDlg = reactive({ open: false, lines: [] as OrderLine[] });

function openRuleDialog(lines: OrderLine[]) {
  ruleDlg.lines = lines;
  ruleDlg.open = true;
}

async function bindPi() {
  busy.value = "bindPi";
  try {
    const body: Record<string, unknown> = { pi_no: ctx.value.pi_no, rule_id: bind.rule_id };
    if (bindOption.value?.plan === "PREFIX") body.new_prefix = bind.new_prefix.trim();
    await api.bindPiRule(body);
    ElMessage.success(t("common.saved"));
    bind.rule_id = undefined;
    bind.new_prefix = "";
    await reloadCtx();
  } finally {
    busy.value = "";
  }
}

async function unbindPi() {
  await ElMessageBox.confirm(t("generate.unbindPiConfirm"), t("generate.piRule"), { type: "warning" });
  busy.value = "unbindPi";
  try {
    await api.unbindPiRule(ctx.value.pi_no);
    ElMessage.success(t("common.done"));
    await reloadCtx();
  } finally {
    busy.value = "";
  }
}

async function adoptTemplate() {
  busy.value = "template";
  try {
    await api.customerTemplate({ pi_no: ctx.value.pi_no, new_prefix: tpl.new_prefix.trim() });
    ElMessage.success(t("common.saved"));
    tpl.new_prefix = "";
    await reloadCtx();
  } finally {
    busy.value = "";
  }
}

async function unbindCustomer() {
  await ElMessageBox.confirm(t("generate.unbindCustomerConfirm", { customer: ctx.value.customer_code }), t("generate.customerRule"), { type: "warning" });
  busy.value = "unbindCustomer";
  try {
    await api.unbindCustomerRule(ctx.value.customer_code);
    ElMessage.success(t("common.done"));
    await reloadCtx();
  } finally {
    busy.value = "";
  }
}

// ---------------- 范围选择：整单按勾选顺序 / 物料行按点选顺序

const scope = ref<"BILLS" | "LINES">("BILLS");
const selBills = ref<string[]>([]);
const selLines = ref<LineRef[]>([]);

function clearSelection() {
  selBills.value = [];
  selLines.value = [];
}
function toggleBill(no: string, on: boolean) {
  selBills.value = on ? [...selBills.value.filter((x) => x !== no), no] : selBills.value.filter((x) => x !== no);
}
const lineIdx = (l: LineRef) => selLines.value.findIndex((x) => x.bill_no === l.bill_no && x.seq === l.seq);
function toggleLine(l: LineRef, on: boolean) {
  const rest = selLines.value.filter((x) => !(x.bill_no === l.bill_no && x.seq === l.seq));
  selLines.value = on ? [...rest, { bill_no: l.bill_no, seq: l.seq }] : rest;
}
const selectedLineRows = computed(() =>
  selLines.value.map((r) => allLines.value.find((l) => l.bill_no === r.bill_no && l.seq === r.seq)).filter(Boolean) as OrderLine[],
);
function onLineClick(row: OrderLine) {
  if (scope.value === "LINES") toggleLine(row, lineIdx(row) < 0);
  else openRuleDialog([row]);
}

/** 补填附件项次（金蝶为空时）、长度（金蝶为空或不是数字时）；保存后刷新详情并重新预演。 */
async function fillAttach(row: OrderLine) {
  let value: string;
  try {
    const r = await ElMessageBox.prompt(t("orders.attachFillHint"), t("orders.attachFillTitle", { bill: row.bill_no, seq: row.seq }), {
      inputValue: row.attach_manual || "",
      inputValidator: (v: string) => (!!v && !!v.trim() && v.trim().length <= 64) || t("orders.attachInvalid"),
    });
    value = (r as { value: string }).value.trim();
  } catch {
    return;
  }
  await api.fillAttach(row.bill_no, row.seq, value);
  ElMessage.success(t("common.saved"));
  await reloadCtx();
}
async function fillLength(row: OrderLine) {
  let value: string;
  try {
    const r = await ElMessageBox.prompt(t("orders.lengthFillHint"), t("orders.lengthFillTitle", { bill: row.bill_no, seq: row.seq }), {
      inputValue: row.length_manual || "",
      inputValidator: (v: string) => /^(?=.*\d)\d*(\.\d*)?$/.test((v || "").trim()) || t("orders.lengthInvalid"),
    });
    value = (r as { value: string }).value.trim();
  } catch {
    return;
  }
  await api.fillLength(row.bill_no, row.seq, value);
  ElMessage.success(t("common.saved"));
  await reloadCtx();
}
function lineClass({ row }: { row: OrderLine }) {
  return scope.value === "LINES" && lineIdx(row) >= 0 ? "picked" : "";
}

/** 未勾选时：整单生成取快照中还有额度的全部单据（按单据编号）。 */
const defaultBills = computed<string[]>(() => (ctx.value ? ctx.value.bills.filter((b: any) => b.in_snapshot && b.quota > 0).map((b: any) => b.bill_no) : []));
const genScope = computed(() =>
  scope.value === "LINES" && selLines.value.length
    ? { scope: "LINES", lines: selLines.value }
    : { scope: "BILLS", bills: selBills.value.length ? selBills.value : defaultBills.value },
);
const scopeText = computed(() => {
  if (scope.value === "LINES" && selLines.value.length) return t("generate.scopeLinesN", { n: selLines.value.length });
  if (selBills.value.length) return t("generate.scopeBillsN", { n: selBills.value.length, bills: selBills.value.join("、") });
  return t("generate.scopeAllBills", { n: defaultBills.value.length });
});
const allocScope = computed(() =>
  scope.value === "LINES" && selLines.value.length ? { lines: selLines.value } : selBills.value.length ? { bills: selBills.value } : {},
);
const allocScopeText = computed(() => {
  if (scope.value === "LINES" && selLines.value.length) return t("generate.scopeLinesN", { n: selLines.value.length });
  if (selBills.value.length) return t("generate.scopeBillsN", { n: selBills.value.length, bills: selBills.value.join("、") });
  return t("generate.scopeWholePi");
});

// ---------------- 生成：自动预演 → 凭令牌生成 → 进度 / 取消 / 撤销

const gen = reactive({ qty: undefined as number | undefined, unlocked: false, corrected: false, loading: false, preview: null as any, error: "" });
const quota = computed(() => Number(gen.preview?.quota ?? 0));
const cap = computed(() => genQtyCap(quota.value, limits.value, gen.unlocked));
let previewTimer: ReturnType<typeof setTimeout> | undefined;

function schedulePreview() {
  clearTimeout(previewTimer);
  previewTimer = setTimeout(runPreview, 400);
}

async function runPreview() {
  if (!ctx.value || step.value !== "gen" || running.value) return;
  gen.loading = true;
  gen.error = "";
  try {
    const p = await api.genPreview({ pi_no: ctx.value.pi_no, ...genScope.value, qty: gen.qty }, true);
    gen.preview = p;
    if (gen.qty === undefined && p.generatable) gen.qty = p.qty;
  } catch (e) {
    gen.preview = null;
    gen.error = e instanceof ApiError ? e.message : String(e);
  } finally {
    gen.loading = false;
  }
}

function resetGen() {
  Object.assign(gen, { qty: undefined, unlocked: false, corrected: false, preview: null, error: "" });
}

function onQty(v: number | undefined) {
  const r = clampQty(v, cap.value);
  gen.qty = r.qty || undefined;
  gen.corrected = r.corrected;
  schedulePreview();
}

function onUnlock() {
  gen.qty = genQtyCap(quota.value, limits.value, gen.unlocked) || undefined;
  gen.corrected = false;
  schedulePreview();
}

async function runGen() {
  const p = gen.preview;
  await ElMessageBox.confirm(t("generate.genConfirm", { n: num(p.qty), a: p.start_sn, b: p.end_sn }), t("generate.step.gen"), { type: "warning" });
  busy.value = "run";
  try {
    const job = await api.genRun(p.token);
    if (job.status === "RUNNING") {
      startPoll(job);
    } else {
      ElMessage.success(t("generate.genDone", { n: num(job.qty) }));
      resetGen();
      await reloadCtx();
    }
  } catch {
    // 预演失效等：重新预演
    gen.qty = undefined;
    schedulePreview();
  } finally {
    busy.value = "";
  }
}

const running = ref<any>(null);
let pollTimer: ReturnType<typeof setInterval> | undefined;

function startPoll(job: any) {
  running.value = job;
  step.value = "gen";
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
  if (r.status === "SUCCESS") ElMessage.success(t("generate.genDone", { n: num(r.qty) }));
  else if (r.status === "CANCELED") ElMessage.info(t("generate.genCanceled"));
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

async function revoke(job: any) {
  const { value } = await ElMessageBox.prompt(t("generate.revokeConfirm", { n: num(job.qty), a: job.start_sn, b: job.end_sn }), t("generate.revoke"), {
    type: "warning",
    inputType: "textarea",
    inputPlaceholder: t("common.reasonPlaceholder"),
    inputValidator: (v: string) => (!!v && !!v.trim() && v.trim().length <= 500) || t("common.reasonPlaceholder"),
  });
  busy.value = `revoke:${job.gen_batch}`;
  try {
    await api.revokeGen(job.gen_batch, value.trim());
    ElMessage.success(t("generate.revoked"));
    await reloadCtx();
  } finally {
    busy.value = "";
  }
}

// ---------------- 分配与推送

const alloc = reactive({ loading: false, data: null as any });

async function loadAlloc() {
  if (!ctx.value) return;
  alloc.loading = true;
  try {
    alloc.data = await api.allocPreview({ pi_no: ctx.value.pi_no, ...allocScope.value });
  } catch {
    alloc.data = null;
  } finally {
    alloc.loading = false;
  }
}

async function doAllocate() {
  const d = alloc.data;
  const msg = d.pushes ? t("generate.allocConfirmPush", { n: num(d.qty), m: d.pushes }) : t("generate.allocConfirm", { n: num(d.qty) });
  await ElMessageBox.confirm(msg, t("generate.step.alloc"), { type: "warning" });
  busy.value = "alloc";
  try {
    const r = await api.allocate({ pi_no: ctx.value.pi_no, ...allocScope.value });
    ElMessage.success(t("generate.allocDone", { n: num(r.qty) }));
    if (r.skipped?.length) ElMessage.warning(t("generate.allocSkipped", { n: r.skipped.length }));
    clearSelection();
    await reloadCtx();
  } finally {
    busy.value = "";
  }
}

const canResume = (p: any) => p.mes_enabled && p.remaining?.pushes > 0 && !(p.task && ACTIVE_TASK.includes(p.task.state));

async function resume(org: string) {
  busy.value = `resume:${org}`;
  try {
    const r = await api.resumePush(org, ctx.value.pi_no);
    ElMessage.success(t("generate.resumed", { n: r.pushes }));
    setTimeout(reloadCtx, 800);
  } finally {
    busy.value = "";
  }
}

// ---------------- 批次人工确认 / 作废（5.2.5 第 7 条）

const manual = reactive({ open: false, kind: "confirm" as "confirm" | "void", batch: "", reason: "", basis: "" });
function openManual(row: any, kind: "confirm" | "void") {
  Object.assign(manual, { open: true, kind, batch: row.batch_no, reason: "", basis: "" });
}
async function submitManual() {
  busy.value = "manual";
  try {
    const f = manual.kind === "void" ? api.voidBatch : api.confirmBatch;
    await f(manual.batch, manual.reason.trim(), manual.basis.trim());
    ElMessage.success(t("common.done"));
    manual.open = false;
    await reloadCtx();
  } finally {
    busy.value = "";
  }
}

// ================================================================ 联动

function afterStepChange() {
  if (!ctx.value) return;
  if (step.value === "gen") schedulePreview();
  if (step.value === "alloc") loadAlloc();
}

watch(step, afterStepChange);
watch([scope, selBills, selLines], () => {
  resetGen();
  afterStepChange();
}, { deep: true });

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
  clearTimeout(ctxTimer);
  clearTimeout(previewTimer);
});
</script>

<style scoped>
.snap { display: inline-flex; gap: 8px; align-items: center; }
.kw { width: 260px; }
.summary { margin-left: auto; }
.ml { margin-left: 6px; }
.w160 { width: 160px; }
.w320 { width: 320px; }
.zero { color: var(--el-text-color-placeholder); }
.warn { color: var(--el-color-warning-dark-2); }
.err { color: var(--el-color-danger); font-size: 13px; }
.row-state { display: inline-flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.mini-bar { width: 60px; height: 6px; border-radius: 3px; overflow: hidden; background: var(--el-fill-color-dark); display: inline-block; }
.mini-bar i { display: block; height: 100%; background: var(--el-color-primary); }
.dh { display: flex; flex-direction: column; gap: 6px; }
.dh-title { display: flex; gap: 12px; align-items: baseline; font-size: 16px; color: var(--app-text); }
.kv { display: flex; flex-wrap: wrap; gap: 4px 16px; font-size: 12px; color: var(--app-muted); }
.kv b { color: var(--app-text); font-weight: 600; }
.drawer { display: flex; flex-direction: column; gap: 14px; }
.steps { display: flex; align-items: center; }
.step { display: inline-flex; align-items: center; gap: 8px; border: none; background: none; cursor: pointer; font: inherit; color: var(--app-muted); padding: 4px 0; }
.step.cur { color: var(--app-text); font-weight: 600; }
.dot { width: 24px; height: 24px; border-radius: 50%; border: 1.5px solid var(--el-border-color-darker); display: inline-flex; align-items: center; justify-content: center; font-size: 12px; }
.step.cur .dot { border-color: var(--el-color-primary); background: var(--el-color-primary); color: #fff; }
.step.ok .dot { border-color: var(--el-color-success); color: var(--el-color-success); }
.steps .line { flex: 1; min-width: 16px; height: 1px; margin: 0 12px; background: var(--el-border-color); }
.steps .line.ok { background: var(--el-color-success); }
.done-tag { margin-left: 16px; }
.panel { border: 1px solid var(--el-border-color-lighter); border-radius: 10px; }
.prow { display: grid; grid-template-columns: 96px minmax(0, 1fr); gap: 12px; padding: 12px 16px; }
.prow + .prow { border-top: 1px solid var(--el-border-color-lighter); }
.prow .k { color: var(--app-muted); line-height: 32px; }
.prow .v { min-height: 32px; display: flex; flex-direction: column; justify-content: center; gap: 8px; min-width: 0; }
.act { display: flex; align-items: center; gap: 12px; padding: 10px 16px; border-top: 1px solid var(--el-border-color-lighter); background: var(--el-fill-color-light); border-radius: 0 0 10px 10px; }
.act .msg { flex: 1; min-width: 0; color: var(--app-muted); font-size: 12px; }
.act .msg.err { color: var(--el-color-danger); }
.rule-now, .bind, .qty-row, .range, .push-h, .line-rule, .issue-tags { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.issue-tags { margin-top: 4px; gap: 4px; }
.qty { width: 180px; }
.issues { margin: 0; padding-left: 18px; font-size: 13px; max-height: 200px; overflow-y: auto; }
.cell-edit { display: inline-flex; align-items: center; gap: 4px; flex-wrap: wrap; }
.skipped { display: flex; flex-direction: column; gap: 2px; font-size: 12px; }
.push-row { display: flex; flex-direction: column; gap: 2px; padding: 6px 0; border-bottom: 1px dashed var(--el-border-color-lighter); }
.push-row:last-child { border-bottom: none; }
.prog { padding: 16px; display: flex; flex-direction: column; gap: 12px; }
.prog-h { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
.prog-h .pct { font-size: 28px; color: var(--el-color-primary); }
.prog-h .push { margin-left: auto; }
.prog.cx .pct { color: var(--el-color-warning); }
.run-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.run-grid > div { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.bill { border: 1px solid var(--el-border-color-lighter); border-radius: 8px; margin-bottom: 10px; overflow: hidden; }
.bill-h { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 8px 12px; background: var(--el-fill-color-light); }
.bill-h .push { margin-left: auto; }
.order { display: inline-flex; align-items: center; justify-content: center; min-width: 18px; height: 18px; padding: 0 4px; border-radius: 9px; font-size: 11px; color: #fff; background: var(--el-color-primary); }
.sel { display: inline-flex; align-items: center; gap: 4px; }
.lines :deep(tr.picked > td) { background: var(--el-color-primary-light-9); }
.lines :deep(.el-table__row) { cursor: pointer; }
.no-lines { padding: 8px 12px; margin: 0; }
.job-lines { margin: 0 12px 0 48px; width: auto; }
</style>

<style>
.gen-drawer .el-drawer__header { margin-bottom: 12px; align-items: flex-start; }
</style>
