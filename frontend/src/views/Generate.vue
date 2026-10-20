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

    <!-- 详情：步骤条「规则与信息 → 生成 → 分配」+ 当前步骤面板；下方物料行（勾选即范围）；底部生成记录、分配记录 -->
    <el-drawer :model-value="!!detailPi" size="min(1240px, 94vw)" class="gen-drawer" @close="closeDetail">
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

        <!-- ① 规则与信息：有额度的物料行还差什么（规则、附件项次、冻结），以及 PI / 客户规则 -->
        <div v-if="step === 'rule'" class="panel">
          <div class="prow">
            <div class="k">{{ t("generate.todo") }}</div>
            <div class="v">
              <span v-if="!todoGroups.length" class="ok-text"><el-icon><Check /></el-icon>{{ t("generate.prepReady") }}</span>
              <div v-for="g in todoGroups" :key="g.code" class="todo">
                <el-tag size="small" type="danger" effect="plain">{{ issueText(g.code) }}</el-tag>
                <span class="num">{{ t("generate.issueLines", { n: g.lines.length }) }}</span>
                <span class="muted">{{ t(`generate.todoHow.${g.code}`) }}</span>
                <el-button v-if="g.code === 'NO_RULE' || g.code === 'RULE_PREFIX'" size="small" type="primary" plain @click="openRuleDialog(g.lines)">
                  {{ t("generate.pickForNeeded") }}
                </el-button>
                <router-link v-else-if="g.code === 'PI_FROZEN' || g.code === 'MATERIAL_LOST'" :to="{ path: '/orders', query: { bill_no: g.lines[0].bill_no } }">
                  {{ t("generate.toOrders") }}
                </router-link>
              </div>
              <span v-if="todoGroups.length" class="muted">{{ t("generate.todoNote") }}</span>
            </div>
          </div>
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
            <span class="msg">{{ t("generate.ruleNote") }}</span>
            <el-button type="primary" :plain="!stepDone.rule" @click="step = 'gen'">{{ t("generate.nextStep") }}</el-button>
          </div>
        </div>

        <!-- ② 生成：范围（下方勾选）→ 所选物料行整行生成 → 自动预演 → 确认生成；生成中显示进度可取消；可撤销流水最末的物料行 -->
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
                <span>{{ scopeText("gen") }}<el-button v-if="hasSel" link type="primary" class="ml" @click="clearSelection">{{ t("generate.clearSel") }}</el-button></span>
                <span class="muted">{{ t("generate.scopeHint") }}</span>
              </div>
            </div>
            <div class="prow">
              <div class="k">{{ t("common.qty") }}</div>
              <div class="v">
                <div class="qty-row">
                  <b class="num big">{{ num(quota) }}</b>
                  <span class="muted">{{ t("generate.wholeLines", { n: genLineCount }) }}</span>
                  <el-checkbox v-if="quota > limits.default && quota <= limits.max" v-model="gen.unlocked">{{ t("generate.unlock") }}</el-checkbox>
                </div>
                <span v-if="limitState === 'UNLOCK'" class="muted warn">{{ t("generate.limitHint", { limit: num(limits.default) }) }}</span>
              </div>
            </div>
            <div class="prow">
              <div class="k">{{ t("generate.preview") }}</div>
              <div v-loading="gen.loading" class="v">
                <span v-if="gen.error" class="err">{{ gen.error }}</span>
                <span v-else-if="nothingLeft" class="muted">{{ t("generate.nothingLeft") }}</span>
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
            <div v-if="ctx.revocable" class="prow">
              <div class="k">{{ t("generate.revokeTitle") }}</div>
              <div class="v">
                <div class="rule-now">
                  <span>{{ t("generate.tailLine", { bill: ctx.revocable.bill_no, seq: ctx.revocable.seq, n: num(ctx.revocable.qty) }) }}</span>
                  <span class="sn-mono muted">{{ ctx.revocable.start_sn }} ~ {{ ctx.revocable.end_sn }}</span>
                  <el-button size="small" type="danger" plain :loading="busy === revokeKey(ctx.revocable)" @click="revokeLine(ctx.revocable)">{{ t("generate.revokeLine") }}</el-button>
                </div>
                <span class="muted">{{ t("generate.revokeRule") }}</span>
              </div>
            </div>
            <div class="act">
              <span class="msg">{{ t("generate.genNote", { n: num(limits.sync_threshold) }) }}</span>
              <el-button v-if="ctx.summary.pending_alloc" @click="step = 'alloc'">{{ t("generate.nextAlloc") }}</el-button>
              <el-button :loading="gen.loading" @click="runPreview">{{ t("generate.rePreview") }}</el-button>
              <el-button type="primary" :disabled="!gen.preview?.generatable || gen.loading || !!limitState" :loading="busy === 'run'" @click="runGen">
                {{ t("generate.genBtn", { n: num(gen.preview?.generatable ? gen.preview.qty : 0) }) }}
              </el-button>
            </div>
          </template>
        </div>

        <!-- ③ 分配：按生产组织汇总；对接 MES 的自动分批推送 -->
        <div v-else-if="step === 'alloc'" class="panel">
          <div class="prow">
            <div class="k">{{ t("generate.scope") }}</div>
            <div class="v">
              <span>{{ scopeText("alloc") }}<el-button v-if="hasSel" link type="primary" class="ml" @click="clearSelection">{{ t("generate.clearSel") }}</el-button></span>
              <span class="muted">{{ t("generate.scopeHint") }}</span>
            </div>
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

        <!-- 物料行：单据 → 物料行；勾选单据或物料行即为生成、分配的范围；在此填写缺失的附件项次、长度，配置 SN 规则 -->
        <section class="lines-sec">
          <div class="toolbar">
            <b class="sec-title">{{ t("generate.linesTitle") }}</b>
            <el-radio-group v-model="lineFilter" size="small">
              <el-radio-button v-for="f in FILTERS" :key="f" :value="f">{{ t(`generate.filter.${f}`, { n: filterCount[f] }) }}</el-radio-button>
            </el-radio-group>
            <span class="push" />
            <span v-if="hasSel" class="muted">{{ selText }}</span>
            <el-button v-if="hasSel" size="small" link @click="clearSelection">{{ t("generate.clearSel") }}</el-button>
            <el-button size="small" :disabled="!pickedRows.length" @click="openRuleDialog(pickedRows)">
              {{ pickedRows.length ? t("generate.ruleForSelected", { n: pickedRows.length }) : t("generate.ruleForSelected0") }}
            </el-button>
            <el-button size="small" :loading="busy === 'lengths'" @click="fillLengths">{{ t("generate.lengthBatch") }}</el-button>
          </div>
          <p class="muted sel-hint">{{ t("generate.selHint") }}</p>
          <div v-for="b in shownBills" :key="b.bill_no" class="bill">
            <div class="bill-h">
              <el-checkbox v-bind="billCheck(b.bill_no)" :disabled="!b.in_snapshot || !b.lines.length" @change="(v: any) => onBill(b.bill_no, !!v)" />
              <span v-if="billCheck(b.bill_no).order >= 0" class="order">{{ billCheck(b.bill_no).order + 1 }}</span>
              <b>{{ b.bill_no }}</b>
              <span class="muted">PO {{ b.po || "—" }} · {{ b.org_name || nameOf(b.org_code) }}</span>
              <el-tag v-if="!b.in_snapshot" size="small" type="info">{{ t("generate.notInSnapshot") }}</el-tag>
              <el-tag v-if="b.state.suspend" size="small" type="warning">{{ suspendLabel(b.state.suspend) }}</el-tag>
              <el-tag v-if="b.state.leave_state" size="small" type="danger">{{ leaveStateLabel(b.state.leave_state) }}</el-tag>
              <el-tag v-if="b.state.pi_frozen" size="small" type="danger">{{ t("generate.piFrozen") }}</el-tag>
              <el-tag v-if="b.state.open_alerts" size="small" type="warning">{{ t("generate.openAlerts", { n: b.state.open_alerts }) }}</el-tag>
              <span class="push muted num">{{ t("generate.billCounts", { total: num(b.total_qty), gen: num(b.generated_qty), quota: num(b.quota), pending: num(b.pending_alloc) }) }}</span>
            </div>
            <el-table v-if="b.shown.length" :data="b.shown" size="small" class="lines" :row-class-name="lineClass" @row-click="onLineClick">
              <el-table-column width="52">
                <template #default="{ row }">
                  <span class="sel" @click.stop>
                    <el-checkbox :model-value="lineIdx(row) >= 0 || billCheck(row.bill_no).order >= 0" @change="(v: any) => onLine(row, !!v)" />
                    <span v-if="lineIdx(row) >= 0" class="order">{{ lineIdx(row) + 1 }}</span>
                  </span>
                </template>
              </el-table-column>
              <el-table-column :label="t('common.billSeq')" prop="seq" width="52" />
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
              <el-table-column :label="t('common.length')" width="100">
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
              <el-table-column :label="t('generate.generated')" align="right" width="80">
                <template #default="{ row }">
                  <el-tooltip :content="t('generate.ownTip', { own: num(row.own_generated_qty), gen: num(row.generated_qty) })">
                    <span class="num">{{ num(row.generated_qty) }}</span>
                  </el-tooltip>
                </template>
              </el-table-column>
              <el-table-column :label="t('generate.allocated')" align="right" width="80">
                <template #default="{ row }"><span class="num">{{ num(row.allocated_qty) }}</span></template>
              </el-table-column>
              <el-table-column :label="t('generate.quota')" align="right" width="70">
                <template #default="{ row }"><span class="num" :class="{ zero: !row.quota }">{{ num(row.quota) }}</span></template>
              </el-table-column>
              <el-table-column :label="t('common.status')" min-width="120">
                <template #default="{ row }">
                  <div v-if="row.quota && row.issues?.length" class="issue-tags">
                    <el-tag v-for="i in row.issues" :key="i" size="small" type="danger" effect="plain">{{ issueText(i) }}</el-tag>
                  </div>
                  <el-tag v-else size="small" :type="LINE_STATE_TAG[lineState(row)]" disable-transitions>{{ t(`generate.lineState.${lineState(row)}`) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column :label="t('generate.ruleCol')" min-width="220">
                <template #default="{ row }">
                  <div class="line-rule">
                    <template v-if="row.rule">
                      <el-tag size="small" :type="SOURCE_TAG[row.rule.source]">{{ t(`generate.source.${row.rule.source}`) }}</el-tag>
                      <span class="sn-mono">{{ row.rule.format }}</span>
                      <el-tag v-if="row.rule.plan === 'PREFIX'" size="small" type="danger">{{ t("generate.plan.PREFIX") }}</el-tag>
                    </template>
                    <el-button link type="primary" size="small" @click.stop="openRuleDialog([row])">{{ row.rule ? t("generate.changeRule") : t("generate.needRule") }}</el-button>
                  </div>
                  <div v-if="row.rule?.first_sn && row.quota" class="muted sn-mono">{{ t("generate.firstSn", { sn: row.rule.first_sn }) }}</div>
                </template>
              </el-table-column>
              <el-table-column width="76" align="right">
                <template #default="{ row }">
                  <el-tooltip v-if="isTail(row)" :content="t('generate.revokeRule')">
                    <el-button link type="danger" size="small" :loading="busy === revokeKey(ctx.revocable)" @click.stop="revokeLine(ctx.revocable)">
                      {{ t("generate.revokeGen") }}
                    </el-button>
                  </el-tooltip>
                </template>
              </el-table-column>
            </el-table>
            <p v-else-if="!b.lines.length" class="muted no-lines">{{ t("generate.noLines") }}</p>
          </div>
          <p v-if="!shownBills.length" class="muted no-lines">{{ t("common.empty") }}</p>
        </section>

        <el-tabs v-model="dtab" class="dtabs">
          <!-- 生成记录：最近 20 条；展开看各物料行号段，可撤销流水最末的物料行或整次剩下的物料行 -->
          <el-tab-pane :label="t('generate.tabJobs')" name="jobs">
            <el-table :data="ctx.jobs" size="small" :empty-text="t('common.empty')">
              <el-table-column type="expand">
                <template #default="{ row: job }">
                  <el-table :data="job.lines" size="small" class="job-lines">
                    <el-table-column :label="t('common.billNo')" prop="bill_no" min-width="110" />
                    <el-table-column :label="t('common.billSeq')" prop="seq" width="60" />
                    <el-table-column :label="t('common.material')" prop="material_code" min-width="90" />
                    <el-table-column :label="t('common.length')" prop="length" width="64" />
                    <el-table-column :label="t('common.qty')" prop="qty" align="right" width="70" />
                    <el-table-column :label="t('generate.segment')" min-width="220">
                      <template #default="{ row: l }"><span class="sn-mono" :class="{ struck: l.revoked }">{{ l.start_sn }} ~ {{ l.end_sn }}</span></template>
                    </el-table-column>
                    <el-table-column :label="t('generate.ruleCol')" prop="rule_label" min-width="150" show-overflow-tooltip />
                    <el-table-column width="80" align="right">
                      <template #default="{ row: l }">
                        <el-tag v-if="l.revoked" size="small" type="info">{{ t("generate.job.REVOKED") }}</el-tag>
                        <el-button v-else-if="l.revocable" link type="danger" :loading="busy === revokeKey({ ...l, gen_batch: job.gen_batch })"
                          @click="revokeLine({ ...l, gen_batch: job.gen_batch })">{{ t("generate.revoke") }}</el-button>
                      </template>
                    </el-table-column>
                  </el-table>
                </template>
              </el-table-column>
              <el-table-column :label="t('generate.genBatch')" prop="gen_batch" min-width="170" show-overflow-tooltip />
              <el-table-column :label="t('generate.scope')" width="80">
                <template #default="{ row }">{{ t(`generate.scopeShort.${row.scope}`) }}</template>
              </el-table-column>
              <el-table-column :label="t('common.qty')" align="right" width="100">
                <template #default="{ row }">
                  <span class="num">{{ num(row.qty) }}</span>
                  <div v-if="row.revoked_qty && row.status !== 'REVOKED'" class="muted">{{ t("generate.revokedQty", { n: num(row.revoked_qty) }) }}</div>
                </template>
              </el-table-column>
              <el-table-column :label="t('generate.segment')" min-width="220">
                <template #default="{ row }"><span class="sn-mono">{{ row.start_sn }} ~ {{ row.end_sn }}</span></template>
              </el-table-column>
              <el-table-column :label="t('common.status')" width="90">
                <template #default="{ row }">
                  <el-tooltip :disabled="!row.error_msg && !row.revoke_reason" :content="row.error_msg || row.revoke_reason || ''">
                    <el-tag size="small" :type="JOB_TAG[row.status] || 'info'">{{ t(`generate.job.${row.status}`) }}</el-tag>
                  </el-tooltip>
                </template>
              </el-table-column>
              <el-table-column :label="t('common.operator')" prop="created_by" width="80" show-overflow-tooltip />
              <el-table-column :label="t('common.time')" width="140">
                <template #default="{ row }">{{ fmtTime(row.created_at) }}</template>
              </el-table-column>
              <el-table-column :label="t('common.actions')" width="90" align="right">
                <template #default="{ row }">
                  <el-button v-if="row.revocable" link type="danger" :loading="busy === `revoke:${row.gen_batch}`" @click="revokeJob(row)">{{ t("generate.revokeRest") }}</el-button>
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
import { billMark, emptySel, genLimit, lineOrder, scopeBody, selectedLines, toggleBill, toggleLine } from "@/genScope.js";
import { useFactories } from "@/composables/useFactories";
import { useTime } from "@/composables/useTime";
import { formatDuration } from "@/utils/time";
import {
  BATCH_RESULT_TYPE, batchResultLabel, batchSourceLabel, confirmMethodLabel, leaveStateLabel, pushStateLabel, suspendLabel, taskStateLabel,
} from "@/constants";
import type { OrderLine } from "@/types/api";
import PageHead from "@/components/PageHead.vue";
import RuleDialog from "@/views/generate/RuleDialog.vue";

type Tab = "TO_GENERATE" | "TO_ALLOCATE" | "DONE";
type Step = "rule" | "gen" | "alloc";
type Action = "running" | "view" | "resume" | "alloc" | "gen" | "pick";
type TagType = "" | "info" | "primary" | "success" | "warning" | "danger";
type LineRef = { bill_no: string; seq: number };
type Filter = "ALL" | "TODO" | "GEN" | "ALLOC";
type LineState = "TODO" | "GEN" | "ALLOC" | "DONE" | "NONE";
/** 可撤销的物料行号段（ctx.revocable，或生成记录里标了 revocable 的那一行）。 */
type TailLine = { gen_batch: string; bill_no: string; seq: number; qty: number; start_sn: string; end_sn: string };

const TABS: Tab[] = ["TO_GENERATE", "TO_ALLOCATE", "DONE"];
const STEPS: Step[] = ["rule", "gen", "alloc"];
const FILTERS: Filter[] = ["ALL", "TODO", "GEN", "ALLOC"];
const PAGE = 20;
/** 各页签只列最近 3 页，更早的用搜索；生成中的 PI 排在「待生成」最前，不受此限（F-10 第 2 条）。 */
const RECENT_PAGES = 3;
const LIST_LIMIT = 500;
const STATUS_TAG: Record<string, TagType> = {
  GENERATING: "primary", CANNOT: "danger", NEED_RULE: "warning", TO_GENERATE: "primary", PARTIAL: "primary",
  TO_ALLOCATE: "success", PUSH_INTERRUPTED: "danger", DONE: "info",
};
const ACTION_TYPE: Record<Action, TagType> = { running: "primary", view: "", resume: "warning", alloc: "success", gen: "primary", pick: "primary" };
const JOB_TAG: Record<string, TagType> = { RUNNING: "primary", SUCCESS: "success", FAILED: "danger", CANCELED: "info", REVOKED: "info" };
const LINE_STATE_TAG: Record<LineState, TagType> = { TODO: "danger", GEN: "primary", ALLOC: "success", DONE: "info", NONE: "info" };

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
const dtab = ref("jobs");
let ctxTimer: ReturnType<typeof setTimeout> | undefined;

const allLines = computed<OrderLine[]>(() => (ctx.value ? ctx.value.bills.flatMap((b: any) => b.lines) : []));
/** 还有额度、但还不能生成的物料行（规则、附件项次、冻结等）。 */
const todoLines = computed(() => allLines.value.filter((l) => l.quota > 0 && (l.issues || []).length));
/** 待处理按原因归并：每种原因列出涉及的物料行（一行可有多个原因）。 */
const todoGroups = computed(() => {
  const groups = new Map<string, OrderLine[]>();
  for (const l of todoLines.value) for (const code of l.issues || []) groups.set(code, [...(groups.get(code) || []), l]);
  return [...groups.entries()].map(([code, lines]) => ({ code, lines }));
});
const limits = computed(() => ctx.value?.limits || { default: 10000, max: 500000, sync_threshold: 5000 });
const stepDone = computed<Record<Step, boolean>>(() => {
  const c = ctx.value;
  if (!c) return { rule: false, gen: false, alloc: false };
  const pushLeft = c.push.some((p: any) => p.remaining?.pushes > 0);
  return {
    rule: todoLines.value.length === 0,
    gen: c.summary.quota === 0 && !c.running_job,
    alloc: c.summary.pending_alloc === 0 && !pushLeft && c.summary.generated_qty > 0,
  };
});
const allDone = computed(() => stepDone.value.rule && stepDone.value.gen && stepDone.value.alloc);

function autoStep(): Step {
  const c = ctx.value;
  if (c.running_job) return "gen";
  if (c.summary.quota > 0) return todoLines.value.length ? "rule" : "gen";
  return "alloc";
}

async function openDetail(key: { pi?: string; bill_no?: string }, want?: Step) {
  detailPi.value = key.pi || key.bill_no || "";
  ctx.value = null;
  clearSelection();
  dtab.value = "jobs";
  lineFilter.value = "ALL";
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

// ---------------- 范围：在物料行里勾选单据（整单，按勾选顺序）或物料行（按点选顺序），生成、分配、统一设置长度共用

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
/** 选中的物料行（勾选的单据展开），按排号顺序；用于「为所选行选规则」。 */
const pickedRows = computed(() =>
  selectedLines(sel.value, linesOf)
    .map((r: LineRef) => allLines.value.find((l) => l.bill_no === r.bill_no && l.seq === r.seq))
    .filter(Boolean) as OrderLine[],
);
function onLineClick(row: OrderLine) {
  onLine(row, !(lineIdx(row) >= 0 || billCheck(row.bill_no).order >= 0));
}
function lineClass({ row }: { row: OrderLine }) {
  return lineIdx(row) >= 0 || billCheck(row.bill_no).order >= 0 ? "picked" : "";
}
const selText = computed(() =>
  sel.value.lines.length
    ? t("generate.scopeLinesN", { n: sel.value.lines.length })
    : t("generate.scopeBillsN", { n: sel.value.bills.length, bills: sel.value.bills.join("、") }),
);
/** 未勾选时：生成取快照中还有额度的全部单据（按单据编号），分配取整张 PI。 */
const defaultBills = computed<string[]>(() => (ctx.value ? ctx.value.bills.filter((b: any) => b.in_snapshot && b.quota > 0).map((b: any) => b.bill_no) : []));
const genScope = computed(() => scopeBody(sel.value) ?? { scope: "BILLS", bills: defaultBills.value });
const allocScope = computed(() => {
  const b = scopeBody(sel.value);
  return b ? (b.scope === "LINES" ? { lines: b.lines } : { bills: b.bills }) : {};
});
function scopeText(kind: "gen" | "alloc") {
  if (hasSel.value) return selText.value;
  return kind === "gen" ? t("generate.scopeAllBills", { n: defaultBills.value.length }) : t("generate.scopeWholePi");
}

// ---------------- 物料行：状态、筛选；附件项次、长度的填写

function lineState(l: OrderLine): LineState {
  if (l.quota > 0) return (l.issues || []).length ? "TODO" : "GEN";
  if (l.generated_qty > l.allocated_qty) return "ALLOC";
  return l.generated_qty > 0 ? "DONE" : "NONE";
}
const lineFilter = ref<Filter>("ALL");
const FILTER_OK: Record<Filter, (l: OrderLine) => boolean> = {
  ALL: () => true,
  TODO: (l) => lineState(l) === "TODO",
  GEN: (l) => l.quota > 0,
  ALLOC: (l) => l.generated_qty > l.allocated_qty,
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

/** 金蝶缺失的附件项次（必填）、长度（选填，留空用默认 000）在表格里直接填写；物料行有号后置灰：
 *  已生成未分配的撤销生成后可改，已分配的不能再改。失焦或回车保存，保存后刷新详情。 */
const DEFAULT_LENGTH = "000";
type DraftField = "attach" | "length";
const drafts = reactive<Record<string, string>>({});
const draftKey = (row: OrderLine, f: DraftField) => `${f}:${row.bill_no}/${row.seq}`;
function draftOf(row: OrderLine, f: DraftField): string {
  const k = draftKey(row, f);
  if (k in drafts) return drafts[k];
  return f === "attach" ? row.attach_no : row.length_source === "MANUAL" ? row.length : "";
}
function setDraft(row: OrderLine, f: DraftField, v: string) {
  drafts[draftKey(row, f)] = v;
}
function lockText(row: OrderLine) {
  return row.edit_lock === "ALLOCATED" ? t("generate.lockAllocated") : t("generate.lockGenerated");
}
async function saveDraft(row: OrderLine, f: DraftField, save: (value: string) => Promise<unknown>) {
  const k = draftKey(row, f);
  try {
    await save(drafts[k].trim());
    ElMessage.success(t("common.saved"));
    await reloadCtx();
  } finally {
    delete drafts[k];
  }
}
function saveAttach(row: OrderLine) {
  const value = (drafts[draftKey(row, "attach")] ?? "").trim();
  if (!value || value === row.attach_no) {
    if (!value) ElMessage.warning(t("orders.attachInvalid"));
    delete drafts[draftKey(row, "attach")];
    return;
  }
  return saveDraft(row, "attach", (v) => api.fillAttach(row.bill_no, row.seq, v));
}
function saveLength(row: OrderLine) {
  const value = (drafts[draftKey(row, "length")] ?? "").trim();
  if (value === (row.length_source === "MANUAL" ? row.length : "")) {
    delete drafts[draftKey(row, "length")];
    return;
  }
  return saveDraft(row, "length", (v) => api.fillLength(row.bill_no, row.seq, v));
}
/** 统一设置长度：所选物料行 / 勾选的单据 / 都没选 = 整张 PI 内长度还是默认 000、还没有号的物料行一次设为同一个值。 */
async function fillLengths() {
  if (!ctx.value) return;
  const range = hasSel.value ? selText.value : t("generate.scopeWholePiLines", { pi: ctx.value.pi_no });
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
    const r: any = await api.fillLengths({ pi_no: ctx.value.pi_no, value, ...allocScope.value });
    ElMessage.success(t("generate.lengthBatchDone", { n: r.filled, v: r.value }));
    await reloadCtx();
  } finally {
    busy.value = "";
  }
}

// ---------------- 生成：所选物料行整行生成 → 自动预演 → 凭令牌生成 → 进度 / 取消

const gen = reactive({ unlocked: false, loading: false, preview: null as any, error: "" });
const quota = computed(() => Number(gen.preview?.quota ?? 0));
/** 本次生成的物料行数：预演可生成时为号段数，否则为范围内还有额度的物料行数。 */
const genLineCount = computed(() => (gen.preview?.generatable ? gen.preview.items.length : (gen.preview?.issues?.filter((x: any) => x.bill_no).length ?? 0)));
/** 所选范围都已生成完（只剩「没有可生成额度」）：不算错误，提示去分配或改选。 */
const nothingLeft = computed(() => !!gen.preview && !gen.preview.generatable && issueGroups.value.every((g) => g.code === "QUOTA_EMPTY"));
const limitState = computed(() => (gen.preview?.generatable ? genLimit(gen.preview.qty, limits.value, gen.unlocked) : null));
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

/** 生成完成：刷新详情，额度都用完时直接进入分配（所选范围保留，便于接着分配这些物料行）。 */
async function afterGenerated() {
  resetGen();
  await reloadCtx();
  if (ctx.value && !ctx.value.summary.quota) step.value = "alloc";
}

async function runGen() {
  const p = gen.preview;
  await ElMessageBox.confirm(t("generate.genConfirm", { n: num(p.qty), m: p.items.length, a: p.start_sn, b: p.end_sn }), t("generate.step.gen"), { type: "warning" });
  busy.value = "run";
  try {
    const job = await api.genRun(p.token);
    if (job.status === "RUNNING") {
      startPoll(job);
    } else {
      ElMessage.success(t("generate.genDone", { n: num(job.qty) }));
      await afterGenerated();
    }
  } catch {
    // 预演失效等：重新预演
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
  if (r.status === "SUCCESS") await afterGenerated();
  else {
    resetGen();
    await reloadCtx();
  }
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

// ---------------- 撤销：最小单位物料行，只能从 PI 流水最末的物料行往前逐行撤；也可整次撤销剩下的物料行

const revokeKey = (x: { gen_batch: string; bill_no: string; seq: number }) => `revoke:${x.gen_batch}:${x.bill_no}/${x.seq}`;
const isTail = (l: OrderLine) => ctx.value?.revocable?.bill_no === l.bill_no && ctx.value?.revocable?.seq === l.seq;

async function askReason(message: string): Promise<string> {
  const { value } = await ElMessageBox.prompt(message, t("generate.revoke"), {
    type: "warning",
    inputType: "textarea",
    inputPlaceholder: t("common.reasonPlaceholder"),
    inputValidator: (v: string) => (!!v && !!v.trim() && v.trim().length <= 500) || t("common.reasonPlaceholder"),
  });
  return value.trim();
}

async function doRevoke(key: string, batch: string, reason: string, line?: LineRef) {
  busy.value = key;
  try {
    const r = await api.revokeGen(batch, reason, line);
    ElMessage.success(t("generate.revokedN", { n: num(r.qty) }));
    await reloadCtx();
  } finally {
    busy.value = "";
  }
}

async function revokeLine(x: TailLine) {
  const reason = await askReason(t("generate.revokeLineConfirm", { bill: x.bill_no, seq: x.seq, n: num(x.qty), a: x.start_sn, b: x.end_sn }));
  await doRevoke(revokeKey(x), x.gen_batch, reason, { bill_no: x.bill_no, seq: x.seq });
}

async function revokeJob(job: any) {
  const rest = job.lines.filter((l: any) => !l.revoked);
  const reason = await askReason(
    t("generate.revokeConfirm", {
      m: rest.length,
      n: num(rest.reduce((s: number, l: any) => s + l.qty, 0)),
      a: rest[0]?.start_sn,
      b: rest[rest.length - 1]?.end_sn,
    }),
  );
  await doRevoke(`revoke:${job.gen_batch}`, job.gen_batch, reason);
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
watch(sel, () => {
  resetGen();
  afterStepChange();
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
.prow { display: grid; grid-template-columns: 112px minmax(0, 1fr); gap: 12px; padding: 12px 16px; }
.prow + .prow { border-top: 1px solid var(--el-border-color-lighter); }
.prow .k { color: var(--app-muted); line-height: 32px; }
.prow .v { min-height: 32px; display: flex; flex-direction: column; justify-content: center; gap: 8px; min-width: 0; }
.act { display: flex; align-items: center; gap: 12px; padding: 10px 16px; border-top: 1px solid var(--el-border-color-lighter); background: var(--el-fill-color-light); border-radius: 0 0 10px 10px; }
.act .msg { flex: 1; min-width: 0; color: var(--app-muted); font-size: 12px; }
.act .msg.err { color: var(--el-color-danger); }
.rule-now, .bind, .qty-row, .range, .push-h, .line-rule, .issue-tags { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.issue-tags { gap: 4px; }
.big { font-size: 18px; }
.ok-text { display: inline-flex; align-items: center; gap: 6px; color: var(--el-color-success); }
.todo { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.lines-sec { display: flex; flex-direction: column; gap: 8px; }
.sec-title { font-size: 14px; }
.sel-hint { margin: -4px 0 0; font-size: 12px; }
.struck { text-decoration: line-through; color: var(--el-text-color-placeholder); }
.issues { margin: 0; padding-left: 18px; font-size: 13px; max-height: 200px; overflow-y: auto; }
.cell-input { display: block; }
.cell-input .missing :deep(.el-input__wrapper) { box-shadow: 0 0 0 1px var(--el-color-danger) inset; }
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
.bill-h .push, .toolbar .push { margin-left: auto; }
.order { display: inline-flex; align-items: center; justify-content: center; min-width: 18px; height: 18px; padding: 0 4px; border-radius: 9px; font-size: 11px; color: #fff; background: var(--el-color-primary); }
.sel { display: inline-flex; align-items: center; gap: 4px; }
.lines :deep(tr.picked > td) { background: var(--el-color-primary-light-9); }
.lines :deep(.el-table__row) { cursor: pointer; }
.no-lines { padding: 8px 12px; margin: 0; }
.job-lines { margin: 0 12px 0 48px; width: calc(100% - 60px); }
</style>

<style>
.gen-drawer .el-drawer__header { margin-bottom: 12px; align-items: flex-start; }
</style>
