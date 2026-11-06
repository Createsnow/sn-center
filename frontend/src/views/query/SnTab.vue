<template>
  <!-- 常用条件：组织、PI、单据、完整 SN、状态；其余条件在第二行 -->
  <div class="toolbar">
    <FactorySelect v-if="store.multiScope" v-model="f.factory_code" scoped clearable />
    <el-input v-model="f.pi" placeholder="PI" clearable @keyup.enter="load(1)" />
    <el-input v-model="f.bill_no" :placeholder="t('common.billNo')" clearable @keyup.enter="load(1)" />
    <el-input v-model="f.sn" :placeholder="t('common.sn')" clearable class="sn-mono w240" @keyup.enter="load(1)" />
    <el-select v-model="f.status" clearable :placeholder="t('common.status')">
      <el-option v-for="s in STATUS_CODES" :key="s" :value="s" :label="statusLabel(s)" />
      <el-option value="SUSPENDED" :label="t('query.suspended')" />
    </el-select>
    <el-button type="primary" @click="load(1)">{{ t("common.search") }}</el-button>
    <el-button @click="reset">{{ t("common.reset") }}</el-button>
    <span class="push" />
    <el-button :loading="exporting" @click="exportSn"><el-icon><Download /></el-icon><span>{{ t("common.downloadXlsx") }}</span></el-button>
  </div>
  <div class="toolbar">
    <el-input v-model="f.customer_code" :placeholder="t('common.customer')" clearable @keyup.enter="load(1)" />
    <el-input v-model="f.material_code" :placeholder="t('common.material')" clearable @keyup.enter="load(1)" />
    <el-input v-model="f.length" :placeholder="t('common.length')" clearable class="w120" @keyup.enter="load(1)" />
    <div class="seq-range">
      <span class="seq-range__label">{{ t("query.seqRange") }}</span>
      <el-input v-model="f.seq_from" :placeholder="t('query.seqFrom')" clearable class="sn-mono" @keyup.enter="load(1)" />
      <span>–</span>
      <el-input v-model="f.seq_to" :placeholder="t('query.seqTo')" clearable class="sn-mono" @keyup.enter="load(1)" />
    </div>
    <el-switch v-if="store.isOperator" v-model="f.pi_all" :active-text="t('query.piAll')" :disabled="!f.pi.trim()" />
  </div>
  <el-alert v-if="f.pi_all" type="info" :closable="false" show-icon :title="t('query.piAllTip')" class="mb" />
  <el-alert v-if="hasRange" type="info" :closable="false" show-icon :title="t('query.seqRangeTip')" class="mb" />
  <div v-if="range" class="range-sum mb">
    <span>{{ t("query.rangeFound", { n: range.found }) }}</span>
    <span v-for="(n, s) in range.by_status" :key="s"><StatusTag :status="String(s)" /> <b>{{ n }}</b></span>
    <span v-if="range.expected != null" class="range-sum__note">
      {{ range.missing ? t("query.rangeMissing", { expected: range.expected, n: range.missing }) : t("query.rangeFull") }}
    </span>
  </div>
  <div class="fill-body">
    <!-- 列宽按本页内容自适应（w），单元格单行完整显示、不截断；超出容器时横向滚动（PI、完整 SN 固定在左侧）；点行弹出该枚 SN 的详情 -->
    <el-table v-loading="state.loading" height="100%" :data="items" size="small" class="sn-list" :empty-text="t('common.empty')"
      @row-click="openInfo">
      <el-table-column label="PI" :min-width="w.pi" fixed>
        <template #default="{ row }">
          <span>{{ row.pi_no }}</span>
          <el-tag v-if="row.pi_changed" size="small" type="warning" class="ml">{{ t("query.piChanged") }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('common.material')" :min-width="w.material" fixed><template #default="{ row }">{{ row.material_code || "—" }}</template></el-table-column>
      <el-table-column :label="t('common.sn')" :min-width="w.sn" fixed>
        <template #default="{ row }"><span class="sn-mono">{{ row.sn }}</span></template>
      </el-table-column>
      <el-table-column :label="t('rules.seqDigits')" :min-width="w.seqLen" align="right"><template #default="{ row }">{{ row.seq_len ?? "—" }}</template></el-table-column>
      <el-table-column :label="t('common.seqBase')" :min-width="w.base" align="right"><template #default="{ row }">{{ row.base ?? "—" }}</template></el-table-column>
      <el-table-column :label="t('rules.seqNo')" :min-width="w.seqText"><template #default="{ row }"><span class="sn-mono">{{ row.seq_text }}</span></template></el-table-column>
      <el-table-column :label="t('generate.seqDec')" :min-width="w.seqDec" align="right"><template #default="{ row }"><span class="sn-mono">{{ row.seq_dec || "—" }}</span></template></el-table-column>
      <el-table-column :label="t('common.attachNo')" :min-width="w.attachNo"><template #default="{ row }">{{ row.attach_no || "—" }}</template></el-table-column>
      <el-table-column :label="t('common.length')" :min-width="w.length"><template #default="{ row }">{{ row.length ?? "—" }}</template></el-table-column>
      <el-table-column :label="t('common.unit')" :min-width="w.unit"><template #default="{ row }">{{ row.unit || "—" }}</template></el-table-column>
      <el-table-column :label="t('common.status')" :min-width="w.status">
        <template #default="{ row }">
          <StatusTag :status="row.status" />
          <el-tag v-if="row.suspend" size="small" type="danger" class="ml">{{ suspendLabel(row.suspend) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('query.billSeq')" :min-width="w.billSeq">
        <template #default="{ row }">{{ billSeq(row) }}</template>
      </el-table-column>
      <el-table-column :label="t('query.seqOwner')" :min-width="w.seqOwner">
        <template #default="{ row }">
          <span>{{ row.seq_pi_no }}</span>
          <el-tag v-if="row.transferred_in" size="small" type="info" class="ml">{{ t("query.transferredIn") }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('common.factoryCode')" :min-width="w.factoryCode"><template #default="{ row }">{{ row.factory_code || "—" }}</template></el-table-column>
      <el-table-column :label="t('common.factory')" :min-width="w.factory">
        <template #default="{ row }">{{ nameOf(row.factory_code) }}</template>
      </el-table-column>
      <el-table-column :label="t('common.customer')" :min-width="w.customer"><template #default="{ row }">{{ row.customer_code || "—" }}</template></el-table-column>
      <el-table-column :label="t('query.generatedAt')" :min-width="w.createdAt"><template #default="{ row }">{{ fmt(row.created_at) }}</template></el-table-column>
    </el-table>
  </div>
  <div class="foot">
    <span v-if="state.capped" class="muted">{{ t("common.capped", { n: state.total }) }}</span>
    <span v-else />
    <el-pagination layout="total, sizes, prev, pager, next" :total="state.total" :page-size="state.pageSize"
      :current-page="state.page" :page-sizes="[20, 50, 100, 500]" @current-change="load" @size-change="onSize" />
  </div>

  <!-- 单枚 SN 详情：各列完整列出（长值自动换行），可复制完整 SN -->
  <el-dialog v-model="infoOpen" :title="t('query.snInfo')" width="min(640px, 96vw)" append-to-body>
    <div v-if="info" ref="infoBox">
      <el-descriptions :column="1" border size="small" class="sn-info">
        <el-descriptions-item label="PI">
          {{ info.pi_no }}
          <el-tag v-if="info.pi_changed" size="small" type="warning" class="ml">{{ t("query.piChanged") }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item :label="t('common.material')">{{ info.material_code || "—" }}</el-descriptions-item>
        <el-descriptions-item :label="t('common.sn')">
          <div class="sn-line">
            <span class="sn-mono">{{ info.sn }}</span>
            <el-button size="small" link type="primary" @click="copySn">{{ t("common.copy") }}</el-button>
          </div>
        </el-descriptions-item>
        <el-descriptions-item :label="t('rules.seqDigits')">{{ info.seq_len ?? "—" }}</el-descriptions-item>
        <el-descriptions-item :label="t('common.seqBase')">{{ info.base ?? "—" }}</el-descriptions-item>
        <el-descriptions-item :label="t('rules.seqNo')"><span class="sn-mono">{{ info.seq_text }}</span></el-descriptions-item>
        <el-descriptions-item :label="t('generate.seqDec')"><span class="sn-mono">{{ info.seq_dec || "—" }}</span></el-descriptions-item>
        <el-descriptions-item :label="t('common.attachNo')">{{ info.attach_no || "—" }}</el-descriptions-item>
        <el-descriptions-item :label="t('common.length')">{{ info.length ?? "—" }}</el-descriptions-item>
        <el-descriptions-item :label="t('common.unit')">{{ info.unit || "—" }}</el-descriptions-item>
        <el-descriptions-item :label="t('common.status')">
          <StatusTag :status="info.status" />
          <el-tag v-if="info.suspend" size="small" type="danger" class="ml">{{ suspendLabel(info.suspend) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item :label="t('query.billSeq')">{{ billSeq(info) }}</el-descriptions-item>
        <el-descriptions-item :label="t('query.seqOwner')">
          {{ info.seq_pi_no }}
          <el-tag v-if="info.transferred_in" size="small" type="info" class="ml">{{ t("query.transferredIn") }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item :label="t('common.factoryCode')">{{ info.factory_code || "—" }}</el-descriptions-item>
        <el-descriptions-item :label="t('common.factory')">{{ nameOf(info.factory_code) }}</el-descriptions-item>
        <el-descriptions-item :label="t('common.customer')">{{ info.customer_code || "—" }}</el-descriptions-item>
        <el-descriptions-item :label="t('query.generatedAt')">{{ fmt(info.created_at) }}</el-descriptions-item>
      </el-descriptions>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * SN 明细（F-18）：按组织 / PI / 单据 / 完整 SN / 状态，及客户 / 物料 / 米数 / 流水范围筛选，导出。
 * 列依次为 PI、物料编码、完整 SN、流水位数、进制位数、流水号、十进制流水号、附件项次、米数、产品单位、状态、单据 / 序号、流水归属 PI、
 * 生产组织编号、生产组织、客户编码、生成时间（PI、物料编码、完整 SN 固定在左侧）。
 * 行按 PI、附件项次从小到大（附件项次按数字比，空的排最后；后台在分页前排好）。
 * 各列宽度按本页内容自适应，PI、完整 SN、生产组织等值单行完整显示；点行弹出该枚 SN 的详情（可复制完整 SN）。
 * 只列号本身的信息，不列交付方式、交付批次、推送类型等交付业务信息（交付批次在「交付批次」页签看）。
 */
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { api, urls } from "@/api";
import { useAuthStore } from "@/stores/auth";
import { usePaged } from "@/composables/usePaged";
import { useFactories } from "@/composables/useFactories";
import { useTime } from "@/composables/useTime";
import { STATUS_CODES, statusLabel, suspendLabel } from "@/constants";
import { downloadWithToken } from "@/utils/download";
import { copyText } from "@/utils/clipboard";
import { colWidth, tagWidth, textWidth } from "@/colWidth.js";
import FactorySelect from "@/components/FactorySelect.vue";
import StatusTag from "@/components/StatusTag.vue";
import { trimmed } from "./filters";

const props = defineProps<{ init: { factory_code: string; pi: string; bill_no: string } }>();

const { t } = useI18n();
const store = useAuthStore();
const { nameOf } = useFactories();
const { fmt } = useTime();

const blank = () => ({
  factory_code: "", pi: "", customer_code: "", bill_no: "", material_code: "", length: "",
  status: "", sn: "", seq_from: "", seq_to: "", pi_all: false,
});
const f = reactive({ ...blank(), ...props.init });
const exporting = ref(false);
const params = () => ({ ...trimmed(f), pi_all: f.pi_all || undefined });

type RangeSummary = { found: number; expected: number | null; missing: number | null; by_status: Record<string, number> };
const range = ref<RangeSummary | null>(null);
const hasRange = computed(() => !!(f.seq_from.trim() || f.seq_to.trim()));
const { items, state, load, onSize } = usePaged<any>(async (q) => {
  range.value = null;
  const r = await api.items({ ...params(), ...q });
  range.value = r.range ?? null;
  return r;
});

const billSeq = (r: any) => `${r.bill_no} / ${r.bill_seq}`;
/** 行内标签与前面文字的间距（.ml）。 */
const TAG_GAP = 4;
const tagAfter = (text: string | false | null | undefined) => (text ? TAG_GAP + tagWidth(text) : 0);

/** 各列宽度：按本页各行内容量出（PI、完整 SN、生产组织不设上限，保证完整显示）。 */
const w = computed(() => {
  const rows = items.value;
  const col = (label: string, f: (r: any) => number, min = 0) => colWidth(label, rows.map(f), { min });
  const mono = (v: unknown) => textWidth(v as string, { mono: true });
  return {
    pi: col("PI", (r) => textWidth(r.pi_no) + tagAfter(r.pi_changed && t("query.piChanged")), 100),
    material: col(t("common.material"), (r) => textWidth(r.material_code || "—")),
    sn: col(t("common.sn"), (r) => mono(r.sn), 140),
    seqLen: col(t("rules.seqDigits"), (r) => textWidth(String(r.seq_len ?? "—"))),
    base: col(t("common.seqBase"), (r) => textWidth(String(r.base ?? "—"))),
    seqText: col(t("rules.seqNo"), (r) => mono(r.seq_text)),
    seqDec: col(t("generate.seqDec"), (r) => mono(r.seq_dec || "—")),
    attachNo: col(t("common.attachNo"), (r) => textWidth(r.attach_no || "—")),
    length: col(t("common.length"), (r) => textWidth(r.length ?? "—")),
    unit: col(t("common.unit"), (r) => textWidth(r.unit || "—")),
    status: col(t("common.status"), (r) => tagWidth(statusLabel(r.status)) + tagAfter(r.suspend && suspendLabel(r.suspend))),
    billSeq: col(t("query.billSeq"), (r) => textWidth(billSeq(r))),
    seqOwner: col(t("query.seqOwner"), (r) => textWidth(r.seq_pi_no) + tagAfter(r.transferred_in && t("query.transferredIn"))),
    factoryCode: col(t("common.factoryCode"), (r) => textWidth(r.factory_code || "—")),
    factory: col(t("common.factory"), (r) => textWidth(nameOf(r.factory_code))),
    customer: col(t("common.customer"), (r) => textWidth(r.customer_code || "—")),
    createdAt: col(t("query.generatedAt"), (r) => textWidth(fmt(r.created_at))),
  };
});

const info = ref<any>(null);
const infoOpen = ref(false);
const infoBox = ref<HTMLElement | null>(null);

function openInfo(row: any) {
  // 选中文字（如拖选复制 SN）时不弹窗
  if (window.getSelection()?.toString()) return;
  info.value = row;
  infoOpen.value = true;
}

async function copySn() {
  if (!info.value) return;
  // 弹窗内调用须把临时文本框放进弹窗（见 copyText 说明）
  if (await copyText(info.value.sn, infoBox.value ?? undefined)) ElMessage.success(t("common.copied"));
  else ElMessage.error(t("common.copyFailed"));
}

watch(() => f.pi, (v) => {
  if (!v.trim()) f.pi_all = false;
});

function reset() {
  Object.assign(f, blank());
  load(1);
}

/** 超过导出上限只导出前 N 行，先提示。 */
async function exportSn() {
  exporting.value = true;
  try {
    const p = params();
    const c = await api.snExportCheck(p);
    if (c.truncated) {
      try {
        await ElMessageBox.confirm(t("common.exportTruncated", { limit: c.limit }), t("common.export"), { type: "warning" });
      } catch {
        return;
      }
    }
    await downloadWithToken(urls.snExport(p), "sn_export.xlsx");
  } catch (e: any) {
    if (e?.message) ElMessage.error(e.message);
  } finally {
    exporting.value = false;
  }
}

onMounted(() => load(1));
</script>

<style scoped>
.mb { margin-bottom: 12px; }
.ml { margin-left: 4px; }
.w120 { width: 120px !important; }
.w240 { width: 240px !important; }
.push { margin-left: auto; }
.seq-range { display: flex; align-items: center; gap: 6px; }
.seq-range .el-input { width: 150px; }
.seq-range__label { color: var(--el-text-color-regular); white-space: nowrap; }
.range-sum { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 16px; padding: 8px 12px; border-radius: 4px; background: var(--el-fill-color-light); font-size: 13px; }
.range-sum b { font-variant-numeric: tabular-nums; }
.range-sum__note { color: var(--el-text-color-secondary); }
/* 列宽按内容算好（w），单元格单行完整显示 */
.sn-list :deep(.cell) { white-space: nowrap; text-overflow: clip; }
.sn-list :deep(.el-table__row) { cursor: pointer; }
.sn-info :deep(.el-descriptions__label) { width: 120px; white-space: nowrap; }
.sn-info :deep(.el-descriptions__content) { word-break: break-all; }
.sn-line { display: flex; align-items: flex-start; gap: 8px; }
.sn-line .sn-mono { flex: 1; }
.foot { display: flex; justify-content: space-between; align-items: center; margin-top: 12px; }
</style>
