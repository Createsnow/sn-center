<template>
  <div class="page">
    <PageHead :title="t('transfer.title')" :desc="t('transfer.desc')">
      <el-button v-if="tab === 'bill' && store.isAdmin" type="primary" @click="openBill()"><el-icon><Plus /></el-icon><span>{{ t("transfer.newBill") }}</span></el-button>
      <el-button v-if="tab === 'org' && store.canAct" type="primary" @click="openOrg()"><el-icon><Plus /></el-icon><span>{{ store.isAdmin ? t("transfer.newDirect") : t("transfer.newApply") }}</span></el-button>
    </PageHead>

    <el-tabs v-model="tab" @tab-change="onTab">
      <!-- ================= 转单记录 ================= -->
      <el-tab-pane :label="t('transfer.tabBill')" name="bill">
        <div class="surface">
          <div class="toolbar">
            <FactorySelect v-if="!store.bound" v-model="bq.factory_code" clearable :placeholder="t('app.allFactories')" @update:model-value="bills.load(1)" />
            <el-input v-model="bq.pi" placeholder="PI" clearable @keyup.enter="bills.load(1)" @clear="bills.load(1)" />
            <el-input v-model="bq.bill_no" :placeholder="t('common.billNo')" clearable @keyup.enter="bills.load(1)" @clear="bills.load(1)" />
            <el-button type="primary" @click="bills.load(1)">{{ t("common.search") }}</el-button>
          </div>
          <el-table v-loading="bills.state.loading" :data="bills.items.value" size="small" :empty-text="t('common.empty')">
            <el-table-column prop="transfer_no" :label="t('transfer.no')" min-width="180"><template #default="{ row }"><span class="sn-mono">{{ row.transfer_no }}</span></template></el-table-column>
            <el-table-column :label="t('transfer.mode')" width="100"><template #default="{ row }">{{ t(`transfer.billMode.${row.mode}`) }}</template></el-table-column>
            <el-table-column :label="t('transfer.fromTo')" min-width="220">
              <template #default="{ row }">
                <div>{{ row.from_bill }} → <b>{{ row.to_bill }}</b></div>
                <div class="muted">{{ row.from_pi }} → {{ row.to_pi }}</div>
              </template>
            </el-table-column>
            <el-table-column prop="scope_text" :label="t('transfer.scope')" min-width="160" show-overflow-tooltip />
            <el-table-column :label="t('common.qty')" align="right" width="70"><template #default="{ row }"><span class="num">{{ row.qty }}</span></template></el-table-column>
            <el-table-column prop="reason" :label="t('common.reason')" min-width="140" show-overflow-tooltip />
            <el-table-column prop="created_by" :label="t('common.operator')" width="100" />
            <el-table-column :label="t('common.time')" width="160"><template #default="{ row }">{{ fmt(row.created_at) }}</template></el-table-column>
            <el-table-column :label="t('common.actions')" width="80" align="right">
              <template #default="{ row }"><el-button link type="primary" @click="openDetail('BILL', row.id)">{{ t("common.detail") }}</el-button></template>
            </el-table-column>
          </el-table>
          <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="bills.state.total" :page-size="bills.state.pageSize"
            :current-page="bills.state.page" :page-sizes="[20, 50, 100]" @current-change="bills.load" @size-change="bills.onSize" />
        </div>
      </el-tab-pane>

      <!-- ================= 转组织记录 ================= -->
      <el-tab-pane name="org">
        <template #label>
          <span>{{ t("transfer.tabOrg") }}</span>
          <el-badge v-if="pending" :value="pending" class="badge" />
        </template>
        <div class="surface">
          <div class="toolbar">
            <el-radio-group v-model="oq.status" @change="orgs.load(1)">
              <el-radio-button v-for="s in ORG_STATUSES" :key="s" :value="s">
                {{ s ? transferStatusLabel(s) : t("common.all") }}<span v-if="s === 'PENDING' && pending" class="cnt num">{{ pending }}</span>
              </el-radio-button>
            </el-radio-group>
            <FactorySelect v-if="!store.bound" v-model="oq.factory_code" clearable :placeholder="t('app.allFactories')" @update:model-value="orgs.load(1)" />
            <el-input v-model="oq.pi" :placeholder="t('transfer.piInOut')" clearable @keyup.enter="orgs.load(1)" @clear="orgs.load(1)" />
            <el-input v-model="oq.bill_no" :placeholder="t('common.billNo')" clearable @keyup.enter="orgs.load(1)" @clear="orgs.load(1)" />
            <el-button type="primary" @click="orgs.load(1)">{{ t("common.search") }}</el-button>
          </div>
          <el-table v-loading="orgs.state.loading" :data="orgRows" size="small" :empty-text="t('common.empty')">
            <el-table-column prop="transfer_no" :label="t('transfer.no')" min-width="180">
              <template #default="{ row }">
                <span class="sn-mono">{{ row.transfer_no }}</span>
                <el-tag v-if="row.direction" size="small" :type="row.direction === 'OUT' ? 'warning' : 'success'" class="ml">{{ t(`transfer.direction.${row.direction}`) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('common.status')" width="100">
              <template #default="{ row }"><el-tag size="small" :type="TRANSFER_STATUS_TYPE[row.status] || 'info'">{{ transferStatusLabel(row.status) }}</el-tag></template>
            </el-table-column>
            <el-table-column :label="t('transfer.mode')" width="100"><template #default="{ row }">{{ t(`transfer.orgMode.${row.mode}`) }}</template></el-table-column>
            <el-table-column :label="t('transfer.source')" min-width="200">
              <template #default="{ row }">
                <div>{{ nameOf(row.from_factory) }} · {{ row.from_pi }}</div>
                <div class="muted">{{ scopeLabel(row.scope) }}<template v-if="row.scope === 'MATERIAL'"> · {{ row.from_material || t("transfer.emptyMaterial") }}</template><template v-if="row.scope === 'SINGLE'"> · <span class="sn-mono">{{ row.from_sn }}</span></template></div>
              </template>
            </el-table-column>
            <el-table-column :label="t('transfer.target')" min-width="200">
              <template #default="{ row }">
                <div>
                  {{ nameOf(row.to_factory) }} · {{ row.to_pi }}
                  <el-tag v-if="row.to_factory_disabled" size="small" type="danger" class="ml">{{ t("transfer.targetDisabled") }}</el-tag>
                </div>
                <div class="muted">
                  <template v-if="row.to_customer">{{ t("common.customer") }} {{ row.to_customer }} </template>
                  <template v-if="row.to_material">{{ t("common.material") }} {{ row.to_material }}</template>
                  <el-tag v-if="row.manual_fields" size="small" type="info" class="ml">{{ t("transfer.manual") }}</el-tag>
                </div>
              </template>
            </el-table-column>
            <el-table-column :label="t('common.qty')" align="right" width="70"><template #default="{ row }"><span class="num">{{ row.qty }}</span></template></el-table-column>
            <el-table-column :label="t('transfer.applied')" width="160">
              <template #default="{ row }"><div>{{ row.applied_by }}</div><div class="muted">{{ fmt(row.applied_at) }}</div></template>
            </el-table-column>
            <el-table-column :label="t('common.actions')" width="200" align="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openDetail('ORG', row.id)">{{ t("common.detail") }}</el-button>
                <template v-if="row.status === 'PENDING'">
                  <template v-if="store.isAdmin">
                    <el-button link type="success" :disabled="row.to_factory_disabled" @click="decide(row, 'approve')">{{ t("transfer.approve") }}</el-button>
                    <el-button link type="danger" @click="decide(row, 'reject')">{{ t("transfer.reject") }}</el-button>
                  </template>
                  <el-button v-else-if="store.isFactory && row.direction === 'OUT'" link type="warning" @click="withdraw(row)">{{ t("transfer.withdraw") }}</el-button>
                </template>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="orgs.state.total" :page-size="orgs.state.pageSize"
            :current-page="orgs.state.page" :page-sizes="[20, 50, 100]" @current-change="orgs.load" @size-change="orgs.onSize" />
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- ================= 新建转单 ================= -->
    <el-dialog v-model="bf.open" :title="t('transfer.newBill')" width="860px" top="5vh">
      <el-form label-width="120px" class="form">
        <el-form-item :label="t('transfer.fromBill')" required>
          <el-input v-model="bf.from_bill" class="w260" clearable @change="loadFromLines" />
          <span v-if="fromLines.length" class="muted ml">{{ t("transfer.linesInSnapshot", { n: fromLines.length }) }}</span>
          <span v-else-if="bf.from_bill" class="muted ml">{{ t("transfer.sourceNotInSnapshot") }}</span>
        </el-form-item>
        <el-form-item :label="t('transfer.mode')">
          <el-radio-group v-model="bf.mode" @change="resetPreview">
            <el-radio-button value="VOID">{{ t("transfer.billMode.VOID") }}</el-radio-button>
            <el-radio-button value="SPLIT">{{ t("transfer.billMode.SPLIT") }}</el-radio-button>
          </el-radio-group>
          <span class="muted ml">{{ t(`transfer.billModeTip.${bf.mode}`) }}</span>
        </el-form-item>
        <el-form-item :label="t('transfer.toBill')" required>
          <el-autocomplete v-model="bf.to_bill" class="w260" :fetch-suggestions="suggestBills" :trigger-on-focus="false" clearable
            @select="loadToLines" @change="loadToLines" />
          <span v-if="toBill" class="muted ml">{{ nameOf(toBill.org_code) }} · PI {{ toBill.pi }} · {{ t("common.customer") }} {{ toBill.customer_code }}</span>
        </el-form-item>
        <template v-if="bf.mode === 'SPLIT'">
          <el-form-item :label="t('transfer.fromLine')" required>
            <el-select v-if="fromLines.length" v-model="bf.from_seq" class="w260" @change="resetPreview">
              <el-option v-for="l in fromLines" :key="l.seq" :value="l.seq" :label="`${l.seq} · ${l.material_code || t('transfer.emptyMaterial')} · ${l.length}`" />
            </el-select>
            <el-input-number v-else v-model="bf.from_seq" :min="1" controls-position="right" @change="resetPreview" />
          </el-form-item>
          <el-form-item :label="t('transfer.splitScope')">
            <FactorySelect v-model="bf.factory_code" clearable :placeholder="t('common.factory')" @update:model-value="resetPreview" />
            <el-input v-model="bf.pi_no" class="w180 ml" placeholder="PI" clearable @change="resetPreview" />
          </el-form-item>
          <el-form-item :label="t('transfer.range')">
            <el-input v-model="bf.start_sn" class="w220" :placeholder="t('transfer.startSn')" clearable @change="resetPreview" />
            <span class="ml">~</span>
            <el-input v-model="bf.end_sn" class="w220 ml" :placeholder="t('transfer.endSn')" clearable @change="resetPreview" />
            <div class="muted hint">{{ t("transfer.rangeHint") }}</div>
          </el-form-item>
          <el-form-item :label="t('transfer.toLine')">
            <el-select v-model="bf.to_seq" class="w260" clearable :placeholder="t('transfer.autoByMaterial')" @change="resetPreview">
              <el-option v-for="l in toLines" :key="l.seq" :value="l.seq" :label="lineLabel(l)" />
            </el-select>
          </el-form-item>
        </template>
        <el-form-item v-else-if="mapRows.length" :label="t('transfer.mapping')">
          <div class="map">
            <div v-for="r in mapRows" :key="r.seq" class="map-row">
              <span>{{ t("transfer.sourceLine", { seq: r.seq, m: r.material || t("transfer.emptyMaterial") }) }}</span>
              <span>→</span>
              <el-select v-model="mapping[r.seq]" class="w260" clearable :placeholder="t('transfer.autoByMaterial')" @change="resetPreview">
                <el-option v-for="l in toLines" :key="l.seq" :value="l.seq" :label="lineLabel(l)" />
              </el-select>
            </div>
            <div class="muted">{{ t("transfer.mappingHint") }}</div>
          </div>
        </el-form-item>
        <el-form-item :label="t('common.reason')" required>
          <el-input v-model="bf.reason" type="textarea" :rows="2" maxlength="500" show-word-limit :placeholder="t('common.reasonPlaceholder')" />
        </el-form-item>
      </el-form>

      <div v-if="bPreview" class="preview">
        <div class="kpis">
          <span>{{ t("transfer.pvTotal") }} <b class="num">{{ bPreview.total }}</b></span>
          <span>{{ t("transfer.pvTransferable") }} <b class="num ok">{{ bPreview.transferable }}</b></span>
          <span>{{ t("transfer.pvDelivered") }} <b class="num">{{ bPreview.delivered }}</b></span>
          <span v-if="bPreview.other_org">{{ t("transfer.pvOtherOrg") }} <b class="num warn">{{ bPreview.other_org }}</b></span>
          <span class="muted">→ {{ nameOf(bPreview.to_org) }} · PI {{ bPreview.to_pi }} · {{ bPreview.scope }}</span>
        </div>
        <div class="tags">
          <el-tag v-for="(n, s) in bPreview.by_status" :key="s" size="small" effect="plain">{{ statusLabel(String(s)) }} {{ n }}</el-tag>
          <el-tag v-for="(n, r) in bPreview.blocked" :key="r" size="small" type="danger">{{ t(`transfer.blocked.${r}`) }} {{ n }}</el-tag>
        </div>
        <el-table :data="bPreview.generated_changes" size="small" class="mt">
          <el-table-column :label="t('common.billNo')" min-width="160"><template #default="{ row }">{{ row.bill_no }} / {{ row.seq }}</template></el-table-column>
          <el-table-column :label="t('transfer.genBefore')" align="right" width="110"><template #default="{ row }"><span class="num">{{ row.before }}</span></template></el-table-column>
          <el-table-column :label="t('transfer.genAfter')" align="right" width="110"><template #default="{ row }"><span class="num">{{ row.after }}</span></template></el-table-column>
        </el-table>
        <p class="muted">{{ t("transfer.quotaUnchanged") }}</p>
      </div>
      <template #footer>
        <el-button @click="bf.open = false">{{ t("common.cancel") }}</el-button>
        <el-button :loading="busy === 'bpv'" :disabled="!bf.from_bill || !bf.to_bill" @click="previewBill">{{ t("transfer.preview") }}</el-button>
        <el-button type="primary" :loading="busy === 'bill'" :disabled="!bPreview || !bPreview.transferable || !bf.reason.trim()" @click="submitBill">{{ t("transfer.submitBill") }}</el-button>
      </template>
    </el-dialog>

    <!-- ================= 新建转组织 ================= -->
    <el-dialog v-model="of.open" :title="store.isAdmin ? t('transfer.newDirect') : t('transfer.newApply')" width="820px" top="5vh">
      <el-form label-width="120px" class="form">
        <el-form-item :label="t('transfer.fromOrg')" required>
          <FactorySelect v-model="of.factory_code" :disabled="store.isFactory" @update:model-value="resetCandidates" />
        </el-form-item>
        <el-form-item label="PI" required>
          <el-input v-model="of.pi_no" class="w260" clearable @change="resetCandidates" />
        </el-form-item>
        <el-form-item :label="t('transfer.scope')">
          <el-radio-group v-model="of.scope" @change="resetCandidates">
            <el-radio-button v-for="s in ['PI', 'MATERIAL', 'SINGLE']" :key="s" :value="s">{{ scopeLabel(s) }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="of.scope === 'MATERIAL'" :label="t('common.material')" required>
          <el-select v-model="of.material_code" class="w260" filterable allow-create :placeholder="t('transfer.pickMaterial')" @change="resetCandidates">
            <el-option v-for="mm in materials" :key="mm" :value="mm" :label="mm || t('transfer.emptyMaterial')" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="of.scope === 'SINGLE'" :label="t('common.sn')" required>
          <el-input v-model="of.sn" class="w260 sn-mono" clearable @change="resetCandidates" />
        </el-form-item>
        <el-divider />
        <el-form-item :label="t('transfer.toOrg')" required>
          <el-select v-model="of.to_factory" filterable class="w260" @change="resetCandidates">
            <el-option v-for="x in factories" :key="x.factory_code" :value="x.factory_code" :disabled="!x.enabled"
              :label="`${x.factory_name}（${x.factory_code}）${x.mes_enabled ? ' · MES' : ''}${x.enabled ? '' : t('common.disabledSuffix')}`" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('transfer.toPi')" required>
          <el-autocomplete v-model="of.to_pi" class="w260" :fetch-suggestions="(q: string, cb: any) => suggest('pi', q, cb)" :trigger-on-focus="false" clearable />
          <el-tag v-if="isManual('pi')" size="small" type="info" class="ml">{{ t("transfer.manual") }}</el-tag>
        </el-form-item>
        <el-form-item :label="t('transfer.toCustomer')">
          <el-autocomplete v-model="of.to_customer" class="w260" :fetch-suggestions="(q: string, cb: any) => suggest('customer', q, cb)" :trigger-on-focus="false"
            clearable :placeholder="t('transfer.keepOriginal')" />
          <el-tag v-if="isManual('customer')" size="small" type="info" class="ml">{{ t("transfer.manual") }}</el-tag>
        </el-form-item>
        <el-form-item :label="t('transfer.toMaterial')">
          <el-autocomplete v-model="of.to_material" class="w260" :fetch-suggestions="(q: string, cb: any) => suggest('material', q, cb)" :trigger-on-focus="false"
            clearable :placeholder="t('transfer.keepOriginal')" />
          <el-tag v-if="isManual('material')" size="small" type="info" class="ml">{{ t("transfer.manual") }}</el-tag>
        </el-form-item>
        <el-form-item :label="t('common.reason')" required>
          <el-input v-model="of.reason" type="textarea" :rows="2" maxlength="500" show-word-limit :placeholder="t('common.reasonPlaceholder')" />
        </el-form-item>
      </el-form>
      <div v-if="cand" class="preview">
        <div class="kpis">
          <span>{{ t("transfer.pvTotal") }} <b class="num">{{ cand.total }}</b></span>
          <span>{{ t("transfer.pvTransferable") }} <b class="num ok">{{ cand.transferable }}</b></span>
          <span>{{ t("transfer.pvDelivered") }} <b class="num">{{ cand.delivered }}</b></span>
          <span v-if="cand.xo_qty">{{ t("transfer.pvXo") }} <b class="num warn">{{ cand.xo_qty }}</b></span>
        </div>
        <div class="tags">
          <el-tag v-for="(n, s) in cand.by_status" :key="s" size="small" effect="plain">{{ statusLabel(String(s)) }} {{ n }}</el-tag>
          <el-tag v-for="(n, r) in cand.blocked" :key="r" size="small" type="danger">{{ t(`transfer.blocked.${r}`) }} {{ n }}</el-tag>
        </div>
        <p class="muted">{{ store.isAdmin ? t("transfer.directNote") : t("transfer.applyNote") }}</p>
      </div>
      <template #footer>
        <el-button @click="of.open = false">{{ t("common.cancel") }}</el-button>
        <el-button :loading="busy === 'cand'" :disabled="!of.factory_code || !of.pi_no" @click="loadCandidates">{{ t("transfer.preview") }}</el-button>
        <el-button type="primary" :loading="busy === 'org'" :disabled="!cand || !cand.transferable || !of.to_factory || !of.to_pi || !of.reason.trim()"
          @click="submitOrg">{{ store.isAdmin ? t("transfer.submitDirect") : t("transfer.submitApply") }}</el-button>
      </template>
    </el-dialog>

    <!-- ================= 单据详情 ================= -->
    <el-drawer :model-value="!!det" size="980px" @close="det = null">
      <template #header>
        <div v-if="det" class="dh">
          <b class="sn-mono">{{ det.doc.transfer_no }}</b>
          <el-tag v-if="det.kind === 'ORG'" size="small" :type="TRANSFER_STATUS_TYPE[det.doc.status] || 'info'">{{ transferStatusLabel(det.doc.status) }}</el-tag>
          <el-tag v-if="det.doc.direction" size="small" effect="plain">{{ t(`transfer.direction.${det.doc.direction}`) }}</el-tag>
        </div>
      </template>
      <template v-if="det">
        <el-descriptions :column="2" size="small" border>
          <template v-if="det.kind === 'BILL'">
            <el-descriptions-item :label="t('transfer.mode')">{{ t(`transfer.billMode.${det.doc.mode}`) }}</el-descriptions-item>
            <el-descriptions-item :label="t('transfer.scope')">{{ det.doc.scope_text }}</el-descriptions-item>
            <el-descriptions-item :label="t('transfer.fromBill')">{{ det.doc.from_bill }} · {{ det.doc.from_pi }}</el-descriptions-item>
            <el-descriptions-item :label="t('transfer.toBill')">{{ det.doc.to_bill }} · {{ det.doc.to_pi }}</el-descriptions-item>
            <el-descriptions-item :label="t('common.operator')">{{ det.doc.created_by }} · {{ fmt(det.doc.created_at) }}</el-descriptions-item>
          </template>
          <template v-else>
            <el-descriptions-item :label="t('transfer.mode')">{{ t(`transfer.orgMode.${det.doc.mode}`) }}</el-descriptions-item>
            <el-descriptions-item :label="t('transfer.scope')">{{ scopeLabel(det.doc.scope) }} {{ det.doc.from_material || det.doc.from_sn }}</el-descriptions-item>
            <el-descriptions-item :label="t('transfer.source')">{{ nameOf(det.doc.from_factory) }} · {{ det.doc.from_pi }}</el-descriptions-item>
            <el-descriptions-item :label="t('transfer.target')">
              {{ nameOf(det.doc.to_factory) }} · {{ det.doc.to_pi }} {{ det.doc.to_customer }} {{ det.doc.to_material }}
              <el-tag v-if="det.doc.manual_fields" size="small" type="info" class="ml">{{ t("transfer.manual") }}：{{ det.doc.manual_fields }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item :label="t('transfer.applied')">{{ det.doc.applied_by }} · {{ fmt(det.doc.applied_at) }}</el-descriptions-item>
            <el-descriptions-item :label="t('transfer.decided')">
              <template v-if="det.doc.decided_by">{{ det.doc.decided_by }} · {{ fmt(det.doc.decided_at) }}<span v-if="det.doc.decide_note"> · {{ det.doc.decide_note }}</span></template>
              <span v-else class="muted">—</span>
            </el-descriptions-item>
          </template>
          <el-descriptions-item :label="t('common.qty')"><span class="num">{{ det.doc.qty }}</span></el-descriptions-item>
          <el-descriptions-item :label="t('common.reason')">{{ det.doc.reason }}</el-descriptions-item>
        </el-descriptions>

        <div class="row mt">
          <el-button v-if="det.kind === 'ORG' && det.doc.status === 'APPROVED'" size="small" @click="downloadOutList(det.doc)">
            <el-icon><Download /></el-icon><span>{{ t("transfer.outList") }}</span>
          </el-button>
        </div>

        <template v-if="det.doc.deliveries?.length || det.doc.out_batches?.length">
          <h4 class="sub">{{ t("transfer.pushTitle") }}</h4>
          <el-table v-if="det.doc.deliveries?.length" :data="det.doc.deliveries" size="small">
            <el-table-column :label="t('transfer.pushTo')" min-width="160"><template #default="{ row }">{{ nameOf(row.factory_code) }} · {{ row.pi_no }}</template></el-table-column>
            <el-table-column :label="t('common.qty')" align="right" width="70"><template #default="{ row }"><span class="num">{{ row.qty }}</span></template></el-table-column>
            <el-table-column :label="t('status.TO_PUSH')" align="right" width="80"><template #default="{ row }"><span class="num">{{ row.to_push }}</span></template></el-table-column>
            <el-table-column :label="t('transfer.batches')" min-width="200"><template #default="{ row }"><span class="sn-mono small">{{ row.batches.join("、") || "—" }}</span></template></el-table-column>
            <el-table-column :label="t('common.actions')" width="170" align="right">
              <template #default="{ row }">
                <el-button v-if="store.isAdmin && row.remaining && (row.remaining.batches || row.remaining.unbatched)" size="small"
                  :loading="busy === `resume:${row.factory_code}:${row.pi_no}`" @click="resume(row.factory_code, row.pi_no)">
                  {{ t("transfer.resume", { n: row.remaining.pushes }) }}
                </el-button>
                <span v-else-if="!row.remaining" class="muted">{{ t("transfer.noMes") }}</span>
              </template>
            </el-table-column>
          </el-table>
          <el-table v-if="det.doc.out_batches?.length" :data="det.doc.out_batches" size="small" class="mt">
            <el-table-column :label="t('transfer.outBatch')" min-width="220"><template #default="{ row }"><span class="sn-mono">{{ row.batch_no }}</span></template></el-table-column>
            <el-table-column :label="t('common.factory')" min-width="120"><template #default="{ row }">{{ nameOf(row.factory_code) }}</template></el-table-column>
            <el-table-column :label="t('common.qty')" align="right" width="70"><template #default="{ row }"><span class="num">{{ row.qty }}</span></template></el-table-column>
            <el-table-column :label="t('common.status')" width="110">
              <template #default="{ row }"><el-tag size="small" :type="BATCH_RESULT_TYPE[row.result] || 'info'">{{ batchResultLabel(row.result) }}</el-tag></template>
            </el-table-column>
            <el-table-column :label="t('transfer.failReason')" min-width="160" show-overflow-tooltip><template #default="{ row }">{{ row.fail_reason || "—" }}</template></el-table-column>
            <el-table-column :label="t('common.actions')" width="230" align="right">
              <template #default="{ row }">
                <template v-if="store.isAdmin && (row.result === 'UNCONFIRMED' || row.result === 'FAILED')">
                  <el-button link type="primary" :loading="busy === `resume:${row.factory_code}:#XO`" @click="resume(row.factory_code, '#XO')">{{ t("transfer.resumeShort") }}</el-button>
                  <el-button link type="success" @click="openManual(row, 'confirm')">{{ t("transfer.manualConfirm") }}</el-button>
                  <el-button v-if="row.voidable" link type="danger" @click="openManual(row, 'void')">{{ t("transfer.void") }}</el-button>
                </template>
              </template>
            </el-table-column>
          </el-table>
        </template>

        <h4 class="sub">{{ t("transfer.itemsTitle") }}</h4>
        <el-table v-loading="ditems.state.loading" :data="ditems.items.value" size="small" :empty-text="t('common.empty')">
          <el-table-column :label="t('common.sn')" min-width="190">
            <template #default="{ row }"><div class="sn-mono">{{ row.sn }}</div><div class="muted">{{ row.seq_text }} · {{ row.seq_pi_no }}</div></template>
          </el-table-column>
          <el-table-column :label="t('transfer.before')" min-width="200">
            <template #default="{ row }">
              <div>{{ statusLabel(row.from_status) }} · {{ row.from_factory || "—" }} · {{ row.from_pi }}</div>
              <div class="muted">{{ row.from_bill }}/{{ row.from_seq }} · {{ row.from_material || "—" }} · rev {{ row.from_rev }}</div>
              <div v-if="row.from_delivery_batch || row.from_acquire_batch" class="muted sn-mono small">{{ row.from_delivery_batch || row.from_acquire_batch }}</div>
            </template>
          </el-table-column>
          <el-table-column :label="t('transfer.after')" min-width="200">
            <template #default="{ row }">
              <div>{{ statusLabel(row.to_status) }} · {{ row.to_factory || "—" }} · {{ row.to_pi }}</div>
              <div class="muted">{{ row.to_bill }}/{{ row.to_seq }} · {{ row.to_material || "—" }} · rev {{ row.to_rev }}</div>
              <div v-if="row.out_batch_no" class="muted sn-mono small">{{ t("transfer.outBatch") }} {{ row.out_batch_no }}</div>
            </template>
          </el-table-column>
          <el-table-column :label="t('transfer.current')" width="150">
            <template #default="{ row }">
              <el-tag v-if="row.current_status === 'TRANSFERRED_OUT'" size="small" type="info">{{ t("transfer.transferredOut") }}</el-tag>
              <StatusTag v-else-if="row.current_status" :status="row.current_status" />
              <div v-if="row.cur_batch" class="muted sn-mono small">{{ row.cur_batch }}</div>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination class="pager" layout="total, prev, pager, next" :total="ditems.state.total" :page-size="ditems.state.pageSize"
          :current-page="ditems.state.page" @current-change="ditems.load" />
      </template>
    </el-drawer>

    <!-- 批次人工确认 / 作废 -->
    <el-dialog v-model="man.open" :title="man.kind === 'void' ? t('transfer.void') : t('transfer.manualConfirm')" width="520px">
      <p class="muted">{{ man.kind === "void" ? t("transfer.voidTip") : t("transfer.confirmTip") }}</p>
      <el-form label-width="80px">
        <el-form-item :label="t('common.batchNo')"><span class="sn-mono">{{ man.batch?.batch_no }}</span></el-form-item>
        <el-form-item :label="t('common.reason')" required><el-input v-model="man.reason" type="textarea" :rows="2" maxlength="500" show-word-limit /></el-form-item>
        <el-form-item :label="t('transfer.basis')" required><el-input v-model="man.basis" type="textarea" :rows="2" maxlength="500" show-word-limit :placeholder="t('transfer.basisPlaceholder')" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="man.open = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" :loading="busy === 'manual'" :disabled="!man.reason.trim() || !man.basis.trim()" @click="submitManual">{{ t("common.confirm") }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute } from "vue-router";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { api, urls } from "@/api";
import { ApiError, http } from "@/api/http";
import PageHead from "@/components/PageHead.vue";
import FactorySelect from "@/components/FactorySelect.vue";
import StatusTag from "@/components/StatusTag.vue";
import { useFactories } from "@/composables/useFactories";
import { usePaged } from "@/composables/usePaged";
import { useTime } from "@/composables/useTime";
import { BATCH_RESULT_TYPE, TRANSFER_STATUS_TYPE, batchResultLabel, scopeLabel, statusLabel, transferStatusLabel } from "@/constants";
import { useAuthStore } from "@/stores/auth";
import type { DeliveryBatch, OrderLine } from "@/types/api";
import { downloadWithToken } from "@/utils/download";

interface Target {
  org_code: string;
  org_name: string;
  customer_code: string;
  pi: string;
  bill_no: string;
  seq: number;
  material_code: string;
  material_name: string;
  length: string;
}
interface Summary {
  total: number;
  transferable: number;
  by_status: Record<string, number>;
  blocked: Record<string, number>;
  delivered: number;
}
interface BillPreview extends Summary {
  generated_changes: { bill_no: string; seq: number; before: number; after: number }[];
  to_pi: string;
  to_org: string;
  scope: string;
  other_org: number;
}
interface Candidates extends Summary {
  materials: string[];
  xo_qty: number;
}

const ORG_STATUSES = ["", "PENDING", "APPROVED", "REJECTED", "WITHDRAWN"];

const { t } = useI18n();
const store = useAuthStore();
const route = useRoute();
const { factories, nameOf } = useFactories();
const { fmt } = useTime();
const busy = ref("");

const tab = ref<"bill" | "org">(route.query.tab === "org" || !store.isAdmin ? "org" : "bill");

// ---------------- 转单列表
const bq = reactive({ factory_code: "", pi: "", bill_no: "" });
const bills = usePaged<any>((q) => api.billTransfers({ ...q, ...bq, pi: bq.pi.trim(), bill_no: bq.bill_no.trim() }));

// ---------------- 转组织列表（总部默认看待处理）
const oq = reactive({ status: store.isAdmin ? "PENDING" : "", factory_code: "", pi: "", bill_no: "" });
const pending = ref(0);
const orgs = usePaged<any>(async (q) => {
  const r = await api.orgTransfers({ ...q, ...oq, pi: oq.pi.trim(), bill_no: oq.bill_no.trim() });
  pending.value = r.pending ?? 0;
  return r;
});
const orgRows = computed(() =>
  orgs.items.value.map((x: any) => ({ ...x.transfer, direction: x.direction, to_factory_disabled: x.to_factory_disabled })),
);

function onTab(name: string | number) {
  if (name === "bill") bills.load(1);
  else orgs.load(1);
}

async function decide(row: any, kind: "approve" | "reject") {
  const { value } = await ElMessageBox.prompt(
    t(kind === "approve" ? "transfer.approveConfirm" : "transfer.rejectConfirm", { no: row.transfer_no, n: row.qty }),
    kind === "approve" ? t("transfer.approve") : t("transfer.reject"),
    { inputType: "textarea", inputPlaceholder: t("transfer.notePlaceholder"), inputValidator: (v) => (v || "").length <= 500, type: "warning" },
  );
  const r = kind === "approve" ? await api.approveOrg(row.id, value || undefined) : await api.rejectOrg(row.id, value || undefined);
  ElMessage.success(t("common.done"));
  orgs.load();
  if (kind === "approve") openDetail("ORG", r.id);
}

async function withdraw(row: any) {
  await ElMessageBox.confirm(t("transfer.withdrawConfirm", { no: row.transfer_no }), t("transfer.withdraw"), { type: "warning" });
  await api.withdrawOrg(row.id);
  ElMessage.success(t("common.done"));
  orgs.load();
}

// ---------------- 目标候选（快照中的物料行）
const known = reactive({ pi: new Set<string>(), customer: new Set<string>(), material: new Set<string>() });
async function fetchTargets(q: string): Promise<Target[]> {
  const rows: Target[] = await api.orderTargets(q.trim(), 50);
  rows.forEach((r) => {
    known.pi.add(r.pi);
    known.customer.add(r.customer_code);
    known.material.add(r.material_code);
  });
  return rows;
}
async function suggestBills(q: string, cb: (x: { value: string }[]) => void) {
  if (!q.trim()) return cb([]);
  const rows = await fetchTargets(q);
  cb([...new Set(rows.map((r) => r.bill_no))].map((v) => ({ value: v })));
}
type Field = "pi" | "customer" | "material";
async function suggest(field: Field, q: string, cb: (x: { value: string }[]) => void) {
  if (!q.trim()) return cb([]);
  const rows = await fetchTargets(q);
  const pick = (r: Target) => (field === "pi" ? r.pi : field === "customer" ? r.customer_code : r.material_code);
  cb([...new Set(rows.map(pick).filter(Boolean))].map((v) => ({ value: v })));
}

// ---------------- 新建转单
const bf = reactive({
  open: false, from_bill: "", to_bill: "", mode: "VOID" as "VOID" | "SPLIT", factory_code: "", pi_no: "",
  from_seq: undefined as number | undefined, start_sn: "", end_sn: "", to_seq: undefined as number | undefined, reason: "",
});
const fromLines = ref<OrderLine[]>([]);
const toLines = ref<OrderLine[]>([]);
const toBill = ref<{ org_code: string; pi: string; customer_code: string } | null>(null);
const mapping = reactive<Record<number, number | undefined>>({});
const mapRows = ref<{ seq: number; material: string }[]>([]);
const bPreview = ref<BillPreview | null>(null);

function resetPreview() {
  bPreview.value = null;
}
function lineLabel(l: OrderLine) {
  return `${l.seq} · ${l.material_code || t("transfer.emptyMaterial")} · ${t("common.length")} ${l.length || "—"}`;
}

function openBill() {
  Object.assign(bf, { open: true, from_bill: "", to_bill: "", mode: "VOID", factory_code: "", pi_no: "", from_seq: undefined, start_sn: "", end_sn: "", to_seq: undefined, reason: "" });
  fromLines.value = [];
  toLines.value = [];
  toBill.value = null;
  mapRows.value = [];
  Object.keys(mapping).forEach((k) => delete mapping[Number(k)]);
  resetPreview();
}

/** 单据可能已不在快照中：查不到时静默（不弹错误）。 */
async function linesOf(billNo: string): Promise<{ head: any; lines: OrderLine[] } | null> {
  if (!billNo.trim()) return null;
  try {
    const r = await http.get<any, any>(`/orders/${encodeURIComponent(billNo.trim())}/lines`, { silent: true });
    return { head: r, lines: r.lines || [] };
  } catch {
    return null;
  }
}

async function loadFromLines() {
  resetPreview();
  const r = await linesOf(bf.from_bill);
  fromLines.value = r?.lines ?? [];
  rebuildMapRows();
}
async function loadToLines() {
  resetPreview();
  const r = await linesOf(bf.to_bill);
  toLines.value = r?.lines ?? [];
  toBill.value = r ? { org_code: r.head.org_code, pi: r.head.pi, customer_code: r.head.customer_code } : null;
  rebuildMapRows();
}
/** 整单作废：来源各物料行在目标单据中没有或有多行同物料时，须手工选目标物料行。 */
function rebuildMapRows() {
  if (!fromLines.value.length || !toLines.value.length) return;
  const rows = fromLines.value
    .filter((l) => toLines.value.filter((x) => x.material_code === l.material_code).length !== 1)
    .map((l) => ({ seq: l.seq, material: l.material_code }));
  const extra = mapRows.value.filter((r) => !rows.some((x) => x.seq === r.seq));
  mapRows.value = [...rows, ...extra];
}

function billBody() {
  const map: Record<string, number> = {};
  Object.entries(mapping).forEach(([k, v]) => {
    if (v !== undefined && v !== null) map[k] = v;
  });
  return bf.mode === "VOID"
    ? { from_bill: bf.from_bill.trim(), to_bill: bf.to_bill.trim(), mode: "VOID", mapping: map, reason: bf.reason }
    : {
        from_bill: bf.from_bill.trim(), to_bill: bf.to_bill.trim(), mode: "SPLIT", factory_code: bf.factory_code || null,
        pi_no: bf.pi_no.trim() || null, from_seq: bf.from_seq ?? null, start_sn: bf.start_sn.trim() || null,
        end_sn: bf.end_sn.trim() || null, to_seq: bf.to_seq ?? null, reason: bf.reason,
      };
}

async function previewBill() {
  busy.value = "bpv";
  try {
    bPreview.value = await api.billTransferPreview(billBody());
  } catch (e) {
    // 目标单据中没有或有多行对应：加一行让用户手工选目标物料行
    if (e instanceof ApiError && e.code === "TR_TARGET_LINE_REQUIRED" && bf.mode === "VOID") {
      const seq = Number(e.params?.seq);
      if (seq && !mapRows.value.some((r) => r.seq === seq)) mapRows.value.push({ seq, material: String(e.params?.material ?? "") });
      if (!toLines.value.length) await loadToLines();
    }
  } finally {
    busy.value = "";
  }
}

async function submitBill() {
  const p = bPreview.value!;
  await ElMessageBox.confirm(t("transfer.billConfirm", { n: p.transferable, to: bf.to_bill }), t("transfer.newBill"), { type: "warning" });
  busy.value = "bill";
  try {
    const r = await api.billTransfer(billBody());
    ElMessage.success(t("transfer.billDone", { no: r.transfer_no, n: r.qty }));
    bf.open = false;
    tab.value = "bill";
    bills.load(1);
    showDoc("BILL", r);
  } finally {
    busy.value = "";
  }
}

// ---------------- 新建转组织
const of = reactive({
  open: false, factory_code: "", pi_no: "", scope: "PI" as "PI" | "MATERIAL" | "SINGLE", material_code: "", sn: "",
  to_factory: "", to_pi: "", to_customer: "", to_material: "", reason: "",
});
const cand = ref<Candidates | null>(null);
const materials = ref<string[]>([]);

function resetCandidates() {
  cand.value = null;
}
function isManual(field: Field) {
  const v = (field === "pi" ? of.to_pi : field === "customer" ? of.to_customer : of.to_material).trim();
  return !!v && !known[field].has(v);
}

function openOrg(prefill: { factory_code?: string; pi?: string; material?: string } = {}) {
  Object.assign(of, {
    open: true, factory_code: store.isFactory ? store.boundFactory : prefill.factory_code || "", pi_no: prefill.pi || "",
    scope: prefill.material !== undefined ? "MATERIAL" : "PI", material_code: prefill.material ?? "", sn: "",
    to_factory: "", to_pi: prefill.pi || "", to_customer: "", to_material: "", reason: "",
  });
  materials.value = [];
  resetCandidates();
}

function orgBody() {
  const manual = (["pi", "customer", "material"] as Field[]).filter(isManual);
  return {
    factory_code: of.factory_code, pi_no: of.pi_no.trim(), scope: of.scope,
    material_code: of.scope === "MATERIAL" ? of.material_code : null, sn: of.scope === "SINGLE" ? of.sn.trim() : null,
    to_factory: of.to_factory, to_pi: of.to_pi.trim(), to_customer: of.to_customer.trim() || null,
    to_material: of.to_material.trim() || null, manual_fields: manual, reason: of.reason,
  };
}

async function loadCandidates() {
  busy.value = "cand";
  try {
    const r: Candidates = await api.orgCandidates(orgBody());
    cand.value = r;
    materials.value = r.materials;
  } finally {
    busy.value = "";
  }
}

async function submitOrg() {
  const c = cand.value!;
  const key = store.isAdmin ? "transfer.directConfirm" : "transfer.applyConfirm";
  await ElMessageBox.confirm(t(key, { n: c.transferable, to: nameOf(of.to_factory), pi: of.to_pi }), store.isAdmin ? t("transfer.newDirect") : t("transfer.newApply"), { type: "warning" });
  busy.value = "org";
  try {
    const r = store.isAdmin ? await api.orgDirect(orgBody()) : await api.orgApply(orgBody());
    ElMessage.success(t("transfer.orgDone", { no: r.transfer_no, n: r.qty }));
    of.open = false;
    tab.value = "org";
    orgs.load(1);
    if (r.status === "APPROVED") openDetail("ORG", r.id);
  } finally {
    busy.value = "";
  }
}

// ---------------- 详情
const det = ref<{ kind: "BILL" | "ORG"; doc: any } | null>(null);
const ditems = usePaged<any>((q) => (det.value!.kind === "BILL" ? api.billTransferItems(det.value!.doc.id, q) : api.orgTransferItems(det.value!.doc.id, q)), 50);

function showDoc(kind: "BILL" | "ORG", doc: any) {
  det.value = { kind, doc };
  ditems.load(1);
}
async function openDetail(kind: "BILL" | "ORG", id: number) {
  showDoc(kind, kind === "BILL" ? await api.billTransferOne(id) : await api.orgTransfer(id));
}
async function refreshDetail() {
  if (det.value) await openDetail(det.value.kind, det.value.doc.id);
}

async function resume(factory: string, key: string) {
  busy.value = `resume:${factory}:${key}`;
  try {
    await api.resumePush(factory, key);
    ElMessage.success(t("transfer.resumed"));
    await refreshDetail();
  } finally {
    busy.value = "";
  }
}

async function downloadOutList(doc: any) {
  try {
    await downloadWithToken(urls.outList(doc.id, "xlsx"), `out_${doc.transfer_no}.xlsx`);
  } catch (e) {
    ElMessage.error((e as Error).message);
  }
}

const man = reactive({ open: false, kind: "confirm" as "confirm" | "void", batch: null as DeliveryBatch | null, reason: "", basis: "" });
function openManual(b: DeliveryBatch, kind: "confirm" | "void") {
  Object.assign(man, { open: true, kind, batch: b, reason: "", basis: "" });
}
async function submitManual() {
  const b = man.batch!;
  busy.value = "manual";
  try {
    if (man.kind === "void") await api.voidBatch(b.batch_no, man.reason, man.basis);
    else await api.confirmBatch(b.batch_no, man.reason, man.basis);
    ElMessage.success(t("common.done"));
    man.open = false;
    await refreshDetail();
  } finally {
    busy.value = "";
  }
}

onMounted(() => {
  if (tab.value === "bill") bills.load(1);
  orgs.load(1);
  if (route.query.new === "1" && store.canAct) {
    tab.value = "org";
    openOrg({
      factory_code: route.query.factory_code ? String(route.query.factory_code) : undefined,
      pi: route.query.pi ? String(route.query.pi) : undefined,
      material: route.query.material !== undefined ? String(route.query.material) : undefined,
    });
  }
});
</script>

<style scoped>
.w180 { width: 180px; }
.w220 { width: 220px; }
.w260 { width: 260px; }
.ml { margin-left: 8px; }
.mt { margin-top: 12px; }
.cnt { margin-left: 6px; font-size: 12px; }
.badge { margin-left: 4px; vertical-align: top; }
.hint { width: 100%; margin-top: 4px; }
.form :deep(.el-form-item) { margin-bottom: 14px; }
.map { display: flex; flex-direction: column; gap: 8px; }
.map-row { display: flex; align-items: center; gap: 8px; }
.preview { border: 1px solid var(--app-line); border-radius: 8px; padding: 12px 16px; background: var(--app-canvas); }
.kpis { display: flex; flex-wrap: wrap; gap: 18px; align-items: baseline; }
.tags { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.ok { color: var(--el-color-success); }
.warn { color: var(--el-color-danger); }
.dh { display: flex; align-items: center; gap: 10px; font-size: 15px; color: var(--app-text); }
.row { display: flex; gap: 8px; flex-wrap: wrap; }
.sub { margin: 18px 0 8px; font-size: 14px; }
.small { font-size: 11px; }
</style>
