import { computed, shallowRef, watch } from "vue";
import { useRoute, type LocationQuery } from "vue-router";

/**
 * 页签缓存（keep-alive）下的「本页路由」：query 只在本页是当前页签时跟随地址栏。
 * 页面切到后台后仍在内存里，直接读 useRoute() 会读到别的页面的参数（如分配页的 ?pi=），
 * 让后台页面跟着加载、甚至用 router.replace 改写别的页面的地址；用这个代替 useRoute() 读参数。
 * active：本页当前是否在前台；改地址（router.replace）前先判断。
 */
export function usePageRoute() {
  const route = useRoute();
  const path = route.path;
  const query = shallowRef<LocationQuery>(route.query);
  const active = computed(() => route.path === path);
  watch(() => route.query, (q) => {
    if (active.value) query.value = q;
  });
  return {
    path,
    get query() {
      return query.value;
    },
    get active() {
      return active.value;
    },
  };
}
