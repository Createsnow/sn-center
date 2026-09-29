/**
 * 生成请求 ID。局域网 HTTP（非安全上下文）下没有 crypto.randomUUID，需降级。
 * @param {{ randomUUID?: () => string, getRandomValues?: (arr: Uint8Array) => Uint8Array } | Crypto | undefined} webCrypto
 * @returns {string}
 */
export function newRequestId(webCrypto = globalThis.crypto) {
  if (webCrypto && typeof webCrypto.randomUUID === "function") {
    return webCrypto.randomUUID();
  }
  if (webCrypto && typeof webCrypto.getRandomValues === "function") {
    const bytes = webCrypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const hex = [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }
  return `${Date.now().toString(16)}-${Math.random().toString(16).slice(2, 18)}`;
}
