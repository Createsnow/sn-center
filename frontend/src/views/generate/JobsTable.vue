<template>
  <el-table :data="jobs" size="small" :empty-text="t('common.empty')">
    <el-table-column type="expand">
      <template #default="{ row: job }">
        <el-table :data="job.lines" size="small" class="job-lines">
          <el-table-column :label="t('common.billNo')" prop="bill_no" min-width="110" />
          <el-table-column :label="t('common.billSeq')" prop="seq" width="72" />
          <el-table-column :label="t('common.material')" prop="material_code" min-width="90" />
          <el-table-column :label="t('common.length')" prop="length" width="64" />
          <el-table-column :label="t('common.qty')" prop="qty" align="right" width="70" />
          <el-table-column :label="t('generate.segment')" min-width="220">
            <template #default="{ row: l }"><span class="sn-mono" :class="{ struck: l.revoked }">{{ l.start_sn }} ~ {{ l.end_sn }}</span></template>
          </el-table-column>
          <el-table-column :label="t('generate.ruleCol')" prop="rule_label" min-width="150" show-overflow-tooltip />
          <el-table-column width="80" align="right">
            <template #default="{ row: l }"><el-tag v-if="l.revoked" size="small" type="info">{{ t("generate.job.REVOKED") }}</el-tag></template>
          </el-table-column>
        </el-table>
      </template>
    </el-table-column>
    <el-table-column :label="t('generate.genBatch')" prop="gen_batch" min-width="170" show-overflow-tooltip />
    <el-table-column :label="t('generate.scope')" width="80">
      <template #default="{ row }">{{ t(`generate.scopeShort.${row.scope}`) }}</template>
    </el-table-column>
    <el-table-column :label="t('common.qty')" align="right" width="100">
      <template #default="{ row }">
        <span class="num">{{ num(row.qty) }}</span>
        <div v-if="row.revoked_qty && row.status !== 'REVOKED'" class="muted">{{ t("generate.revokedQty", { n: num(row.revoked_qty) }) }}</div>
      </template>
    </el-table-column>
    <el-table-column :label="t('generate.segment')" min-width="220">
      <template #default="{ row }"><span class="sn-mono">{{ row.start_sn }} ~ {{ row.end_sn }}</span></template>
    </el-table-column>
    <el-table-column :label="t('common.status')" width="90">
      <template #default="{ row }">
        <el-tooltip :disabled="!row.error_msg && !row.revoke_reason" :content="row.error_msg || row.revoke_reason || ''">
          <el-tag size="small" :type="JOB_TAG[row.status] || 'info'">{{ t(`generate.job.${row.status}`) }}</el-tag>
        </el-tooltip>
      </template>
    </el-table-column>
    <el-table-column :label="t('common.operator')" prop="created_by" width="80" show-overflow-tooltip />
    <el-table-column :label="t('common.time')" width="140">
      <template #default="{ row }">{{ fmt(row.created_at) }}</template>
    </el-table-column>
    <el-table-column :label="t('common.actions')" width="130" align="right">
      <template #default="{ row }">
        <el-button v-if="leftOf(row).length" link type="primary" @click="emit('pick', leftOf(row))">{{ t("generate.pickJobLines") }}</el-button>
      </template>
    </el-table-column>
  </el-table>
  <p class="muted small">{{ t("generate.jobsNote") }}</p>
</template>

<script setup lang="ts">
/** 生成记录：只看；「勾选这次的物料行」把这次还没撤销的物料行设为范围，再在底部撤销（整次撤销）。 */
import { useI18n } from "vue-i18n";
import { useTime } from "@/composables/useTime";
import { num } from "./format";

type TagType = "" | "info" | "primary" | "success" | "warning" | "danger";
type LineRef = { bill_no: string; seq: number };

const JOB_TAG: Record<string, TagType> = { RUNNING: "primary", SUCCESS: "success", FAILED: "danger", CANCELED: "info", REVOKED: "info" };

/** segments：PI 流水上未撤销的物料行号段（ctx.segments）。 */
const props = defineProps<{ jobs: any[]; segments: (LineRef & { gen_batch: string })[] }>();
const emit = defineEmits<{ pick: [lines: LineRef[]] }>();

const { t } = useI18n();
const { fmt } = useTime();

/** 一次生成中还没撤销的物料行。 */
const leftOf = (job: any): LineRef[] =>
  job.lines
    .filter((l: any) => !l.revoked && props.segments.some((s) => s.gen_batch === job.gen_batch && s.bill_no === l.bill_no && s.seq === l.seq))
    .map((l: any) => ({ bill_no: l.bill_no, seq: l.seq }));
</script>

<style scoped>
.small { font-size: 12px; }
.struck { text-decoration: line-through; color: var(--el-text-color-placeholder); }
.job-lines { margin: 0 12px 0 48px; width: calc(100% - 60px); }
</style>
