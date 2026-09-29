<template>
  <el-tag :type="STATUS_TYPE[status] || 'info'" size="small" disable-transitions>{{ statusLabel(status) }}</el-tag>
</template>

<script setup lang="ts">
import { STATUS_TYPE, statusLabel } from "@/constants";

defineProps<{ status: string }>();
</script>
