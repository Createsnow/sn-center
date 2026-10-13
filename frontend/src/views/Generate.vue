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
            <el-button v-if="actionOf(row) === 'running'" size="small" type="primary" plain loading @click.stop="openDetail(row.pi)">{{ t("generate.jobRUNNING") }}</el-button>
            <el-button v-else-if="actionOf(row) === 'gen'" size="small" type="primary" @click.stop="openDetail(row.pi)">{{ t("generate.btnGen") }}</el-button>
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

    <!-- 详情：步骤条标明走到哪一步；中间只放当前要做的事（生成 / 生成中 / 分配），主按钮在面板底部；单据与记录收进标签页 -->
    <el-drawer :model-value="!!detailPi" size="760px" class="gen-drawer" @close="closeDetail">
      <template #header>
        <div v-if="ctx" class="dh">
          <div class="dh-title">
            <b>PI {{ ctx.pi_no }}</b>
            <el-tag size="small" round :type="STATE_TYPE[detailState] ?? 'primary'" disable-transitions>{{ detailStateText }}</el-tag>
          </div>
          <div class="muted small">{{ t("generate.detailSub", { c: ctx.customer_code, n: ctx.bills.length, o: orgCount, q: fmt(ctx.total_qty) }) }}</div>
        </div>
      </template>
      <div v-if="ctx" v-loading="ctxLoading" class="drawer">
        <div class="steps">
          <template v-for="(s, i) in steps" :key="s.key">
            <i v-if="i" class="line" :class="{ ok: i <= stepIdx }" />
            <div class="step" :class="{ ok: i < stepIdx, cur: i === stepIdx }">
              <span class="dot">
                <el-icon v-if="i < stepIdx"><Check /></el-icon>
                <el-icon v-else-if="i === stepIdx && detailRun" class="is-loading"><Loading /></el-icon>
                <template v-else>{{ i + 1 }}</template>
              </span>
              <span>{{ s.label }}</span>
              <span class="muted small num">{{ s.sub }}</span>
            </div>
          </template>
        </div>

        <el-alert v-for="msg in issueTexts" :key="msg" type="error" show-icon :closable="false" :title="msg" />

        <!-- 生成中：整张 PI 一个实时进度，可取消（整批回滚） -->
        <div v-if="detailRun" class="panel">
          <div class="prog" :class="{ cx: !!detailRun.cancel_at }">
            <div class="prog-h">
              <b class="pct num">{{ pctText(detailRun) }}%</b>
              <span class="muted num">{{ t("generate.runCount", { done: fmt(detailRun.done_qty), qty: fmt(detailRun.qty) }) }}</span>
              <span class="muted small push">{{ t("generate.runMeta", { time: detailRun.created_at, who: detailRun.created_by }) }}</span>
            </div>
            <el-progress :percentage="pctOf(detailRun)" :stroke-width="10" :show-text="false" :status="detailRun.cancel_at ? 'warning' : undefined" striped striped-flow />
            <div class="run-grid">
              <div><span class="muted small">{{ t("generate.writtenTo") }}</span><span class="sn-mono">{{ detailRun.current_sn || "—" }}</span></div>
              <div><span class="muted small">{{ t("generate.speed") }}</span><span>{{ speedOf(detailRun) ? t("generate.speedVal", { n: fmt(speedOf(detailRun)) }) : "—" }}</span></div>
              <div><span class="muted small">{{ t("generate.eta") }}</span><span>{{ etaOf(detailRun) != null ? t("generate.etaVal", { n: etaOf(detailRun) }) : "—" }}</span></div>
            </div>
          </div>
          <div class="act">
            <span class="msg">{{ detailRun.cancel_at ? t("generate.cancelingNote") : t("generate.runNoteShort") }}</span>
            <el-button type="danger" plain :disabled="!!detailRun.cancel_at" :loading="!!detailRun.cancel_at || busy === `cancel:${detailRun.batch}`" @click="cancelGen(detailRun)">
              {{ detailRun.cancel_at ? t("generate.stCANCELING") : t("generate.cancelGen") }}
            </el-button>
          </div>
        </div>

        <template v-else>
          <!-- 生成：规则 + 数量 + 号段预览，一次确认 -->
          <div v-if="ctx.quota" class="panel">
            <div class="prow">
              <div class="k">{{ t("generate.stepRule") }}</div>
              <div class="v">
                <template v-if="picking">
                  <div class="inl">
                    <el-radio-group v-model="ruleTab">
                      <el-radio-button value="lib">{{ t("generate.ruleLibrary") }}</el-radio-button>
                      <el-radio-button value="custom">{{ t("generate.ruleCustom") }}</el-radio-button>
                    </el-radio-group>
                    <span class="push inl">
                      <el-button v-if="ruleId != null" size="small" :loading="ruleBusy" @click="bindRule">{{ t("generate.ruleAssign") }}</el-button>
                      <el-button v-if="override" link @click="override = false">{{ t("common.cancel") }}</el-button>
                    </span>
                  </div>
                  <!-- 规则库：搜索 + 分类 + 固定高度可滚动列表；与本 PI 客户 / PI 匹配的排在前面并标「推荐」 -->
                  <template v-if="ruleTab === 'lib'">
                    <el-input v-model.trim="ruleQ" clearable prefix-icon="Search" :placeholder="t('generate.ruleSearch')" />
                    <div class="chips">
                      <button v-for="c in RULE_CATS" :key="c" type="button" class="chip" :class="{ on: ruleCat === c }" @click="ruleCat = c">
                        {{ t(`generate.ruleCat${c}`) }} <span class="num">{{ catCount(c) }}</span>
                      </button>
                    </div>
                    <div class="rule-list">
                      <template v-for="grp in ruleGroups" :key="grp.key">
                        <div class="grp">{{ t(`generate.ruleGrp${grp.key}`) }}</div>
                        <div v-for="o in grp.items" :key="o.rule_id" class="it" :class="{ on: libPick === o.rule_id }" role="radio" :aria-checked="libPick === o.rule_id" tabindex="0"
                          @click="libPick = o.rule_id" @keydown.enter.space.prevent="libPick = o.rule_id">
                          <span class="radio" />
                          <span class="nm">
                            <b>{{ o.rule_name }}</b>
                            <span v-if="grp.key === 'REC'" class="ct rec">{{ t("generate.ruleRec") }}</span>
                            <span class="ct" :class="o.category_scope">{{ o.category_scope === "NONE" ? t("generate.ruleCatNONE") : o.category_value }}</span>
                          </span>
                          <span class="meta">
                            <span class="sn-mono">{{ fmtOf(o) }}</span>
                            <span v-if="o.used_pis != null"> · {{ o.used_pis ? t("generate.ruleUsedN", { n: o.used_pis }) : t("generate.ruleUnused") }}</span>
                          </span>
                        </div>
                      </template>
                      <div v-if="!ruleGroups.length" class="empty">{{ t("generate.ruleNoMatch") }}</div>
                    </div>
                    <div class="inl small muted">
                      <span>{{ t("generate.ruleShown", { n: ctx.rule_options.length, m: shownOptions.length }) }}</span>
                      <router-link to="/rules" class="push">{{ t("generate.ruleManage") }}</router-link>
                    </div>
                  </template>
                  <div v-if="manualMode" class="combo" :class="{ bad: !manual.prefix }">
                    <input v-model.trim="manual.prefix" :placeholder="t('generate.prefixPh')" :aria-label="t('generate.rulePrefix')" maxlength="40" />
                    <span class="seq">
                      <button type="button" :disabled="manual.seq_len <= 1" @click="manual.seq_len--">−</button>
                      <b class="num">{{ manual.seq_len }}</b>{{ t("generate.seqDigits") }}
                      <button type="button" :disabled="manual.seq_len >= 12" @click="manual.seq_len++">＋</button>
                    </span>
                    <input v-model.trim="manual.suffix" :placeholder="t('generate.suffixPh')" :aria-label="t('generate.ruleSuffix')" maxlength="40" />
                  </div>
                </template>
                <div v-else-if="ctx.rule" class="inl">
                  <b>{{ ctx.rule.rule_name }}</b>
                  <span class="muted">v{{ ctx.rule.version }}</span>
                  <el-tag size="small" round :type="ctx.rule.source === 'CUSTOMER' ? 'warning' : 'primary'" disable-transitions>{{ t(`generate.ruleSrc${ctx.rule.source}`) }}</el-tag>
                  <span class="push inl">
                    <el-button v-if="ctx.rule.source === 'CUSTOMER'" link type="primary" @click="startOverride">{{ t("generate.ruleOverride") }}</el-button>
                    <el-button v-if="!unbindAsk" link type="danger" @click="unbindAsk = true">{{ t("generate.ruleUnbind") }}</el-button>
                  </span>
                </div>
                <div v-if="unbindAsk && ctx.rule && !picking" class="unbind-ask">
                  <span>{{ t("generate.ruleUnbindAsk") }}</span>
                  <span class="inl">
                    <el-button link type="danger" :loading="ruleBusy" @click="unbindRule">{{ t("generate.ruleUnbindYes") }}</el-button>
                    <el-button link @click="unbindAsk = false">{{ t("common.cancel") }}</el-button>
                  </span>
                </div>
                <div class="inl small">
                  <span class="muted">{{ t("generate.nextStart") }}</span>
                  <span v-if="parts && (!manualMode || manual.prefix)" class="sn-mono next">{{ parts.prefix }}<span class="z">{{ parts.zeros }}</span>{{ parts.digits }}{{ parts.suffix }}<span class="ver">{{ parts.ver }}</span></span>
                  <span v-else class="muted">—</span>
                  <el-tooltip v-if="!picking && ctx.next?.adjusted" :content="t('generate.startAdjusted', { sn: ctx.next.by_sn, start: ctx.next.start_sn })">
                    <el-tag size="small" type="warning">{{ t("generate.adjustedTag") }}</el-tag>
                  </el-tooltip>
                  <span class="muted push">{{ t("generate.lastSeq") }} <span class="num">{{ ctx.counter.last_seq_dec }}</span></span>
                </div>
                <div class="rule-status" :class="status.cls"><i />{{ status.text }}</div>
              </div>
            </div>

            <div class="prow">
              <div class="k">{{ t("generate.qtyRow") }}</div>
              <div class="v">
                <div class="inl">
                  <el-input-number v-model="gq.qty" :min="1" :step="1000" :precision="0" controls-position="right" class="qty" :disabled="busy === 'gen'" @change="onQty" />
                  <span class="muted">{{ t("generate.capOf", { n: fmt(genCap) }) }}</span>
                </div>
                <div v-if="gq.corrected" class="warn small">{{ gq.corrected }}</div>
                <div v-if="limited" class="inl small">
                  <span v-if="!gq.unlocked" class="muted">{{ t("generate.limitHint", { n: fmt(ctx.limits.default) }) }}</span>
                  <el-checkbox v-model="gq.unlocked" size="small" @change="onUnlock">{{ t("generate.unlock", { n: fmt(Math.min(ctx.quota, ctx.limits.max)) }) }}</el-checkbox>
                  <span v-if="gq.unlocked && ctx.quota > ctx.limits.max" class="muted">{{ t("generate.maxHint", { n: fmt(ctx.limits.max) }) }}</span>
                </div>
                <template v-if="ruleReady && canGenerate">
                  <div v-if="gq.previewing" class="muted small">{{ t("generate.previewing") }}</div>
                  <div v-else-if="gq.error" class="err small">{{ gq.error }}</div>
                  <template v-else-if="gq.preview">
                    <div class="inl small">
                      <span class="muted">{{ t("generate.segment") }}</span>
                      <span class="sn-mono">{{ gq.preview.start_sn }} ~ {{ gq.preview.end_sn }}</span>
                      <el-tooltip v-if="gq.preview.next?.adjusted" :content="t('generate.startAdjusted', { sn: gq.preview.next.by_sn, start: gq.preview.start_sn })">
                        <el-tag size="small" type="warning">{{ t("generate.adjustedTag") }}</el-tag>
                      </el-tooltip>
                    </div>
                    <div class="muted small">
                      <span v-for="it in gq.preview.items" :key="it.bill_no" class="by-bill">{{ it.bill_no }} <span class="num">{{ fmt(it.qty) }}</span></span>
                      · {{ ctx.quota - gq.preview.qty > 0 ? t("generate.afterLeft", { n: fmt(ctx.quota - gq.preview.qty) }) : t("generate.afterDone") }}
                    </div>
                  </template>
                </template>
              </div>
            </div>

            <div class="act">
              <span class="msg" :class="{ err: !ruleReady || !canGenerate }">
                {{ !canGenerate ? t("generate.stBLOCKED") : manualMode && !manual.prefix ? t("generate.prefixRequired") : !ruleReady ? t("generate.rulePickRequired") : t("generate.genNote", { n: ctx.bills.length }) }}
              </span>
              <el-button type="primary" :loading="busy === 'gen'" :disabled="!canGenerate || !ruleReady || !gq.preview || gq.previewing || (!!busy && busy !== 'gen')" @click="runGenerate">
                {{ busy === "gen" ? t("generate.submitting") : t("generate.genBtnN", { n: fmt(gq.qty || 0) }) }}
              </el-button>
            </div>
          </div>

          <!-- 分配：整张 PI 一次分完；分配之前可撤回最近一次生成 -->
          <div v-if="ctx.pending_alloc" class="panel">
            <div v-if="!ctx.quota && ctx.rule" class="prow">
              <div class="k">{{ t("generate.stepRule") }}</div>
              <div class="v">
                <div class="inl">
                  <b>{{ ctx.rule.rule_name }}</b><span class="muted">v{{ ctx.rule.version }}</span>
                  <span class="muted small push">{{ t("generate.lastSeq") }} <span class="num">{{ ctx.counter.last_seq_dec }}</span></span>
                </div>
              </div>
            </div>
            <div class="prow">
              <div class="k">{{ t("generate.allocTo") }}</div>
              <div class="v">
                <div v-for="g in allocGroups" :key="g.factory" class="alloc-row">
                  <span><b>{{ g.name }}</b> <span class="muted small">{{ g.bills.map((b: any) => b.bill_no).join(" · ") }}</span></span>
                  <b class="num">{{ fmt(g.qty) }}</b>
                </div>
              </div>
            </div>
            <div class="act">
              <el-button v-if="ctx.revocable_batch" link type="danger" :disabled="!!busy" :loading="busy === `revoke:${ctx.revocable_batch.batch}`" @click="revokeBatch(ctx.revocable_batch)">
                {{ t("generate.revokeThis") }}
              </el-button>
              <span class="msg">
                <template v-if="ctx.revocable_batch">{{ t("generate.revokeRange", { start: ctx.revocable_batch.start_sn, end: ctx.revocable_batch.end_sn }) }}</template>
                <template v-else>{{ t("generate.allocNote") }}</template>
              </span>
              <el-button type="success" :loading="busy === `alloc:${ctx.pi_no}`" :disabled="!!busy" @click="allocatePi(ctx.pi_no, ctx.pending_alloc)">
                {{ t("generate.allocBtnN", { n: fmt(ctx.pending_alloc) }) }}
              </el-button>
            </div>
          </div>

          <div v-if="!ctx.quota && !ctx.pending_alloc && ctx.generated_qty" class="panel result-ok">
            <el-icon class="ic"><CircleCheckFilled /></el-icon>
            <b>{{ t("generate.doneTitle") }}</b>
            <span class="muted">{{ t("generate.doneDesc", { qty: fmt(ctx.allocated_qty) }) }}</span>
          </div>
        </template>

        <el-tabs v-model="detailTab" class="dtabs">
          <el-tab-pane name="bills" :label="`${t('generate.bills')} ${ctx.bills.length}`">
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
              <el-table-column :label="t('orders.org')" min-width="150" show-overflow-tooltip>
                <template #default="{ row }">
                  {{ row.factory_name || row.prd_org_name }}
                  <el-tag v-if="!row.factory_code" size="small" type="danger" class="ml">{{ t("generate.unmappedTag") }}</el-tag>
                  <el-tag v-else-if="row.factory_disabled" size="small" type="danger" class="ml">{{ t("generate.disabledTag") }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column :label="t('orders.totalQty')" align="right" width="84"><template #default="{ row }"><span class="num">{{ fmt(row.total_qty) }}</span></template></el-table-column>
              <el-table-column :label="t('generate.generated')" align="right" width="76"><template #default="{ row }"><span class="num">{{ detailRun ? "—" : fmt(row.generated_qty) }}</span></template></el-table-column>
              <el-table-column :label="t('generate.allocated')" align="right" width="76"><template #default="{ row }"><span class="num">{{ fmt(row.allocated_qty) }}</span></template></el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane name="jobs" :label="`${t('generate.jobs')} ${ctx.jobs.length}`">
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
          <el-tab-pane name="allocs" :label="`${t('generate.allocations')} ${ctx.allocations.length}`">
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
      </div>
    </el-drawer>
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

/** 行上按钮（下一步）：生成中（转圈）/ 生成 / 选规则 / 分配 / 查看。 */
function actionOf(row: any): "running" | "gen" | "pick" | "alloc" | "view" {
  const st = stateOf(row);
  if (st === "RUNNING") return "running";
  if (st === "BLOCKED") return "view";
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
const ruleBusy = ref(false);
let alive = true;

// ------------------------------------------------------------------ 详情里的 SN 规则

/** 未绑定（或按客户绑定时为本 PI 单独设置）时的选择：规则库里的一条模板，或 "manual" = 手动设置。 */
/** 规则库 / 自定义规则两个页签；规则库里选中的模板单独记住，来回切换不丢。 */
const ruleTab = ref<"lib" | "custom">("lib");
const libPick = ref<number | null>(null);
const pickKey = computed<number | "manual" | null>(() => (ruleTab.value === "custom" ? "manual" : libPick.value));
/** 按客户绑定时点了「为本 PI 单独设置」。 */
const override = ref(false);
const unbindAsk = ref(false);
const manual = reactive({ prefix: "", seq_len: 6, suffix: "" });
/** 手动设置的即时判断结果（后台校验前缀冲突、给出第一个号）。 */
const manualPlan = ref<any>(null);
const manualError = ref("");
const manualChecking = ref(false);

const picking = computed(() => !!ctx.value && (!ctx.value.rule || override.value));
const manualMode = computed(() => picking.value && pickKey.value === "manual");
const ruleId = computed<number | null>(() => (picking.value && typeof pickKey.value === "number" ? pickKey.value : null));
const pickedOpt = computed(() => (ruleId.value == null ? null : ctx.value?.rule_options.find((o: any) => o.rule_id === ruleId.value) ?? null));
/** 当前显示的规则（含本 PI 要用的版本与第一个号）。 */
const shownRule = computed<any>(() => (manualMode.value ? manualPlan.value?.rule ?? null : picking.value ? pickedOpt.value : ctx.value?.rule ?? null));
const nextSn = computed<string | null>(() => {
  if (!picking.value) return ctx.value?.next?.start_sn ?? null;
  if (manualMode.value) return manualError.value ? null : manualPlan.value?.next?.start_sn ?? null;
  return pickedOpt.value?.start_sn ?? null;
});
const ruleReady = computed(() => {
  if (!ctx.value) return false;
  if (!picking.value) return !!ctx.value.rule;
  if (manualMode.value) return !!manualPlan.value && !manualError.value && !manualChecking.value;
  return ruleId.value != null;
});

/** 规则库筛选：关键字（名称 / 编码 / 前后缀 / 分类的客户或 PI）+ 分类；与本 PI 的客户或 PI 匹配的为「推荐」，排在前面。 */
const RULE_CATS = ["ALL", "REC", "CUSTOMER", "PI", "NONE"] as const;
type RuleCat = (typeof RULE_CATS)[number];
const ruleQ = ref("");
const ruleCat = ref<RuleCat>("ALL");
const isRec = (o: any) =>
  !!ctx.value && ((o.category_scope === "CUSTOMER" && o.category_value === ctx.value.customer_code) || (o.category_scope === "PI" && o.category_value === ctx.value.pi_no));
const inCat = (o: any, c: RuleCat) => c === "ALL" || (c === "REC" ? isRec(o) : o.category_scope === c);
const catCount = (c: RuleCat) => (ctx.value?.rule_options ?? []).filter((o: any) => inCat(o, c)).length;
const shownOptions = computed<any[]>(() => {
  const k = ruleQ.value.toLowerCase();
  return (ctx.value?.rule_options ?? []).filter(
    (o: any) => inCat(o, ruleCat.value) && (!k || [o.rule_name, o.rule_code, o.prefix, o.suffix, o.category_value].some((x) => x?.toLowerCase().includes(k))),
  );
});
const ruleGroups = computed(() => {
  const rec = shownOptions.value.filter(isRec);
  const rest = shownOptions.value.filter((o) => !isRec(o));
  return [
    { key: "REC", items: rec },
    { key: "OTHER", items: rest },
  ].filter((g) => g.items.length);
});
/** 默认选中：第一个推荐模板，否则第一个模板；规则库为空时直接进自定义规则。 */
function defaultPick(c: any) {
  const opts: any[] = c?.rule_options ?? [];
  libPick.value = (opts.find(isRec) ?? opts[0])?.rule_id ?? null;
  if (!opts.length) ruleTab.value = "custom";
}
const fmtOf = (o: any) => `${o.prefix}${"#".repeat(o.seq_len)}${o.suffix}`;
const inW = (v: string) => `${Math.max(4, (v || "").length + 1)}ch`;

/** 大号 SN 的三段：前缀 / 流水（前导 0 浅色）/ 后缀（沿用派生的 _v版本号 单独标色）。 */
const parts = computed(() => {
  if (manualMode.value) {
    const w = manual.seq_len || 1;
    const sn = manualChecking.value ? null : nextSn.value;
    const seq = sn ? sn.slice(manual.prefix.length, sn.length - manual.suffix.length) : "1".padStart(w, "0");
    return { prefix: manual.prefix, ...splitSeq(seq), width: w, suffix: manual.suffix, ver: "" };
  }
  const r = shownRule.value;
  if (!r) return null;
  const sn = nextSn.value ?? r.sample_sn;
  const seq = sn.slice(r.prefix.length, sn.length - r.suffix.length);
  const tag = `_v${r.version}`;
  const derived = (r.plan === "DERIVE" || r.derived) && r.suffix.endsWith(tag);
  return { prefix: r.prefix, ...splitSeq(seq), width: r.seq_len, suffix: derived ? r.suffix.slice(0, -tag.length) : r.suffix, ver: derived ? tag : "" };
});
function splitSeq(seq: string) {
  const i = seq.search(/[^0]/);
  const cut = i < 0 ? Math.max(0, seq.length - 1) : i;
  return { zeros: seq.slice(0, cut), digits: seq.slice(cut) };
}

/** 一行说明：本 PI 用哪一版、为什么。 */
const status = computed<{ cls: string; text: string }>(() => {
  if (manualMode.value) {
    if (manualError.value) return { cls: "err", text: manualError.value };
    if (manualChecking.value || !manualPlan.value) return { cls: "", text: t("generate.ruleChecking") };
    return { cls: "new", text: t("generate.ruleStNEW", { name: manualPlan.value.rule.rule_name }) };
  }
  const r = shownRule.value;
  if (!r) return { cls: "", text: picking.value ? t("generate.rulePickRequired") : "—" };
  if (r.plan === "DERIVE") return { cls: "ok", text: t("generate.ruleStDERIVE", { pi: r.shared_by, v: r.version }) };
  if (!picking.value && r.source === "CUSTOMER") return { cls: "", text: t("generate.ruleStCUSTOMER") };
  return { cls: "ok", text: t(r.plan === "CLAIM" ? "generate.ruleStCLAIM" : "generate.ruleStUSE", { v: r.version }) };
});

let planTimer: number | undefined;
let planSeq = 0;
watch(() => [manualMode.value, manual.prefix, manual.suffix, manual.seq_len], () => {
  window.clearTimeout(planTimer);
  planSeq++;
  if (!manualMode.value || !ctx.value) return;
  manualChecking.value = true;
  manualError.value = "";
  const seq = planSeq;
  planTimer = window.setTimeout(async () => {
    try {
      const p = await api.genRulePlan({ pi_no: ctx.value.pi_no, manual: { ...manual } });
      if (seq === planSeq) manualPlan.value = p;
    } catch (e: any) {
      if (seq === planSeq) {
        manualPlan.value = null;
        manualError.value = e?.message || "";
      }
    } finally {
      if (seq === planSeq) manualChecking.value = false;
    }
  }, 300);
});

function resetRulePick(c: any) {
  override.value = false;
  ruleQ.value = "";
  unbindAsk.value = false;
  ruleCat.value = "ALL";
  if (!c.rule_options.some((o: any) => o.rule_id === libPick.value)) defaultPick(c);
}

function startOverride() {
  override.value = true;
  if (libPick.value == null) defaultPick(ctx.value);
}
const detailRun = computed<Tracked | null>(() => (detailPi.value ? tracked[detailPi.value] ?? null : null));
const canGenerate = computed(() => !!ctx.value && !ctx.value.issues.some((c: string) => ["FACTORY_UNMAPPED", "FACTORY_DISABLED"].includes(c)));
const detailTab = ref("bills");
const orgCount = computed(() => new Set((ctx.value?.bills ?? []).map((b: any) => b.factory_code || b.prd_org_name)).size);

/** 详情的状态：与列表行同一套（生成中 / 不能生成 / 需先选规则 / 还可生成 / 待生成 / 待分配 / 已完成）。 */
const detailState = computed(() => {
  const c = ctx.value;
  if (!c) return "";
  if (detailRun.value) return "RUNNING";
  if (c.quota > 0) {
    if (!canGenerate.value) return "BLOCKED";
    if (c.generated_qty > 0) return "MORE";
    return c.rule ? "TO_GEN" : "PICK";
  }
  return c.pending_alloc > 0 ? "TO_ALLOC" : "DONE";
});
const detailStateText = computed(() => {
  const r = detailRun.value;
  if (r) return r.cancel_at ? t("generate.stCANCELING") : t("generate.stRUNNING", { pct: pctText(r) });
  return detailState.value ? t(`generate.st${detailState.value}`) : "";
});

/** 步骤条：SN 规则 → 生成 → 分配；stepIdx 为当前一步（3 = 全部完成）。 */
const stepIdx = computed(() => {
  const c = ctx.value;
  if (!c) return 0;
  if (detailRun.value) return 1;
  if (c.quota > 0) return ruleReady.value ? 1 : 0;
  return c.pending_alloc > 0 ? 2 : 3;
});
const steps = computed(() => {
  const c = ctx.value;
  const r = detailRun.value;
  return [
    { key: "rule", label: t("generate.stepRule"), sub: stepIdx.value > 0 || c?.rule ? t("generate.stepRuleOk") : t("generate.stepRuleTodo") },
    { key: "gen", label: t("generate.stepGen"), sub: r ? `${pctText(r)}%` : `${fmt(c?.generated_qty)} / ${fmt(c?.total_qty)}` },
    { key: "alloc", label: t("generate.stepAlloc"), sub: `${fmt(c?.allocated_qty)} / ${fmt(c?.total_qty)}` },
  ];
});
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
    const switched = ctx.value?.pi_no !== c.pi_no;
    const prevQuota = ctx.value?.quota;
    ctx.value = c;
    detailPi.value = c.pi_no;
    if (switched) {
      Object.assign(manual, { prefix: "", seq_len: 6, suffix: "" });
      libPick.value = null;
      ruleTab.value = "lib";
      detailTab.value = "bills";
    }
    if (switched || c.quota !== prevQuota) resetQty(c);
    resetRulePick(c);
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
  ruleBusy.value = true;
  try {
    await api.unbindPiRule(c.pi_no);
    ElMessage.success(t("generate.ruleUnbound"));
    await refresh(c.pi_no);
  } finally {
    ruleBusy.value = false;
  }
}

// ------------------------------------------------------------------ 详情里的生成：数量 + 号段预览

/** 数量默认填满额度；额度超过默认上限时先按上限，可解除限制；超过上限自动纠正。 */
const gq = reactive<{ qty: number | undefined; unlocked: boolean; corrected: string; preview: any; previewing: boolean; error: string }>({
  qty: undefined, unlocked: false, corrected: "", preview: null, previewing: false, error: "",
});
const genCap = computed(() => (ctx.value ? genQtyCap(ctx.value.quota, ctx.value.limits, gq.unlocked) : 0));
const limited = computed(() => !!ctx.value && ctx.value.quota > ctx.value.limits.default);
let previewTimer: number | undefined;
let previewSeq = 0;

/** 换 PI 或额度变化时重新按额度填数量。 */
function resetQty(c: any) {
  Object.assign(gq, { unlocked: false, corrected: "", qty: c.quota ? genQtyCap(c.quota, c.limits, false) : undefined });
}

/** 数量改动：超过上限自动改为上限并提示。 */
function onQty(v: number | undefined) {
  const r = clampQty(v, genCap.value);
  gq.corrected = r.corrected ? t("generate.qtyCorrected", { n: fmt(Math.floor(Number(v))), cap: fmt(r.qty) }) : "";
  gq.qty = r.qty;
}

function onUnlock() {
  gq.corrected = "";
  if ((gq.qty ?? 0) > genCap.value) gq.qty = genCap.value;
}

// 规则、数量停顿后自动计算号段（后台预演，不写号）；结果过时就丢弃
const previewable = computed(() => !!ctx.value?.quota && canGenerate.value && ruleReady.value && !detailRun.value && !!gq.qty);
watch(
  () => [previewable.value, ctx.value, gq.qty, ruleId.value, manualMode.value, manualPlan.value],
  () => {
    window.clearTimeout(previewTimer);
    previewSeq++;
    gq.preview = null;
    gq.error = "";
    gq.previewing = previewable.value;
    if (previewable.value) previewTimer = window.setTimeout(doPreview, 400);
  },
);

async function doPreview() {
  const seq = ++previewSeq;
  const c = ctx.value;
  try {
    const p = await api.genPiPreview({ pi_no: c.pi_no, qty: gq.qty, rule_id: ruleId.value, manual: manualMode.value ? { ...manual } : null }, true);
    if (seq === previewSeq) gq.preview = p;
  } catch (e: any) {
    if (seq === previewSeq) gq.error = e.message;
  } finally {
    if (seq === previewSeq) gq.previewing = false;
  }
}

/** 凭预演令牌一次生成整张 PI：后台在一个事务里依次生成各订单，失败或取消整批回滚。 */
async function runGenerate() {
  const p = gq.preview;
  if (!p) return;
  busy.value = "gen";
  try {
    const items = p.items.map((it: any) => ({ preview_token: it.token, bill_no: it.bill_no, qty: it.qty }));
    const r = await api.generatePi({ pi_no: p.pi_no, items });
    if (r.jobs.some((j: any) => j.status === "RUNNING")) track(p.pi_no, r.batch);
    else ElMessage.success(t("generate.done", { qty: fmt(r.qty) }));
    await refresh(p.pi_no);
  } catch {
    // 错误已由请求层提示；刷新后预演会按最新状态重新计算
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
.push { margin-left: auto; }
.inl { display: flex; align-items: center; gap: 8px 10px; flex-wrap: wrap; }
.err { color: var(--el-color-danger); }
.warn { color: var(--el-color-warning-dark-2); }
.hint { margin-top: 8px; font-size: 12px; }
.dh { display: flex; flex-direction: column; gap: 3px; color: var(--app-text); min-width: 0; }
.dh-title { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 16px; }
.drawer { display: flex; flex-direction: column; gap: 18px; font-size: 13px; }
/* 步骤条 */
.steps { display: flex; align-items: center; padding-bottom: 16px; border-bottom: 1px solid var(--el-border-color-lighter); }
.step { display: flex; align-items: center; gap: 8px; white-space: nowrap; color: var(--app-muted); }
.step .dot { width: 22px; height: 22px; border-radius: 50%; border: 1.5px solid var(--el-border-color-darker); display: inline-flex; align-items: center; justify-content: center; font-size: 12px; flex: none; }
.step.cur { color: var(--app-text); font-weight: 600; }
.step.cur .dot { border-color: var(--el-color-primary); background: var(--el-color-primary); color: #fff; }
.step.ok .dot { border-color: var(--el-color-success); color: var(--el-color-success); }
.step .small { font-weight: 400; }
.steps .line { flex: 1; min-width: 16px; height: 1px; margin: 0 12px; background: var(--el-border-color); }
.steps .line.ok { background: var(--el-color-success); }
/* 当前一步的面板：左标签右内容，主按钮在底部操作条 */
.panel { border: 1px solid var(--el-border-color-lighter); border-radius: 10px; }
.prow { display: grid; grid-template-columns: 72px minmax(0, 1fr); gap: 12px; padding: 14px 16px; }
.prow + .prow { border-top: 1px solid var(--el-border-color-lighter); }
.prow .k { color: var(--app-muted); line-height: 32px; }
.prow .v { min-height: 32px; display: flex; flex-direction: column; justify-content: center; gap: 8px; min-width: 0; }
.act { display: flex; align-items: center; gap: 12px; padding: 10px 16px; border-top: 1px solid var(--el-border-color-lighter); background: var(--el-fill-color-light); border-radius: 0 0 10px 10px; }
.act .msg { flex: 1; min-width: 0; color: var(--app-muted); font-size: 12px; }
.act .msg.err { color: var(--el-color-danger); }
.chips { display: flex; gap: 6px; flex-wrap: wrap; }
.chip { border: 1px solid var(--el-border-color-lighter); background: var(--el-fill-color-light); border-radius: 14px; padding: 1px 10px; font: inherit; font-size: 12px; color: var(--app-muted); cursor: pointer; }
.chip.on { border-color: var(--el-color-primary); background: var(--el-color-primary-light-9); color: var(--el-color-primary); }
.rule-list { border: 1px solid var(--el-border-color-lighter); border-radius: 8px; max-height: 240px; overflow: auto; }
.rule-list .grp { position: sticky; top: 0; z-index: 1; padding: 5px 12px; font-size: 12px; color: var(--app-muted); background: var(--el-fill-color-light); border-bottom: 1px solid var(--el-border-color-lighter); }
.rule-list .it { display: grid; grid-template-columns: 16px minmax(0, 1fr) auto; gap: 10px; align-items: center; padding: 8px 12px; cursor: pointer; border-bottom: 1px solid var(--el-border-color-lighter); outline: none; }
.rule-list .it:last-child { border-bottom: none; }
.rule-list .it:hover, .rule-list .it:focus-visible { background: var(--el-fill-color-light); }
.rule-list .it.on { background: var(--el-color-primary-light-9); }
.rule-list .radio { width: 14px; height: 14px; border-radius: 50%; border: 1.5px solid var(--el-border-color-darker); }
.rule-list .it.on .radio { border: 4px solid var(--el-color-primary); }
.rule-list .nm { display: flex; align-items: center; gap: 6px; min-width: 0; }
.rule-list .nm b { font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rule-list .meta { font-size: 12px; color: var(--app-muted); white-space: nowrap; }
.rule-list .empty { padding: 20px; text-align: center; color: var(--app-muted); }
.ct { font-size: 11px; line-height: 18px; padding: 0 6px; border-radius: 4px; white-space: nowrap; max-width: 150px; overflow: hidden; text-overflow: ellipsis; flex: none; color: var(--app-muted); background: var(--el-fill-color); }
.ct.CUSTOMER { color: var(--el-color-warning-dark-2); background: var(--el-color-warning-light-9); }
.ct.PI { color: var(--el-color-primary); background: var(--el-color-primary-light-9); }
.ct.rec { color: #fff; background: var(--el-color-success); }
.combo { display: inline-flex; align-self: flex-start; max-width: 100%; height: 34px; border: 1px solid var(--el-border-color); border-radius: 6px; overflow: hidden; }
.combo:focus-within { border-color: var(--el-color-primary); }
.combo input { border: none; outline: none; width: 130px; min-width: 0; padding: 0 10px; font: 15px var(--sn-mono, ui-monospace, SFMono-Regular, Consolas, monospace); color: var(--app-text); background: transparent; }
.combo input::placeholder { font-family: inherit; font-size: 13px; color: var(--el-text-color-placeholder); }
.combo.bad input:first-child { background: var(--el-color-danger-light-9); }
.combo .seq { display: flex; align-items: center; gap: 4px; padding: 0 4px; font-size: 13px; white-space: nowrap; color: var(--app-muted); background: var(--el-fill-color-light); border-left: 1px solid var(--el-border-color); border-right: 1px solid var(--el-border-color); }
.combo .seq b { color: var(--app-text); font-weight: 500; }
.combo .seq button { width: 22px; height: 22px; border: none; background: none; cursor: pointer; color: var(--app-muted); font-size: 14px; }
.combo .seq button:disabled { opacity: 0.4; cursor: not-allowed; }
.next { font-size: 15px; color: var(--app-text); }
.next .z { color: var(--el-text-color-placeholder); }
.next .ver { color: var(--el-color-primary); }
.rule-status { display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--app-muted); }
.rule-status > i { width: 7px; height: 7px; border-radius: 50%; background: var(--el-border-color-darker); flex: none; }
.rule-status.ok > i { background: var(--el-color-success); }
.rule-status.new > i { background: var(--el-color-primary); }
.rule-status.err { color: var(--el-color-danger); }
.rule-status.err > i { background: var(--el-color-danger); }
.unbind-ask { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; font-size: 13px; padding: 8px 12px; border-radius: 8px; background: var(--el-color-warning-light-9); }
.qty { width: 160px; }
.by-bill + .by-bill::before { content: " · "; }
.alloc-row { display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; padding: 6px 0; border-bottom: 1px dashed var(--el-border-color-lighter); }
.alloc-row:last-child { border-bottom: none; }
.prog { padding: 16px; display: flex; flex-direction: column; gap: 12px; }
.prog-h { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
.prog-h .pct { font-size: 28px; color: var(--el-color-primary); }
.prog.cx .pct { color: var(--el-color-warning); }
.run-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.run-grid > div { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.result-ok { display: flex; flex-direction: column; align-items: center; gap: 4px; padding: 24px 16px; }
.result-ok .ic { font-size: 40px; color: var(--el-color-success); margin-bottom: 4px; }
.dtabs :deep(.el-tabs__header) { margin-bottom: 8px; }
.lines { margin: 0 12px 0 48px; width: auto; }
:deep(.row-state) { display: inline-flex; align-items: center; gap: 8px; flex-wrap: wrap; }
:deep(.row-sub) { color: var(--app-muted); font-size: 12px; }
:deep(.run-cell) { display: flex; flex-direction: column; gap: 4px; }
:deep(.run-cell__top) { display: flex; justify-content: space-between; gap: 8px; font-size: 12px; }
:deep(.run-cell__label) { color: var(--el-color-primary); font-weight: 500; }
:deep(.mini-bar) { height: 6px; border-radius: 3px; overflow: hidden; background: var(--el-fill-color-dark); }
:deep(.mini-bar i) { display: block; height: 100%; background: var(--el-color-primary); }
:deep(.mini-bar i.warn) { background: var(--el-color-warning); }
</style>

<style>
/* 抽屉挂在 body 下，标题区间距用非 scoped 样式调整 */
.gen-drawer .el-drawer__header { margin-bottom: 16px; align-items: flex-start; }
</style>
