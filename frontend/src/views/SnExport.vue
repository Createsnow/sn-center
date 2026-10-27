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
        <el-table-column :label="t('export.qty')" min-width="200">
          <template #default="{ row }">
            <span class="num">{{ num(row.qty) }}</span>
            <!-- 导出与推送相互独立：不显示推送进度，只提示申请中（可能转走）、暂停 -->
            <el-tag v-if="row.applying" size="small" type="warning" effect="plain" class="ml">{{ t("export.applying", { n: num(row.applying) }) }}</el-tag>
            <el-tag v-if="row.suspended" size="small" type="danger" effect="plain" class="ml">{{ t("export.suspended", { n: num(row.suspended) }) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="150" align="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openDetail(row)">{{ t("common.detail") }}</el-button>
            <el-button link type="primary" :loading="busy === keyOf(row)" @click.stop="download(row)">xlsx</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="list.state.total" :page-size="list.state.pageSize"
        :current-page="list.state.page" :page-sizes="[20, 50, 100]" @current-change="list.load" @size-change="list.onSize" />
      <p class="muted">{{ t("export.rule") }}</p>
    </div>

    <!-- 明细：概况 + 物料行 + 逐枚列出全部 SN -->
    <el-drawer :model-value="!!detail" size="900px" @close="detail = null">
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
          <span class="total">{{ t("export.total", { n: num(detail.qty), l: lines.length || "…" }) }}</span>
          <span class="sp" />
          <span class="muted">{{ t("export.fileCols") }}</span>
          <el-button size="small" type="primary" :loading="busy === keyOf(detail)" @click="download(detail)">{{ t("export.download", { f: "xlsx" }) }}</el-button>
        </div>
        <el-alert v-if="notes.length" type="warning" show-icon :closable="false" class="mb">
          <template #title><div v-for="n in notes" :key="n">{{ n }}</div></template>
        </el-alert>

        <div class="surface__title">{{ t("export.lines") }}</div>
        <el-table v-loading="linesLoading" :data="lines" size="small" class="mb" :empty-text="t('common.empty')">
          <el-table-column prop="bill_seq" :label="t('export.seq')" width="90" />
          <el-table-column prop="material_code" :label="t('common.material')" min-width="110" />
          <el-table-column :label="t('common.attachNo')" width="90"><template #default="{ row }">{{ row.attach_no || "—" }}</template></el-table-column>
          <el-table-column :label="t('common.length')" width="80"><template #default="{ row }">{{ row.length || "—" }}</template></el-table-column>
          <el-table-column :label="t('common.qty')" align="right" width="80"><template #default="{ row }"><span class="num">{{ num(row.qty) }}</span></template></el-table-column>
          <el-table-column :label="t('export.attention')" min-width="200">
            <template #default="{ row }">
              <el-tag v-if="row.applying" size="small" type="warning" effect="plain" class="mr">{{ t("export.applying", { n: num(row.applying) }) }}</el-tag>
              <el-tag v-if="row.suspended" size="small" type="danger" effect="plain">{{ t("export.suspended", { n: num(row.suspended) }) }}</el-tag>
              <span v-if="!row.applying && !row.suspended" class="muted">—</span>
            </template>
          </el-table-column>
        </el-table>

        <div class="seg-head">
          <span class="surface__title">{{ t("export.sns") }}</span>
        </div>
        <el-table v-loading="sns.state.loading" :data="sns.items.value" size="small" :empty-text="t('common.empty')">
          <el-table-column prop="line_no" :label="t('export.lineNo')" width="70" />
          <el-table-column :label="t('common.sn')" min-width="220">
            <template #default="{ row }">
              <span class="sn-mono">{{ row.sn }}</span>
              <el-tag v-if="row.transferred_in" size="small" type="info" effect="plain" class="ml">{{ t("export.seqOf", { pi: row.seq_pi_no }) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="seq_dec" :label="t('common.seqDec')" width="100" align="right" />
          <el-table-column prop="bill_seq" :label="t('export.seq')" width="90" />
          <el-table-column prop="material_code" :label="t('common.material')" min-width="100" show-overflow-tooltip />
          <el-table-column :label="t('common.attachNo')" width="90"><template #default="{ row }">{{ row.attach_no || "—" }}</template></el-table-column>
          <el-table-column :label="t('common.length')" width="80"><template #default="{ row }">{{ row.length || "—" }}</template></el-table-column>
          <el-table-column :label="t('export.attention')" width="150">
            <template #default="{ row }">
              <el-tag v-if="row.applying" size="small" type="warning" effect="plain" class="mr">{{ t("export.applyingOne") }}</el-tag>
              <el-tag v-if="row.suspend" size="small" type="danger">{{ suspendLabel(row.suspend) }}</el-tag>
              <span v-if="!row.applying && !row.suspend" class="muted">—</span>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="sns.state.total" :page-size="sns.state.pageSize"
          :current-page="sns.state.page" :page-sizes="[50, 100, 200, 500]" @current-change="sns.load" @size-change="sns.onSize" />
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
/** SN 导出：已分配的号（不论是否对接 MES）按「生产组织 + PI + 单据」列出；看明细或直接下载，只读、可反复下载。
 *  与分配后的推送、拉取相互独立：不显示推送进度，推送成败都不影响列出和下载。 */
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute } from "vue-router";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import { api, urls } from "@/api";
import PageHead from "@/components/PageHead.vue";
import FactorySelect from "@/components/FactorySelect.vue";
import { useFactories } from "@/composables/useFactories";
import { usePaged } from "@/composables/usePaged";
import { suspendLabel } from "@/constants";
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
  applying: number;
}

const { t } = useI18n();
const store = useAuthStore();
const route = useRoute();
const { nameOf } = useFactories();
const num = (n: number) => Number(n).toLocaleString();

const f = reactive({ factory_code: "", mes: "", q: String(route.query.pi || route.query.bill_no || "") });
const list = usePaged<BillRow>((q) => api.exportBills({ ...q, factory_code: f.factory_code, mes: f.mes, q: f.q.trim() }));

const busy = ref("");
const keyOf = (r: BillRow) => `${r.factory_code}|${r.pi_no}|${r.bill_no}`;
async function download(r: BillRow) {
  busy.value = keyOf(r);
  try {
    const url = urls.exportFile({ factory_code: r.factory_code, pi: r.pi_no, bill_no: r.bill_no });
    await downloadWithToken(url, `SN_${r.pi_no}_${r.bill_no}.xlsx`);
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    busy.value = "";
  }
}

interface LineRow {
  bill_seq: number;
  material_code: string;
  attach_no: string;
  length: string;
  qty: number;
  suspended: number;
  applying: number;
}
interface SnRow {
  line_no: number;
  sn: string;
  bill_seq: number;
  material_code: string;
  attach_no: string;
  length: string;
  seq_pi_no: string;
  transferred_in: boolean;
  seq_dec: number;
  applying: boolean;
  suspend: string | null;
}

const detail = ref<BillRow | null>(null);
const lines = ref<LineRow[]>([]);
const linesLoading = ref(false);
/** 明细逐枚列出该单据的全部 SN（不按号段合并），顺序同下载文件。 */
const sns = usePaged<SnRow>(
  (q) => api.exportItems({ ...q, factory_code: detail.value!.factory_code, pi: detail.value!.pi_no, bill_no: detail.value!.bill_no }),
  100,
);

async function openDetail(row: BillRow) {
  detail.value = row;
  lines.value = [];
  sns.load(1);
  linesLoading.value = true;
  try {
    const got: LineRow[] = await api.exportLines({ factory_code: row.factory_code, pi: row.pi_no, bill_no: row.bill_no });
    if (detail.value === row) lines.value = got;
  } finally {
    linesLoading.value = false;
  }
}

/** 需要留意的号：申请中（可能转走）、暂停交付。 */
const notes = computed(() => {
  const d = detail.value;
  if (!d) return [];
  const out: string[] = [];
  if (d.applying) out.push(t("export.applyingNote", { n: num(d.applying) }));
  if (d.suspended) out.push(t("export.suspendedNote", { n: num(d.suspended) }));
  return out;
});

onMounted(() => list.load(1));
</script>

<style scoped>
.w140 { width: 140px; }
.w220 { width: 220px; }
.ml { margin-left: 6px; }
.mr { margin-right: 6px; }
.mb { margin-bottom: 12px; }
.total { font-size: 14px; font-weight: 600; color: var(--app-text); }
.seg-head { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 8px; }
.seg-head .surface__title { margin: 0; }
.sp { flex: 1; }
.clickable :deep(.el-table__row) { cursor: pointer; }
.dh { display: flex; align-items: center; gap: 10px; font-size: 15px; color: var(--app-text); }
</style>
