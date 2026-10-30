<template>
  <el-tabs model-value="sn" class="ap">
    <el-tab-pane :label="t('allocate.snDetail', { bill })" name="sn">
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
          <el-button size="small" :loading="downloading" @click="download">xlsx</el-button>
        </div>

        <!-- 每页条数按高度算，正好放满，不出滚动条 -->
        <div ref="box" class="ap-table">
          <el-table v-loading="sns.state.loading" :data="sns.items.value" size="small" class="fit-table" :empty-text="t('common.empty')">
            <el-table-column :label="t('export.lineNo')" width="80"><template #default="{ row }">{{ row.line_no }}</template></el-table-column>
            <el-table-column :label="t('common.sn')" min-width="220">
              <template #default="{ row }">
                <span class="sn-mono">{{ row.sn }}</span>
                <el-tag v-if="row.transferred_in" size="small" type="info" effect="plain" class="ml">{{ t("export.seqOf", { pi: row.seq_pi_no }) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="material_code" :label="t('common.material')" min-width="120" />
            <el-table-column :label="t('rules.base')" width="80" align="right"><template #default="{ row }">{{ row.base ?? "—" }}</template></el-table-column>
            <el-table-column :label="t('common.seqText')" width="130"><template #default="{ row }"><span class="sn-mono">{{ row.seq_text }}</span></template></el-table-column>
            <el-table-column prop="seq_dec" :label="t('common.seqDec')" width="120" align="right" />
          </el-table>
        </div>
        <el-pagination class="pager" small layout="total, prev, pager, next" :total="sns.state.total" :page-size="sns.state.pageSize"
          :current-page="sns.state.page" @current-change="sns.load" />
      </div>
    </el-tab-pane>
  </el-tabs>
</template>

<script setup lang="ts">
/** SN 分配页「已分配」页签的右侧（F-14 SN 导出）：左侧点一张单据后，逐枚列出该单据已分配的号（SN、物料、进制、
 *  进制流水、十进制流水），头部按物料编码、SN 起止号筛选，分页；可下载该单据的 xlsx，只读、可反复下载。
 *  与分配后的推送、拉取相互独立：推送成败都不影响列出和下载。 */
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import { api, urls } from "@/api";
import { useFitRows } from "@/composables/useFitRows";
import { usePaged } from "@/composables/usePaged";
import { downloadWithToken } from "@/utils/download";

interface SnRow {
  line_no: number;
  sn: string;
  material_code: string;
  seq_pi_no: string;
  transferred_in: boolean;
  base: number | null;
  seq_text: string;
  seq_dec: number;
}

/** pi、bill：左侧所选单据。 */
const props = defineProps<{ pi: string; bill: string }>();

const { t } = useI18n();

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

function resetFilter() {
  material.value = "";
  snFrom.value = "";
  snTo.value = "";
  sns.load(1);
}

async function open() {
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
    await downloadWithToken(urls.exportFile({ pi: props.pi, bill_no: props.bill }), `SN_${props.pi}_${props.bill}.xlsx`);
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
.sp { flex: 1; }
.toolbar { flex-wrap: nowrap; }
.w-mat { width: 160px !important; flex: none; }
.w-sn { width: 180px !important; flex: none; }
</style>
