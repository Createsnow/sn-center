<template>
  <div class="page">
    <PageHead :title="t('piInit.title')" :desc="t('piInit.desc')" />
    <div class="surface">
      <div class="toolbar">
        <el-input v-model="pi" :placeholder="t('piInit.piPlaceholder')" clearable class="w300" @keyup.enter="lookup" />
        <el-button type="primary" :loading="loading" @click="lookup">{{ t("common.search") }}</el-button>
      </div>
      <template v-if="status">
        <el-descriptions :column="3" size="small" border>
          <el-descriptions-item label="PI"><b>{{ status.counter.pi_no }}</b></el-descriptions-item>
          <el-descriptions-item :label="t('piInit.inSnapshot')">
            <el-tag :type="status.in_snapshot ? 'success' : 'warning'" size="small">{{ status.in_snapshot ? t("common.yes") : t("common.no") }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('common.customer')">{{ status.customer_code || "—" }}</el-descriptions-item>
          <el-descriptions-item :label="t('generate.lastSeq')"><span class="num">{{ status.counter.last_seq_dec }}</span></el-descriptions-item>
          <el-descriptions-item :label="t('generate.piGenerated')"><span class="num">{{ status.counter.generated_qty }}</span></el-descriptions-item>
          <el-descriptions-item :label="t('generate.piImported')"><span class="num">{{ status.counter.imported_qty }}</span></el-descriptions-item>
          <el-descriptions-item :label="t('generate.rule')" :span="3">
            <span v-if="status.rule" class="sn-mono">{{ status.rule.rule_code }} v{{ status.rule.version }} · {{ status.rule.sample_sn }}</span>
            <span v-else class="muted">{{ t("piInit.noRule") }}</span>
          </el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="!status.start_allowed" class="mt" type="info" show-icon :closable="false"
          :title="t('piInit.locked', { start: status.counter.start_seq_dec ?? '—' })" />
      </template>
    </div>

    <div v-if="status?.start_allowed" class="surface">
      <div class="surface__title">{{ t("piInit.formTitle") }}</div>
      <el-form label-position="top" class="field-stack">
        <el-form-item :label="t('generate.start')">
          <el-input v-model="startText" clearable :placeholder="t('piInit.startPlaceholder')" />
          <div class="muted">{{ t("piInit.startHint") }}</div>
        </el-form-item>
        <el-form-item :label="t('piInit.snList')">
          <el-input v-model="snText" type="textarea" :rows="8" :placeholder="t('piInit.snPlaceholder')" />
          <div class="file-row">
            <el-upload :auto-upload="false" :show-file-list="false" accept=".txt,.csv" :on-change="onFile">
              <el-button size="small"><el-icon><Upload /></el-icon><span>{{ t("piInit.upload") }}</span></el-button>
            </el-upload>
            <span class="muted">{{ t("piInit.parsed", { n: parsed.sns.length }) }}</span>
            <el-tag v-if="parsed.duplicates.length" type="danger" size="small">{{ t("piInit.dups", { n: parsed.duplicates.length }) }}</el-tag>
          </div>
        </el-form-item>
        <el-form-item :label="t('piInit.factory')">
          <FactorySelect v-model="factoryCode" clearable />
        </el-form-item>
        <el-form-item :label="t('piInit.customer')">
          <el-input v-model="customer" :placeholder="status.customer_code || ''" clearable />
        </el-form-item>
        <el-form-item :label="t('piInit.material')">
          <el-input v-model="material" clearable />
        </el-form-item>
      </el-form>
      <el-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="submit">{{ t("piInit.submit") }}</el-button>
    </div>

    <div v-if="status?.imports?.length" class="surface">
      <div class="surface__title">{{ t("piInit.history") }}</div>
      <el-table :data="status.imports" size="small">
        <el-table-column prop="start_seq_dec" :label="t('generate.start')" />
        <el-table-column prop="import_qty" :label="t('piInit.importQty')" />
        <el-table-column prop="max_seq_dec" :label="t('piInit.maxSeq')" />
        <el-table-column prop="factory_code" :label="t('common.factory')" />
        <el-table-column prop="file_name" :label="t('piInit.file')" />
        <el-table-column prop="created_by" :label="t('common.operator')" />
        <el-table-column prop="created_at" :label="t('common.time')" width="170" />
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox, type UploadFile } from "element-plus";
import { api } from "@/api";
import { parseSnList } from "@/snList.js";
import PageHead from "@/components/PageHead.vue";
import FactorySelect from "@/components/FactorySelect.vue";

const { t } = useI18n();
const pi = ref("");
const status = ref<any>(null);
const loading = ref(false);
const startText = ref("");
const snText = ref("");
const fileName = ref("");
const factoryCode = ref("");
const customer = ref("");
const material = ref("");
const submitting = ref(false);

const parsed = computed(() => parseSnList(snText.value));
const startSeq = computed(() => (startText.value.trim() ? Number(startText.value.trim()) : null));
const canSubmit = computed(() => (startSeq.value !== null || parsed.value.sns.length > 0) && !parsed.value.duplicates.length);

async function lookup() {
  if (!pi.value.trim()) return;
  loading.value = true;
  try {
    status.value = await api.piStatus(pi.value.trim());
  } finally {
    loading.value = false;
  }
}

function onFile(file: UploadFile) {
  if (!file.raw) return;
  fileName.value = file.name;
  const reader = new FileReader();
  reader.onload = () => {
    snText.value = String(reader.result || "");
  };
  reader.readAsText(file.raw, "utf-8");
}

async function submit() {
  if (startSeq.value !== null && (!Number.isInteger(startSeq.value) || startSeq.value < 1)) {
    ElMessage.error(t("generate.startInvalid"));
    return;
  }
  await ElMessageBox.confirm(
    t("piInit.confirm", { pi: status.value.counter.pi_no, start: startSeq.value ?? "—", n: parsed.value.sns.length }),
    t("piInit.submit"),
    { type: "warning" },
  );
  submitting.value = true;
  try {
    const r: any = await api.piInit({
      pi_no: status.value.counter.pi_no,
      start_seq: startSeq.value,
      sns: parsed.value.sns,
      factory_code: factoryCode.value || null,
      customer_code: customer.value || null,
      material_code: material.value || null,
      file_name: fileName.value || null,
    });
    ElMessage.success(t("piInit.done", { n: r.imported_qty, decoded: r.decoded_qty, last: r.last_seq_dec }));
    snText.value = "";
    startText.value = "";
    await lookup();
  } finally {
    submitting.value = false;
  }
}
</script>

<style scoped>
.w300 { width: 300px; }
.mt { margin-top: 12px; }
.file-row { display: flex; gap: 10px; align-items: center; margin-top: 8px; }
</style>
