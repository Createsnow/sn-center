import { computed } from "vue";
import { useAuthStore } from "@/stores/auth";
import { DEFAULT_TZ_OFFSET_MIN, formatTime, tzLabel } from "@/utils/time";

/** 按当前账户的显示时区格式化时间（绑定生产组织的账户用该组织的时区，总部默认东八区）。 */
export function useTime() {
  const store = useAuthStore();
  const offset = computed(() => store.profile?.tz_offset_min ?? DEFAULT_TZ_OFFSET_MIN);
  const fmt = (iso: string | null | undefined, withSeconds = true) => formatTime(iso, offset.value, withSeconds);
  const tz = computed(() => tzLabel(offset.value));
  return { fmt, tz, offset };
}
