<template>
  <div class="page">
    <PageHead :title="t('groups.title')" :desc="t('groups.desc')">
      <el-button @click="router.push('/users')"><el-icon><User /></el-icon><span>{{ t("groups.toUsers") }}</span></el-button>
    </PageHead>

    <div class="cards">
      <div v-for="g in GROUPS" :key="g" class="surface card">
        <div class="card-h">
          <b>{{ roleLabel(g) }}</b>
          <span class="muted num">{{ t("groups.accounts", { n: counts[g] ?? "—" }) }}</span>
        </div>
        <p class="muted hint">{{ t(`users.roleHint.${g}`) }}</p>
      </div>
    </div>

    <!-- 功能模块分权：各用户组可见的菜单（与侧栏、路由守卫同一份配置），MES 接口账户只调接口、不登录页面 -->
    <div class="surface">
      <div class="surface__title"><span>{{ t("groups.matrix") }}</span></div>
      <el-table :data="rows" size="small" :span-method="spanGroup" border>
        <el-table-column :label="t('groups.module')" width="140">
          <template #default="{ row }"><b>{{ t(row.group) }}</b></template>
        </el-table-column>
        <el-table-column :label="t('groups.function')" min-width="160">
          <template #default="{ row }">
            {{ t(row.title) }}
            <el-tag v-if="row.soon" size="small" type="info" effect="plain" class="ml">{{ t("nav.soon") }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column v-for="g in GROUPS" :key="g" :label="roleLabel(g)" align="center" min-width="110">
          <template #default="{ row }">
            <el-icon v-if="row.roles.includes(g)" class="yes"><Select /></el-icon>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
      </el-table>
      <p class="muted note">{{ t("groups.note") }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
/** 用户组（功能模块分权）：按现有固定角色列出各组能用的功能模块与账户数；只读，分权仍按账户的角色。 */
import { computed, onMounted, reactive } from "vue";
import { useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { MENU } from "@/router";
import { roleLabel } from "@/constants";
import PageHead from "@/components/PageHead.vue";

const GROUPS = ["admin", "operator", "auditor", "mes"];

const { t } = useI18n();
const router = useRouter();
const counts = reactive<Record<string, number>>({});

const rows = computed(() =>
  MENU.filter((g) => g.group !== "nav.groupWork").flatMap((g) => g.items.map((m, i) => ({ ...m, group: g.group, first: i === 0, span: g.items.length }))),
);

function spanGroup({ row, columnIndex }: { row: { first: boolean; span: number }; columnIndex: number }) {
  if (columnIndex !== 0) return { rowspan: 1, colspan: 1 };
  return row.first ? { rowspan: row.span, colspan: 1 } : { rowspan: 0, colspan: 0 };
}

onMounted(async () => {
  for (const g of GROUPS) {
    const q = g === "mes" ? { kind: "mes" } : { kind: "page", role: g };
    api.users({ ...q, status: "ACTIVE", page: 1, page_size: 1 }).then((r: any) => (counts[g] = r.total)).catch(() => undefined);
  }
});
</script>

<style scoped>
.cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 16px; }
.card { display: flex; flex-direction: column; gap: 6px; }
.card-h { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.hint { margin: 0; line-height: 1.6; font-size: 12px; }
.yes { color: var(--el-color-success); font-size: 16px; }
.ml { margin-left: 6px; }
.note { margin: 12px 0 0; }
</style>
