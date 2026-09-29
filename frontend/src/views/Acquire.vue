<template>
  <div class="page">
    <PageHead :title="t('acquire.title')" :desc="t('acquire.desc')" />

    <div class="surface">
      <div class="toolbar">
        <FactorySelect v-if="!store.boundFactory" v-model="f.factory_code" clearable :placeholder="t('acquire.allFactories')" @update:model-value="reload" />
        <el-tag v-else type="warning" effect="plain" size="large">{{ store.profile?.factory_name || store.boundFactory }}</el-tag>
        <el-input v-model="f.pi" placeholder="PI" clearable @keyup.enter="reload" @clear="reload" />
        <el-input v-model="f.material_code" :placeholder="t('common.material')" clearable @keyup.enter="reloadSegments" @clear="reloadSegments" />
        <el-switch v-if="store.isFactory" v-model="f.pi_all" :active-text="t('acquire.piAll')" :disabled="!f.pi.trim()" @change="reload" />
        <el-button type="primary" @click="reload">{{ t("common.search") }}</el-button>
      </div>
      <el-alert v-if="readOnly" type="info" :closable="false" show-icon :title="readOnlyText" class="mb" />

      <div class="surface__title">{{ t("acquire.byPi") }}</div>
      <el-table v-loading="pisLoading" :data="pis" size="small" :empty-text="t('acquire.emptyPis')" @row-click="pickPi">
        <el-table-column prop="factory_code" :label="t('common.factory')" width="120">
          <template #default="{ row }">{{ nameOf(row.factory_code) }}</template>
        </el-table-column>
        <el-table-column prop="pi_no" label="PI" min-width="170"><template #default="{ row }"><b>{{ row.pi_no }}</b></template></el-table-column>
        <el-table-column prop="customer_code" :label="t('common.customer')" width="100" />
        <el-table-column :label="t('status.TO_ACQUIRE')" width="190">
          <template #default="{ row }">
            <span class="num cnt">{{ row.to_acquire }}</span>
            <el-button v-if="actable" size="small" type="primary" :disabled="!row.to_acquire" :loading="busy === `take:${key(row)}`"
              @click.stop="take(row)">{{ t("acquire.take") }}</el-button>
          </template>
        </el-table-column>
        <el-table-column :label="t('status.TO_PRINT')" width="190">
          <template #default="{ row }">
            <span class="num cnt">{{ row.to_print }}</span>
            <el-button v-if="actable" size="small" type="warning" :disabled="!row.to_print" :loading="busy === `print:${key(row)}`"
              @click.stop="print(row)">{{ t("acquire.print") }}</el-button>
          </template>
        </el-table-column>
        <el-table-column :label="t('status.APPLYING')" width="100">
          <template #default="{ row }"><span class="num" :class="{ warn: row.applying }">{{ row.applying }}</span></template>
        </el-table-column>
      </el-table>
      <p class="muted">{{ t("acquire.rule") }}</p>
    </div>

    <div class="surface">
      <div class="surface__title">
        <span>{{ t("acquire.segments") }}</span>
        <el-radio-group v-model="f.status" size="small" @change="reloadSegments">
          <el-radio-button value="">{{ t("common.all") }}</el-radio-button>
          <el-radio-button v-for="s in ['TO_ACQUIRE', 'TO_PRINT', 'APPLYING']" :key="s" :value="s">{{ statusLabel(s) }}</el-radio-button>
        </el-radio-group>
      </div>
      <el-table v-loading="segState.loading" :data="segments" size="small" :empty-text="t('common.empty')">
        <el-table-column :label="t('common.factory')" width="120"><template #default="{ row }">{{ nameOf(row.factory_code) }}</template></el-table-column>
        <el-table-column prop="pi_no" label="PI" min-width="160" />
        <el-table-column :label="t('common.material')" width="120">
          <template #default="{ row }">{{ row.material_code || t("orders.noMaterial") }}</template>
        </el-table-column>
        <el-table-column :label="t('common.status')" width="90"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
        <el-table-column :label="t('acquire.range')" min-width="260">
          <template #default="{ row }"><span class="sn-mono">{{ row.start_sn }} ~ {{ row.end_sn }}</span></template>
        </el-table-column>
        <el-table-column :label="t('acquire.decRange')" width="150">
          <template #default="{ row }"><span class="num">{{ row.start_seq }} ~ {{ row.end_seq }}</span></template>
        </el-table-column>
        <el-table-column :label="t('common.qty')" align="right" width="80"><template #default="{ row }"><span class="num">{{ row.qty }}</span></template></el-table-column>
        <el-table-column prop="seq_pi_no" :label="t('acquire.seqOwner')" min-width="150" show-overflow-tooltip />
      </el-table>
      <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="segState.total" :page-size="segState.pageSize"
        :current-page="segState.page" :page-sizes="[20, 50, 100]" @current-change="loadSegments" @size-change="segSize" />
    </div>

    <div class="surface">
      <el-tabs v-model="tab" @tab-change="onTab">
        <el-tab-pane :label="t('acquire.batches')" name="batches">
          <el-table v-loading="batchState.loading" :data="batches" size="small" :empty-text="t('common.empty')">
            <el-table-column prop="batch_no" :label="t('acquire.batchNo')" min-width="190" />
            <el-table-column :label="t('common.factory')" width="120"><template #default="{ row }">{{ nameOf(row.factory_code) }}</template></el-table-column>
            <el-table-column prop="pi_no" label="PI" min-width="150" />
            <el-table-column prop="qty" :label="t('common.qty')" align="right" width="80" />
            <el-table-column :label="t('audit.source')" width="80"><template #default="{ row }">{{ t(`audit.src${row.source}`) }}</template></el-table-column>
            <el-table-column prop="request_no" :label="t('acquire.requestNo')" min-width="150" show-overflow-tooltip />
            <el-table-column prop="created_by" :label="t('common.operator')" width="90" />
            <el-table-column prop="created_at" :label="t('common.time')" width="160" />
            <el-table-column :label="t('common.actions')" width="80">
              <template #default="{ row }"><el-button link type="primary" @click="openBatch(row)">{{ t("common.detail") }}</el-button></template>
            </el-table-column>
          </el-table>
          <el-pagination class="pager" layout="total, prev, pager, next" :total="batchState.total" :page-size="batchState.pageSize"
            :current-page="batchState.page" @current-change="loadBatches" />
        </el-tab-pane>
        <el-tab-pane :label="t('acquire.prints')" name="prints">
          <el-table v-loading="printState.loading" :data="prints" size="small" :empty-text="t('common.empty')">
            <el-table-column prop="print_no" :label="t('acquire.printNo')" min-width="190" />
            <el-table-column :label="t('common.factory')" width="120"><template #default="{ row }">{{ nameOf(row.factory_code) }}</template></el-table-column>
            <el-table-column prop="pi_no" label="PI" min-width="150" />
            <el-table-column prop="qty" :label="t('common.qty')" align="right" width="80" />
            <el-table-column prop="created_by" :label="t('common.operator')" width="90" />
            <el-table-column prop="created_at" :label="t('common.time')" width="160" />
            <el-table-column :label="t('acquire.file')" width="140">
              <template #default="{ row }">
                <el-button link type="primary" @click="download(row.print_no, 'xlsx')">xlsx</el-button>
                <el-button link type="primary" @click="download(row.print_no, 'csv')">csv</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination class="pager" layout="total, prev, pager, next" :total="printState.total" :page-size="printState.pageSize"
            :current-page="printState.page" @current-change="loadPrints" />
        </el-tab-pane>
      </el-tabs>
    </div>

    <el-drawer :model-value="!!batch" :title="t('acquire.batchDetail', { no: batch?.batch_no })" size="760px" @close="batch = null">
      <el-table v-loading="itemState.loading" :data="batchItems" size="small">
        <el-table-column prop="sn" :label="t('common.sn')" min-width="170"><template #default="{ row }"><span class="sn-mono">{{ row.sn }}</span></template></el-table-column>
        <el-table-column prop="seq_text" :label="t('common.seqText')" width="110"><template #default="{ row }"><span class="sn-mono">{{ row.seq_text }}</span></template></el-table-column>
        <el-table-column prop="seq_dec" :label="t('common.seqDec')" width="100" />
        <el-table-column prop="material_code" :label="t('common.material')" width="110" />
        <el-table-column :label="t('common.status')" width="90"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
        <el-table-column prop="pi_no" label="PI" min-width="140" />
      </el-table>
      <el-pagination class="pager" layout="total, prev, pager, next" :total="itemState.total" :page-size="itemState.pageSize"
        :current-page="itemState.page" @current-change="loadBatchItems" />
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { api, urls } from "@/api";
import { useAuthStore } from "@/stores/auth";
import { usePaged } from "@/composables/usePaged";
import { useFactories } from "@/composables/useFactories";
import { statusLabel } from "@/constants";
import { newRequestId } from "@/requestId";
import { downloadWithToken } from "@/utils/download";
import PageHead from "@/components/PageHead.vue";
import FactorySelect from "@/components/FactorySelect.vue";
import StatusTag from "@/components/StatusTag.vue";

const { t } = useI18n();
const store = useAuthStore();
const { nameOf } = useFactories();
const f = reactive({ factory_code: "", pi: "", material_code: "", status: "", pi_all: false });
const pis = ref<any[]>([]);
const pisLoading = ref(false);
const busy = ref("");
const tab = ref("batches");
const batch = ref<any>(null);

const readOnly = computed(() => store.isQuery || (store.isFactory && f.pi_all));
const actable = computed(() => store.canAct && !f.pi_all);
const readOnlyText = computed(() => (store.isQuery ? t("acquire.queryReadOnly") : t("acquire.piAllReadOnly")));
const key = (row: any) => `${row.factory_code}|${row.pi_no}`;

const params = () => ({ factory_code: f.factory_code, pi: f.pi.trim(), material_code: f.material_code.trim(), status: f.status, pi_all: f.pi_all || undefined });
const { items: segments, state: segState, load: loadSegments, onSize: segSize } = usePaged<any>((q) => api.acquireSegments({ ...params(), ...q }));
const { items: batches, state: batchState, load: loadBatches } = usePaged<any>((q) => api.batches({ factory_code: f.factory_code, pi: f.pi.trim(), ...q }), 10);
const { items: prints, state: printState, load: loadPrints } = usePaged<any>((q) => api.prints({ factory_code: f.factory_code, pi: f.pi.trim(), ...q }), 10);
const { items: batchItems, state: itemState, load: loadBatchItems } = usePaged<any>((q) => api.batchItems(batch.value.batch_no, q), 50);

async function loadPis() {
  pisLoading.value = true;
  try {
    pis.value = f.pi_all ? [] : await api.acquirePis({ factory_code: f.factory_code, pi: f.pi.trim() });
  } finally {
    pisLoading.value = false;
  }
}

function reloadSegments() {
  loadSegments(1);
}

async function reload() {
  if (f.pi_all && !f.pi.trim()) f.pi_all = false;
  await Promise.all([loadPis(), loadSegments(1), tab.value === "batches" ? loadBatches(1) : loadPrints(1)]);
}

function onTab(name: string | number) {
  if (name === "batches") loadBatches(1);
  else loadPrints(1);
}

function pickPi(row: any) {
  f.pi = row.pi_no;
  reloadSegments();
}

async function take(row: any) {
  await ElMessageBox.confirm(t("acquire.takeConfirm", { n: row.to_acquire, pi: row.pi_no, factory: nameOf(row.factory_code) }), t("acquire.take"), { type: "warning" });
  busy.value = `take:${key(row)}`;
  try {
    const r = await api.take({ factory_code: row.factory_code, pi_no: row.pi_no, request_no: newRequestId() });
    ElMessage.success(t("acquire.taken", { n: r.qty, batch: r.batch_no }));
    await reload();
  } finally {
    busy.value = "";
  }
}

async function print(row: any) {
  await ElMessageBox.confirm(t("acquire.printConfirm", { n: row.to_print, pi: row.pi_no, factory: nameOf(row.factory_code) }), t("acquire.print"), { type: "warning" });
  busy.value = `print:${key(row)}`;
  try {
    const r = await api.print({ factory_code: row.factory_code, pi_no: row.pi_no, request_no: newRequestId() });
    ElMessage.success(t("acquire.printed", { n: r.qty }));
    await download(r.print_no, "xlsx");
    await reload();
  } finally {
    busy.value = "";
  }
}

async function download(printNo: string, format: string) {
  try {
    await downloadWithToken(urls.printFile(printNo, format), `print_${printNo}.${format}`);
  } catch (e: any) {
    ElMessage.error(e.message);
  }
}

function openBatch(row: any) {
  batch.value = row;
  loadBatchItems(1);
}

onMounted(reload);
</script>

<style scoped>
.mb { margin-bottom: 12px; }
.cnt { display: inline-block; min-width: 56px; }
.warn { color: var(--el-color-danger); font-weight: 600; }
.el-table :deep(.el-table__row) { cursor: pointer; }
</style>
