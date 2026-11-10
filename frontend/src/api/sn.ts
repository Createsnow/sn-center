import {
  apiDownload,
  apiGet,
  apiPost,
  apiUpload,
  type DataParser,
  type QueryParams,
} from './client';

// sn 功能（溯码中台）的后端接口：/api/v1/sn/*。应答 data 为服务端文档（键为 camelCase；ID、计数为十进制字符串），
// 页面按需读取字段（见 src/features/sn/lib/row.ts），这里只校验是对象或数组。

/** 服务端文档的一行：字段按需读取，读取时经 str / num 等转换。 */
export type Row = Record<string, unknown>;

export interface Page<T = Row> {
  items: T[];
  total: string | number;
  page: number;
  pageSize: number;
  totalCapped?: boolean;
  [key: string]: unknown;
}

const object: DataParser<Row> = (data) => {
  if (typeof data !== 'object' || data === null || Array.isArray(data)) {
    throw new TypeError('Expected object');
  }
  return data as Row;
};
const list: DataParser<Row[]> = (data) => {
  if (!Array.isArray(data)) {
    throw new TypeError('Expected array');
  }
  return data as Row[];
};
const page: DataParser<Page> = (data) => {
  const row = object(data);
  if (!Array.isArray(row.items)) {
    throw new TypeError('Expected page');
  }
  return row as unknown as Page;
};
const any: DataParser<unknown> = (data) => data;

const enc = encodeURIComponent;
const get = <T>(path: string, parse: DataParser<T>, params?: QueryParams) =>
  apiGet(`/sn${path}`, parse, params);
const post = <T>(path: string, body: unknown, parse: DataParser<T>) =>
  apiPost(`/sn${path}`, body ?? {}, parse);
const slow = <T>(path: string, body: unknown, parse: DataParser<T>) =>
  apiPost(`/sn${path}`, body ?? {}, parse, { timeout: 0 });

export interface LineRef {
  billNo: string;
  seq: number;
}

export const snApi = {
  // ---------------- 账户与登录
  /** web-token：页面会话，令牌只存在本标签页（不同标签页可登录不同账户）。 */
  login: (empNo: string, password: string) =>
    apiPost('/sn/auth/login', { empNo, password }, object, {
      headers: { 'X-SN-Client': 'web-token' },
    }),
  me: () => get('/auth/me', object),
  changePassword: (oldPassword: string, newPassword: string) =>
    post('/auth/password', { oldPassword, newPassword }, any),
  setLang: (lang: string | null) => post('/auth/lang', { lang }, object),
  logout: () => post('/auth/logout', {}, any),
  users: (params?: QueryParams) => get('/users', page, params),
  createUser: (data: Row) => post('/users', data, object),
  updateUser: (id: string, data: Row) => post(`/users/${id}/update`, data, object),
  disableUser: (id: string) => post(`/users/${id}/disable`, {}, any),
  enableUser: (id: string) => post(`/users/${id}/enable`, {}, any),
  resetPassword: (id: string, password: string) =>
    post(`/users/${id}/reset-password`, { password }, any),
  resetSecret: (id: string) => post(`/users/${id}/reset-secret`, {}, object),
  viewSecret: (id: string) => get(`/users/${id}/secret`, object),

  // ---------------- 生产组织与 MES 对接配置
  factories: () => get('/factories', list),
  syncFactories: () => slow('/factories/sync', {}, object),
  saveMesConfig: (code: string, data: Row) => post(`/factories/${enc(code)}/mes-config`, data, object),
  testMes: (code: string, data: Row) => slow(`/factories/${enc(code)}/mes-test`, data, object),
  mesTogglePreview: (code: string) => get(`/factories/${enc(code)}/mes-toggle`, object),
  toggleMes: (code: string, enabled: boolean) =>
    post(`/factories/${enc(code)}/mes-toggle`, { enabled }, object),

  // ---------------- 金蝶连接
  k3Config: () => get('/k3-config', object),
  testK3Config: (data: Row) => slow('/k3-config/test', data, object),
  saveK3Config: (data: Row) => slow('/k3-config', data, object),
  resetK3Config: () => slow('/k3-config/reset', {}, object),

  // ---------------- 生产订单快照
  orders: (params?: QueryParams) => get('/orders', page, params),
  orderMeta: () => get('/orders/meta', object),
  orderSyncProgress: () => get('/orders/sync-progress', object),
  orderDepartments: (params?: QueryParams) => get('/orders/departments', any, params),
  orderLines: (billNo: string) => get(`/orders/${enc(billNo)}/lines`, object),
  orderCustomers: (q?: string) => get('/orders/customers', list, { q }),
  orderTargets: (q?: string, limit?: number, pi?: string) =>
    get('/orders/targets', list, { q, limit, pi }),
  syncOrder: (billNo: string) => slow('/orders/sync', { billNo }, object),
  compareOrders: () => slow('/orders/compare', {}, object),
  pageSyncOrders: () => post('/orders/page-sync', {}, any),
  rebuildOrders: () => slow('/orders/rebuild', {}, object),
  fillAttach: (billNo: string, seq: number, value: string) =>
    post('/orders/attach', { billNo, seq, value }, object),
  fillLength: (billNo: string, seq: number, value: string) =>
    post('/orders/length', { billNo, seq, value }, object),
  fillUnit: (billNo: string, seq: number, value: string) =>
    post('/orders/unit', { billNo, seq, value }, object),
  orderAlerts: (params?: QueryParams) => get('/orders/alerts', page, params),
  handleOrderAlert: (id: string, data: Row) => post(`/orders/alerts/${id}/handle`, data, any),

  // ---------------- 规则
  rules: (params?: QueryParams) => get('/rules', list, params),
  createRule: (data: Row) => post('/rules', data, object),
  updateRule: (id: string, data: Row) => post(`/rules/${id}/update`, data, object),
  deleteRule: (id: string) => post(`/rules/${id}/delete`, {}, object),
  previewRule: (data: Row) => post('/rules/preview', data, object),
  ruleOptions: (params: QueryParams) => get('/rules/options', object, params),
  selectLineRule: (data: Row) => post('/rules/line-select', data, object),
  clearLineRule: (data: Row) => post('/rules/line-clear', data, object),
  bindPiRule: (data: Row) => post('/rules/pi-binding', data, object),
  unbindPiRule: (pi: string) =>
    apiPost('/sn/rules/pi-binding/delete', {}, object, { params: { pi } }),

  // ---------------- SN 生成与删号
  genPis: (params?: QueryParams) => get('/generate/pis', object, params),
  genContext: (params: { pi?: string; billNo?: string }) => get('/generate/context', object, params),
  genPreview: (data: Row) => post('/generate/preview', data, object),
  genRun: (token: string) => post('/generate/run', { token }, object),
  genJobs: (pi: string, limit?: number) => get('/generate/jobs', any, { pi, limit }),
  genProgress: (batch: string) => get(`/generate/batches/${enc(batch)}`, object),
  cancelGen: (batch: string) => post(`/generate/batches/${enc(batch)}/cancel`, {}, object),
  genGaps: (pi: string) => get('/generate/gaps', object, { pi }),
  genSegments: (pi: string, billNo: string) => get('/generate/segments', object, { pi, billNo }),
  genSegmentSns: (params: QueryParams) => get('/generate/segments/sns', object, params),
  genDeletePreview: (data: Row) => post('/generate/delete/preview', data, object),
  genDelete: (data: Row) => post('/generate/delete', data, object),
  genDeleted: (pi: string) => get('/generate/deleted', any, { pi }),
  revokeConfig: (piNo: string, lines: LineRef[]) =>
    post('/generate/revoke-config', { piNo, lines }, object),

  // ---------------- SN 分配
  allocBills: (params?: QueryParams) => get('/allocation/bills', page, params),
  allocContext: (pi: string) => get('/allocation/context', object, { pi }),
  allocPreview: (data: Row) => post('/allocation/preview', data, object),
  allocate: (data: Row) => post('/allocation/allocate', data, object),

  // ---------------- 交付批次与推送
  deliveryBatches: (params?: QueryParams) => get('/delivery/batches', page, params),
  deliveryBatch: (batchNo: string) => get(`/delivery/batches/${enc(batchNo)}`, object),
  deliveryBatchItems: (batchNo: string, params?: QueryParams) =>
    get(`/delivery/batches/${enc(batchNo)}/items`, page, params),
  confirmBatch: (batchNo: string, reason: string, basis: string) =>
    post(`/delivery/batches/${enc(batchNo)}/confirm`, { reason, basis }, object),
  voidBatch: (batchNo: string, reason: string, basis: string) =>
    post(`/delivery/batches/${enc(batchNo)}/void`, { reason, basis }, object),
  resumePush: (factoryCode: string, key: string) =>
    post('/delivery/resume', { factoryCode, key }, object),
  remainingPush: (factoryCode: string, key: string) =>
    get('/delivery/remaining', object, { factoryCode, key }),
  deliveryAlerts: (params?: QueryParams) => get('/delivery/alerts', page, params),
  handleDeliveryAlert: (id: string, note: string) =>
    post(`/delivery/alerts/${id}/handle`, { note }, object),

  // ---------------- SN 导出（只读）
  exportBills: (params?: QueryParams) => get('/export/bills', page, params),
  exportLines: (params: QueryParams) => get('/export/lines', list, params),
  exportSegments: (params?: QueryParams) => get('/export/segments', page, params),
  exportItems: (params?: QueryParams) => get('/export/items', page, params),

  // ---------------- 转单 / 转组织
  transferSources: (params: QueryParams) => get('/transfers/sources', object, params),
  transferLocate: (q: string) => get('/transfers/locate', object, { q }),
  transferPreview: (data: Row) => post('/transfers/preview', data, object),
  transferCreate: (data: Row) => post('/transfers', data, object),
  transfers: (params?: QueryParams) => get('/transfers', page, params),
  billTransferOne: (id: string) => get(`/transfers/bill/${id}`, object),
  billTransferItems: (id: string, params?: QueryParams) =>
    get(`/transfers/bill/${id}/items`, page, params),
  orgTransfer: (id: string) => get(`/transfers/org/${id}`, object),
  orgTransferItems: (id: string, params?: QueryParams) =>
    get(`/transfers/org/${id}/items`, page, params),

  // ---------------- SN 导入：xlsx 直接作请求体
  snImportCheck: (file: Blob) => apiUpload('/sn/sn-import/check', file, object),
  snImportRun: (file: Blob, fileName: string) =>
    apiUpload('/sn/sn-import', file, object, { fileName }),
  snImportItems: (params?: QueryParams) => get('/sn-import/items', page, params),
  snImportSummary: (no: string) => get(`/sn-import/imports/${enc(no)}`, object),
  snImportDelete: (no: string) => post(`/sn-import/imports/${enc(no)}/delete`, {}, object),

  // ---------------- 查询与留痕
  items: (params?: QueryParams) => get('/sn', page, params),
  snExportCheck: (params?: QueryParams) => get('/sn/export-check', object, params),
  audits: (params?: QueryParams) => get('/audits', page, params),
  auditExportCheck: (params?: QueryParams) => get('/audits/export-check', object, params),
  auditMeta: () => get('/audits/meta', object),
  dashboard: () => get('/dashboard', object),
};

/** 文件下载（xlsx）。 */
export const snDownload = {
  exportFile: (params: QueryParams) => apiDownload('/sn/export/file', 'sn.xlsx', params),
  deliveryExport: (batchNo: string) =>
    apiDownload(`/sn/delivery/batches/${enc(batchNo)}/export`, `batch_${batchNo}.xlsx`),
  outList: (id: string) => apiDownload(`/sn/transfers/org/${id}/out-list`, `out_${id}.xlsx`),
  snExport: (params: QueryParams) => apiDownload('/sn/sn/export', 'sn_export.xlsx', params),
  auditExport: (params: QueryParams) => apiDownload('/sn/audits/export', 'audit_export.xlsx', params),
  snImportTemplate: () => apiDownload('/sn/sn-import/template', 'SN导入模板.xlsx'),
};
