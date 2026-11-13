<template>
  <el-tabs model-value="sn" class="ap">
    <!-- 页签头（右侧头部左侧）：单据编号 + 单据所属的生产组织名称（小号加粗） -->
    <el-tab-pane name="sn">
      <template #label>
        <span class="ap-title">{{ bill }}<b v-if="billOrg" class="bill-org">{{ billOrg }}</b></span>
      </template>
      <div class="ap-body">
        <!-- 头部筛选：物料编码、SN 起止号（含两端，可只填一端）；不筛选时列出该单据的全部已分配号 -->
        <div class="toolbar">
          <el-select v-model="material" size="small" class="w-mat" :placeholder="t('common.material')" clearable @change="sns.load(1)">
            <el-option v-for="mc in materials" :key="mc" :value="mc" :label="mc" />
          </el-select>
          <el-input v-model="snFrom" size="small" class="w-sn" :placeholder="t('export.snFrom')" clearable @keyup.enter="sns.load(1)" @clear="sns.load(1)" />
          <span class="muted">~</span>
          <el-input v-model="snTo" size="small" class="w-sn" :placeholder="t('export.snTo')" clearable @keyup.enter="sns.load(1)" @clear="sns.load(1)" />
          <el-button size="small" type="primary" @click="sns.load(1)">{{ t("common.search") }}</el-button>
          <el-button size="small" @click="resetFilter">{{ t("common.reset") }}</el-button>
          <span class="sp" />
          <el-tooltip :content="t('export.rangeHint')" placement="top">
            <el-button size="small" :loading="downloading" :disabled="!sns.state.total" @click="download">xlsx</el-button>
          </el-tooltip>
        </div>

        <!-- 每页条数按高度算，正好放满，不出滚动条；PI 列宽按内容量出，完整显示；
             物料编码前为附件项次、后为 ERP行号；流水号后为十进制流水号；行按附件项次从小到大（后台排好，与下载文件同一顺序） -->
        <div ref="box" class="ap-table">
          <el-table v-loading="sns.state.loading" :data="sns.items.value" size="small" class="fit-table" :empty-text="t('common.empty')">
            <el-table-column prop="pi_no" label="PI" :min-width="piW" />
            <el-table-column :label="t('common.attachNo')" min-width="66" show-overflow-tooltip><template #default="{ row }">{{ row.attach_no || "—" }}</template></el-table-column>
            <el-table-column prop="material_code" :label="t('common.material')" min-width="86" show-overflow-tooltip />
            <el-table-column prop="bill_seq" :label="t('common.lineNo')" width="72" />
            <el-table-column :label="t('common.sn')" min-width="170" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="sn-mono">{{ row.sn }}</span>
                <el-tag v-if="row.transferred_in" size="small" type="info" effect="plain" class="ml">{{ t("export.seqOf", { pi: row.seq_pi_no }) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('rules.seqDigits')" min-width="66" align="right"><template #default="{ row }">{{ row.seq_len ?? "—" }}</template></el-table-column>
            <el-table-column :label="t('common.seqBase')" min-width="66" align="right"><template #default="{ row }">{{ row.base ?? "—" }}</template></el-table-column>
            <el-table-column :label="t('rules.seqNo')" min-width="76"><template #default="{ row }"><span class="sn-mono">{{ row.seq_text }}</span></template></el-table-column>
            <el-table-column :label="t('generate.seqDec')" min-width="96" align="right"><template #default="{ row }"><span class="sn-mono">{{ row.seq_dec ?? "—" }}</span></template></el-table-column>
            <el-table-column :label="t('common.length')" min-width="56" show-overflow-tooltip><template #default="{ row }">{{ row.length || "—" }}</template></el-table-column>
            <el-table-column :label="t('common.unit')" min-width="66" show-overflow-tooltip><template #default="{ row }">{{ row.unit || "—" }}</template></el-table-column>
            <el-table-column prop="factory_code" :label="t('common.factoryCode')" min-width="90" show-overflow-tooltip />
            <el-table-column prop="factory_name" :label="t('common.factory')" min-width="100" show-overflow-tooltip />
          </el-table>
        </div>
        <el-pagination class="pager" small layout="total, prev, pager, next" :total="sns.state.total" :page-size="sns.state.pageSize"
          :current-page="sns.state.page" @current-change="sns.load" />
      </div>
    </el-tab-pane>
  </el-tabs>
</template>

<script setup lang="ts">
/** SN 分配页「已分配」页签的右侧（F-14 SN 导出）：左侧点一张单据后，逐枚列出该单据记载的已分配号（PI、物料编码、完整 SN、
 *  流水位数、进制位数（规则进制 10 / 16 / 32 / 36）、流水号、十进制流水号、附件项次、米数、产品单位、生产组织编号、生产组织），
 *  按附件项次从小到大（项次相同按单据序号、流水），头部按物料编码、SN 起止号筛选，分页；可按同一筛选下载范围内的号（xlsx，
 *  列与 SN 导入模板相同，比明细多单据编号列），只读、可反复下载。
 *  与分配后的推送、拉取相互独立：推送成败都不影响列出和下载。 */
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import { api, urls } from "@/api";
import { useFactories } from "@/composables/useFactories";
import { useFitRows } from "@/composables/useFitRows";
import { colWidth, textWidth } from "@/colWidth.js";
import { usePaged } from "@/composables/usePaged";
import { downloadWithToken } from "@/utils/download";

interface SnRow {
  line_no: number;
  pi_no: string;
  factory_code: string;
  factory_name: string;
  sn: string;
  material_code: string;
  bill_seq: number;
  seq_pi_no: string;
  transferred_in: boolean;
  base: number | null;
  seq_len: number | null;
  seq_text: string;
  seq_dec: number | null;
  attach_no: string;
  length: string;
  unit: string;
}

/** pi、bill：左侧所选单据。 */
const props = defineProps<{ pi: string; bill: string }>();

const { t } = useI18n();
const { nameOf } = useFactories();

/** 单据所属的生产组织名称：取分配详情物料行上的 bill_org_name（同一 PI 只取一次）；取不到时不显示。 */
const billOrg = ref("");
let orgPi = "";
let orgMap = new Map<string, string>();
async function loadBillOrg() {
  const pi = props.pi;
  if (pi !== orgPi) {
    billOrg.value = "";
    try {
      const c: any = await api.allocContext(pi);
      if (pi !== props.pi) return;
      orgMap = new Map((c.lines ?? []).map((l: any) => [l.bill_no, l.bill_org_name || (l.bill_org_code ? nameOf(l.bill_org_code) : "")]));
      orgPi = pi;
    } catch {
      return;
    }
  }
  billOrg.value = orgMap.get(props.bill) ?? "";
}

/** 筛选：物料编码（空 = 全部）、SN 起止号（含两端，可只填一端；须是本单据已分配的号）。 */
const material = ref("");
const snFrom = ref("");
const snTo = ref("");
const materials = ref<string[]>([]);

const sns = usePaged<SnRow>((q) =>
  api.exportItems({
    ...q, pi: props.pi, bill_no: props.bill,
    material: material.value || undefined, sn_from: snFrom.value.trim(), sn_to: snTo.value.trim(),
  }), 20);

const box = ref<HTMLElement | null>(null);
const { rows } = useFitRows(box, (n) => {
  sns.state.pageSize = n;
  sns.load(1);
});
const ready = computed(() => rows.value > 0);

/** PI 列宽：按本单据 PI（及本页各行）量出，PI 完整显示、不截断。 */
const piW = computed(() => colWidth("PI", [props.pi, ...sns.items.value.map((r) => r.pi_no)].map((v) => textWidth(v))));

function resetFilter() {
  material.value = "";
  snFrom.value = "";
  snTo.value = "";
  sns.load(1);
}

async function open() {
  loadBillOrg();
  material.value = "";
  snFrom.value = "";
  snTo.value = "";
  materials.value = [];
  if (ready.value) sns.load(1);
  const got: { material_code: string }[] = await api.exportLines({ pi: props.pi, bill_no: props.bill });
  materials.value = [...new Set(got.map((l) => l.material_code).filter(Boolean))];
}

const downloading = ref(false);
async function download() {
  downloading.value = true;
  try {
    // 与明细同一组筛选：只下载物料编码、SN 起止号范围内的号（不筛选时为整张单据）
    const params = {
      pi: props.pi, bill_no: props.bill,
      material: material.value || undefined, sn_from: snFrom.value.trim() || undefined, sn_to: snTo.value.trim() || undefined,
    };
    await downloadWithToken(urls.exportFile(params), `SN_${props.pi}_${props.bill}.xlsx`);
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    downloading.value = false;
  }
}

watch(() => [props.pi, props.bill], open, { immediate: true });

defineExpose({ load: () => sns.load() });
</script>

<style scoped>
.ap { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.ap :deep(.el-tabs__content) { flex: 1; min-height: 0; }
.ap :deep(.el-tab-pane) { height: 100%; }
.ap-body { height: 100%; display: flex; flex-direction: column; }
.ap-table { flex: 1; min-height: 240px; overflow: hidden; }
.ml { margin-left: 6px; }
.ap-title { display: inline-flex; align-items: baseline; gap: 8px; font-weight: 600; }
.bill-org { font-size: 12px; font-weight: 700; white-space: nowrap; }
.sp { flex: 1; }
.toolbar { flex-wrap: nowrap; }
.w-mat { width: 160px !important; flex: none; }
.w-sn { width: 180px !important; flex: none; }
</style>
