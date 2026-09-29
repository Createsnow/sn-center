<template>
  <div class="page">
    <PageHead :title="t('transfer.title')" :desc="t('transfer.desc')" />
    <el-tabs v-model="tab" type="border-card">
      <el-tab-pane v-if="store.canAct" :label="store.isAdmin ? t('transfer.directTab') : t('transfer.applyTab')" name="new">
        <el-row :gutter="24">
          <el-col :lg="12" :xs="24">
            <h4>{{ t("transfer.source") }}</h4>
            <el-form label-position="top" class="field-stack">
              <el-form-item :label="t('transfer.fromFactory')" required>
                <FactorySelect v-if="store.isAdmin" v-model="src.factory_code" @update:model-value="loadCandidates" />
                <el-input v-else :model-value="store.profile?.factory_name || store.boundFactory" disabled />
              </el-form-item>
              <el-form-item label="PI" required>
                <el-input v-model="src.pi_no" clearable @change="loadCandidates" />
              </el-form-item>
              <el-form-item :label="t('transfer.scope')" required>
                <el-radio-group v-model="src.scope" @change="loadCandidates">
                  <el-radio-button value="PI">{{ scopeLabel("PI") }}</el-radio-button>
                  <el-radio-button value="MATERIAL">{{ scopeLabel("MATERIAL") }}</el-radio-button>
                  <el-radio-button value="SINGLE">{{ scopeLabel("SINGLE") }}</el-radio-button>
                </el-radio-group>
              </el-form-item>
              <el-form-item v-if="src.scope === 'MATERIAL'" :label="t('common.material')" required>
                <el-select v-model="src.material_code" filterable @change="loadCandidates">
                  <el-option v-for="m in candidates?.materials || []" :key="m" :value="m" :label="m || t('orders.noMaterial')" />
                </el-select>
              </el-form-item>
              <el-form-item v-if="src.scope === 'SINGLE'" :label="t('common.sn')" required>
                <el-input v-model="src.sn" clearable class="sn-mono" @change="loadCandidates" />
              </el-form-item>
            </el-form>
            <el-alert v-if="candidates" :type="candidates.transferable ? 'success' : 'warning'" :closable="false" show-icon
              :title="t('transfer.candidates', { ok: candidates.transferable, total: candidates.total })">
              <div class="by-status">
                <span v-for="(n, s) in candidates.by_status" :key="s"><StatusTag :status="String(s)" /> {{ n }}</span>
              </div>
            </el-alert>
            <p class="muted">{{ t("transfer.rule") }}</p>
          </el-col>
          <el-col :lg="12" :xs="24">
            <h4>
              {{ t("transfer.target") }}
              <el-tag size="small" :type="target.target_source === 'SNAPSHOT' ? 'success' : 'warning'" class="ml">
                {{ t(`transfer.src${target.target_source}`) }}
              </el-tag>
              <el-button size="small" class="ml" @click="pickerOpen = true">{{ t("transfer.fromSnapshot") }}</el-button>
            </h4>
            <el-form label-position="top" class="field-stack">
              <el-form-item :label="t('transfer.toFactory')" required>
                <FactorySelect v-model="target.to_factory" @update:model-value="manual" />
              </el-form-item>
              <el-form-item :label="t('transfer.toPi')" required>
                <el-input v-model="target.to_pi" clearable @input="manual" />
              </el-form-item>
              <el-form-item :label="t('transfer.toCustomer')">
                <el-input v-model="target.to_customer" clearable :placeholder="t('transfer.keep')" @input="manual" />
              </el-form-item>
              <el-form-item :label="t('transfer.toMaterial')">
                <el-input v-model="target.to_material" clearable :placeholder="t('transfer.keep')" @input="manual" />
              </el-form-item>
              <el-form-item :label="t('transfer.reason')" required>
                <el-input v-model="reason" type="textarea" :rows="3" maxlength="500" show-word-limit />
              </el-form-item>
            </el-form>
            <el-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="submit">
              {{ store.isAdmin ? t("transfer.directSubmit") : t("transfer.applySubmit") }}
            </el-button>
          </el-col>
        </el-row>
      </el-tab-pane>

      <el-tab-pane :label="t('transfer.records')" name="records">
        <div class="toolbar">
          <el-select v-model="rf.status" clearable :placeholder="t('common.status')" @change="loadRecords(1)">
            <el-option v-for="s in ['PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN']" :key="s" :value="s" :label="transferStatusLabel(s)" />
          </el-select>
          <el-input v-model="rf.pi" placeholder="PI" clearable @keyup.enter="loadRecords(1)" @clear="loadRecords(1)" />
          <FactorySelect v-if="!store.boundFactory" v-model="rf.factory_code" clearable @update:model-value="loadRecords(1)" />
          <el-button type="primary" @click="loadRecords(1)">{{ t("common.search") }}</el-button>
        </div>
        <el-table v-loading="recState.loading" :data="records" size="small" :empty-text="t('common.empty')">
          <el-table-column :label="t('transfer.no')" min-width="170">
            <template #default="{ row }">
              <div>{{ row.transfer.transfer_no }}</div>
              <el-tag size="small" effect="plain">{{ t(`transfer.mode${row.transfer.mode}`) }}</el-tag>
              <el-tag v-if="row.direction" size="small" class="ml" :type="row.direction === 'OUT' ? 'danger' : 'success'">
                {{ t(`transfer.dir${row.direction}`) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('common.status')" width="90">
            <template #default="{ row }"><el-tag size="small" :type="TRANSFER_STATUS_TYPE[row.transfer.status]">{{ transferStatusLabel(row.transfer.status) }}</el-tag></template>
          </el-table-column>
          <el-table-column :label="t('transfer.source')" min-width="210">
            <template #default="{ row }">
              <div>{{ nameOf(row.transfer.from_factory) }} · <b>{{ row.transfer.from_pi }}</b></div>
              <div class="muted">{{ scopeLabel(row.transfer.scope) }}<template v-if="row.transfer.from_material"> · {{ row.transfer.from_material }}</template><template v-if="row.transfer.from_sn"> · {{ row.transfer.from_sn }}</template></div>
            </template>
          </el-table-column>
          <el-table-column :label="t('transfer.target')" min-width="220">
            <template #default="{ row }">
              <div>{{ nameOf(row.transfer.to_factory) }} · <b>{{ row.transfer.to_pi }}</b></div>
              <div class="muted">
                {{ t("common.customer") }} {{ row.transfer.to_customer || t("transfer.keepShort") }} ·
                {{ t("common.material") }} {{ row.transfer.to_material || t("transfer.keepShort") }}
                <el-tag v-if="row.transfer.target_source === 'MANUAL'" size="small" type="warning">{{ t("transfer.srcMANUAL") }}</el-tag>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="t('common.qty')" align="right" width="70"><template #default="{ row }"><span class="num">{{ row.transfer.qty }}</span></template></el-table-column>
          <el-table-column :label="t('transfer.reason')" min-width="140" show-overflow-tooltip><template #default="{ row }">{{ row.transfer.reason }}</template></el-table-column>
          <el-table-column :label="t('transfer.applied')" width="150">
            <template #default="{ row }"><div>{{ row.transfer.applied_by }}</div><div class="muted">{{ row.transfer.applied_at }}</div></template>
          </el-table-column>
          <el-table-column :label="t('transfer.decided')" width="150">
            <template #default="{ row }"><div>{{ row.transfer.decided_by }}</div><div class="muted">{{ row.transfer.decided_at }}</div></template>
          </el-table-column>
          <el-table-column :label="t('common.actions')" width="170" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openItems(row.transfer)">{{ t("common.detail") }}</el-button>
              <template v-if="row.transfer.status === 'PENDING'">
                <el-button v-if="store.isAdmin" link type="success" @click="decide(row.transfer, 'approve')">{{ t("transfer.approve") }}</el-button>
                <el-button v-if="store.isAdmin" link type="danger" @click="decide(row.transfer, 'reject')">{{ t("transfer.reject") }}</el-button>
                <el-button v-if="store.isFactory && row.transfer.from_factory === store.boundFactory" link type="warning"
                  @click="withdraw(row.transfer)">{{ t("transfer.withdraw") }}</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="recState.total" :page-size="recState.pageSize"
          :current-page="recState.page" :page-sizes="[20, 50, 100]" @current-change="loadRecords" @size-change="recSize" />
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="pickerOpen" :title="t('transfer.fromSnapshot')" width="820px">
      <el-input v-model="targetQ" :placeholder="t('transfer.targetSearch')" clearable prefix-icon="Search" @keyup.enter="searchTargets" />
      <el-table :data="targets" size="small" height="360" class="mt" @row-click="useTarget">
        <el-table-column :label="t('orders.org')" min-width="140">
          <template #default="{ row }">{{ row.prd_org_name }}<el-tag v-if="!row.factory_code" size="small" type="danger" class="ml">{{ t("orders.noFactory") }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="customer_code" :label="t('common.customer')" width="100" />
        <el-table-column prop="pi" label="PI" min-width="160" />
        <el-table-column prop="material_code" :label="t('common.material')" width="120" />
        <el-table-column prop="material_name" :label="t('orders.materialName')" min-width="140" show-overflow-tooltip />
      </el-table>
    </el-dialog>

    <el-drawer :model-value="!!itemsOf" :title="t('transfer.itemsOf', { no: itemsOf?.transfer_no })" size="780px" @close="itemsOf = null">
      <el-table v-loading="itemState.loading" :data="trItems" size="small">
        <el-table-column :label="t('common.sn')" min-width="170"><template #default="{ row }"><span class="sn-mono">{{ row.sn }}</span></template></el-table-column>
        <el-table-column :label="t('common.seqText')" width="110"><template #default="{ row }"><span class="sn-mono">{{ row.seq_text }}</span></template></el-table-column>
        <el-table-column prop="seq_dec" :label="t('common.seqDec')" width="90" />
        <el-table-column :label="t('transfer.fromStatus')" width="100"><template #default="{ row }"><StatusTag :status="row.from_status" /></template></el-table-column>
        <el-table-column :label="t('transfer.nowStatus')" width="100"><template #default="{ row }"><StatusTag v-if="row.current_status" :status="row.current_status" /></template></el-table-column>
        <el-table-column :label="t('transfer.nowAt')" min-width="170">
          <template #default="{ row }">{{ nameOf(row.current_factory) }} · {{ row.current_pi }}</template>
        </el-table-column>
        <el-table-column prop="from_batch_no" :label="t('acquire.batchNo')" min-width="170" />
      </el-table>
      <el-pagination class="pager" layout="total, prev, pager, next" :total="itemState.total" :page-size="itemState.pageSize"
        :current-page="itemState.page" @current-change="loadItems" />
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "@/api";
import { useAuthStore } from "@/stores/auth";
import { usePaged } from "@/composables/usePaged";
import { useFactories } from "@/composables/useFactories";
import { TRANSFER_STATUS_TYPE, scopeLabel, transferStatusLabel } from "@/constants";
import PageHead from "@/components/PageHead.vue";
import FactorySelect from "@/components/FactorySelect.vue";
import StatusTag from "@/components/StatusTag.vue";

const { t } = useI18n();
const store = useAuthStore();
const { nameOf } = useFactories();
const tab = ref(store.canAct ? "new" : "records");
const src = reactive({ factory_code: "", pi_no: "", scope: "PI", material_code: "", sn: "" });
const target = reactive({ to_factory: "", to_pi: "", to_customer: "", to_material: "", target_source: "MANUAL" });
const reason = ref("");
const candidates = ref<any>(null);
const submitting = ref(false);
const pickerOpen = ref(false);
const targetQ = ref("");
const targets = ref<any[]>([]);
const rf = reactive({ status: "", pi: "", factory_code: "" });
const itemsOf = ref<any>(null);

const { items: records, state: recState, load: loadRecords, onSize: recSize } = usePaged<any>((q) => api.transfers({ ...rf, ...q }));
const { items: trItems, state: itemState, load: loadItems } = usePaged<any>((q) => api.transferItems(itemsOf.value.id, q), 50);

const canSubmit = computed(() =>
  !!src.pi_no.trim() && !!target.to_factory && !!target.to_pi.trim() && !!reason.value.trim() &&
  (store.isAdmin ? !!src.factory_code : true) && (candidates.value?.transferable ?? 0) > 0,
);

async function loadCandidates() {
  candidates.value = null;
  const factory = store.isAdmin ? src.factory_code : store.boundFactory;
  if (!factory || !src.pi_no.trim()) return;
  if (src.scope === "SINGLE" && !src.sn.trim()) return;
  candidates.value = await api.transferCandidates({
    factory_code: factory, pi: src.pi_no.trim(), scope: src.scope,
    material_code: src.scope === "MATERIAL" ? src.material_code : undefined,
    sn: src.scope === "SINGLE" ? src.sn.trim() : undefined,
  });
}

function manual() {
  target.target_source = "MANUAL";
}

async function searchTargets() {
  targets.value = await api.transferTargets(targetQ.value.trim());
}

function useTarget(row: any) {
  target.to_factory = row.factory_code || "";
  target.to_pi = row.pi;
  target.to_customer = row.customer_code;
  target.to_material = row.material_code;
  target.target_source = "SNAPSHOT";
  pickerOpen.value = false;
}

async function submit() {
  const body = {
    factory_code: store.isAdmin ? src.factory_code : store.boundFactory,
    pi_no: src.pi_no.trim(), scope: src.scope,
    material_code: src.scope === "MATERIAL" ? src.material_code : null,
    sn: src.scope === "SINGLE" ? src.sn.trim() : null,
    to_factory: target.to_factory, to_pi: target.to_pi.trim(),
    to_customer: target.to_customer.trim(), to_material: target.to_material.trim(),
    target_source: target.target_source, reason: reason.value.trim(),
  };
  await ElMessageBox.confirm(
    t(store.isAdmin ? "transfer.directConfirm" : "transfer.applyConfirm", { n: candidates.value?.transferable, pi: body.to_pi, factory: nameOf(body.to_factory) }),
    store.isAdmin ? t("transfer.directSubmit") : t("transfer.applySubmit"),
    { type: "warning" },
  );
  submitting.value = true;
  try {
    const r = store.isAdmin ? await api.directTransfer(body) : await api.applyTransfer(body);
    ElMessage.success(t("transfer.submitted", { no: r.transfer_no, n: r.qty }));
    reason.value = "";
    await loadCandidates();
    tab.value = "records";
    await loadRecords(1);
  } finally {
    submitting.value = false;
  }
}

async function decide(tr: any, action: "approve" | "reject") {
  const { value } = await ElMessageBox.prompt(
    t(action === "approve" ? "transfer.approveConfirm" : "transfer.rejectConfirm", { no: tr.transfer_no, n: tr.qty }),
    action === "approve" ? t("transfer.approve") : t("transfer.reject"),
    { inputPlaceholder: t("transfer.note"), type: action === "approve" ? "success" : "warning" },
  );
  if (action === "approve") await api.approveTransfer(tr.id, value);
  else await api.rejectTransfer(tr.id, value);
  ElMessage.success(t("common.done"));
  await loadRecords();
}

async function withdraw(tr: any) {
  await ElMessageBox.confirm(t("transfer.withdrawConfirm", { no: tr.transfer_no }), t("transfer.withdraw"), { type: "warning" });
  await api.withdrawTransfer(tr.id);
  ElMessage.success(t("common.done"));
  await loadRecords();
}

function openItems(tr: any) {
  itemsOf.value = tr;
  loadItems(1);
}

onMounted(() => {
  loadRecords(1);
  searchTargets();
});
</script>

<style scoped>
h4 { margin: 4px 0 12px; display: flex; align-items: center; }
.ml { margin-left: 6px; }
.mt { margin-top: 12px; }
.by-status { display: flex; gap: 12px; flex-wrap: wrap; margin-top: 4px; }
</style>
