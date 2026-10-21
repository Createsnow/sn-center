<template>
  <div class="page">
    <PageHead :title="t('export.title')" :desc="t('export.desc')" />

    <div class="surface">
      <div class="toolbar">
        <FactorySelect v-if="!store.bound" v-model="f.factory_code" clearable :placeholder="t('app.allFactories')" @update:model-value="list.load(1)" />
        <el-select v-model="f.mes" clearable class="w140" :placeholder="t('common.mes')" @change="list.load(1)">
          <el-option :label="t('export.mesYes')" value="true" />
          <el-option :label="t('export.mesNo')" value="false" />
        </el-select>
        <el-input v-model="f.q" class="w220" :placeholder="t('export.search')" clearable prefix-icon="Search" @keyup.enter="list.load(1)" @clear="list.load(1)" />
        <el-button @click="list.load(1)"><el-icon><Refresh /></el-icon><span>{{ t("common.refresh") }}</span></el-button>
      </div>

      <el-table v-loading="list.state.loading" :data="list.items.value" size="small" class="clickable" :empty-text="t('common.empty')" @row-click="openDetail">
        <el-table-column prop="bill_no" :label="t('common.billNo')" min-width="140" show-overflow-tooltip><template #default="{ row }"><b>{{ row.bill_no }}</b></template></el-table-column>
        <el-table-column prop="pi_no" label="PI" min-width="130" show-overflow-tooltip />
        <el-table-column v-if="store.scopeFactories.length !== 1" :label="t('common.factory')" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ nameOf(row.factory_code) }}</template>
        </el-table-column>
        <el-table-column prop="customer_code" :label="t('common.customer')" min-width="90" />
        <el-table-column :label="t('common.mes')" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.mes_enabled" size="small" type="success" effect="plain">MES</el-tag>
            <span v-else class="muted">{{ t("common.no") }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('export.qty')" align="right" width="90">
          <template #default="{ row }"><span class="num">{{ num(row.qty) }}</span></template>
        </el-table-column>
        <el-table-column :label="t('common.status')" min-width="220">
          <template #default="{ row }">
            <span v-for="(n, s) in row.statuses" :key="s" class="st"><StatusTag :status="String(s)" /><span class="num">{{ num(n) }}</span></span>
            <el-tag v-if="row.suspended" size="small" type="danger" class="ml">{{ t("export.suspended", { n: num(row.suspended) }) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="150" align="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openDetail(row)">{{ t("common.detail") }}</el-button>
            <el-button link type="primary" :loading="busy === keyOf(row, 'xlsx')" @click.stop="download(row, 'xlsx')">xlsx</el-button>
            <el-button link type="primary" :loading="busy === keyOf(row, 'csv')" @click.stop="download(row, 'csv')">csv</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="list.state.total" :page-size="list.state.pageSize"
        :current-page="list.state.page" :page-sizes="[20, 50, 100]" @current-change="list.load" @size-change="list.onSize" />
      <p class="muted">{{ t("export.rule") }}</p>
    </div>

    <!-- 明细：号段 -->
    <el-drawer :model-value="!!detail" size="820px" @close="detail = null">
      <template #header>
        <div v-if="detail" class="dh">
          <b>{{ detail.bill_no }}</b>
          <el-tag effect="plain" size="small">PI {{ detail.pi_no }}</el-tag>
          <el-tag effect="plain" size="small">{{ nameOf(detail.factory_code) }}<template v-if="detail.customer_code"> · {{ detail.customer_code }}</template></el-tag>
          <el-tag v-if="detail.mes_enabled" size="small" type="success" effect="plain">MES</el-tag>
        </div>
      </template>
      <template v-if="detail">
        <div class="toolbar">
          <el-select v-model="sf.status" clearable class="w160" :placeholder="t('common.status')" @change="segs.load(1)">
            <el-option v-for="s in Object.keys(detail.statuses)" :key="s" :label="statusLabel(s)" :value="s" />
          </el-select>
          <span class="sp" />
          <span class="muted">{{ t("export.fileCols") }}</span>
          <el-button size="small" type="primary" :loading="busy === keyOf(detail, 'xlsx')" @click="download(detail, 'xlsx')">{{ t("export.download", { f: "xlsx" }) }}</el-button>
          <el-button size="small" :loading="busy === keyOf(detail, 'csv')" @click="download(detail, 'csv')">{{ t("export.download", { f: "csv" }) }}</el-button>
        </div>
        <el-table v-loading="segs.state.loading" :data="segs.items.value" size="small" :empty-text="t('common.empty')">
          <el-table-column prop="bill_seq" :label="t('export.seq')" width="64" />
          <el-table-column prop="material_code" :label="t('common.material')" min-width="100" />
          <el-table-column :label="t('export.segment')" min-width="260">
            <template #default="{ row }">
              <div class="sn-mono">{{ row.start_sn }}</div>
              <div v-if="row.end_sn !== row.start_sn" class="sn-mono">~ {{ row.end_sn }}</div>
              <div class="muted">{{ row.start_seq }} ~ {{ row.end_seq }}<template v-if="row.transferred_in"> · {{ t("export.seqOf", { pi: row.seq_pi_no }) }}</template></div>
            </template>
          </el-table-column>
          <el-table-column :label="t('common.qty')" align="right" width="80"><template #default="{ row }"><span class="num">{{ num(row.qty) }}</span></template></el-table-column>
          <el-table-column :label="t('common.status')" width="150">
            <template #default="{ row }">
              <StatusTag :status="row.status" />
              <el-tag v-if="row.suspend" size="small" type="danger" class="ml">{{ suspendLabel(row.suspend) }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination class="pager" layout="total, prev, pager, next" :total="segs.state.total" :page-size="segs.state.pageSize"
          :current-page="segs.state.page" @current-change="segs.load" />
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
/** SN 导出：已分配的号（不论是否对接 MES）按「生产组织 + PI + 单据」列出；看明细或直接下载，只读、可反复下载。 */
import { onMounted, reactive, ref } from "vue";
import { useRoute } from "vue-router";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import { api, urls } from "@/api";
import PageHead from "@/components/PageHead.vue";
import FactorySelect from "@/components/FactorySelect.vue";
import StatusTag from "@/components/StatusTag.vue";
import { useFactories } from "@/composables/useFactories";
import { usePaged } from "@/composables/usePaged";
import { statusLabel, suspendLabel } from "@/constants";
import { useAuthStore } from "@/stores/auth";
import { downloadWithToken } from "@/utils/download";

interface BillRow {
  factory_code: string;
  pi_no: string;
  bill_no: string;
  customer_code: string;
  mes_enabled: boolean;
  qty: number;
  suspended: number;
  statuses: Record<string, number>;
}

const { t } = useI18n();
const store = useAuthStore();
const route = useRoute();
const { nameOf } = useFactories();
const num = (n: number) => Number(n).toLocaleString();

const f = reactive({ factory_code: "", mes: "", q: String(route.query.pi || route.query.bill_no || "") });
const list = usePaged<BillRow>((q) => api.exportBills({ ...q, factory_code: f.factory_code, mes: f.mes, q: f.q.trim() }));

const busy = ref("");
const keyOf = (r: BillRow, fmt: string) => `${r.factory_code}|${r.pi_no}|${r.bill_no}|${fmt}`;
async function download(r: BillRow, fmt: "xlsx" | "csv") {
  busy.value = keyOf(r, fmt);
  try {
    const url = urls.exportFile({ factory_code: r.factory_code, pi: r.pi_no, bill_no: r.bill_no, format: fmt });
    await downloadWithToken(url, `SN_${r.pi_no}_${r.bill_no}.${fmt}`);
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    busy.value = "";
  }
}

const detail = ref<BillRow | null>(null);
const sf = reactive({ status: "" });
const segs = usePaged<any>((q) =>
  api.exportSegments({ ...q, factory_code: detail.value!.factory_code, pi: detail.value!.pi_no, bill_no: detail.value!.bill_no, status: sf.status }),
);
function openDetail(row: BillRow) {
  detail.value = row;
  sf.status = "";
  segs.load(1);
}

onMounted(() => list.load(1));
</script>

<style scoped>
.w140 { width: 140px; }
.w160 { width: 160px; }
.w220 { width: 220px; }
.ml { margin-left: 6px; }
.sp { flex: 1; }
.st { display: inline-flex; align-items: center; gap: 4px; margin-right: 10px; }
.clickable :deep(.el-table__row) { cursor: pointer; }
.dh { display: flex; align-items: center; gap: 10px; font-size: 15px; color: var(--app-text); }
</style>
