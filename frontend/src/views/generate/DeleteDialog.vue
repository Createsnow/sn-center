<template>
  <el-dialog v-model="visible" :title="t('generate.deleteTitle')" width="min(560px, 96vw)">
    <!-- 按所选物料行删（打开即列出）或按 SN 起止号删（填起止号，只填起号 = 单枚，查出后再确认）；有所选行时可切换 -->
    <el-radio-group v-if="lines.length" :model-value="mode" class="modes" @update:model-value="(v: any) => switchMode(v)">
      <el-radio-button value="LINES">{{ t("generate.deleteByLines", { n: lines.length }) }}</el-radio-button>
      <el-radio-button value="SN">{{ t("generate.deleteBySn") }}</el-radio-button>
    </el-radio-group>
    <div v-if="mode === 'SN'" class="sn-row">
      <el-input v-model="startSn" :placeholder="t('generate.deleteStartSn')" clearable class="sn-mono" @change="reset" />
      <span class="muted">~</span>
      <el-input v-model="endSn" :placeholder="t('generate.deleteEndSn')" clearable class="sn-mono" @change="reset" />
      <el-button :loading="loading" :disabled="!startSn.trim()" @click="load">{{ t("common.search") }}</el-button>
    </div>
    <div v-if="info" v-loading="loading" class="info">
      <p class="lead">{{ t("generate.deleteLead", { n: num(info.qty), lines: info.lines.length }) }}</p>
      <div class="tags">
        <span v-for="(n, s) in info.by_status" :key="s" class="st"><StatusTag :status="String(s)" /> <b class="num">{{ num(n) }}</b></span>
      </div>
      <p v-if="info.orgs.length" class="muted">{{ t("common.factory") }}：{{ info.orgs.map((o: string) => nameOf(o)).join("、") }}</p>
      <p v-if="info.transferred_in" class="muted">{{ t("generate.deleteTransferredIn", { n: num(info.transferred_in) }) }}</p>
      <p v-if="info.batches.length" class="warn">{{ t("generate.deleteVoidBatches", { batches: info.batches.join("、") }) }}</p>
      <p class="muted">{{ t("generate.deleteKeepMax") }}</p>
      <el-input v-model="reason" maxlength="500" show-word-limit :placeholder="t('generate.deleteReason')" />
    </div>
    <template #footer>
      <el-button @click="visible = false">{{ t("common.cancel") }}</el-button>
      <el-button type="danger" :loading="busy" :disabled="!info?.qty" @click="submit">
        {{ t("generate.deleteBtn", { n: num(info?.qty || 0) }) }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/** 删号：按物料行 / 单据（打开时给出），或按 SN 起止号（弹窗里填）。先取确认框数据（枚数、各状态枚数、生产组织、
 *  要作废重建的交付批次）再确认；原因选填（不填后端记默认原因）。号的状态不限；最大流水号不退回，删掉的号留下空号段。
 *  父组件用 ref 调 open({ lines }) 或 open({ sn: true })，完成后收到 done（带删后的空号段）。 */
import { ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import { api } from "@/api";
import StatusTag from "@/components/StatusTag.vue";
import { useFactories } from "@/composables/useFactories";
import { num } from "./format";

type LineRef = { bill_no: string; seq: number };

const props = defineProps<{ pi: string }>();
const emit = defineEmits<{ done: [result: any] }>();

const { t } = useI18n();
const { nameOf } = useFactories();
const visible = ref(false);
const mode = ref<"LINES" | "SN">("LINES");
const lines = ref<LineRef[]>([]);
const startSn = ref("");
const endSn = ref("");
const reason = ref("");
const info = ref<any>(null);
const loading = ref(false);
const busy = ref(false);

function body() {
  return mode.value === "SN"
    ? { pi_no: props.pi, start_sn: startSn.value.trim(), end_sn: endSn.value.trim() || null }
    : { pi_no: props.pi, lines: lines.value };
}

function reset() {
  info.value = null;
}

async function load() {
  loading.value = true;
  try {
    info.value = await api.genDeletePreview(body());
  } catch {
    info.value = null;
  } finally {
    loading.value = false;
  }
}

function switchMode(m: "LINES" | "SN") {
  mode.value = m;
  info.value = null;
  if (m === "LINES") load();
}

/** lines 为空或给了 sn 时按 SN 起止号删；否则按所选物料行删（可切到按 SN 起止号）。 */
function open(opts: { lines?: LineRef[]; sn?: boolean }) {
  lines.value = (opts.lines || []).map((x) => ({ bill_no: x.bill_no, seq: x.seq }));
  mode.value = opts.sn || !lines.value.length ? "SN" : "LINES";
  startSn.value = "";
  endSn.value = "";
  reason.value = "";
  info.value = null;
  visible.value = true;
  if (mode.value === "LINES") load();
}

async function submit() {
  busy.value = true;
  try {
    const r = await api.genDelete({ ...body(), reason: reason.value.trim() || null });
    ElMessage.success(t("generate.deletedN", { n: num(r.qty), no: r.del_no }));
    visible.value = false;
    emit("done", r);
  } finally {
    busy.value = false;
  }
}

defineExpose({ open });
</script>

<style scoped>
.modes { margin-bottom: 12px; }
.sn-row { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.info { display: flex; flex-direction: column; gap: 8px; }
.info p { margin: 0; }
.lead { font-weight: 600; }
.tags { display: flex; flex-wrap: wrap; gap: 6px 14px; }
.st { display: inline-flex; align-items: center; gap: 4px; }
.warn { color: var(--el-color-warning-dark-2); }
.sn-mono :deep(input) { font-family: var(--app-mono, monospace); }
</style>
