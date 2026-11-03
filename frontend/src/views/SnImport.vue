<template>
  <div class="page">
    <PageHead :title="t('snImport.title')" :desc="t('snImport.desc')">
      <el-button :loading="downloading" @click="downloadTemplate"><el-icon><Download /></el-icon><span>{{ t("snImport.template") }}</span></el-button>
      <el-button type="primary" @click="openDialog"><el-icon><Upload /></el-icon><span>{{ t("snImport.import") }}</span></el-button>
    </PageHead>

    <!-- ================= 已导入的号（新的在前） ================= -->
    <div class="surface">
      <div class="toolbar">
        <el-input v-model="lq.pi" placeholder="PI" clearable @keyup.enter="list.load(1)" @clear="list.load(1)" />
        <el-input v-model="lq.bill_no" :placeholder="t('common.billNo')" clearable @keyup.enter="list.load(1)" @clear="list.load(1)" />
        <el-input v-model="lq.sn" :placeholder="t('snImport.snPrefix')" clearable @keyup.enter="list.load(1)" @clear="list.load(1)" />
        <el-input v-model="lq.import_no" :placeholder="t('snImport.importNo')" clearable @keyup.enter="list.load(1)" @clear="list.load(1)" />
        <el-button type="primary" @click="list.load(1)">{{ t("common.search") }}</el-button>
      </div>
      <el-table v-loading="list.state.loading" :data="list.items.value" size="small" :empty-text="t('snImport.empty')">
        <el-table-column prop="pi_no" label="PI" min-width="100" show-overflow-tooltip />
        <el-table-column prop="bill_no" :label="t('common.billNo')" min-width="120" show-overflow-tooltip />
        <el-table-column :label="t('common.sn')" min-width="170">
          <template #default="{ row }"><span class="sn-mono">{{ row.sn }}</span></template>
        </el-table-column>
        <el-table-column :label="t('common.factory')" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ nameOf(row.factory_code) || "—" }}<span v-if="nameOf(row.factory_code) !== row.factory_code" class="muted">（{{ row.factory_code }}）</span></template>
        </el-table-column>
        <el-table-column :label="t('snImport.importNo')" min-width="190">
          <template #default="{ row }">
            <el-button link type="primary" class="sn-mono" @click="byImportNo(row.import_no)">{{ row.import_no }}</el-button>
          </template>
        </el-table-column>
        <el-table-column :label="t('snImport.fileRow')" min-width="100" show-overflow-tooltip>
          <template #default="{ row }">{{ row.file_name || "—" }} / {{ row.row_no }}</template>
        </el-table-column>
        <el-table-column :label="t('snImport.importedBy')" width="140">
          <template #default="{ row }"><div>{{ row.created_by }}</div><div class="muted">{{ fmt(row.created_at) }}</div></template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" layout="total, sizes, prev, pager, next" :total="list.state.total" :page-size="list.state.pageSize"
        :current-page="list.state.page" :page-sizes="[20, 50, 100]" @current-change="list.load" @size-change="list.onSize" />
    </div>

    <!-- ================= 导入：选文件即检查，没有问题行才能确定导入 ================= -->
    <el-dialog v-model="dlg.visible" :title="t('snImport.dialogTitle')" width="760px" :close-on-click-modal="false" destroy-on-close>
      <el-upload ref="uploader" drag accept=".xlsx" :auto-upload="false" :show-file-list="false" :on-change="onPick">
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div>{{ dlg.file ? dlg.file.name : t("snImport.drop") }}</div>
      </el-upload>

      <div v-if="dlg.checking" class="muted result">{{ t("snImport.checking") }}</div>
      <template v-else-if="dlg.result">
        <div class="result">
          <el-tag :type="dlg.result.importable ? 'success' : 'danger'" effect="plain">
            {{ t("snImport.summary", { total: dlg.result.total, ok: dlg.result.ok, bad: dlg.result.bad }) }}
          </el-tag>
          <span class="muted ml">{{ t("snImport.scope", { pis: dlg.result.pis, bills: dlg.result.bills }) }}</span>
        </div>
        <template v-if="dlg.result.issues.length">
          <el-table :data="dlg.result.issues" size="small" max-height="320">
            <el-table-column prop="row" :label="t('snImport.rowNo')" width="70" align="right" />
            <el-table-column prop="pi" label="PI" min-width="110" show-overflow-tooltip />
            <el-table-column prop="bill_no" :label="t('common.billNo')" min-width="120" show-overflow-tooltip />
            <el-table-column :label="t('common.sn')" min-width="150" show-overflow-tooltip>
              <template #default="{ row }"><span class="sn-mono">{{ row.sn }}</span></template>
            </el-table-column>
            <el-table-column :label="t('snImport.problem')" min-width="220">
              <template #default="{ row }">
                <div v-for="(x, i) in row.issues" :key="i" class="bad">{{ t(`snImport.issue.${x.code}`, x.params) }}</div>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="dlg.result.bad > dlg.result.issues.length" class="muted listed">{{ t("snImport.listed", { n: dlg.result.issues.length }) }}</div>
        </template>
      </template>

      <template #footer>
        <el-button @click="dlg.visible = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" :loading="dlg.running" :disabled="!dlg.file || !dlg.result?.importable" @click="confirmImport">
          {{ t("snImport.confirm") }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/** SN 导入：系统之外已有的 SN 按「PI + 单据编号 + SN + 生产组织」登记（表头固定）；之后生成撞上这些号整次回滚，不会重复出号。
 *  选文件后先由后端检查（不落库，逐行列出不能导入的原因），没有问题行才能确定导入；确定时后端再检查一遍，
 *  有一行不能导入整个文件都不导入。导入的号不进号池，不参与分配、交付与转移。 */
import { onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, type UploadFile, type UploadInstance } from "element-plus";
import { api, urls } from "@/api";
import PageHead from "@/components/PageHead.vue";
import { useFactories } from "@/composables/useFactories";
import { usePaged } from "@/composables/usePaged";
import { useTime } from "@/composables/useTime";
import { downloadWithToken } from "@/utils/download";

type Issue = { row: number; pi: string; bill_no: string; sn: string; issues: { code: string; params: Record<string, unknown> }[] };
type CheckResult = { total: number; ok: number; bad: number; pis: number; bills: number; importable: boolean; issues: Issue[] };

const { t } = useI18n();
const { nameOf } = useFactories();
const { fmt } = useTime();

// ---------------- 已导入的号
const lq = reactive({ pi: "", bill_no: "", sn: "", import_no: "" });
const list = usePaged<any>((q) =>
  api.snImportItems({ ...q, pi: lq.pi.trim(), bill_no: lq.bill_no.trim(), sn: lq.sn.trim(), import_no: lq.import_no.trim() }),
);

function byImportNo(no: string) {
  Object.assign(lq, { pi: "", bill_no: "", sn: "", import_no: no });
  list.load(1);
}

const downloading = ref(false);
async function downloadTemplate() {
  downloading.value = true;
  try {
    await downloadWithToken(urls.snImportTemplate(), "SN导入模板.xlsx");
  } catch (e) {
    ElMessage.error((e as Error).message);
  } finally {
    downloading.value = false;
  }
}

// ---------------- 导入弹窗
const uploader = ref<UploadInstance | null>(null);
const dlg = reactive({
  visible: false,
  file: null as File | null,
  checking: false,
  running: false,
  result: null as CheckResult | null,
});

function openDialog() {
  Object.assign(dlg, { visible: true, file: null, checking: false, running: false, result: null });
}

/** 选了文件就检查；再选一个文件替换上一个。 */
async function onPick(f: UploadFile) {
  uploader.value?.clearFiles();
  if (!f.raw) return;
  dlg.file = f.raw;
  dlg.result = null;
  dlg.checking = true;
  try {
    const picked = f.raw;
    const r = await api.snImportCheck(picked);
    if (dlg.file === picked) dlg.result = r;
  } catch {
    dlg.file = null;
  } finally {
    dlg.checking = false;
  }
}

async function confirmImport() {
  if (!dlg.file) return;
  dlg.running = true;
  try {
    const r = await api.snImportRun(dlg.file, dlg.file.name);
    ElMessage.success(t("snImport.done", { qty: r.qty, no: r.import_no }));
    dlg.visible = false;
    byImportNo(r.import_no);
  } catch {
    // 确定时重新检查发现问题（别人刚生成或导入了同样的号）：重新检查一遍，列出原因
    const picked = dlg.file;
    if (picked) dlg.result = await api.snImportCheck(picked).catch(() => dlg.result);
  } finally {
    dlg.running = false;
  }
}

onMounted(() => list.load(1));
</script>

<style scoped>
.ml { margin-left: 8px; }
.toolbar :deep(.el-input) { width: 170px; }
.result { margin: 12px 0 8px; }
.listed { margin-top: 6px; }
.bad { color: var(--el-color-danger); }
</style>
