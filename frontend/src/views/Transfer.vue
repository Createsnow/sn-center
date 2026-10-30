<template>
  <div class="page">
    <PageHead :title="t('transfer.title')" :desc="t('transfer.desc')">
      <el-button v-if="store.canAct" type="primary" @click="openNew()"><el-icon><Plus /></el-icon><span>{{ t("transfer.new") }}</span></el-button>
    </PageHead>

    <!-- ================= 转移记录（转单 + 转组织，都直接生效） ================= -->
    <div class="surface">
      <div class="toolbar">
        <el-radio-group v-model="lq.kind" @change="list.load(1)">
          <el-radio-button value="">{{ t("transfer.allKinds") }}</el-radio-button>
          <el-radio-button v-for="k in KINDS" :key="k" :value="k">{{ t(`transfer.kindName.${k}`) }}</el-radio-button>
        </el-radio-group>
        <FactorySelect v-if="store.multiScope" v-model="lq.factory_code" scoped clearable :placeholder="t('app.allFactories')" @update:model-value="list.load(1)" />
        <el-input v-model="lq.pi" :placeholder="t('transfer.piInOut')" clearable @keyup.enter="list.load(1)" @clear="list.load(1)" />
        <el-input v-model="lq.bill_no" :placeholder="t('common.billNo')" clearable @keyup.enter="list.load(1)" @clear="list.load(1)" />
        <el-button type="primary" @click="list.load(1)">{{ t("common.search") }}</el-button>
      </div>
      <el-table v-loading="list.state.loading" :data="list.items.value" size="small" :empty-text="t('common.empty')">
        <el-table-column :label="t('transfer.no')" min-width="180">
          <template #default="{ row }">
            <el-button link type="primary" class="sn-mono" @click="detail?.open(row.kind, row.id)">{{ row.transfer_no }}</el-button>
            <div class="muted">
              <el-tag size="small" effect="plain" :type="row.kind === 'BILL' ? 'primary' : 'warning'">{{ t(`transfer.kindName.${row.kind}`) }}</el-tag>
              <span class="ml">{{ modeLabel(row) }}</span>
              <el-tag v-if="row.direction" size="small" :type="row.direction === 'OUT' ? 'warning' : 'success'" class="ml">{{ t(`transfer.direction.${row.direction}`) }}</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('transfer.fromTo')" min-width="360">
          <template #default="{ row }">
            <div class="flow">
              <div class="end">
                <div class="one" :title="row.from_bill">{{ row.from_factory ? `${nameOf(row.from_factory)} · ` : "" }}{{ row.from_pi }}<template v-if="row.from_bill"> · {{ row.from_bill }}</template></div>
                <div class="muted one" :title="sourceText(row)">{{ sourceText(row) }}</div>
              </div>
              <el-icon class="arrow"><Right /></el-icon>
              <div class="end">
                <div class="one" :title="row.to_factory ? nameOf(row.to_factory) : undefined">
                  <b>{{ row.to_factory ? `${nameOf(row.to_factory)} · ` : "" }}{{ row.to_pi }}</b>
                </div>
                <div class="muted one" :title="targetText(row)">{{ targetText(row) }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.qty')" align="right" width="70"><template #default="{ row }"><span class="num">{{ row.qty }}</span></template></el-table-column>
        <el-table-column prop="reason" :label="t('transfer.reason')" min-width="200" show-overflow-tooltip />
        <el-table-column :label="t('common.operator')" width="150">
          <template #default="{ row }"><div>{{ row.created_by }}</div><div class="muted">{{ fmt(row.created_at) }}</div></template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="80" align="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="detail?.open(row.kind, row.id)">{{ t("common.detail") }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="list.state.total" :page-size="list.state.pageSize"
        :current-page="list.state.page" :page-sizes="[20, 50, 100]" @current-change="list.load" @size-change="list.onSize" />
    </div>
    <TransferDialog ref="dlg" @done="onDone" />
    <TransferDetailDrawer ref="detail" />
  </div>
</template>

<script setup lang="ts">
/** SN 转移（F-15、F-16、F-17）：转单与转组织合在一张记录表；系统管理员、普通操作员都直接转移（原因必填，立即生效），
 *  没有申请、审批。新建与详情见 views/transfer。 */
import { onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import PageHead from "@/components/PageHead.vue";
import FactorySelect from "@/components/FactorySelect.vue";
import { useFactories } from "@/composables/useFactories";
import { usePaged } from "@/composables/usePaged";
import { useTime } from "@/composables/useTime";
import { scopeLabel } from "@/constants";
import { useAuthStore } from "@/stores/auth";
import TransferDialog from "@/views/transfer/TransferDialog.vue";
import TransferDetailDrawer from "@/views/transfer/TransferDetailDrawer.vue";
import { type Kind, KINDS, snRange } from "@/views/transfer/types";

const { t } = useI18n();
const store = useAuthStore();
const { nameOf } = useFactories();
const { fmt } = useTime();
const dlg = ref<InstanceType<typeof TransferDialog> | null>(null);
const detail = ref<InstanceType<typeof TransferDetailDrawer> | null>(null);

// ---------------- 转移记录：转单与转组织合在一张表（新的在前）
const lq = reactive({ kind: "", factory_code: "", pi: "", bill_no: "" });
const list = usePaged<any>((q) => api.transfers({ ...q, ...lq, pi: lq.pi.trim(), bill_no: lq.bill_no.trim() }));

function modeLabel(row: any) {
  return row.kind === "BILL" ? t(`transfer.billMode.${row.mode}`) : t(`transfer.orgMode.${row.mode}`);
}
/** 来源范围：整张单据 / 物料 / 号段。转单没有物料、号段列，取范围说明「PI / 单据 / 物料 / 起止号」去掉 PI、单据。 */
function sourceText(d: any) {
  const rest = d.kind === "BILL" ? (d.scope_text || "").split(" / ").slice(2) : [d.scope !== "BILL" && d.from_material, d.from_sn && snRange(d)];
  return [scopeLabel(d.scope), ...rest].filter(Boolean).join(" · ");
}
/** 目标：单据 / 序号 · 物料 · 客户。 */
function targetText(d: { to_bill?: string; to_seq?: number; to_material?: string; to_customer?: string }) {
  return [
    d.to_bill && (d.to_seq ? `${d.to_bill} / ${d.to_seq}` : d.to_bill),
    d.to_material && `${t("common.material")} ${d.to_material}`,
    d.to_customer && `${t("common.customer")} ${d.to_customer}`,
  ].filter(Boolean).join(" · ");
}

/** 默认转单，对话框里可切换为转组织。 */
function openNew() {
  dlg.value?.open("BILL");
}

function onDone(kind: Kind, doc: any) {
  list.load(1);
  detail.value?.show(kind, doc);
}

onMounted(() => list.load(1));
</script>

<style scoped>
.ml { margin-left: 8px; }
.one { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.toolbar :deep(.el-input), .toolbar :deep(.factory-select) { width: 170px; }
.flow { display: grid; grid-template-columns: minmax(0, 1fr) 16px minmax(0, 1fr); align-items: center; gap: 8px; }
.flow .end { min-width: 0; }
.flow .arrow { color: var(--app-muted); }
</style>
