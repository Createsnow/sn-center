/**
 * 复制文本到剪贴板，成功返回 true。
 * navigator.clipboard 只在安全上下文（HTTPS / localhost）可用；内网按 HTTP 部署时它是 undefined，
 * 退回 execCommand("copy")。临时文本框放进 container：在 el-dialog 里调用时须传弹窗内的元素，
 * 否则弹窗的焦点陷阱会把焦点抢回去，选区落空、复制不到内容。
 */
export async function copyText(text: string, container: HTMLElement = document.body): Promise<boolean> {
  if (window.isSecureContext && navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      /* 权限被拒等，退回 execCommand */
    }
  }
  const ta = document.createElement("textarea");
  ta.value = text;
  ta.setAttribute("readonly", "");
  ta.style.cssText = "position:fixed;top:0;left:0;width:1px;height:1px;opacity:0;";
  container.appendChild(ta);
  try {
    ta.focus();
    ta.select();
    ta.setSelectionRange(0, text.length);
    return document.execCommand("copy");
  } catch {
    return false;
  } finally {
    container.removeChild(ta);
  }
}
