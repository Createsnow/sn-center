<template>
  <el-dialog v-model="of.open" :title="store.isAdmin ? t('transfer.newDirect') : t('transfer.newApply')" width="820px" top="5vh">
    <el-form label-width="120px" class="form">
      <el-form-item :label="t('transfer.fromOrg')" required>
        <FactorySelect v-model="of.factory_code" :disabled="store.isFactory" @update:model-value="resetCandidates" />
      </el-form-item>
      <el-form-item label="PI" required>
        <el-input v-model="of.pi_no" class="w260" clearable @change="resetCandidates" />
      </el-form-item>
      <el-form-item :label="t('transfer.scope')">
        <el-radio-group v-model="of.scope" @change="resetCandidates">
          <el-radio-button v-for="s in ['PI', 'MATERIAL', 'SINGLE']" :key="s" :value="s">{{ scopeLabel(s) }}</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="of.scope === 'MATERIAL'" :label="t('common.material')" required>
        <el-select v-model="of.material_code" class="w260" filterable allow-create :placeholder="t('transfer.pickMaterial')" @change="resetCandidates">
          <el-option v-for="mm in materials" :key="mm" :value="mm" :label="mm || t('transfer.emptyMaterial')" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="of.scope === 'SINGLE'" :label="t('common.sn')" required>
        <el-input v-model="of.sn" class="w260 sn-mono" clearable @change="resetCandidates" />
      </el-form-item>
      <el-divider />
      <el-form-item :label="t('transfer.toOrg')" required>
        <el-select v-model="of.to_factory" filterable class="w260" @change="resetCandidates">
          <el-option v-for="x in factories" :key="x.factory_code" :value="x.factory_code" :disabled="!x.enabled"
            :label="`${x.factory_name}（${x.factory_code}）${x.mes_enabled ? ' · MES' : ''}${x.enabled ? '' : t('common.disabledSuffix')}`" />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('transfer.toPi')" required>
        <el-autocomplete v-model="of.to_pi" class="w260" :fetch-suggestions="(q: string, cb: any) => suggest('pi', q, cb)" :trigger-on-focus="false" clearable />
        <el-tag v-if="isManual('pi')" size="small" type="info" class="ml">{{ t("transfer.manual") }}</el-tag>
      </el-form-item>
      <el-form-item :label="t('transfer.toCustomer')">
        <el-autocomplete v-model="of.to_customer" class="w260" :fetch-suggestions="(q: string, cb: any) => suggest('customer', q, cb)" :trigger-on-focus="false"
          clearable :placeholder="t('transfer.keepOriginal')" />
        <el-tag v-if="isManual('customer')" size="small" type="info" class="ml">{{ t("transfer.manual") }}</el-tag>
      </el-form-item>
      <el-form-item :label="t('transfer.toMaterial')">
        <el-autocomplete v-model="of.to_material" class="w260" :fetch-suggestions="(q: string, cb: any) => suggest('material', q, cb)" :trigger-on-focus="false"
          clearable :placeholder="t('transfer.keepOriginal')" />
        <el-tag v-if="isManual('material')" size="small" type="info" class="ml">{{ t("transfer.manual") }}</el-tag>
      </el-form-item>
      <el-form-item :label="t('common.reason')" required>
        <el-input v-model="of.reason" type="textarea" :rows="2" maxlength="500" show-word-limit :placeholder="t('common.reasonPlaceholder')" />
      </el-form-item>
    </el-form>
    <div v-if="cand" class="preview">
      <div class="kpis">
        <span>{{ t("transfer.pvTotal") }} <b class="num">{{ cand.total }}</b></span>
        <span>{{ t("transfer.pvTransferable") }} <b class="num ok">{{ cand.transferable }}</b></span>
        <span>{{ t("transfer.pvDelivered") }} <b class="num">{{ cand.delivered }}</b></span>
        <span v-if="cand.xo_qty">{{ t("transfer.pvXo") }} <b class="num warn">{{ cand.xo_qty }}</b></span>
      </div>
      <div class="tags">
        <el-tag v-for="(n, s) in cand.by_status" :key="s" size="small" effect="plain">{{ statusLabel(String(s)) }} {{ n }}</el-tag>
        <el-tag v-for="(n, r) in cand.blocked" :key="r" size="small" type="danger">{{ t(`transfer.blocked.${r}`) }} {{ n }}</el-tag>
      </div>
      <p class="muted">{{ store.isAdmin ? t("transfer.directNote") : t("transfer.applyNote") }}</p>
    </div>
    <template #footer>
      <el-button @click="of.open = false">{{ t("common.cancel") }}</el-button>
      <el-button :loading="busy === 'cand'" :disabled="!of.factory_code || !of.pi_no" @click="loadCandidates">{{ t("transfer.preview") }}</el-button>
      <el-button type="primary" :loading="busy === 'submit'" :disabled="!cand || !cand.transferable || !of.to_factory || !of.to_pi || !of.reason.trim()"
        @click="submitOrg">{{ store.isAdmin ? t("transfer.submitDirect") : t("transfer.submitApply") }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/** 新建转组织（F-16）：生产组织操作员提交申请，总部直接转。范围为整张 PI / 按物料 / 单枚；
 *  目标 PI、客户、物料可从快照候选里选，也可手工填写（标出）。完成后发出 done（转组织单）。 */
import { reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "@/api";
import FactorySelect from "@/components/FactorySelect.vue";
import { useFactories } from "@/composables/useFactories";
import { scopeLabel, statusLabel } from "@/constants";
import { useAuthStore } from "@/stores/auth";
import { type Summary, type TargetField, useTargets } from "./useTargets";

interface Candidates extends Summary {
  materials: string[];
  xo_qty: number;
}

const emit = defineEmits<{ done: [doc: any] }>();
const { t } = useI18n();
const store = useAuthStore();
const { factories, nameOf } = useFactories();
const { suggest, isKnown } = useTargets();
const busy = ref("");

const of = reactive({
  open: false, factory_code: "", pi_no: "", scope: "PI" as "PI" | "MATERIAL" | "SINGLE", material_code: "", sn: "",
  to_factory: "", to_pi: "", to_customer: "", to_material: "", reason: "",
});
const cand = ref<Candidates | null>(null);
const materials = ref<string[]>([]);

function resetCandidates() {
  cand.value = null;
}
function isManual(field: TargetField) {
  const v = (field === "pi" ? of.to_pi : field === "customer" ? of.to_customer : of.to_material).trim();
  return !!v && !isKnown(field, v);
}

function open(prefill: { factory_code?: string; pi?: string; material?: string } = {}) {
  Object.assign(of, {
    open: true, factory_code: store.isFactory ? store.boundFactory : prefill.factory_code || "", pi_no: prefill.pi || "",
    scope: prefill.material !== undefined ? "MATERIAL" : "PI", material_code: prefill.material ?? "", sn: "",
    to_factory: "", to_pi: prefill.pi || "", to_customer: "", to_material: "", reason: "",
  });
  materials.value = [];
  resetCandidates();
}

function body() {
  const manual = (["pi", "customer", "material"] as TargetField[]).filter(isManual);
  return {
    factory_code: of.factory_code, pi_no: of.pi_no.trim(), scope: of.scope,
    material_code: of.scope === "MATERIAL" ? of.material_code : null, sn: of.scope === "SINGLE" ? of.sn.trim() : null,
    to_factory: of.to_factory, to_pi: of.to_pi.trim(), to_customer: of.to_customer.trim() || null,
    to_material: of.to_material.trim() || null, manual_fields: manual, reason: of.reason,
  };
}

async function loadCandidates() {
  busy.value = "cand";
  try {
    const r: Candidates = await api.orgCandidates(body());
    cand.value = r;
    materials.value = r.materials;
  } finally {
    busy.value = "";
  }
}

async function submitOrg() {
  const c = cand.value!;
  const key = store.isAdmin ? "transfer.directConfirm" : "transfer.applyConfirm";
  await ElMessageBox.confirm(t(key, { n: c.transferable, to: nameOf(of.to_factory), pi: of.to_pi }), store.isAdmin ? t("transfer.newDirect") : t("transfer.newApply"), { type: "warning" });
  busy.value = "submit";
  try {
    const r = store.isAdmin ? await api.orgDirect(body()) : await api.orgApply(body());
    ElMessage.success(t("transfer.orgDone", { no: r.transfer_no, n: r.qty }));
    of.open = false;
    emit("done", r);
  } finally {
    busy.value = "";
  }
}

defineExpose({ open });
</script>

<style scoped>
.w260 { width: 260px; }
.ml { margin-left: 8px; }
.form :deep(.el-form-item) { margin-bottom: 14px; }
.preview { border: 1px solid var(--app-line); border-radius: 8px; padding: 12px 16px; background: var(--app-canvas); }
.kpis { display: flex; flex-wrap: wrap; gap: 18px; align-items: baseline; }
.tags { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.ok { color: var(--el-color-success); }
.warn { color: var(--el-color-danger); }
</style>
