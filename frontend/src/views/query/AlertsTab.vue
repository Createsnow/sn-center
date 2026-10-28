<template>
  <div class="toolbar">
    <el-radio-group v-model="status" @change="load(1)">
      <el-radio-button value="OPEN">{{ t("query.alertStatus.OPEN") }}</el-radio-button>
      <el-radio-button value="CLOSED">{{ t("query.alertStatus.CLOSED") }}</el-radio-button>
      <el-radio-button value="ALL">{{ t("common.all") }}</el-radio-button>
    </el-radio-group>
    <el-button @click="load(1)"><el-icon><Refresh /></el-icon><span>{{ t("common.refresh") }}</span></el-button>
  </div>
  <div class="fill-body">
    <el-table v-loading="state.loading" height="100%" :data="items" size="small" :empty-text="t('common.empty')">
      <el-table-column :label="t('common.time')" width="160"><template #default="{ row }">{{ fmt(row.created_at) }}</template></el-table-column>
      <el-table-column :label="t('query.alertTypeCol')" width="130">
        <template #default="{ row }"><el-tag size="small" type="warning">{{ codeLabel(t, "query.alertType", row.alert_type) }}</el-tag></template>
      </el-table-column>
      <el-table-column :label="t('common.factory')" width="120" show-overflow-tooltip><template #default="{ row }">{{ nameOf(row.factory_code) }}</template></el-table-column>
      <el-table-column :label="t('common.batchNo')" min-width="210">
        <template #default="{ row }"><el-button v-if="row.batch_no" link type="primary" @click="emit('openDelivery', row.batch_no)">{{ row.batch_no }}</el-button></template>
      </el-table-column>
      <el-table-column :label="t('common.sn')" min-width="170"><template #default="{ row }"><span class="sn-mono">{{ row.sn }}</span></template></el-table-column>
      <el-table-column prop="detail" :label="t('query.alertDetail')" min-width="220" show-overflow-tooltip />
      <el-table-column :label="t('common.status')" width="90">
        <template #default="{ row }"><el-tag size="small" :type="row.status === 'OPEN' ? 'warning' : 'info'">{{ codeLabel(t, "query.alertStatus", row.status) }}</el-tag></template>
      </el-table-column>
      <el-table-column :label="t('query.handledBy')" min-width="220">
        <template #default="{ row }">
          <template v-if="row.status === 'CLOSED'">
            <div>{{ row.closed_by }} · {{ fmt(row.closed_at) }}</div>
            <div class="muted">{{ row.note }}</div>
          </template>
        </template>
      </el-table-column>
      <el-table-column v-if="store.canAct" :label="t('common.actions')" width="120" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 'OPEN'" link type="primary" @click="openHandle(row)">{{ t("query.closeAlert") }}</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
  <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="state.total" :page-size="state.pageSize"
    :current-page="state.page" :page-sizes="[20, 50, 100]" @current-change="load" @size-change="onSize" />

  <!-- 处理交付告警 -->
  <el-dialog v-model="handle.open" :title="t('query.handleTitle', { sn: handle.row?.sn || handle.row?.batch_no || '' })" width="520px">
    <el-input v-model="handle.note" type="textarea" :rows="3" maxlength="500" show-word-limit :placeholder="t('query.notePlaceholder')" />
    <template #footer>
      <el-button @click="handle.open = false">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" :loading="handle.busy" :disabled="!handle.note.trim()" @click="submitHandle">
        {{ t("query.closeAlert") }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/** 交付告警（5.2.5 第 8 条）：每日自检发现的问题；核查后填写结论并关闭。 */
import { onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import { api } from "@/api";
import { useAuthStore } from "@/stores/auth";
import { usePaged } from "@/composables/usePaged";
import { useFactories } from "@/composables/useFactories";
import { useTime } from "@/composables/useTime";
import { codeLabel } from "./filters";

const emit = defineEmits<{ openDelivery: [no: string] }>();

const { t } = useI18n();
const store = useAuthStore();
const { nameOf } = useFactories();
const { fmt } = useTime();

const status = ref("OPEN");
const { items, state, load, onSize } = usePaged<any>((q) => api.deliveryAlerts({ status: status.value, ...q }));

const handle = reactive({ open: false, note: "", busy: false, row: null as any });
function openHandle(row: any) {
  Object.assign(handle, { open: true, note: "", busy: false, row });
}
async function submitHandle() {
  handle.busy = true;
  try {
    await api.handleDeliveryAlert(handle.row.id, handle.note.trim());
    ElMessage.success(t("common.done"));
    handle.open = false;
    load();
  } finally {
    handle.busy = false;
  }
}

onMounted(() => load(1));
</script>
