<template>
  <!-- 顶栏页签：与浏览器标签页一样叠加，用户手动关闭前一直保留；首页固定在最左 -->
  <div class="tabbar">
    <button v-show="overflow" class="arrow" type="button" :disabled="!canLeft" :aria-label="t('tabs.scrollLeft')" @click="scrollBy(-240)">
      <el-icon><ArrowLeft /></el-icon>
    </button>
    <div ref="strip" class="strip" role="tablist" @scroll="measure" @wheel="onWheel">
      <div
        v-for="(tab, i) in store.tabs"
        :key="tab.path"
        :data-path="tab.path"
        class="tab"
        :class="{ on: tab.path === route.path, dragging: dragPath === tab.path }"
        role="tab"
        tabindex="0"
        :aria-selected="tab.path === route.path"
        :title="fullTitle(tab.path)"
        :draggable="i > 0"
        @click="go(tab)"
        @keydown.enter.prevent="go(tab)"
        @keydown.space.prevent="go(tab)"
        @mousedown.middle.prevent
        @auxclick.prevent="$event.button === 1 && close(tab.path)"
        @contextmenu.prevent="openMenu($event, tab.path)"
        @dragstart="onDragStart($event, tab.path)"
        @dragover="onDragOver($event, tab.path)"
        @drop.prevent
        @dragend="dragPath = ''"
      >
        <el-icon v-if="i === 0" :size="14"><House /></el-icon>
        <span class="tt">{{ title(tab.path) }}</span>
        <button v-if="i > 0" class="x" type="button" :aria-label="t('tabs.close')" @click.stop="close(tab.path)">
          <el-icon :size="12"><Close /></el-icon>
        </button>
      </div>
    </div>
    <button v-show="overflow" class="arrow" type="button" :disabled="!canRight" :aria-label="t('tabs.scrollRight')" @click="scrollBy(240)">
      <el-icon><ArrowRight /></el-icon>
    </button>
    <el-dropdown trigger="click" placement="bottom-end" @command="(c: Action) => run(c, route.path)">
      <el-button text class="more" :title="t('tabs.more')" :aria-label="t('tabs.more')"><el-icon><MoreFilled /></el-icon></el-button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item v-for="a in actions(route.path)" :key="a.cmd" :command="a.cmd" :disabled="a.disabled" :divided="a.divided">
            {{ t(a.label) }}
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
    <teleport to="body">
      <div v-if="menu.open" ref="menuEl" class="tab-menu" :style="{ left: `${menu.x}px`, top: `${menu.y}px` }" role="menu" @contextmenu.prevent>
        <template v-for="a in actions(menu.path)" :key="a.cmd">
          <div v-if="a.divided" class="sep" />
          <button type="button" role="menuitem" :disabled="a.disabled" @click="pickMenu(a.cmd)">{{ t(a.label) }}</button>
        </template>
      </div>
    </teleport>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import { useTabsStore, type Tab } from "@/stores/tabs";
import { HOME } from "@/tabList.js";

type Action = "refresh" | "close" | "closeOthers" | "closeRight" | "closeAll";

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const store = useTabsStore();

function meta(path: string) {
  return router.resolve(path).meta as { title?: string; group?: string };
}
function title(path: string) {
  const m = meta(path).title;
  return m ? t(m) : path;
}
/** 悬停显示「模块 / 页面」（顶栏不再放面包屑）。 */
function fullTitle(path: string) {
  const g = meta(path).group;
  return g && g !== "nav.groupWork" ? `${t(g)} / ${title(path)}` : title(path);
}

function go(tab: Tab) {
  if (tab.path !== route.path) router.push(tab.fullPath);
}

function close(path: string) {
  const next = store.close(path, route.path);
  if (next) router.push(next.fullPath);
}

/** 批量关闭后当前页若已关闭，切到 target（关闭全部时为首页）。 */
function afterBulk(target: string) {
  if (store.find(route.path)) return;
  const tab = store.find(target) ?? store.find(HOME);
  if (tab) router.push(tab.fullPath);
}

function actions(path: string) {
  const i = store.tabs.findIndex((x) => x.path === path);
  const n = store.tabs.length;
  return [
    { cmd: "refresh" as const, label: "tabs.refresh", disabled: false, divided: false },
    { cmd: "close" as const, label: "tabs.close", disabled: path === HOME, divided: true },
    { cmd: "closeOthers" as const, label: "tabs.closeOthers", disabled: store.tabs.every((x) => x.path === HOME || x.path === path), divided: false },
    { cmd: "closeRight" as const, label: "tabs.closeRight", disabled: i < 0 || i >= n - 1, divided: false },
    { cmd: "closeAll" as const, label: "tabs.closeAll", disabled: n <= 1, divided: false },
  ];
}

function run(cmd: Action, path: string) {
  if (cmd === "refresh") {
    store.refresh(path);
    const tab = store.find(path);
    if (tab && path !== route.path) router.push(tab.fullPath);
  } else if (cmd === "close") close(path);
  else if (cmd === "closeOthers") {
    store.closeOthers(path);
    afterBulk(path);
  } else if (cmd === "closeRight") {
    store.closeRight(path);
    afterBulk(path);
  } else {
    store.closeAll();
    afterBulk(HOME);
  }
}

// ---------------- 右键菜单

const menu = reactive({ open: false, x: 0, y: 0, path: "" });
const menuEl = ref<HTMLElement | null>(null);

async function openMenu(e: MouseEvent, path: string) {
  Object.assign(menu, { open: true, x: e.clientX, y: e.clientY, path });
  await nextTick();
  const r = menuEl.value?.getBoundingClientRect();
  if (!r) return;
  menu.x = Math.max(4, Math.min(e.clientX, window.innerWidth - r.width - 4));
  menu.y = Math.max(4, Math.min(e.clientY, window.innerHeight - r.height - 4));
}
function closeMenu() {
  menu.open = false;
}
function pickMenu(cmd: Action) {
  closeMenu();
  run(cmd, menu.path);
}
function onDocDown(e: MouseEvent) {
  if (menu.open && !menuEl.value?.contains(e.target as Node)) closeMenu();
}
function onKey(e: KeyboardEvent) {
  if (e.key === "Escape") closeMenu();
}

// ---------------- 拖动排序（首页固定在最左）

const dragPath = ref("");
function onDragStart(e: DragEvent, path: string) {
  if (path === HOME) return e.preventDefault();
  dragPath.value = path;
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = "move";
    e.dataTransfer.setData("text/plain", path);
  }
}
function onDragOver(e: DragEvent, path: string) {
  if (!dragPath.value) return;
  e.preventDefault();
  if (path === dragPath.value || path === HOME) return;
  const from = store.tabs.findIndex((x) => x.path === dragPath.value);
  const to = store.tabs.findIndex((x) => x.path === path);
  store.move(from, to);
}

// ---------------- 放不下时左右滚动；当前页签自动滚到可见位置

const strip = ref<HTMLElement | null>(null);
const overflow = ref(false);
const canLeft = ref(false);
const canRight = ref(false);
let ro: ResizeObserver | undefined;

function measure() {
  const s = strip.value;
  if (!s) return;
  overflow.value = s.scrollWidth > s.clientWidth + 1;
  canLeft.value = s.scrollLeft > 0;
  canRight.value = s.scrollLeft + s.clientWidth < s.scrollWidth - 1;
}
function scrollBy(dx: number) {
  strip.value?.scrollBy({ left: dx, behavior: "smooth" });
}
function onWheel(e: WheelEvent) {
  const s = strip.value;
  if (!s || !overflow.value || Math.abs(e.deltaX) > Math.abs(e.deltaY)) return;
  e.preventDefault();
  s.scrollLeft += e.deltaY;
}
function revealNow() {
  const s = strip.value;
  const el = s?.querySelector<HTMLElement>(".tab.on");
  if (!s || !el) return;
  const left = el.offsetLeft - s.offsetLeft;
  if (left < s.scrollLeft) s.scrollLeft = left - 8;
  else if (left + el.offsetWidth > s.scrollLeft + s.clientWidth) s.scrollLeft = left + el.offsetWidth - s.clientWidth + 8;
  measure();
}
async function reveal() {
  await nextTick();
  revealNow();
}

watch(() => route.path, reveal);
watch(() => store.tabs.length, () => nextTick(measure));

onMounted(() => {
  // 窗口或侧栏宽度变化后，当前页签仍保持可见
  ro = new ResizeObserver(revealNow);
  if (strip.value) ro.observe(strip.value);
  reveal();
  document.addEventListener("mousedown", onDocDown, true);
  document.addEventListener("keydown", onKey);
  window.addEventListener("blur", closeMenu);
  window.addEventListener("resize", closeMenu);
});
onUnmounted(() => {
  ro?.disconnect();
  document.removeEventListener("mousedown", onDocDown, true);
  document.removeEventListener("keydown", onKey);
  window.removeEventListener("blur", closeMenu);
  window.removeEventListener("resize", closeMenu);
});
</script>

<style scoped>
/* 胶囊页签：与右侧「数据范围」胶囊同高，整行在顶栏中线上；操作按钮紧跟最后一个页签 */
.tabbar { flex: 1; min-width: 0; height: 100%; display: flex; align-items: center; gap: 2px; }
.arrow { flex: none; width: 26px; height: 28px; border: 0; border-radius: 6px; background: none; color: var(--app-muted);
  cursor: pointer; display: grid; place-items: center; }
.arrow:hover:not(:disabled) { background: #eef2f7; color: var(--app-text); }
.arrow:disabled { opacity: 0.35; cursor: default; }
.strip { flex: 0 1 auto; min-width: 0; height: 100%; display: flex; align-items: center; gap: 6px; overflow-x: auto; scrollbar-width: none; }
.strip::-webkit-scrollbar { display: none; }
.tab { flex: none; display: flex; align-items: center; gap: 6px; height: 30px; max-width: 180px; padding: 0 6px 0 10px;
  border: 1px solid var(--app-line); border-radius: 6px; background: var(--app-surface); color: #475569; font-size: 13px;
  cursor: pointer; user-select: none; transition: border-color 0.12s, color 0.12s, background 0.12s; }
.tab:first-child { padding-right: 10px; }
.tab:hover { border-color: var(--el-color-primary-light-7); color: var(--el-color-primary); }
.tab:focus-visible { outline: 2px solid var(--el-color-primary-light-5); outline-offset: 1px; }
/* 当前页签：浅蓝底、蓝字，与左侧菜单高亮同色系 */
.tab.on { background: var(--el-color-primary-light-9); border-color: var(--el-color-primary-light-7); color: var(--el-color-primary); font-weight: 600; }
.tab.dragging { opacity: 0.45; }
.tt { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.x { flex: none; width: 18px; height: 18px; padding: 0; border: 0; border-radius: 50%; background: none; color: #94a3b8; cursor: pointer;
  display: grid; place-items: center; }
.x:hover { background: rgba(15, 23, 42, 0.08); color: var(--app-text); }
.tab.on .x { color: var(--el-color-primary); }
.more { flex: none; width: 30px; height: 30px; padding: 0; color: #475569; }
</style>

<style>
/* 右键菜单（挂在 body 上） */
.tab-menu { position: fixed; z-index: 3000; min-width: 140px; padding: 4px; background: var(--app-surface); border: 1px solid var(--app-line);
  border-radius: 8px; box-shadow: 0 8px 24px rgba(15, 23, 42, 0.14); }
.tab-menu button { display: block; width: 100%; padding: 7px 12px; border: 0; border-radius: 6px; background: none; color: var(--app-text);
  font: inherit; font-size: 13px; text-align: left; cursor: pointer; }
.tab-menu button:hover:not(:disabled), .tab-menu button:focus-visible { background: var(--el-color-primary-light-9); color: var(--el-color-primary); outline: none; }
.tab-menu button:disabled { color: #b6c0ce; cursor: default; }
.tab-menu .sep { height: 1px; margin: 4px 0; background: var(--app-line); }
</style>
