import { http } from "./http";

type Q = Record<string, unknown>;

/** 去掉空串 / null / undefined，避免后端把空串当作筛选条件。 */
export function clean(params?: Q): Q | undefined {
  if (!params) return params;
  const out: Q = {};
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== "") out[k] = v;
  });
  return out;
}

const get = <T = any>(url: string, params?: Q) => http.get<T, T>(url, { params: clean(params) });
const post = <T = any>(url: string, data?: unknown, silent = false) => http.post<T, T>(url, data, { silent });
const put = <T = any>(url: string, data?: unknown) => http.put<T, T>(url, data);
const del = <T = any>(url: string, params?: Q) => http.delete<T, T>(url, { params: clean(params) });
const enc = encodeURIComponent;

export const api = {
  // ---------------- 账户与登录
  /** 带 X-SN-Client: web：页面登录，同一账户只在最后登录的页面有效。 */
  login: (emp_no: string, password: string) =>
    http.post<any, any>("/auth/login", { emp_no, password }, { headers: { "X-SN-Client": "web" } }),
  me: () => get("/auth/me"),
  changePassword: (old_password: string, new_password: string) => post("/auth/password", { old_password, new_password }),
  setLang: (lang: string | null) => put("/auth/lang", { lang }),
  users: (params?: Q) => get("/users", params),
  createUser: (data: Q) => post("/users", data),
  updateUser: (id: number, data: Q) => put(`/users/${id}`, data),
  disableUser: (id: number) => post(`/users/${id}/disable`),
  enableUser: (id: number) => post(`/users/${id}/enable`),
  resetPassword: (id: number, password: string) => post(`/users/${id}/reset-password`, { password }),
  resetSecret: (id: number) => post(`/users/${id}/reset-secret`),
  viewSecret: (id: number) => get(`/users/${id}/secret`),

  // ---------------- 生产组织与 MES 对接配置
  factories: () => get("/factories"),
  syncFactories: () => http.post("/factories/sync", null, { timeout: 0 }),
  saveMesConfig: (code: string, data: Q) => put(`/factories/${enc(code)}/mes-config`, data),
  testMes: (code: string, data: Q) => post(`/factories/${enc(code)}/mes-test`, data),
  mesTogglePreview: (code: string) => get(`/factories/${enc(code)}/mes-toggle`),
  toggleMes: (code: string, enabled: boolean) => post(`/factories/${enc(code)}/mes-toggle`, { enabled }),

  // ---------------- 生产订单快照
  orders: (params?: Q) => get("/orders", params),
  orderMeta: () => get("/orders/meta"),
  orderLines: (billNo: string) => get(`/orders/${enc(billNo)}/lines`),
  orderCustomers: (q?: string) => get("/orders/customers", { q }),
  orderTargets: (q?: string, limit?: number, pi?: string) => get("/orders/targets", { q, limit, pi }),
  syncOrder: (bill_no: string) => http.post<any, any>("/orders/sync", { bill_no }, { timeout: 0 }),
  compareOrders: () => http.post<any, any>("/orders/compare", {}, { timeout: 0 }),
  pageSyncOrders: () => post("/orders/page-sync", {}, true),
  rebuildOrders: () => http.post<any, any>("/orders/rebuild", {}, { timeout: 0 }),
  fillAttach: (bill_no: string, seq: number, value: string) => put("/orders/attach", { bill_no, seq, value }),
  fillLength: (bill_no: string, seq: number, value: string) => put("/orders/length", { bill_no, seq, value }),
  fillUnit: (bill_no: string, seq: number, value: string) => put("/orders/unit", { bill_no, seq, value }),
  orderAlerts: (params?: Q) => get("/orders/alerts", params),
  handleOrderAlert: (id: number, data: Q) => post(`/orders/alerts/${id}/handle`, data),

  // ---------------- 规则
  rules: (params?: Q) => get("/rules", params),
  createRule: (data: Q) => post("/rules", data),
  updateRule: (id: number, data: Q) => put(`/rules/${id}`, data),
  deleteRule: (id: number) => del(`/rules/${id}`),
  previewRule: (data: Q) => post("/rules/preview", data, true),
  ruleOptions: (params: Q) => get("/rules/options", params),
  selectLineRule: (data: Q) => post("/rules/line-select", data),
  clearLineRule: (data: Q) => post("/rules/line-clear", data),
  bindPiRule: (data: Q) => put("/rules/pi-binding", data),
  unbindPiRule: (pi: string) => del("/rules/pi-binding", { pi }),
  customerTemplate: (data: Q) => post("/rules/customer-template", data),
  bindCustomerRule: (data: Q) => post("/rules/customer-binding", data),
  unbindCustomerRule: (customer: string) => del("/rules/customer-binding", { customer }),

  // ---------------- SN 生成与撤销
  genPis: (params?: Q) => get("/generate/pis", params),
  genContext: (params: { pi?: string; bill_no?: string }) => get("/generate/context", params),
  genPreview: (data: Q, silent = false) => post("/generate/preview", data, silent),
  genRun: (token: string) => post("/generate/run", { token }),
  genJobs: (pi: string, limit?: number) => get("/generate/jobs", { pi, limit }),
  genProgress: (batch: string) => get(`/generate/batches/${enc(batch)}`),
  cancelGen: (batch: string) => post(`/generate/batches/${enc(batch)}/cancel`, {}),
  /** 撤到所选物料行为止：从流水最末往前撤，流水排在所选行之后的物料行一并撤销；原因选填（不填后端记默认原因）。 */
  revokeLines: (pi_no: string, lines: { bill_no: string; seq: number }[], reason?: string) =>
    post("/generate/revoke", { pi_no, lines, reason }),
  /** 撤销物料行配置（待处理、可生成页签）：还没有号的行，物料行规则、手填的附件项次、米数、产品单位恢复为未配置；有号的行跳过。 */
  revokeConfig: (pi_no: string, lines: { bill_no: string; seq: number }[]) => post("/generate/revoke-config", { pi_no, lines }),

  // ---------------- SN 分配
  allocPis: (params?: Q) => get("/allocation/pis", params),
  allocContext: (pi: string) => get("/allocation/context", { pi }),
  allocPreview: (data: Q) => post("/allocation/preview", data),
  allocate: (data: Q) => post("/allocation/allocate", data),

  // ---------------- 交付批次与推送
  deliveryBatches: (params?: Q) => get("/delivery/batches", params),
  deliveryBatch: (batchNo: string) => get(`/delivery/batches/${enc(batchNo)}`),
  deliveryBatchItems: (batchNo: string, params?: Q) => get(`/delivery/batches/${enc(batchNo)}/items`, params),
  confirmBatch: (batchNo: string, reason: string, basis: string) => post(`/delivery/batches/${enc(batchNo)}/confirm`, { reason, basis }),
  voidBatch: (batchNo: string, reason: string, basis: string) => post(`/delivery/batches/${enc(batchNo)}/void`, { reason, basis }),
  resumePush: (factory_code: string, key: string) => post("/delivery/resume", { factory_code, key }),
  remainingPush: (factory_code: string, key: string) => get("/delivery/remaining", { factory_code, key }),
  deliveryAlerts: (params?: Q) => get("/delivery/alerts", params),
  handleDeliveryAlert: (id: number, note: string) => post(`/delivery/alerts/${id}/handle`, { note }),

  // ---------------- SN 导出（只读）
  exportBills: (params?: Q) => get("/export/bills", params),
  exportLines: (params: Q) => get("/export/lines", params),
  exportSegments: (params?: Q) => get("/export/segments", params),
  exportItems: (params?: Q) => get("/export/items", params),

  // ---------------- 转单 / 转组织
  transferSources: (params: Q) => get("/transfers/sources", params),
  billTransferPreview: (data: Q) => post("/transfers/bill/preview", data),
  billTransfer: (data: Q) => post("/transfers/bill", data),
  billTransfers: (params?: Q) => get("/transfers/bill", params),
  billTransferOne: (id: number) => get(`/transfers/bill/${id}`),
  billTransferItems: (id: number, params?: Q) => get(`/transfers/bill/${id}/items`, params),
  orgCandidates: (data: Q) => post("/transfers/org/candidates", data),
  orgApply: (data: Q) => post("/transfers/org", data),
  orgDirect: (data: Q) => post("/transfers/org/direct", data),
  transfers: (params?: Q) => get("/transfers", params),
  orgTransfers: (params?: Q) => get("/transfers/org", params),
  orgTransfer: (id: number) => get(`/transfers/org/${id}`),
  orgTransferItems: (id: number, params?: Q) => get(`/transfers/org/${id}/items`, params),
  approveOrg: (id: number, note?: string) => post(`/transfers/org/${id}/approve`, { note }),
  rejectOrg: (id: number, note?: string) => post(`/transfers/org/${id}/reject`, { note }),
  withdrawOrg: (id: number) => post(`/transfers/org/${id}/withdraw`),

  // ---------------- 查询与留痕
  items: (params?: Q) => get("/sn", params),
  snExportCheck: (params?: Q) => get("/sn/export-check", params),
  audits: (params?: Q) => get("/audits", params),
  auditExportCheck: (params?: Q) => get("/audits/export-check", params),
  auditMeta: () => get("/audits/meta"),
  dashboard: () => get("/dashboard"),
};

function withQuery(path: string, params: Q) {
  const q = new URLSearchParams();
  Object.entries(clean(params) || {}).forEach(([k, v]) => q.set(k, String(v)));
  const s = q.toString();
  return s ? `${path}?${s}` : path;
}

const base = import.meta.env.VITE_API_BASE_URL || "/api";
export const urls = {
  exportFile: (params: Q) => withQuery(`${base}/export/file`, params),
  deliveryExport: (batchNo: string) => `${base}/delivery/batches/${enc(batchNo)}/export`,
  outList: (id: number) => `${base}/transfers/org/${id}/out-list`,
  snExport: (params: Q) => withQuery(`${base}/sn/export`, params),
  auditExport: (params: Q) => withQuery(`${base}/audits/export`, params),
};

export default http;
