<template>
  <div class="page page--fill">
    <PageHead :title="t('query.title')" :desc="t('query.desc')">
      <template v-if="tab === 'sn'">
        <el-button :loading="exporting" @click="exportSn('xlsx')"><el-icon><Download /></el-icon><span>{{ t("common.downloadXlsx") }}</span></el-button>
        <el-button :loading="exporting" @click="exportSn('csv')">{{ t("common.downloadCsv") }}</el-button>
      </template>
    </PageHead>
    <div class="surface fill">
      <el-tabs v-model="tab" class="tabs-fill" @tab-change="onTab">
        <!-- ================= SN 明细 ================= -->
        <el-tab-pane :label="t('query.tabSn')" name="sn">
          <div class="toolbar">
            <FactorySelect v-if="store.isAdmin" v-model="f.factory_code" clearable />
            <el-input v-model="f.pi" placeholder="PI" clearable @keyup.enter="load(1)" />
            <el-input v-model="f.customer_code" :placeholder="t('common.customer')" clearable @keyup.enter="load(1)" />
            <el-input v-model="f.bill_no" :placeholder="t('common.billNo')" clearable @keyup.enter="load(1)" />
            <el-input v-model="f.material_code" :placeholder="t('common.material')" clearable @keyup.enter="load(1)" />
            <el-input v-model="f.length" :placeholder="t('common.length')" clearable class="w120" @keyup.enter="load(1)" />
            <el-select v-model="f.status" clearable :placeholder="t('common.status')">
              <el-option v-for="s in STATUS_CODES" :key="s" :value="s" :label="statusLabel(s)" />
              <el-option value="SUSPENDED" :label="t('query.suspended')" />
            </el-select>
            <el-select v-model="f.delivery_mode" clearable :placeholder="t('query.deliveryMode')">
              <el-option v-for="m in MODES" :key="m" :value="m" :label="deliveryModeLabel(m)" />
            </el-select>
            <el-input v-model="f.sn" :placeholder="t('common.sn')" clearable class="sn-mono" @keyup.enter="load(1)" />
            <el-input v-model="f.batch_no" :placeholder="t('query.batchFilter')" clearable @keyup.enter="load(1)" />
            <div class="seq-range">
              <span class="seq-range__label">{{ t("query.seqRange") }}</span>
              <el-input v-model="f.seq_from" :placeholder="t('query.seqFrom')" clearable class="sn-mono" @keyup.enter="load(1)" />
              <span>–</span>
              <el-input v-model="f.seq_to" :placeholder="t('query.seqTo')" clearable class="sn-mono" @keyup.enter="load(1)" />
            </div>
            <el-switch v-if="!store.isAdmin && store.bound" v-model="f.pi_all" :active-text="t('query.piAll')" :disabled="!f.pi.trim()" />
            <el-button type="primary" @click="load(1)">{{ t("common.search") }}</el-button>
            <el-button @click="resetSn">{{ t("common.reset") }}</el-button>
          </div>
          <el-alert v-if="f.pi_all" type="info" :closable="false" show-icon :title="t('query.piAllTip')" class="mb" />
          <el-alert v-if="hasRange" type="info" :closable="false" show-icon :title="t('query.seqRangeTip')" class="mb" />
          <div v-if="range" class="range-sum mb">
            <span>{{ t("query.rangeFound", { n: range.found }) }}</span>
            <span v-for="(n, s) in range.by_status" :key="s"><StatusTag :status="String(s)" /> <b>{{ n }}</b></span>
            <span v-if="range.expected != null" class="range-sum__note">
              {{ range.missing ? t("query.rangeMissing", { expected: range.expected, n: range.missing }) : t("query.rangeFull") }}
            </span>
          </div>
          <div class="fill-body">
            <el-table v-loading="state.loading" height="100%" :data="items" size="small" :empty-text="t('common.empty')">
              <el-table-column :label="t('common.sn')" min-width="180" fixed>
                <template #default="{ row }"><span class="sn-mono">{{ row.sn }}</span></template>
              </el-table-column>
              <el-table-column :label="t('common.seqText')" width="110"><template #default="{ row }"><span class="sn-mono">{{ row.seq_text }}</span></template></el-table-column>
              <el-table-column prop="seq_dec" :label="t('common.seqDec')" width="100" align="right" />
              <el-table-column label="PI" min-width="150" show-overflow-tooltip>
                <template #default="{ row }">
                  <span>{{ row.pi_no }}</span>
                  <el-tag v-if="row.pi_changed" size="small" type="warning" class="ml">{{ t("query.piChanged") }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column :label="t('query.billSeq')" min-width="150" show-overflow-tooltip>
                <template #default="{ row }">{{ row.bill_no }} / {{ row.bill_seq }}</template>
              </el-table-column>
              <el-table-column prop="material_code" :label="t('common.material')" width="110" show-overflow-tooltip />
              <el-table-column prop="length" :label="t('common.length')" width="70" />
              <el-table-column prop="customer_code" :label="t('common.customer')" width="90" show-overflow-tooltip />
              <el-table-column :label="t('common.factory')" width="120" show-overflow-tooltip>
                <template #default="{ row }">{{ nameOf(row.factory_code) }}</template>
              </el-table-column>
              <el-table-column :label="t('common.status')" width="130">
                <template #default="{ row }">
                  <StatusTag :status="row.status" />
                  <el-tag v-if="row.suspend" size="small" type="danger" class="ml">{{ suspendLabel(row.suspend) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column :label="t('query.deliveryMode')" width="90">
                <template #default="{ row }">{{ deliveryModeLabel(row.delivery_mode) }}</template>
              </el-table-column>
              <el-table-column prop="seq_pi_no" :label="t('query.seqOwner')" min-width="140" show-overflow-tooltip />
              <el-table-column :label="t('query.deliveryBatch')" min-width="200">
                <template #default="{ row }">
                  <el-button v-if="row.delivery_batch_no" link type="primary" @click="openDelivery(row.delivery_batch_no)">{{ row.delivery_batch_no }}</el-button>
                </template>
              </el-table-column>
              <el-table-column :label="t('query.acquireBatch')" min-width="180">
                <template #default="{ row }">
                  <el-button v-if="row.acquire_batch_no" link type="primary" @click="openAcquire(row.acquire_batch_no)">{{ row.acquire_batch_no }}</el-button>
                </template>
              </el-table-column>
              <el-table-column prop="rev" label="rev" width="56" align="right" />
              <el-table-column :label="t('query.pushType')" width="90">
                <template #default="{ row }">
                  {{ pushTypeLabel(row.push_type) }}
                  <el-tag v-if="row.transferred_in" size="small" type="info" class="ml">{{ t("query.transferredIn") }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column :label="t('query.mesHolder')" width="120" show-overflow-tooltip>
                <template #default="{ row }">{{ row.mes_holder ? nameOf(row.mes_holder) : "" }}</template>
              </el-table-column>
              <el-table-column :label="t('query.generatedAt')" width="160"><template #default="{ row }">{{ fmt(row.created_at) }}</template></el-table-column>
              <el-table-column :label="t('query.allocatedAt')" width="160"><template #default="{ row }">{{ fmt(row.allocated_at) }}</template></el-table-column>
              <el-table-column :label="t('query.deliveredAt')" width="160"><template #default="{ row }">{{ fmt(row.delivered_at) }}</template></el-table-column>
            </el-table>
          </div>
          <div class="foot">
            <span v-if="state.capped" class="muted">{{ t("common.capped", { n: state.total }) }}</span>
            <span v-else />
            <el-pagination layout="total, sizes, prev, pager, next" :total="state.total" :page-size="state.pageSize"
              :current-page="state.page" :page-sizes="[20, 50, 100, 500]" @current-change="load" @size-change="onSize" />
          </div>
        </el-tab-pane>

        <!-- ================= 交付批次 ================= -->
        <el-tab-pane :label="t('query.tabDelivery')" name="delivery">
          <div class="toolbar">
            <FactorySelect v-if="store.isAdmin" v-model="df.factory_code" clearable />
            <el-input v-model="df.pi" placeholder="PI" clearable @keyup.enter="loadDelivery(1)" />
            <el-input v-model="df.batch_no" :placeholder="t('common.batchNo')" clearable @keyup.enter="loadDelivery(1)" />
            <el-select v-model="df.source" clearable :placeholder="t('query.batchSource')">
              <el-option v-for="s in ['PUSH', 'PULL']" :key="s" :value="s" :label="batchSourceLabel(s)" />
            </el-select>
            <el-select v-model="df.batch_type" clearable :placeholder="t('query.batchType')">
              <el-option v-for="s in ['DELIVERY', 'TRANSFER_OUT']" :key="s" :value="s" :label="batchTypeLabel(s)" />
            </el-select>
            <el-select v-model="df.result" clearable :placeholder="t('query.result')">
              <el-option v-for="s in BATCH_RESULTS" :key="s" :value="s" :label="batchResultLabel(s)" />
            </el-select>
            <el-input v-model="df.transfer_no" :placeholder="t('query.transferNo')" clearable @keyup.enter="loadDelivery(1)" />
            <el-button type="primary" @click="loadDelivery(1)">{{ t("common.search") }}</el-button>
          </div>
          <div class="fill-body">
            <el-table v-loading="dState.loading" height="100%" :data="deliveries" size="small" :empty-text="t('common.empty')">
              <el-table-column :label="t('common.batchNo')" min-width="220" fixed>
                <template #default="{ row }"><el-button link type="primary" @click="openDelivery(row.batch_no)">{{ row.batch_no }}</el-button></template>
              </el-table-column>
              <el-table-column :label="t('query.batchSource')" width="76"><template #default="{ row }">{{ batchSourceLabel(row.source) }}</template></el-table-column>
              <el-table-column :label="t('query.batchType')" width="76"><template #default="{ row }">{{ batchTypeLabel(row.batch_type) }}</template></el-table-column>
              <el-table-column :label="t('common.factory')" width="120" show-overflow-tooltip><template #default="{ row }">{{ nameOf(row.factory_code) }}</template></el-table-column>
              <el-table-column prop="pi_no" label="PI" min-width="140" show-overflow-tooltip />
              <el-table-column prop="qty" :label="t('common.qty')" width="70" align="right" />
              <el-table-column :label="t('query.result')" width="130">
                <template #default="{ row }"><el-tag size="small" :type="resultType(row)">{{ resultText(row) }}</el-tag></template>
              </el-table-column>
              <el-table-column :label="t('query.confirmMethod')" width="150">
                <template #default="{ row }">
                  <div>{{ confirmMethodLabel(row.confirm_method) }}</div>
                  <div v-if="row.confirmed_at" class="muted">{{ fmt(row.confirmed_at) }}</div>
                </template>
              </el-table-column>
              <el-table-column :label="t('query.receiptCounts')" width="150">
                <template #default="{ row }"><span class="num">{{ row.accepted_qty }} / {{ row.duplicate_qty }} / {{ row.conflict_qty }} / {{ row.stale_qty }}</span></template>
              </el-table-column>
              <el-table-column :label="t('query.pushState')" width="110"><template #default="{ row }">{{ row.source === "PUSH" ? pushStateLabel(row.push_state) : "" }}</template></el-table-column>
              <el-table-column :label="t('query.retry')" width="80" align="right"><template #default="{ row }">{{ row.retry_count }}</template></el-table-column>
              <el-table-column :label="t('query.nextRetry')" width="160"><template #default="{ row }">{{ row.next_retry_at ? fmt(row.next_retry_at) : "" }}</template></el-table-column>
              <el-table-column prop="fail_reason" :label="t('query.failReason')" min-width="180" show-overflow-tooltip />
              <el-table-column prop="transfer_doc_no" :label="t('query.transferNo')" min-width="170" show-overflow-tooltip />
              <el-table-column prop="created_by" :label="t('common.operator')" width="90" />
              <el-table-column :label="t('common.createdAt')" width="160"><template #default="{ row }">{{ fmt(row.created_at) }}</template></el-table-column>
            </el-table>
          </div>
          <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="dState.total" :page-size="dState.pageSize"
            :current-page="dState.page" :page-sizes="[20, 50, 100]" @current-change="loadDelivery" @size-change="dSize" />
        </el-tab-pane>

        <!-- ================= 领取批次 ================= -->
        <el-tab-pane :label="t('query.tabAcquire')" name="acquire">
          <div class="toolbar">
            <FactorySelect v-if="store.isAdmin" v-model="af.factory_code" clearable />
            <el-input v-model="af.pi" placeholder="PI" clearable @keyup.enter="loadAcquire(1)" />
            <el-input v-model="af.batch_no" :placeholder="t('common.batchNo')" clearable @keyup.enter="loadAcquire(1)" />
            <el-input v-model="af.request_no" :placeholder="t('query.requestNo')" clearable @keyup.enter="loadAcquire(1)" />
            <el-button type="primary" @click="loadAcquire(1)">{{ t("common.search") }}</el-button>
          </div>
          <div class="fill-body">
            <el-table v-loading="aState.loading" height="100%" :data="acquires" size="small" :empty-text="t('common.empty')">
              <el-table-column :label="t('common.batchNo')" min-width="190">
                <template #default="{ row }"><el-button link type="primary" @click="openAcquire(row.batch_no)">{{ row.batch_no }}</el-button></template>
              </el-table-column>
              <el-table-column :label="t('common.factory')" width="120" show-overflow-tooltip><template #default="{ row }">{{ nameOf(row.factory_code) }}</template></el-table-column>
              <el-table-column prop="pi_no" label="PI" min-width="140" show-overflow-tooltip />
              <el-table-column prop="request_no" :label="t('query.requestNo')" min-width="160" show-overflow-tooltip />
              <el-table-column :label="t('query.mesOrg')" width="100"><template #default="{ row }">{{ row.mes_org ? t("common.yes") : t("common.no") }}</template></el-table-column>
              <el-table-column :label="t('common.status')" width="90">
                <template #default="{ row }"><el-tag size="small" :type="row.status === 'DONE' ? 'success' : 'warning'">{{ acqStatus(row.status) }}</el-tag></template>
              </el-table-column>
              <el-table-column prop="qty" :label="t('common.qty')" width="80" align="right" />
              <el-table-column prop="created_by" :label="t('common.operator')" width="100" />
              <el-table-column :label="t('common.createdAt')" width="160"><template #default="{ row }">{{ fmt(row.created_at) }}</template></el-table-column>
              <el-table-column :label="t('query.finishedAt')" width="160"><template #default="{ row }">{{ fmt(row.finished_at) }}</template></el-table-column>
              <el-table-column :label="t('common.download')" width="120">
                <template #default="{ row }">
                  <el-button link type="primary" :disabled="row.status !== 'DONE'" @click="downloadAcquire(row.batch_no, 'xlsx')">xlsx</el-button>
                  <el-button link type="primary" :disabled="row.status !== 'DONE'" @click="downloadAcquire(row.batch_no, 'csv')">csv</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
          <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="aState.total" :page-size="aState.pageSize"
            :current-page="aState.page" :page-sizes="[20, 50, 100]" @current-change="loadAcquire" @size-change="aSize" />
        </el-tab-pane>

        <!-- ================= 交付告警 ================= -->
        <el-tab-pane :label="t('query.tabAlerts')" name="alerts">
          <div class="toolbar">
            <el-radio-group v-model="alertStatus" @change="loadAlerts(1)">
              <el-radio-button value="OPEN">{{ t("query.alertStatus.OPEN") }}</el-radio-button>
              <el-radio-button value="CLOSED">{{ t("query.alertStatus.CLOSED") }}</el-radio-button>
              <el-radio-button value="ALL">{{ t("common.all") }}</el-radio-button>
            </el-radio-group>
            <el-button @click="loadAlerts(1)"><el-icon><Refresh /></el-icon><span>{{ t("common.refresh") }}</span></el-button>
          </div>
          <div class="fill-body">
            <el-table v-loading="alState.loading" height="100%" :data="alerts" size="small" :empty-text="t('common.empty')">
              <el-table-column :label="t('common.time')" width="160"><template #default="{ row }">{{ fmt(row.created_at) }}</template></el-table-column>
              <el-table-column :label="t('query.alertTypeCol')" width="130">
                <template #default="{ row }"><el-tag size="small" :type="row.alert_type === 'CONFLICT' ? 'danger' : 'warning'">{{ alertTypeLabel(row.alert_type) }}</el-tag></template>
              </el-table-column>
              <el-table-column :label="t('common.factory')" width="120" show-overflow-tooltip><template #default="{ row }">{{ nameOf(row.factory_code) }}</template></el-table-column>
              <el-table-column :label="t('common.batchNo')" min-width="210">
                <template #default="{ row }"><el-button v-if="row.batch_no" link type="primary" @click="openDelivery(row.batch_no)">{{ row.batch_no }}</el-button></template>
              </el-table-column>
              <el-table-column :label="t('common.sn')" min-width="170"><template #default="{ row }"><span class="sn-mono">{{ row.sn }}</span></template></el-table-column>
              <el-table-column :label="t('query.alertRev')" width="120">
                <template #default="{ row }"><span v-if="row.rev != null || row.existing_rev != null">{{ row.rev ?? "—" }} / {{ row.existing_rev ?? "—" }}</span></template>
              </el-table-column>
              <el-table-column prop="existing_batch_no" :label="t('query.existing')" min-width="190" show-overflow-tooltip />
              <el-table-column prop="detail" :label="t('query.alertDetail')" min-width="220" show-overflow-tooltip />
              <el-table-column :label="t('common.status')" width="90">
                <template #default="{ row }"><el-tag size="small" :type="row.status === 'OPEN' ? 'warning' : 'info'">{{ alertStatusLabel(row.status) }}</el-tag></template>
              </el-table-column>
              <el-table-column :label="t('query.handledBy')" min-width="220">
                <template #default="{ row }">
                  <template v-if="row.status === 'CLOSED'">
                    <div>{{ closeActionLabel(row.close_action) }} · {{ row.closed_by }} · {{ fmt(row.closed_at) }}</div>
                    <div class="muted">{{ row.note }}</div>
                  </template>
                </template>
              </el-table-column>
              <el-table-column v-if="store.isAdmin" :label="t('common.actions')" width="200" fixed="right">
                <template #default="{ row }">
                  <template v-if="row.status === 'OPEN'">
                    <el-button v-if="row.item_id" link type="primary" @click="openHandle(row, 'RESEND')">{{ t("query.resend") }}</el-button>
                    <el-button link type="primary" @click="openHandle(row, 'CLOSE')">{{ t("query.closeAlert") }}</el-button>
                  </template>
                </template>
              </el-table-column>
            </el-table>
          </div>
          <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="alState.total" :page-size="alState.pageSize"
            :current-page="alState.page" :page-sizes="[20, 50, 100]" @current-change="loadAlerts" @size-change="alSize" />
        </el-tab-pane>
      </el-tabs>
    </div>

    <!-- 交付批次详情 -->
    <el-drawer :model-value="!!dNo" :title="t('query.batchDetail', { no: dNo })" size="880px" @close="dNo = ''">
      <template v-if="batch">
        <div class="drawer-actions">
          <el-button size="small" @click="exportDelivery('xlsx')"><el-icon><Download /></el-icon><span>{{ t("common.downloadXlsx") }}</span></el-button>
          <el-button size="small" @click="exportDelivery('csv')">{{ t("common.downloadCsv") }}</el-button>
          <template v-if="store.isAdmin">
            <el-button v-if="batch.result === 'UNCONFIRMED' || batch.result === 'FAILED'" size="small" type="primary" @click="openManual('confirm')">{{ t("query.confirmBatch") }}</el-button>
            <el-button v-if="batch.voidable && (batch.result === 'UNCONFIRMED' || batch.result === 'FAILED')" size="small" type="danger" plain @click="openManual('void')">{{ t("query.voidBatch") }}</el-button>
          </template>
        </div>
        <el-descriptions :column="2" size="small" border class="mb">
          <el-descriptions-item :label="t('query.batchSource')">{{ batchSourceLabel(batch.source) }} · {{ batchTypeLabel(batch.batch_type) }}</el-descriptions-item>
          <el-descriptions-item :label="t('query.result')"><el-tag size="small" :type="resultType(batch)">{{ resultText(batch) }}</el-tag></el-descriptions-item>
          <el-descriptions-item :label="t('common.factory')">{{ nameOf(batch.factory_code) }}（{{ batch.factory_code }}）</el-descriptions-item>
          <el-descriptions-item label="PI">{{ batch.pi_no }}</el-descriptions-item>
          <el-descriptions-item :label="t('common.qty')">{{ batch.qty }}</el-descriptions-item>
          <el-descriptions-item :label="t('query.receiptCounts')">{{ batch.accepted_qty }} / {{ batch.duplicate_qty }} / {{ batch.conflict_qty }} / {{ batch.stale_qty }}</el-descriptions-item>
          <el-descriptions-item :label="t('query.confirmMethod')">{{ confirmMethodLabel(batch.confirm_method) || "—" }}</el-descriptions-item>
          <el-descriptions-item :label="t('query.confirmedAt')">{{ fmt(batch.confirmed_at) }}</el-descriptions-item>
          <el-descriptions-item v-if="batch.source === 'PUSH'" :label="t('query.pushState')">{{ pushStateLabel(batch.push_state) }} · {{ t("query.retry") }} {{ batch.retry_count }}</el-descriptions-item>
          <el-descriptions-item v-if="batch.source === 'PUSH'" :label="t('query.pushCount')">{{ batch.push_count }} / {{ batch.repush_count }}</el-descriptions-item>
          <el-descriptions-item v-if="batch.fail_reason" :label="t('query.failReason')" :span="2">{{ batch.fail_code ? `[${batch.fail_code}] ` : "" }}{{ batch.fail_reason }}</el-descriptions-item>
          <el-descriptions-item v-if="batch.manual_at" :label="t('query.manual')" :span="2">
            <div>{{ t("query.manualBy", { by: batch.manual_by, at: fmt(batch.manual_at) }) }}</div>
            <div>{{ t("common.reason") }}：{{ batch.manual_reason }}</div>
            <div>{{ t("query.basis") }}：{{ batch.manual_basis }}</div>
          </el-descriptions-item>
          <el-descriptions-item v-if="batch.rebuilt_from" :label="t('query.rebuiltFrom')"><el-button link type="primary" @click="openDelivery(batch.rebuilt_from)">{{ batch.rebuilt_from }}</el-button></el-descriptions-item>
          <el-descriptions-item v-if="batch.rebuilt_to" :label="t('query.rebuiltTo')"><el-button link type="primary" @click="openDelivery(batch.rebuilt_to)">{{ batch.rebuilt_to }}</el-button></el-descriptions-item>
          <el-descriptions-item v-if="batch.transfer_doc_no" :label="t('query.transferNo')">{{ batch.transfer_doc_no }}</el-descriptions-item>
          <el-descriptions-item :label="t('query.bills')" :span="2">{{ (batch.bills || []).join("、") }}</el-descriptions-item>
          <el-descriptions-item :label="t('query.materials')" :span="2">{{ (batch.materials || []).join("、") }}</el-descriptions-item>
          <el-descriptions-item :label="t('query.digest')" :span="2"><span class="sn-mono digest">{{ batch.items_digest }}</span></el-descriptions-item>
          <el-descriptions-item :label="t('common.operator')">{{ batch.created_by }}</el-descriptions-item>
          <el-descriptions-item :label="t('common.createdAt')">{{ fmt(batch.created_at) }}</el-descriptions-item>
        </el-descriptions>

        <div class="surface__title">{{ t("query.segments") }}</div>
        <el-table :data="batch.segments || []" size="small" class="mb" :empty-text="t('common.empty')">
          <el-table-column prop="seq_pi_no" :label="t('query.seqOwner')" min-width="140" />
          <el-table-column :label="t('query.segRange')" min-width="300">
            <template #default="{ row }"><span class="sn-mono">{{ row.start_sn }} ~ {{ row.end_sn }}</span></template>
          </el-table-column>
          <el-table-column :label="t('common.seqDec')" width="130"><template #default="{ row }">{{ row.start_seq }} ~ {{ row.end_seq }}</template></el-table-column>
          <el-table-column prop="qty" :label="t('common.qty')" width="70" align="right" />
        </el-table>

        <div class="surface__title">{{ t("query.receipts") }}</div>
        <el-table :data="batch.receipts || []" size="small" class="mb" :empty-text="t('common.empty')">
          <el-table-column :label="t('common.time')" width="160"><template #default="{ row }">{{ fmt(row.created_at) }}</template></el-table-column>
          <el-table-column :label="t('query.receiptMethod')" width="110"><template #default="{ row }">{{ confirmMethodLabel(row.method) }}</template></el-table-column>
          <el-table-column :label="t('query.receiptOutcome')" width="100">
            <template #default="{ row }">{{ receiptOutcomeLabel(row.outcome) }}</template>
          </el-table-column>
          <el-table-column :label="t('query.receiptVerdict')" width="100">
            <template #default="{ row }">{{ receiptVerdictLabel(row.verdict) }}</template>
          </el-table-column>
          <el-table-column prop="detail" :label="t('query.alertDetail')" min-width="200" show-overflow-tooltip />
          <el-table-column prop="created_by" :label="t('common.operator')" width="90" />
        </el-table>
      </template>

      <div class="surface__title">{{ t("query.items") }}</div>
      <el-table v-loading="diState.loading" :data="dItems" size="small" :empty-text="t('common.empty')">
        <el-table-column prop="line_no" :label="t('query.lineNo')" width="60" />
        <el-table-column :label="t('common.sn')" min-width="170"><template #default="{ row }"><span class="sn-mono">{{ row.sn }}</span></template></el-table-column>
        <el-table-column prop="rev" label="rev" width="50" />
        <el-table-column :label="t('query.pushType')" width="80"><template #default="{ row }">{{ pushTypeLabel(row.push_type) }}</template></el-table-column>
        <el-table-column :label="t('query.billSeq')" min-width="140"><template #default="{ row }">{{ row.bill_no }} / {{ row.bill_seq }}</template></el-table-column>
        <el-table-column prop="material_code" :label="t('common.material')" width="100" />
        <el-table-column :label="t('query.receiptResult')" width="100">
          <template #default="{ row }"><el-tag v-if="row.receipt_result" size="small" :type="receiptType(row.receipt_result)">{{ receiptResultLabel(row.receipt_result) }}</el-tag></template>
        </el-table-column>
        <el-table-column :label="t('query.existing')" min-width="150">
          <template #default="{ row }"><span v-if="row.existing_rev != null || row.existing_batch_no">rev {{ row.existing_rev ?? "—" }} · {{ row.existing_batch_no || "—" }}</span></template>
        </el-table-column>
        <el-table-column :label="t('query.currentState')" width="140">
          <template #default="{ row }"><StatusTag v-if="row.status" :status="row.status" /> <span class="muted">{{ row.current_factory }}</span></template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" layout="total, prev, pager, next" :total="diState.total" :page-size="diState.pageSize"
        :current-page="diState.page" @current-change="loadDItems" />
    </el-drawer>

    <!-- 领取批次详情 -->
    <el-drawer :model-value="!!aNo" :title="t('query.acquireDetail', { no: aNo })" size="760px" @close="aNo = ''">
      <div class="drawer-actions">
        <el-button size="small" @click="downloadAcquire(aNo, 'xlsx')"><el-icon><Download /></el-icon><span>{{ t("common.downloadXlsx") }}</span></el-button>
        <el-button size="small" @click="downloadAcquire(aNo, 'csv')">{{ t("common.downloadCsv") }}</el-button>
      </div>
      <el-table v-loading="aiState.loading" :data="aItems" size="small" :empty-text="t('common.empty')">
        <el-table-column prop="line_no" :label="t('query.lineNo')" width="60" />
        <el-table-column :label="t('common.sn')" min-width="170"><template #default="{ row }"><span class="sn-mono">{{ row.sn }}</span></template></el-table-column>
        <el-table-column :label="t('common.seqText')" width="100"><template #default="{ row }"><span class="sn-mono">{{ row.seq_text }}</span></template></el-table-column>
        <el-table-column prop="seq_dec" :label="t('common.seqDec')" width="90" />
        <el-table-column :label="t('query.billSeq')" min-width="140"><template #default="{ row }">{{ row.bill_no }} / {{ row.bill_seq }}</template></el-table-column>
        <el-table-column prop="material_code" :label="t('common.material')" width="100" />
        <el-table-column prop="length" :label="t('common.length')" width="70" />
        <el-table-column :label="t('common.status')" width="90"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
      </el-table>
      <el-pagination class="pager" layout="total, prev, pager, next" :total="aiState.total" :page-size="aiState.pageSize"
        :current-page="aiState.page" @current-change="loadAItems" />
    </el-drawer>

    <!-- 人工确认 / 作废 -->
    <el-dialog v-model="manual.open" :title="manual.kind === 'confirm' ? t('query.confirmTitle', { no: dNo }) : t('query.voidTitle', { no: dNo })" width="520px">
      <el-alert type="warning" :closable="false" show-icon :title="manual.kind === 'confirm' ? t('query.confirmTip') : t('query.voidTip')" class="mb" />
      <el-form label-position="top">
        <el-form-item :label="t('common.reason')" required>
          <el-input v-model="manual.reason" type="textarea" :rows="2" maxlength="500" show-word-limit :placeholder="t('common.reasonPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('query.basis')" required>
          <el-input v-model="manual.basis" type="textarea" :rows="2" maxlength="500" show-word-limit :placeholder="t('query.basisPlaceholder')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="manual.open = false">{{ t("common.cancel") }}</el-button>
        <el-button :type="manual.kind === 'confirm' ? 'primary' : 'danger'" :loading="manual.busy"
          :disabled="!manual.reason.trim() || !manual.basis.trim()" @click="submitManual">{{ t("common.confirm") }}</el-button>
      </template>
    </el-dialog>

    <!-- 处理交付告警 -->
    <el-dialog v-model="handle.open" :title="t('query.handleTitle', { sn: handle.row?.sn || handle.row?.batch_no || '' })" width="520px">
      <el-alert v-if="handle.action === 'RESEND'" type="info" :closable="false" show-icon :title="t('query.resendTip')" class="mb" />
      <el-input v-model="handle.note" type="textarea" :rows="3" maxlength="500" show-word-limit :placeholder="t('query.notePlaceholder')" />
      <template #footer>
        <el-button @click="handle.open = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" :loading="handle.busy" :disabled="!handle.note.trim()" @click="submitHandle">
          {{ handle.action === "RESEND" ? t("query.resend") : t("query.closeAlert") }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { api, urls } from "@/api";
import { useAuthStore } from "@/stores/auth";
import { usePaged } from "@/composables/usePaged";
import { useFactories } from "@/composables/useFactories";
import { useTime } from "@/composables/useTime";
import {
  STATUS_CODES, BATCH_RESULTS, BATCH_RESULT_TYPE, RECEIPT_RESULT_TYPE, receiptResultLabel, statusLabel, deliveryModeLabel, pushTypeLabel, suspendLabel, batchResultLabel,
  batchSourceLabel, batchTypeLabel, confirmMethodLabel, pushStateLabel, receiptOutcomeLabel, receiptVerdictLabel,
} from "@/constants";
import { downloadWithToken } from "@/utils/download";
import PageHead from "@/components/PageHead.vue";
import FactorySelect from "@/components/FactorySelect.vue";
import StatusTag from "@/components/StatusTag.vue";

const TABS = ["sn", "delivery", "acquire", "alerts"];
const MODES = ["PUSH", "PULL", "PAGE"];

const { t } = useI18n();
const route = useRoute();
const store = useAuthStore();
const { nameOf } = useFactories();
const { fmt } = useTime();
const str = (v: unknown) => (typeof v === "string" ? v : "");

/** 从其他页跳来时可带 ?tab=&pi=&batch_no=&bill_no=&factory=；只有总部有生产组织筛选。 */
const initFactory = store.isAdmin ? str(route.query.factory) : "";
const initPi = str(route.query.pi);
const initBatch = str(route.query.batch_no);
const tab = ref(TABS.includes(str(route.query.tab)) ? str(route.query.tab) : "sn");
const blankSn = () => ({
  factory_code: initFactory, pi: initPi, customer_code: "", bill_no: str(route.query.bill_no), material_code: "", length: "",
  status: "", delivery_mode: "", sn: "", batch_no: tab.value === "sn" ? initBatch : "", seq_from: "", seq_to: "", pi_all: false,
});
const f = reactive(blankSn());
const df = reactive({ factory_code: initFactory, pi: initPi, batch_no: tab.value === "delivery" ? initBatch : "", source: "", batch_type: "", result: "", transfer_no: str(route.query.transfer_no) });
const af = reactive({ factory_code: initFactory, pi: initPi, batch_no: tab.value === "acquire" ? initBatch : "", request_no: "" });
const alertStatus = ref("OPEN");
const exporting = ref(false);
const loaded = new Set<string>();

const trimmed = (o: Record<string, unknown>) =>
  Object.fromEntries(Object.entries(o).map(([k, v]) => [k, typeof v === "string" ? v.trim() : v]));
const snParams = () => ({ ...trimmed(f), pi_all: f.pi_all || undefined });

type RangeSummary = { found: number; expected: number | null; missing: number | null; by_status: Record<string, number> };
const range = ref<RangeSummary | null>(null);
const hasRange = computed(() => !!(f.seq_from.trim() || f.seq_to.trim()));
const { items, state, load, onSize } = usePaged<any>(async (q) => {
  range.value = null;
  const r = await api.items({ ...snParams(), ...q });
  range.value = r.range ?? null;
  return r;
});
const { items: deliveries, state: dState, load: loadDelivery, onSize: dSize } = usePaged<any>((q) => api.deliveryBatches({ ...trimmed(df), ...q }));
const { items: acquires, state: aState, load: loadAcquire, onSize: aSize } = usePaged<any>((q) => api.acquireBatches({ ...trimmed(af), ...q }));
const { items: alerts, state: alState, load: loadAlerts, onSize: alSize } = usePaged<any>((q) => api.deliveryAlerts({ status: alertStatus.value, ...q }));

watch(() => f.pi, (v) => {
  if (!v.trim()) f.pi_all = false;
});

function resetSn() {
  Object.assign(f, blankSn(), { factory_code: "", pi: "", bill_no: "", batch_no: "" });
  load(1);
}

/** 每个标签第一次打开时查询一次，之后按各自的「查询」按钮刷新。 */
function onTab() {
  if (loaded.has(tab.value)) return;
  loaded.add(tab.value);
  if (tab.value === "delivery") loadDelivery(1);
  else if (tab.value === "acquire") loadAcquire(1);
  else if (tab.value === "alerts") loadAlerts(1);
  else load(1);
}

// ---------------- 标签与文案
const resultText = (b: any) => batchResultLabel(b.result);
const resultType = (b: any) => BATCH_RESULT_TYPE[b.result] || "info";
const receiptType = (r: string) => RECEIPT_RESULT_TYPE[r] || "info";
function dyn(prefix: string, code?: string | null) {
  if (!code) return "";
  const key = `${prefix}.${code}`;
  const s = t(key);
  return s === key ? code : s;
}
const acqStatus = (c: string) => dyn("query.acqStatus", c);
const alertTypeLabel = (c: string) => dyn("query.alertType", c);
const alertStatusLabel = (c: string) => dyn("query.alertStatus", c);
const closeActionLabel = (c: string) => dyn("query.closeAction", c);

// ---------------- 交付批次详情
const dNo = ref("");
const batch = ref<any>(null);
const { items: dItems, state: diState, load: loadDItems } = usePaged<any>((q) => api.deliveryBatchItems(dNo.value, q), 50);

async function openDelivery(no: string) {
  dNo.value = no;
  batch.value = null;
  loadDItems(1);
  batch.value = await api.deliveryBatch(no);
}

async function exportDelivery(format: string) {
  try {
    await downloadWithToken(urls.deliveryExport(dNo.value, format), `${dNo.value}.${format}`);
  } catch (e: any) {
    ElMessage.error(e.message);
  }
}

const manual = reactive({ open: false, kind: "confirm" as "confirm" | "void", reason: "", basis: "", busy: false });
function openManual(kind: "confirm" | "void") {
  Object.assign(manual, { open: true, kind, reason: "", basis: "", busy: false });
}
async function submitManual() {
  manual.busy = true;
  try {
    if (manual.kind === "confirm") await api.confirmBatch(dNo.value, manual.reason.trim(), manual.basis.trim());
    else await api.voidBatch(dNo.value, manual.reason.trim(), manual.basis.trim());
    ElMessage.success(t("common.done"));
    manual.open = false;
    await openDelivery(dNo.value);
    if (loaded.has("delivery")) loadDelivery();
  } finally {
    manual.busy = false;
  }
}

// ---------------- 领取批次详情
const aNo = ref("");
const { items: aItems, state: aiState, load: loadAItems } = usePaged<any>((q) => api.acquireBatchItems(aNo.value, q), 50);
function openAcquire(no: string) {
  aNo.value = no;
  loadAItems(1);
}
async function downloadAcquire(no: string, format: string) {
  try {
    await downloadWithToken(urls.acquireFile(no, format), `${no}.${format}`);
  } catch (e: any) {
    ElMessage.error(e.message);
  }
}

// ---------------- 交付告警处理
const handle = reactive({ open: false, action: "CLOSE", note: "", busy: false, row: null as any });
function openHandle(row: any, action: string) {
  Object.assign(handle, { open: true, action, note: "", busy: false, row });
}
async function submitHandle() {
  handle.busy = true;
  try {
    await api.handleDeliveryAlert(handle.row.id, handle.action, handle.note.trim());
    ElMessage.success(t("common.done"));
    handle.open = false;
    loadAlerts();
  } finally {
    handle.busy = false;
  }
}

// ---------------- SN 导出（超过上限只导出前 N 行，先提示）
async function exportSn(format: string) {
  exporting.value = true;
  try {
    const params = snParams();
    const c = await api.snExportCheck(params);
    if (c.truncated) {
      try {
        await ElMessageBox.confirm(t("common.exportTruncated", { limit: c.limit }), t("common.export"), { type: "warning" });
      } catch {
        return;
      }
    }
    await downloadWithToken(urls.snExport({ ...params, format }), `sn_export.${format}`);
  } catch (e: any) {
    if (e?.message) ElMessage.error(e.message);
  } finally {
    exporting.value = false;
  }
}

onMounted(() => {
  onTab();
  if (initBatch && tab.value === "delivery") openDelivery(initBatch);
});
</script>

<style scoped>
.mb { margin-bottom: 12px; }
.ml { margin-left: 4px; }
.w120 { width: 120px !important; }
/* 页签铺满卡片，当前页签内的表格占剩余高度 */
.tabs-fill { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.tabs-fill :deep(.el-tabs__content) { flex: 1; min-height: 0; }
.tabs-fill :deep(.el-tab-pane) { height: 100%; display: flex; flex-direction: column; }
@media (max-width: 1199px) { .tabs-fill :deep(.el-tab-pane) { height: auto; } }
.seq-range { display: flex; align-items: center; gap: 6px; }
.seq-range .el-input { width: 150px; }
.seq-range__label { color: var(--el-text-color-regular); white-space: nowrap; }
.range-sum { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 16px; padding: 8px 12px; border-radius: 4px; background: var(--el-fill-color-light); font-size: 13px; }
.range-sum b { font-variant-numeric: tabular-nums; }
.range-sum__note { color: var(--el-text-color-secondary); }
.foot { display: flex; justify-content: space-between; align-items: center; margin-top: 12px; }
.drawer-actions { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 12px; }
.digest { word-break: break-all; font-size: 12px; }
</style>
