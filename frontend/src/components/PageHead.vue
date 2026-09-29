<template>
  <div class="page__head">
    <div>
      <h2 class="page__title">{{ title }}</h2>
      <p v-if="desc" class="page__desc">{{ desc }}</p>
    </div>
    <div class="page__actions"><slot /></div>
  </div>
</template>

<script setup lang="ts">
defineProps<{ title: string; desc?: string }>();
</script>
