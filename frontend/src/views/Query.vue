<template>
  <div class="page page--fill">
    <PageHead :title="t('query.title')" :desc="t('query.desc')" />
    <div class="surface fill">
      <!-- 每个页签第一次打开时查询一次，之后按各自的「查询」按钮刷新 -->
      <el-tabs v-model="tab" class="tabs-fill">
        <el-tab-pane :label="t('query.tabSn')" name="sn" lazy>
          <SnTab :init="{ factory_code: initFactory, pi: initPi, bill_no: str(route.query.bill_no), batch_no: initTab === 'sn' ? initBatch : '' }"
            @open-delivery="(no) => (deliveryNo = no)" />
        </el-tab-pane>
        <!-- 交付批次、交付告警：系统管理员、普通操作员；审计员只有 SN 明细 -->
        <el-tab-pane v-if="store.canAct" :label="t('query.tabDelivery')" name="delivery" lazy>
          <DeliveryTab ref="deliveryTab" :init="{ factory_code: initFactory, pi: initPi, batch_no: initTab === 'delivery' ? initBatch : '', transfer_no: str(route.query.transfer_no) }"
            @open-delivery="(no) => (deliveryNo = no)" />
        </el-tab-pane>
        <el-tab-pane v-if="store.canAct" :label="t('query.tabAlerts')" name="alerts" lazy>
          <AlertsTab @open-delivery="(no) => (deliveryNo = no)" />
        </el-tab-pane>
      </el-tabs>
    </div>

    <DeliveryBatchDrawer v-model:batch-no="deliveryNo" @changed="deliveryTab?.reload()" />
  </div>
</template>

<script setup lang="ts">
/** SN 查询（F-18）：SN 明细、交付批次、交付告警三个页签；交付批次详情抽屉各页签共用。 */
import { ref } from "vue";
import { useRoute } from "vue-router";
import { useI18n } from "vue-i18n";
import { useAuthStore } from "@/stores/auth";
import PageHead from "@/components/PageHead.vue";
import DeliveryBatchDrawer from "@/components/batch/DeliveryBatchDrawer.vue";
import SnTab from "@/views/query/SnTab.vue";
import DeliveryTab from "@/views/query/DeliveryTab.vue";
import AlertsTab from "@/views/query/AlertsTab.vue";

const TABS = ["sn", "delivery", "alerts"];

const { t } = useI18n();
const route = useRoute();
const store = useAuthStore();
const str = (v: unknown) => (typeof v === "string" ? v : "");

/** 从其他页跳来时可带 ?tab=&pi=&batch_no=&bill_no=&factory=&transfer_no=；系统管理员、授权多个组织的账户有生产组织筛选。 */
const initTab = store.canAct && TABS.includes(str(route.query.tab)) ? str(route.query.tab) : "sn";
const initFactory = store.multiScope ? str(route.query.factory) : "";
const initPi = str(route.query.pi);
const initBatch = str(route.query.batch_no);
const tab = ref(initTab);

const deliveryTab = ref<InstanceType<typeof DeliveryTab> | null>(null);
const deliveryNo = ref(initTab === "delivery" ? initBatch : "");
</script>

<style scoped>
/* 页签铺满卡片，当前页签内的表格占剩余高度 */
.tabs-fill { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.tabs-fill :deep(.el-tabs__content) { flex: 1; min-height: 0; }
.tabs-fill :deep(.el-tab-pane) { height: 100%; display: flex; flex-direction: column; }
@media (max-width: 1199px) { .tabs-fill :deep(.el-tab-pane) { height: auto; } }
</style>
