<template>
  <div class="page">
    <PageHead :title="t('query.title')" :desc="t('query.desc')">
      <template v-if="tab === 'sn'">
        <el-button :loading="exporting" @click="exportFile('xlsx')"><el-icon><Download /></el-icon><span>{{ t("query.exportXlsx") }}</span></el-button>
        <el-button :loading="exporting" @click="exportFile('csv')">CSV</el-button>
      </template>
    </PageHead>
    <div class="surface">
      <el-tabs v-model="tab" @tab-change="onTab">
        <el-tab-pane :label="t('query.tabSn')" name="sn">
          <div class="toolbar">
            <FactorySelect v-if="!store.boundFactory" v-model="f.factory_code" clearable :disabled="f.pi_all" />
            <el-tag v-else type="warning" effect="plain" size="large">{{ store.profile?.factory_name || store.boundFactory }}</el-tag>
            <el-input v-model="f.pi" placeholder="PI" clearable />
            <el-input v-model="f.material_code" :placeholder="t('common.material')" clearable />
            <el-input v-model="f.customer_code" :placeholder="t('common.customer')" clearable />
            <el-select v-model="f.status" clearable :placeholder="t('common.status')">
              <el-option v-for="s in STATUS_CODES" :key="s" :value="s" :label="statusLabel(s)" />
            </el-select>
            <el-input v-model="f.sn" :placeholder="t('common.sn')" clearable class="sn-mono" />
            <el-input v-model="f.bill_no" :placeholder="t('query.sourceBill')" clearable />
            <el-input v-model="f.batch_no" :placeholder="t('acquire.batchNo')" clearable />
            <el-switch v-if="store.isFactory" v-model="f.pi_all" :active-text="t('acquire.piAll')" :disabled="!f.pi.trim()" />
            <el-button type="primary" @click="load(1)">{{ t("common.search") }}</el-button>
          </div>
          <el-alert v-if="f.pi_all" type="info" :closable="false" show-icon :title="t('acquire.piAllReadOnly')" class="mb" />
          <el-table v-loading="state.loading" :data="items" size="small" :empty-text="t('common.empty')">
            <el-table-column :label="t('common.sn')" min-width="170" fixed><template #default="{ row }"><span class="sn-mono">{{ row.sn }}</span></template></el-table-column>
            <el-table-column :label="t('common.seqText')" width="110"><template #default="{ row }"><span class="sn-mono">{{ row.seq_text }}</span></template></el-table-column>
            <el-table-column prop="seq_dec" :label="t('common.seqDec')" width="100" />
            <el-table-column prop="pi_no" label="PI" min-width="150" />
            <el-table-column prop="material_code" :label="t('common.material')" width="110" />
            <el-table-column prop="customer_code" :label="t('common.customer')" width="90" />
            <el-table-column :label="t('common.factory')" width="110"><template #default="{ row }">{{ nameOf(row.factory_code) }}</template></el-table-column>
            <el-table-column :label="t('common.status')" width="90"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
            <el-table-column prop="seq_pi_no" :label="t('acquire.seqOwner')" min-width="150" show-overflow-tooltip />
            <el-table-column prop="bill_no" :label="t('query.sourceBill')" min-width="130" />
            <el-table-column :label="t('acquire.batchNo')" min-width="170">
              <template #default="{ row }"><el-button v-if="row.batch_no" link type="primary" @click="openBatch(row.batch_no)">{{ row.batch_no }}</el-button></template>
            </el-table-column>
            <el-table-column :label="t('query.source')" width="80"><template #default="{ row }">{{ t(`query.src${row.source}`) }}</template></el-table-column>
            <el-table-column prop="created_at" :label="t('query.createdAt')" width="160" />
            <el-table-column prop="printed_at" :label="t('query.printedAt')" width="160" />
          </el-table>
          <div class="foot">
            <span v-if="state.capped" class="muted">{{ t("query.capped", { n: state.total }) }}</span>
            <el-pagination layout="total, sizes, prev, pager, next" :total="state.total" :page-size="state.pageSize"
              :current-page="state.page" :page-sizes="[20, 50, 100, 500]" @current-change="load" @size-change="onSize" />
          </div>
        </el-tab-pane>

        <el-tab-pane :label="t('acquire.batches')" name="batches">
          <div class="toolbar">
            <FactorySelect v-if="!store.boundFactory" v-model="bf.factory_code" clearable />
            <el-input v-model="bf.pi" placeholder="PI" clearable @keyup.enter="loadBatches(1)" />
            <el-input v-model="bf.batch_no" :placeholder="t('acquire.batchNo')" clearable @keyup.enter="loadBatches(1)" />
            <el-input v-model="bf.request_no" :placeholder="t('acquire.requestNo')" clearable @keyup.enter="loadBatches(1)" />
            <el-select v-model="bf.source" clearable :placeholder="t('audit.source')">
              <el-option v-for="s in ['PAGE', 'API']" :key="s" :value="s" :label="t(`audit.src${s}`)" />
            </el-select>
            <el-button type="primary" @click="loadBatches(1)">{{ t("common.search") }}</el-button>
          </div>
          <el-table v-loading="batchState.loading" :data="batches" size="small" :empty-text="t('common.empty')">
            <el-table-column prop="batch_no" :label="t('acquire.batchNo')" min-width="190" />
            <el-table-column :label="t('common.factory')" width="120"><template #default="{ row }">{{ nameOf(row.factory_code) }}</template></el-table-column>
            <el-table-column prop="pi_no" label="PI" min-width="150" />
            <el-table-column prop="qty" :label="t('common.qty')" align="right" width="80" />
            <el-table-column :label="t('audit.source')" width="80"><template #default="{ row }">{{ t(`audit.src${row.source}`) }}</template></el-table-column>
            <el-table-column prop="request_no" :label="t('acquire.requestNo')" min-width="200" show-overflow-tooltip />
            <el-table-column prop="created_by" :label="t('common.operator')" width="100" />
            <el-table-column prop="created_at" :label="t('common.time')" width="160" />
            <el-table-column :label="t('common.actions')" width="80">
              <template #default="{ row }"><el-button link type="primary" @click="openBatch(row.batch_no)">{{ t("common.detail") }}</el-button></template>
            </el-table-column>
          </el-table>
          <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="batchState.total" :page-size="batchState.pageSize"
            :current-page="batchState.page" :page-sizes="[20, 50, 100]" @current-change="loadBatches" @size-change="batchSize" />
        </el-tab-pane>

        <el-tab-pane :label="t('acquire.prints')" name="prints">
          <div class="toolbar">
            <FactorySelect v-if="!store.boundFactory" v-model="pf.factory_code" clearable />
            <el-input v-model="pf.pi" placeholder="PI" clearable @keyup.enter="loadPrints(1)" />
            <el-input v-model="pf.print_no" :placeholder="t('acquire.printNo')" clearable @keyup.enter="loadPrints(1)" />
            <el-input v-model="pf.request_no" :placeholder="t('acquire.requestNo')" clearable @keyup.enter="loadPrints(1)" />
            <el-button type="primary" @click="loadPrints(1)">{{ t("common.search") }}</el-button>
          </div>
          <el-table v-loading="printState.loading" :data="prints" size="small" :empty-text="t('common.empty')">
            <el-table-column prop="print_no" :label="t('acquire.printNo')" min-width="190" />
            <el-table-column :label="t('common.factory')" width="120"><template #default="{ row }">{{ nameOf(row.factory_code) }}</template></el-table-column>
            <el-table-column prop="pi_no" label="PI" min-width="150" />
            <el-table-column prop="qty" :label="t('common.qty')" align="right" width="80" />
            <el-table-column prop="request_no" :label="t('acquire.requestNo')" min-width="200" show-overflow-tooltip />
            <el-table-column prop="created_by" :label="t('common.operator')" width="100" />
            <el-table-column prop="created_at" :label="t('common.time')" width="160" />
            <el-table-column :label="t('acquire.file')" width="120">
              <template #default="{ row }">
                <el-button link type="primary" @click="download(row.print_no, 'xlsx')">xlsx</el-button>
                <el-button link type="primary" @click="download(row.print_no, 'csv')">csv</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="printState.total" :page-size="printState.pageSize"
            :current-page="printState.page" :page-sizes="[20, 50, 100]" @current-change="loadPrints" @size-change="printSize" />
        </el-tab-pane>
      </el-tabs>
    </div>

    <el-drawer :model-value="!!batchNo" :title="t('acquire.batchDetail', { no: batchNo })" size="760px" @close="batchNo = ''">
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
import { onMounted, reactive, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import { api, urls } from "@/api";
import { useAuthStore } from "@/stores/auth";
import { usePaged } from "@/composables/usePaged";
import { useFactories } from "@/composables/useFactories";
import { STATUS_CODES, statusLabel } from "@/constants";
import { downloadWithToken } from "@/utils/download";
import PageHead from "@/components/PageHead.vue";
import FactorySelect from "@/components/FactorySelect.vue";
import StatusTag from "@/components/StatusTag.vue";

const TABS = ["sn", "batches", "prints"];

const { t } = useI18n();
const route = useRoute();
const store = useAuthStore();
const { nameOf } = useFactories();
const str = (v: unknown) => (typeof v === "string" ? v : "");
/** 从领取页跳来时带 ?tab=&factory=&pi=，三个标签共用这两个初始条件。 */
const initFactory = store.boundFactory ? "" : str(route.query.factory);
const initPi = str(route.query.pi);
const tab = ref(TABS.includes(str(route.query.tab)) ? str(route.query.tab) : "sn");
const f = reactive({ factory_code: initFactory, pi: initPi, material_code: "", customer_code: "", status: "", sn: "", bill_no: "", batch_no: "", pi_all: false });
const bf = reactive({ factory_code: initFactory, pi: initPi, batch_no: "", request_no: "", source: "" });
const pf = reactive({ factory_code: initFactory, pi: initPi, print_no: "", request_no: "" });
const exporting = ref(false);
const batchNo = ref("");
const loaded = new Set<string>();

const params = () => ({ ...f, pi: f.pi.trim(), pi_all: f.pi_all || undefined });
const trimmed = (o: Record<string, string>) => Object.fromEntries(Object.entries(o).map(([k, v]) => [k, v.trim()]));
const { items, state, load, onSize } = usePaged<any>((q) => api.items({ ...params(), ...q }));
const { items: batches, state: batchState, load: loadBatches, onSize: batchSize } = usePaged<any>((q) => api.batches({ ...trimmed(bf), ...q }));
const { items: prints, state: printState, load: loadPrints, onSize: printSize } = usePaged<any>((q) => api.prints({ ...trimmed(pf), ...q }));
const { items: batchItems, state: itemState, load: loadBatchItems } = usePaged<any>((q) => api.batchItems(batchNo.value, q), 50);

watch(() => f.pi, (v) => {
  if (!v.trim()) f.pi_all = false;
});

/** 每个标签第一次打开时查询一次，之后按各自的「查询」按钮刷新。 */
function onTab() {
  if (loaded.has(tab.value)) return;
  loaded.add(tab.value);
  if (tab.value === "batches") loadBatches(1);
  else if (tab.value === "prints") loadPrints(1);
  else load(1);
}

function openBatch(no: string) {
  batchNo.value = no;
  loadBatchItems(1);
}

async function exportFile(format: string) {
  exporting.value = true;
  try {
    await downloadWithToken(urls.snExport({ ...params(), format }), `sn_export.${format}`);
  } catch (e: any) {
    ElMessage.error(e.message);
  } finally {
    exporting.value = false;
  }
}

async function download(printNo: string, format: string) {
  try {
    await downloadWithToken(urls.printFile(printNo, format), `print_${printNo}.${format}`);
  } catch (e: any) {
    ElMessage.error(e.message);
  }
}

onMounted(onTab);
</script>

<style scoped>
.mb { margin-bottom: 12px; }
.foot { display: flex; justify-content: space-between; align-items: center; margin-top: 12px; }
</style>
