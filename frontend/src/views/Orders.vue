<template>
  <div class="page">
    <PageHead :title="t('orders.title')" :desc="t('orders.desc')">
      <template v-if="store.isAdmin">
        <el-input v-model="syncBillNo" :placeholder="t('orders.billToSync')" clearable class="w200" @keyup.enter="syncOne" />
        <el-button :loading="syncing === 'one'" :disabled="!syncBillNo.trim()" @click="syncOne">{{ t("orders.syncOne") }}</el-button>
        <el-button type="primary" :loading="syncing === 'all'" @click="syncAll">
          <el-icon><Refresh /></el-icon><span>{{ t("orders.syncAll") }}</span>
        </el-button>
      </template>
    </PageHead>

    <el-alert v-if="meta?.unmapped_orgs?.length" type="warning" show-icon :closable="false"
      :title="t('orders.unmapped', { orgs: meta.unmapped_orgs.join('、') })" />

    <div class="surface meta">
      <span>{{ t("orders.syncedAt") }}：<b>{{ meta?.synced_at || "—" }}</b></span>
      <span>{{ t("orders.bills") }}：<b class="num">{{ meta?.bills ?? 0 }}</b></span>
      <span>{{ t("orders.rows") }}：<b class="num">{{ meta?.rows ?? 0 }}</b></span>
      <span class="muted">{{ t("orders.excluded", { n: meta?.excluded_empty_customer ?? 0 }) }}</span>
    </div>

    <el-row :gutter="16">
      <el-col :lg="16" :xs="24">
        <div class="surface">
          <div class="toolbar">
            <el-input v-model="f.q" :placeholder="t('orders.search')" clearable prefix-icon="Search" @keyup.enter="load(1)" @clear="load(1)" />
            <el-input v-model="f.customer" :placeholder="t('common.customer')" clearable @keyup.enter="load(1)" @clear="load(1)" />
            <FactorySelect v-if="!store.boundFactory" v-model="f.factory_code" clearable @update:model-value="load(1)" />
            <el-button type="primary" @click="load(1)">{{ t("common.search") }}</el-button>
          </div>
          <el-table v-loading="state.loading" :data="items" highlight-current-row size="small" height="560"
            :empty-text="t('common.empty')" @current-change="select">
            <el-table-column prop="bill_no" :label="t('common.billNo')" min-width="120" show-overflow-tooltip />
            <el-table-column prop="customer_number" :label="t('common.customer')" width="80" show-overflow-tooltip />
            <el-table-column prop="pi" label="PI" min-width="140" show-overflow-tooltip />
            <el-table-column :label="t('orders.org')" min-width="100" show-overflow-tooltip>
              <template #default="{ row }">
                <span>{{ row.prd_org_name }}</span>
                <el-tag v-if="!row.factory_code" size="small" type="danger" class="ml">{{ t("orders.noFactory") }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('orders.status')" width="84" show-overflow-tooltip>
              <template #default="{ row }">{{ statusText(row.statuses) }}</template>
            </el-table-column>
            <el-table-column :label="t('orders.totalQty')" align="right" width="76">
              <template #default="{ row }"><span class="num">{{ row.total_qty }}</span></template>
            </el-table-column>
            <el-table-column :label="t('orders.generated')" align="right" width="70">
              <template #default="{ row }"><span class="num">{{ row.generated_qty }}</span></template>
            </el-table-column>
            <el-table-column :label="t('orders.quota')" align="right" width="70">
              <template #default="{ row }"><span class="num" :class="{ zero: !row.quota }">{{ row.quota }}</span></template>
            </el-table-column>
          </el-table>
          <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="state.total" :page-size="state.pageSize"
            :current-page="state.page" :page-sizes="[20, 50, 100]" @current-change="load" @size-change="onSize" />
        </div>
      </el-col>
      <el-col :lg="8" :xs="24">
        <div class="surface">
          <div class="surface__title">
            <span>{{ current ? t("orders.linesOf", { bill: current.bill_no }) : t("orders.lines") }}</span>
            <el-button v-if="current && store.isAdmin" type="primary" size="small"
              @click="router.push({ path: '/generate', query: { bill_no: current.bill_no } })">
              {{ t("orders.toGenerate") }}
            </el-button>
          </div>
          <template v-if="current">
            <el-descriptions :column="1" size="small" border class="mb">
              <el-descriptions-item label="PI">{{ current.pi }}</el-descriptions-item>
              <el-descriptions-item label="PO">{{ current.po }}</el-descriptions-item>
              <el-descriptions-item :label="t('common.customer')">{{ current.customer_number }}</el-descriptions-item>
              <el-descriptions-item :label="t('common.factory')">{{ current.prd_org_name }}（{{ current.factory_code || "—" }}）</el-descriptions-item>
            </el-descriptions>
            <el-table v-loading="linesLoading" :data="lines" size="small" :empty-text="t('common.empty')">
              <el-table-column prop="line_seq" label="#" width="48" />
              <el-table-column :label="t('common.material')" min-width="100">
                <template #default="{ row }"><span v-if="row.material_number">{{ row.material_number }}</span><span v-else class="muted">{{ t("orders.noMaterial") }}</span></template>
              </el-table-column>
              <el-table-column :label="t('orders.materialName')" min-width="120" show-overflow-tooltip>
                <template #default="{ row }">
                  <div>{{ row.material_name }}</div>
                  <div v-if="row.demand_bill_no" class="muted">{{ t("orders.demand") }} {{ row.demand_bill_no }}</div>
                </template>
              </el-table-column>
              <el-table-column :label="t('common.qty')" align="right" width="70">
                <template #default="{ row }"><span class="num">{{ Number(row.qty) }}</span></template>
              </el-table-column>
            </el-table>
          </template>
          <el-empty v-else :description="t('orders.pick')" />
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import { api } from "@/api";
import { useAuthStore } from "@/stores/auth";
import { usePaged } from "@/composables/usePaged";
import PageHead from "@/components/PageHead.vue";
import FactorySelect from "@/components/FactorySelect.vue";

const { t } = useI18n();
const router = useRouter();
const store = useAuthStore();
const f = reactive({ q: "", customer: "", factory_code: "" });
const meta = ref<any>(null);
const current = ref<any>(null);
const lines = ref<any[]>([]);
const linesLoading = ref(false);
const syncBillNo = ref("");
const syncing = ref<"" | "all" | "one">("");

const { items, state, load, onSize } = usePaged<any>((q) => api.orders({ ...f, ...q }));

function statusText(s: string) {
  return (s || "").split(",").map((x) => t(`orders.st${x}`)).join(" / ");
}

async function select(row: any) {
  current.value = row;
  if (!row) return;
  linesLoading.value = true;
  try {
    lines.value = await api.orderLines(row.bill_no);
  } finally {
    linesLoading.value = false;
  }
}

async function refresh() {
  meta.value = await api.orderMeta();
  await load(1);
}

async function syncAll() {
  syncing.value = "all";
  try {
    const r: any = await api.syncOrders();
    ElMessage.success(t("orders.syncedAll", { bills: r.bills, rows: r.rows }));
    await refresh();
  } finally {
    syncing.value = "";
  }
}

async function syncOne() {
  const no = syncBillNo.value.trim();
  if (!no) return;
  syncing.value = "one";
  try {
    const r: any = await api.syncOrders(no);
    ElMessage.success(r.removed ? t("orders.syncedRemoved", { bill: no }) : t("orders.syncedOne", { bill: no, rows: r.rows }));
    await refresh();
  } finally {
    syncing.value = "";
  }
}

onMounted(refresh);
</script>

<style scoped>
.meta { display: flex; gap: 24px; flex-wrap: wrap; align-items: center; font-size: 13px; padding: 12px 20px; }
.w200 { width: 200px; }
.ml { margin-left: 6px; }
.mb { margin-bottom: 12px; }
.zero { color: var(--app-muted); }
.el-col { margin-bottom: 16px; }
</style>
