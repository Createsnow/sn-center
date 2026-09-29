import { currentLocale, t, translateApiError } from "@/i18n";
import { TOKEN_KEY } from "@/api/http";

/** 带 Bearer 令牌下载文件；非 2xx 时抛出本地化后的后端错误。文件名取响应头，缺省用 fallback。 */
export async function downloadWithToken(url: string, fallback: string): Promise<void> {
  const token = localStorage.getItem(TOKEN_KEY);
  const headers: Record<string, string> = { "Accept-Language": currentLocale.value };
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(url, { headers });
  if (!res.ok) {
    let msg = t("common.downloadFailed", { status: res.status });
    try {
      msg = translateApiError(await res.json(), msg);
    } catch {
      /* 非 JSON 响应 */
    }
    throw new Error(msg);
  }
  const cd = res.headers.get("Content-Disposition") || "";
  const m = /filename\*=UTF-8''([^;]+)/i.exec(cd) || /filename="?([^";]+)"?/i.exec(cd);
  const name = m ? decodeURIComponent(m[1]) : fallback;
  const blob = await res.blob();
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
}
