/** 后端时间一律 UTC（ISO 8601，带 Z）；页面按生产组织时区显示（默认东八区，480 分钟）。 */
export const DEFAULT_TZ_OFFSET_MIN = 480;

const pad = (n: number) => String(n).padStart(2, "0");

/** UTC ISO → 「YYYY-MM-DD HH:mm:ss」（按 offsetMin 换算）；空值返回 —。 */
export function formatTime(iso: string | null | undefined, offsetMin = DEFAULT_TZ_OFFSET_MIN, withSeconds = true): string {
  if (!iso) return "—";
  const ms = Date.parse(iso);
  if (Number.isNaN(ms)) return iso;
  const d = new Date(ms + offsetMin * 60_000);
  const s = `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())} ${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`;
  return withSeconds ? `${s}:${pad(d.getUTCSeconds())}` : s;
}

/** 480 → UTC+08:00 */
export function tzLabel(offsetMin = DEFAULT_TZ_OFFSET_MIN): string {
  const sign = offsetMin < 0 ? "-" : "+";
  const a = Math.abs(offsetMin);
  return `UTC${sign}${pad(Math.floor(a / 60))}:${pad(a % 60)}`;
}

/** 秒数 → 「1 时 2 分 3 秒」式的紧凑文本（h / m / s）。 */
export function formatDuration(sec: number | null | undefined): string {
  if (sec === null || sec === undefined || !Number.isFinite(sec)) return "—";
  const s = Math.max(0, Math.round(sec));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const r = s % 60;
  return [h ? `${h}h` : "", m ? `${m}m` : "", `${r}s`].filter(Boolean).join(" ");
}
