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

export const api = {
  // 账户与登录
  login: (emp_no: string, password: string) => post("/auth/login", { emp_no, password }),
  me: () => get("/auth/me"),
  changePassword: (old_password: string, new_password: string) => post("/auth/password", { old_password, new_password }),
  setLang: (lang: string | null) => put("/auth/lang", { lang }),
  users: (params?: Q) => get("/users", params),
  createUser: (data: Q) => post("/users", data),
  updateUser: (id: number, data: Q) => put(`/users/${id}`, data),
  disableUser: (id: number) => post(`/users/${id}/disable`),
  enableUser: (id: number) => post(`/users/${id}/enable`),
  resetPassword: (id: number, password: string) => post(`/users/${id}/reset-password`, { password }),
  // 工厂
  factories: () => get("/factories"),
  syncFactories: () => http.post("/factories/sync", null, { timeout: 0 }),
  createFactory: (data: Q) => post("/factories", data),
  // 生产订单快照
  orders: (params?: Q) => get("/orders", params),
  orderMeta: () => get("/orders/meta"),
  orderLines: (billNo: string) => get(`/orders/${encodeURIComponent(billNo)}/lines`),
  orderCustomers: (q?: string) => get("/orders/customers", { q }),
  orderPis: (params?: Q) => get("/orders/pis", params),
  orderSyncSchedule: () => get("/orders/sync-schedule"),
  saveOrderSyncSchedule: (data: { enabled: boolean; interval_minutes?: number }) => put("/orders/sync-schedule", data),
  syncOrders: (bill_no?: string) => http.post("/orders/sync", bill_no ? { bill_no } : {}, { timeout: 0 }),
  // 规则
  rules: (params?: Q) => get("/rules", params),
  createRule: (data: Q) => post("/rules", data),
  updateRule: (id: number, data: Q) => put(`/rules/${id}`, data),
  previewRule: (data: Q) => post("/rules/preview", data, true),
  effectiveRule: (pi: string) => get("/rules/effective", { pi }),
  // 生成与分配
  genContext: (bill_no: string) => get("/generate/context", { bill_no }),
  genPreview: (data: Q) => post("/generate/preview", data),
  generate: (data: Q) => post("/generate", data),
  genJob: (id: number) => get(`/generate/jobs/${id}`),
  genJobs: (params?: Q) => get("/generate/jobs", params),
  allocate: (data: Q) => post("/generate/allocate", data),
  allocations: (bill_no: string) => get("/generate/allocations", { bill_no }),
  piStatus: (pi: string) => get("/pi-init", { pi }),
  piInit: (data: Q) => http.post("/pi-init", data, { timeout: 0 }),
  // 领取与打印
  acquirePis: (params?: Q) => get("/acquire/pis", params),
  acquireSegments: (params?: Q) => get("/acquire/segments", params),
  take: (data: Q, silent = false) => post("/acquire/take", data, silent),
  print: (data: Q, silent = false) => post("/acquire/print", data, silent),
  prints: (params?: Q) => get("/acquire/prints", params),
  batches: (params?: Q) => get("/acquire/batches", params),
  batchItems: (batchNo: string, params?: Q) => get(`/acquire/batches/${encodeURIComponent(batchNo)}/items`, params),
  // 转厂
  // 空物料（material_code = ""）是一个真实的物料分组，不能被 clean 去掉
  transferCandidates: (params: Q) =>
    http.get<any, any>("/transfers/candidates", { params: { ...clean(params), ...(params.material_code === "" ? { material_code: "" } : {}) } }),
  transferTargets: (q?: string) => get("/transfers/targets", { q }),
  applyTransfer: (data: Q) => post("/transfers", data),
  directTransfer: (data: Q) => post("/transfers/direct", data),
  approveTransfer: (id: number, note?: string) => post(`/transfers/${id}/approve`, { note }),
  rejectTransfer: (id: number, note?: string) => post(`/transfers/${id}/reject`, { note }),
  withdrawTransfer: (id: number) => post(`/transfers/${id}/withdraw`),
  transfers: (params?: Q) => get("/transfers", params),
  transferItems: (id: number, params?: Q) => get(`/transfers/${id}/items`, params),
  transfer: (id: number) => get(`/transfers/${id}`),
  // 查询与留痕
  items: (params?: Q) => get("/sn", params),
  audits: (params?: Q) => get("/audits", params),
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
  printFile: (printNo: string, format = "xlsx") =>
    withQuery(`${base}/acquire/prints/${encodeURIComponent(printNo)}/file`, { format }),
  snExport: (params: Q) => withQuery(`${base}/sn/export`, params),
  auditExport: (params: Q) => withQuery(`${base}/audits/export`, params),
};

export default http;
