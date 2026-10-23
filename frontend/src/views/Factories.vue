<template>
  <div class="page">
    <PageHead :title="t('factories.title')" :desc="t('factories.desc')">
      <el-button type="primary" :loading="syncing" @click="sync(false)"><el-icon><Refresh /></el-icon><span>{{ t("factories.sync") }}</span></el-button>
    </PageHead>
    <div class="surface">
      <el-table :data="factories" size="small" :empty-text="t('common.empty')" :row-class-name="({ row }: any) => (row.enabled ? '' : 'off')">
        <el-table-column prop="factory_code" :label="t('factories.code')" width="150" />
        <el-table-column prop="factory_name" :label="t('factories.name')" min-width="160" show-overflow-tooltip />
        <el-table-column :label="t('factories.k3Status')" width="100">
          <template #default="{ row }"><el-tag size="small" :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? t("factories.enabled") : t("factories.disabled") }}</el-tag></template>
        </el-table-column>
        <el-table-column :label="t('common.mes')" width="110">
          <template #default="{ row }">
            <el-switch :model-value="row.mes_enabled" :loading="busy === row.factory_code" :before-change="() => askToggle(row)" />
          </template>
        </el-table-column>
        <el-table-column :label="t('factories.cfgStatus')" min-width="180">
          <template #default="{ row }">
            <el-tag size="small" :type="CFG_TYPE[row.cfg_status || ''] || 'info'">{{ cfgStatusLabel(row.cfg_status) }}</el-tag>
            <el-tooltip v-if="row.cfg_message" :content="row.cfg_message"><el-icon class="ml warn"><WarningFilled /></el-icon></el-tooltip>
            <div v-if="row.cfg_checked_at" class="muted">{{ t("factories.checkedAt") }} {{ fmt(row.cfg_checked_at) }}</div>
          </template>
        </el-table-column>
        <el-table-column :label="t('factories.mesUrl')" min-width="200" show-overflow-tooltip>
          <template #default="{ row }"><span v-if="row.mes_url" class="sn-mono">{{ row.mes_url }}</span><span v-else class="muted">—</span></template>
        </el-table-column>
        <el-table-column :label="t('factories.batchSize')" align="right" width="100">
          <template #default="{ row }"><span class="num">{{ row.batch_size }}</span></template>
        </el-table-column>
        <el-table-column :label="t('factories.tz')" width="110"><template #default="{ row }">{{ tzLabel(row.tz_offset_min) }}</template></el-table-column>
        <el-table-column :label="t('factories.boundUsers')" align="right" width="110">
          <template #default="{ row }"><span class="num">{{ row.bound_users ?? 0 }}</span></template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="110" align="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openConfig(row)">{{ t("factories.config") }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <p class="muted hint">{{ t("factories.disableHint") }}</p>
    </div>

    <el-dialog v-model="cfgOpen" :title="t('factories.configTitle', { name: editing?.factory_name, code: editing?.factory_code })" width="620px">
      <el-alert v-if="editing?.mes_enabled" type="warning" :closable="false" show-icon class="mb" :title="t('factories.enabledEditHint')" />
      <el-form label-width="130px">
        <el-form-item :label="t('factories.mesUrl')" required>
          <el-input v-model.trim="form.mes_url" placeholder="https://mes.example.com/sn/batches" />
        </el-form-item>
        <el-form-item :label="t('factories.authType')" required>
          <el-radio-group v-model="form.mes_auth_type">
            <el-radio-button value="TOKEN">{{ t("factories.authTOKEN") }}</el-radio-button>
            <el-radio-button value="HMAC">{{ t("factories.authHMAC") }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="t('factories.credential')" :required="!editing?.credential_set">
          <el-input v-model="form.mes_credential" type="password" show-password autocomplete="new-password"
            :placeholder="editing?.credential_set ? t('factories.credentialKeep') : ''" />
          <div class="muted hint">{{ t("factories.credentialHint") }}</div>
        </el-form-item>
        <el-form-item :label="t('factories.gzip')">
          <el-switch v-model="form.mes_gzip" />
          <div class="muted hint">{{ t("factories.gzipHint") }}</div>
        </el-form-item>
        <el-form-item :label="t('factories.batchSize')">
          <el-input-number v-model="form.batch_size" :min="1" :max="50000" :step="500" />
          <span class="muted ml">{{ t("factories.batchSizeHint") }}</span>
        </el-form-item>
        <el-form-item :label="t('factories.timeout')">
          <el-input-number v-model="form.push_timeout_sec" :min="5" :max="600" />
          <span class="muted ml">{{ t("factories.seconds") }}</span>
        </el-form-item>
        <el-form-item :label="t('factories.concurrency')">
          <el-input-number v-model="form.push_concurrency" :min="1" :max="20" />
          <span class="muted ml">{{ t("factories.concurrencyHint") }}</span>
        </el-form-item>
        <el-form-item :label="t('factories.tz')">
          <el-select v-model="form.tz_offset_min" filterable class="w200">
            <el-option v-for="o in TZ_OPTIONS" :key="o" :value="o" :label="tzLabel(o)" />
          </el-select>
          <div class="muted hint">{{ t("factories.tzHint") }}</div>
        </el-form-item>
        <el-form-item v-if="testResult" :label="t('factories.testResult')">
          <el-alert :type="testResult.ok ? 'success' : 'error'" :closable="false" show-icon
            :title="testResult.ok ? t('factories.testOk', { batch: testResult.batch_no }) : t('factories.testFail', { message: testResult.message })" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cfgOpen = false">{{ t("common.cancel") }}</el-button>
        <el-button :loading="testing" @click="test">{{ t("factories.test") }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ t("common.save") }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="toggleOpen" :title="toggle?.mes_enabled ? t('factories.disableMes') : t('factories.enableMes')" width="560px">
      <template v-if="toggle">
        <template v-if="!toggle.mes_enabled">
          <el-alert v-if="!toggle.can_enable" type="error" :closable="false" show-icon :title="t('factories.enableBlocked')" />
          <p v-else>{{ t("factories.enableEffect", { n: toggle.allocated }) }}</p>
        </template>
        <template v-else>
          <template v-if="blocked">
            <el-alert type="error" :closable="false" show-icon :title="t('factories.disableBlocked')" class="mb" />
            <ul class="blockers">
              <li v-if="toggle.blockers.tasks">{{ t("factories.blockTasks", { n: toggle.blockers.tasks }) }}</li>
              <li v-if="toggle.blockers.batch_total">{{ t("factories.blockBatches", { n: toggle.blockers.batch_total, qty: toggle.blockers.batch_qty }) }}</li>
              <li v-if="toggle.blockers.reassign_qty">{{ t("factories.blockReassign", { n: toggle.blockers.reassign_qty }) }}</li>
            </ul>
            <el-table v-if="toggle.blockers.batches.length" :data="toggle.blockers.batches" size="small" max-height="220">
              <el-table-column prop="batch_no" :label="t('common.batchNo')" min-width="200" />
              <el-table-column prop="qty" :label="t('common.qty')" width="80" align="right" />
              <el-table-column :label="t('common.status')" width="100"><template #default="{ row }">{{ batchResultLabel(row.result) }}</template></el-table-column>
            </el-table>
            <p class="muted">{{ t("factories.disableBlockedHint") }}</p>
          </template>
          <p v-else>{{ t("factories.disableEffect", { n: toggle.unbatched }) }}</p>
        </template>
      </template>
      <template #footer>
        <el-button @click="toggleOpen = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" :disabled="!canToggle" :loading="busy === toggle?.factory_code" @click="doToggle">{{ t("common.confirm") }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import { api } from "@/api";
import { useFactories } from "@/composables/useFactories";
import { useTime } from "@/composables/useTime";
import { batchResultLabel, cfgStatusLabel } from "@/constants";
import { tzLabel } from "@/utils/time";
import type { Factory } from "@/types/api";
import PageHead from "@/components/PageHead.vue";

const { t } = useI18n();
const { fmt } = useTime();
const { factories, load } = useFactories();
const CFG_TYPE: Record<string, "success" | "danger" | "info"> = { OK: "success", FAILED: "danger", UNCONFIGURED: "info" };
/** UTC-12:00 … UTC+14:00，按 15 分钟。 */
const TZ_OPTIONS = Array.from({ length: (840 + 720) / 15 + 1 }, (_, i) => -720 + i * 15);
const syncing = ref(false);
const busy = ref("");

async function sync(silent: boolean) {
  syncing.value = true;
  try {
    const r: any = await (silent ? api.syncFactories().catch(() => null) : api.syncFactories());
    if (r && !silent) ElMessage.success(t("factories.synced", { total: r.total, inserted: r.inserted, renamed: r.renamed, disabled: r.disabled }));
    await load(true);
  } finally {
    syncing.value = false;
  }
}

// ---------------- MES 对接配置
const cfgOpen = ref(false);
const editing = ref<Factory | null>(null);
const testing = ref(false);
const saving = ref(false);
const testResult = ref<any>(null);
const form = reactive({ mes_url: "", mes_auth_type: "TOKEN", mes_credential: "", mes_gzip: false, batch_size: 5000, push_timeout_sec: 60, push_concurrency: 2, tz_offset_min: 480 });

function openConfig(row: Factory) {
  editing.value = row;
  testResult.value = null;
  Object.assign(form, {
    mes_url: row.mes_url || "", mes_auth_type: row.mes_auth_type || "TOKEN", mes_credential: "", mes_gzip: row.mes_gzip ?? false,
    batch_size: row.batch_size ?? 5000, push_timeout_sec: row.push_timeout_sec ?? 60, push_concurrency: row.push_concurrency ?? 2,
    tz_offset_min: row.tz_offset_min ?? 480,
  });
  cfgOpen.value = true;
}

/** 凭证留空 = 沿用已存的（不回显）。 */
const payload = () => ({ ...form, mes_credential: form.mes_credential || undefined });

async function test() {
  if (!editing.value) return;
  testing.value = true;
  try {
    testResult.value = await api.testMes(editing.value.factory_code, payload());
    await load(true);
  } finally {
    testing.value = false;
  }
}

async function save() {
  if (!editing.value) return;
  saving.value = true;
  try {
    await api.saveMesConfig(editing.value.factory_code, payload());
    ElMessage.success(t("common.saved"));
    cfgOpen.value = false;
    await load(true);
  } finally {
    saving.value = false;
  }
}

// ---------------- 开启 / 关闭「对接 MES」
const toggleOpen = ref(false);
const toggle = ref<any>(null);
const blocked = computed(() => {
  const b = toggle.value?.blockers;
  return !!b && (b.tasks > 0 || b.batch_total > 0 || b.reassign_qty > 0);
});
const canToggle = computed(() => !!toggle.value && (toggle.value.mes_enabled ? !blocked.value : !!toggle.value.can_enable));

/** 开关不直接切换：先弹出受影响枚数 / 阻塞项，确认后再切。 */
function askToggle(row: Factory) {
  openToggle(row);
  return false;
}

async function openToggle(row: Factory) {
  busy.value = row.factory_code;
  try {
    toggle.value = await api.mesTogglePreview(row.factory_code);
    toggleOpen.value = true;
  } finally {
    busy.value = "";
  }
}

async function doToggle() {
  const p = toggle.value;
  busy.value = p.factory_code;
  try {
    await api.toggleMes(p.factory_code, !p.mes_enabled);
    ElMessage.success(p.mes_enabled ? t("factories.mesOff") : t("factories.mesOn"));
    toggleOpen.value = false;
    await load(true);
  } finally {
    busy.value = "";
  }
}

// 打开页签先显示现有列表，再自动同步一次（失败只保留现有列表）
onMounted(() => sync(true));
</script>

<style scoped>
.hint { margin: 8px 0 0; line-height: 1.5; width: 100%; }
.ml { margin-left: 6px; }
.mb { margin-bottom: 12px; }
.w200 { width: 200px; }
.warn { color: var(--el-color-danger); vertical-align: middle; }
.blockers { margin: 0 0 8px; padding-left: 20px; line-height: 1.8; }
:deep(.off) { color: var(--app-muted); }
</style>
