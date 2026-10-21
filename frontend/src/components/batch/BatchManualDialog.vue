<template>
  <el-dialog v-model="open" :title="kind === 'void' ? t('batch.voidTitle', { no: batchNo }) : t('batch.confirmTitle', { no: batchNo })" width="520px">
    <el-alert type="warning" :closable="false" show-icon :title="kind === 'void' ? t('batch.voidTip') : t('batch.confirmTip')" class="mb" />
    <el-form label-position="top" @submit.prevent>
      <el-form-item :label="t('common.reason')" required>
        <el-input v-model="reason" type="textarea" :rows="2" maxlength="500" show-word-limit :placeholder="t('common.reasonPlaceholder')" />
      </el-form-item>
      <el-form-item :label="t('batch.basis')" required>
        <el-input v-model="basis" type="textarea" :rows="2" maxlength="500" show-word-limit :placeholder="t('batch.basisPlaceholder')" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="open = false">{{ t("common.cancel") }}</el-button>
      <el-button :type="kind === 'void' ? 'danger' : 'primary'" :loading="busy" :disabled="!reason.trim() || !basis.trim()" @click="submit">
        {{ t("common.confirm") }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/** 交付批次（含转出批次）人工确认 / 作废：原因、依据必填。父组件用 ref 调 open(批次号, 方式)，完成后收到 done。 */
import { ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import { api } from "@/api";

type Kind = "confirm" | "void";

const emit = defineEmits<{ done: [kind: Kind, batchNo: string] }>();
const { t } = useI18n();
const open = ref(false);
const kind = ref<Kind>("confirm");
const batchNo = ref("");
const reason = ref("");
const basis = ref("");
const busy = ref(false);

function show(no: string, k: Kind) {
  batchNo.value = no;
  kind.value = k;
  reason.value = "";
  basis.value = "";
  open.value = true;
}

async function submit() {
  busy.value = true;
  try {
    const f = kind.value === "void" ? api.voidBatch : api.confirmBatch;
    await f(batchNo.value, reason.value.trim(), basis.value.trim());
    ElMessage.success(t("common.done"));
    open.value = false;
    emit("done", kind.value, batchNo.value);
  } finally {
    busy.value = false;
  }
}

defineExpose({ open: show });
</script>

<style scoped>
.mb { margin-bottom: 12px; }
</style>
