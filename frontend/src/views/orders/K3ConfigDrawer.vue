<template>
  <!-- 生产订单页「金蝶连接」：只有系统管理员。保存时后端先用新参数连接测试，通过才保存；应用密钥不回显 -->
  <el-drawer :model-value="modelValue" :title="t('k3.title')" size="640px" class="k3-drawer" @update:model-value="emit('update:modelValue', $event)" @open="load">
    <div v-loading="loading" class="body">
      <p class="muted desc">{{ t("k3.desc") }}</p>

      <div v-if="cfg" class="kv">
        <span class="k">{{ t("k3.source") }}</span>
        <span>
          <el-tag size="small" :type="cfg.source === 'PAGE' ? 'primary' : 'info'">{{ t(`k3.source${cfg.source}`) }}</el-tag>
          <span class="muted ml">{{ t(`k3.sourceHint${cfg.source}`) }}</span>
        </span>
        <span class="k">{{ t("k3.lastTest") }}</span>
        <span>
          <template v-if="cfg.last_test">
            <el-tag size="small" :type="cfg.last_test.ok ? 'success' : 'danger'">{{ cfg.last_test.ok ? t("k3.testPassed") : t("k3.testFailedTag") }}</el-tag>
            <span class="muted ml">{{ fmt(cfg.last_test.at) }} · {{ cfg.last_test.by }}</span>
          </template>
          <span v-else class="muted">{{ t("k3.never") }}</span>
        </span>
        <template v-if="cfg.updated_at">
          <span class="k">{{ t("k3.updated") }}</span>
          <span>{{ fmt(cfg.updated_at) }} · {{ cfg.updated_by }}</span>
        </template>
      </div>

      <el-alert v-if="cfg?.secret_unreadable" type="error" :closable="false" show-icon class="mb" :title="t('k3.secretUnreadable')" />
      <el-alert v-if="result" :type="result.ok ? 'success' : 'error'" :closable="false" show-icon class="mb"
        :title="result.ok ? t('k3.testOk', { orgs: result.orgs, sec: (result.elapsed_ms / 1000).toFixed(1) }) : t('k3.testFail', { message: result.message })" />

      <div class="sec">{{ t("k3.section") }}</div>
      <el-form label-width="120px" @submit.prevent>
        <el-form-item :label="t('k3.serverUrl')" required>
          <el-input v-model.trim="form.server_url" class="sn-mono" placeholder="http://10.0.0.1/k3cloud/" />
          <div class="muted hint">{{ t("k3.serverUrlHint") }}</div>
        </el-form-item>
        <el-form-item :label="t('k3.acctId')" required>
          <el-input v-model.trim="form.acct_id" class="sn-mono" />
          <div class="muted hint">{{ t("k3.acctIdHint") }}</div>
        </el-form-item>
        <el-form-item :label="t('k3.appId')" required>
          <el-input v-model.trim="form.app_id" class="sn-mono" />
        </el-form-item>
        <el-form-item :label="t('k3.appSecret')" :required="!cfg?.secret_set || cfg?.secret_unreadable">
          <el-input v-model="form.app_secret" type="password" show-password autocomplete="new-password"
            :placeholder="cfg?.secret_set && !cfg?.secret_unreadable ? t('k3.secretKeep') : ''" />
          <div class="muted hint"><el-icon class="ico"><Lock /></el-icon> {{ t("k3.secretHint") }}</div>
        </el-form-item>
        <el-form-item :label="t('k3.username')" required>
          <el-input v-model.trim="form.username" class="w240" />
          <div class="muted hint full">{{ t("k3.usernameHint") }}</div>
        </el-form-item>
        <el-form-item :label="t('k3.lcid')">
          <el-select v-model="form.lcid" class="w240">
            <el-option v-for="l in LCIDS" :key="l" :value="l" :label="t(`k3.lcid${l}`)" />
          </el-select>
        </el-form-item>
      </el-form>

      <el-collapse v-model="adv" class="adv">
        <el-collapse-item name="adv">
          <template #title><span class="sec flat">{{ t("k3.advanced") }}<span class="muted plain">{{ t("k3.advancedHint") }}</span></span></template>
          <el-form label-width="120px" @submit.prevent>
            <el-form-item :label="t('k3.loginTimeout')">
              <el-input-number v-model="form.login_timeout" :min="5" :max="300" /><span class="muted ml">{{ t("k3.seconds") }}</span>
            </el-form-item>
            <el-form-item :label="t('k3.queryTimeout')">
              <el-input-number v-model="form.query_timeout" :min="10" :max="600" /><span class="muted ml">{{ t("k3.queryTimeoutHint") }}</span>
            </el-form-item>
            <el-form-item :label="t('k3.viewTimeout')">
              <el-input-number v-model="form.view_timeout" :min="5" :max="300" /><span class="muted ml">{{ t("k3.seconds") }}</span>
            </el-form-item>
            <el-form-item :label="t('k3.retries')">
              <el-input-number v-model="form.page_retries" :min="0" :max="10" class="w120" />
              <span class="muted ml">{{ t("k3.retriesUnit") }}</span>
              <el-input-number v-model="form.page_retry_seconds" :min="0" :max="120" class="w120 ml" />
              <span class="muted ml">{{ t("k3.seconds") }}</span>
            </el-form-item>
          </el-form>
        </el-collapse-item>
      </el-collapse>

      <div class="sec">{{ t("k3.history") }}</div>
      <el-timeline v-if="cfg?.history?.length" class="tl">
        <el-timeline-item v-for="(h, i) in cfg.history" :key="i" :timestamp="`${fmt(h.at)} · ${h.by}`" :type="i === 0 ? 'primary' : undefined">
          {{ historyText(h) }}
        </el-timeline-item>
      </el-timeline>
      <div v-else class="muted">{{ t("k3.historyEmpty") }}</div>
      <div class="muted hint">{{ t("k3.historyHint") }}</div>
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">{{ t("common.cancel") }}</el-button>
      <el-button :loading="busy === 'test'" :disabled="!!busy" @click="test"><el-icon><Connection /></el-icon><span>{{ t("k3.test") }}</span></el-button>
      <el-button type="primary" :loading="busy === 'save'" :disabled="!!busy" @click="save">{{ t("common.save") }}</el-button>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import { api } from "@/api";
import { useTime } from "@/composables/useTime";

defineProps<{ modelValue: boolean }>();
const emit = defineEmits<{ (e: "update:modelValue", v: boolean): void; (e: "saved", cfg: any): void }>();

const { t } = useI18n();
const { fmt } = useTime();

const LCIDS = [2052, 1033, 3076];
const cfg = ref<any>(null);
const loading = ref(false);
const busy = ref<"" | "test" | "save">("");
const result = ref<any>(null);
const adv = ref<string[]>([]);
const form = reactive({
  server_url: "", acct_id: "", app_id: "", app_secret: "", username: "", lcid: 2052,
  login_timeout: 30, query_timeout: 120, view_timeout: 60, page_retries: 3, page_retry_seconds: 10,
});

function fill(c: any) {
  cfg.value = c;
  Object.assign(form, {
    server_url: c.server_url || "", acct_id: c.acct_id || "", app_id: c.app_id || "", app_secret: "", username: c.username || "",
    lcid: LCIDS.includes(c.lcid) ? c.lcid : 2052, login_timeout: c.login_timeout, query_timeout: c.query_timeout,
    view_timeout: c.view_timeout, page_retries: c.page_retries, page_retry_seconds: c.page_retry_seconds,
  });
}

async function load() {
  result.value = null;
  loading.value = true;
  try {
    fill(await api.k3Config());
  } finally {
    loading.value = false;
  }
}

/** 应用密钥留空 = 沿用当前的（不提交）。 */
const payload = () => ({ ...form, app_secret: form.app_secret.trim() || undefined });

const historyText = (h: any) => {
  if (h.from_env) return t("k3.historyFromEnv");
  if (!h.fields?.length) return t("k3.historyNoChange");
  return t("k3.historyChanged", { fields: h.fields.map((f: string) => t(`k3.field.${f}`)).join(t("k3.sep")) });
};

async function test() {
  busy.value = "test";
  try {
    result.value = await api.testK3Config(payload());
    cfg.value = { ...cfg.value, ...(await api.k3Config().catch(() => ({}))) };
  } finally {
    busy.value = "";
  }
}

async function save() {
  busy.value = "save";
  try {
    const c = await api.saveK3Config(payload());
    fill(c);
    result.value = null;
    ElMessage.success(t("k3.saved"));
    emit("saved", c);
  } catch {
    // 连接测试未通过等错误已由请求拦截器提示；刷新「最近测试」
    cfg.value = { ...cfg.value, ...(await api.k3Config().catch(() => ({}))) };
  } finally {
    busy.value = "";
  }
}
</script>

<style scoped>
.body { padding: 0 4px; }
.desc { font-size: 13px; line-height: 1.6; margin: 0 0 14px; }
.kv { display: grid; grid-template-columns: 96px 1fr; row-gap: 8px; font-size: 13px; margin-bottom: 16px;
  background: #f8fafc; border: 1px solid var(--app-line); border-radius: 8px; padding: 12px 14px; }
.kv .k { color: var(--app-muted); }
.sec { font-size: 13px; font-weight: 600; color: #334155; margin: 6px 0 12px; padding-left: 8px; border-left: 3px solid var(--el-color-primary); }
.sec.flat { margin: 0; }
.plain { font-weight: 400; margin-left: 4px; }
.hint { margin-top: 4px; line-height: 1.5; }
.full { width: 100%; }
.ico { vertical-align: -2px; }
.ml { margin-left: 8px; }
.mb { margin-bottom: 14px; }
.w240 { width: 240px; }
.w120 { width: 120px; }
.adv { border: none; margin-bottom: 12px; }
.adv :deep(.el-collapse-item__header) { border-bottom: none; }
.tl { padding-left: 2px; margin-bottom: 4px; }
.k3-drawer :deep(.el-drawer__body) { padding-top: 4px; }
</style>
