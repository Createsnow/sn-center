<template>
  <div class="toolbar">
    <FactorySelect v-if="store.multiScope" v-model="f.factory_code" scoped clearable />
    <el-input v-model="f.pi" placeholder="PI" clearable @keyup.enter="load(1)" />
    <el-input v-model="f.batch_no" :placeholder="t('common.batchNo')" clearable @keyup.enter="load(1)" />
    <el-select v-model="f.source" clearable :placeholder="t('query.batchSource')">
      <el-option v-for="s in ['PUSH', 'PULL']" :key="s" :value="s" :label="batchSourceLabel(s)" />
    </el-select>
    <el-select v-model="f.batch_type" clearable :placeholder="t('query.batchType')">
      <el-option v-for="s in BATCH_TYPES" :key="s" :value="s" :label="batchTypeLabel(s)" />
    </el-select>
    <el-select v-model="f.result" clearable :placeholder="t('query.result')">
      <el-option v-for="s in BATCH_RESULTS" :key="s" :value="s" :label="batchResultLabel(s)" />
    </el-select>
    <el-input v-model="f.transfer_no" :placeholder="t('query.transferNo')" clearable @keyup.enter="load(1)" />
    <el-button type="primary" @click="load(1)">{{ t("common.search") }}</el-button>
  </div>
  <div class="fill-body">
    <el-table v-loading="state.loading" height="100%" :data="items" size="small" :empty-text="t('common.empty')">
      <el-table-column :label="t('common.batchNo')" min-width="220" fixed>
        <template #default="{ row }"><el-button link type="primary" @click="emit('openDelivery', row.batch_no)">{{ row.batch_no }}</el-button></template>
      </el-table-column>
      <el-table-column :label="t('query.batchSource')" width="76"><template #default="{ row }">{{ batchSourceLabel(row.source) }}</template></el-table-column>
      <el-table-column :label="t('query.batchType')" width="76"><template #default="{ row }">{{ batchTypeLabel(row.batch_type) }}</template></el-table-column>
      <el-table-column :label="t('common.factory')" width="120" show-overflow-tooltip><template #default="{ row }">{{ nameOf(row.factory_code) }}</template></el-table-column>
      <el-table-column prop="pi_no" label="PI" min-width="140" show-overflow-tooltip />
      <el-table-column prop="qty" :label="t('common.qty')" width="70" align="right" />
      <el-table-column :label="t('query.result')" width="130">
        <template #default="{ row }"><el-tag size="small" :type="BATCH_RESULT_TYPE[row.result] || 'info'">{{ batchResultLabel(row.result) }}</el-tag></template>
      </el-table-column>
      <el-table-column :label="t('query.confirmMethod')" width="150">
        <template #default="{ row }">
          <div>{{ confirmMethodLabel(row.confirm_method) }}</div>
          <div v-if="row.confirmed_at" class="muted">{{ fmt(row.confirmed_at) }}</div>
        </template>
      </el-table-column>
      <el-table-column :label="t('query.acceptedQty')" width="90" align="right">
        <template #default="{ row }"><span class="num">{{ row.accepted_qty }}</span></template>
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
  <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="state.total" :page-size="state.pageSize"
    :current-page="state.page" :page-sizes="[20, 50, 100]" @current-change="load" @size-change="onSize" />
</template>

<script setup lang="ts">
/** 交付批次（推送、拉取、转出）列表；点批次号打开详情。 */
import { onMounted, reactive } from "vue";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useAuthStore } from "@/stores/auth";
import { usePaged } from "@/composables/usePaged";
import { useFactories } from "@/composables/useFactories";
import { useTime } from "@/composables/useTime";
import {
  BATCH_RESULTS, BATCH_RESULT_TYPE, BATCH_TYPES, batchResultLabel, batchSourceLabel, batchTypeLabel, confirmMethodLabel,
  pushStateLabel,
} from "@/constants";
import FactorySelect from "@/components/FactorySelect.vue";
import { trimmed } from "./filters";

const props = defineProps<{ init: { factory_code: string; pi: string; batch_no: string; transfer_no: string } }>();
const emit = defineEmits<{ openDelivery: [no: string] }>();

const { t } = useI18n();
const store = useAuthStore();
const { nameOf } = useFactories();
const { fmt } = useTime();

const f = reactive({ source: "", batch_type: "", result: "", ...props.init });
const { items, state, load, onSize } = usePaged<any>((q) => api.deliveryBatches({ ...trimmed(f), ...q }));

onMounted(() => load(1));
defineExpose({ reload: () => load() });
</script>
