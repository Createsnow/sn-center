import type { components } from './generated/schema';
import { query, submit, replace } from './client';
import { isRecord } from './contract';
import { loadCatalog } from './catalog';

export type Factory = components['schemas']['FactoryVO'];
export type Organization = components['schemas']['OrganizationVO'];
export type FactorySync = components['schemas']['FactorySyncVO'];
export type SnRule = components['schemas']['RuleVO'];
export type RuleVersion = components['schemas']['RuleVersionVO'];
export type RuleChange = components['schemas']['RuleChangeVO'];
export type RuleCommand = components['schemas']['RuleSaveDTO'];
export type RulePreview = components['schemas']['RulePreviewVO'];
export type PreviewCommand = components['schemas']['RulePreviewDTO'];
export type Order = components['schemas']['OrderBillVO'];
export type OrderLine = components['schemas']['OrderLineVO'];
export type OrderMeta = components['schemas']['OrderMetaVO'];
export type OrderSync = components['schemas']['OrderSyncVO'];
export type OrderSchedule = components['schemas']['OrderScheduleVO'];
export type OrderScheduleCommand = components['schemas']['OrderScheduleDTO'];
export type OrderPi = components['schemas']['OrderPiVO'];
export interface Page<T> {
  items: T[];
  total: string;
  page: number;
  size: number;
}
export interface SnFilter {
  page?: number;
  size?: number;
  q?: string;
  customer?: string;
  pi?: string;
  factoryId?: string;
  scope?: string;
  pending?: boolean;
}
const decimal = (value: unknown): value is string =>
  typeof value === 'string' && /^(0|[1-9]\d*)$/.test(value);
/** ERP精确小数仅作为文本传输；ID/SN计数仍使用独立整数守卫。 */
export const plannedQuantity = (value: unknown): value is string =>
  typeof value === 'string' && /^(0|[1-9]\d*)(\.\d{1,4})?$/.test(value);
const id = (value: unknown): value is string => decimal(value) && value !== '0';
const text = (value: unknown): value is string => typeof value === 'string';
const date = (value: unknown): value is string => text(value) && Number.isFinite(Date.parse(value));
const nullable =
  <T>(guard: (value: unknown) => value is T) =>
  (value: unknown): value is T | null =>
    value === null || guard(value);
const fields = (
  value: Record<string, unknown>,
  names: string[],
  guard: (value: unknown) => boolean,
) => names.every((name) => guard(value[name]));
const strings = (value: unknown): value is string[] => Array.isArray(value) && value.every(text);
export const isFactory = (value: unknown): value is Factory =>
  isRecord(value) &&
  fields(value, ['id', 'version'], id) &&
  fields(value, ['code', 'name'], text) &&
  text(value.source) &&
  ['MANUAL', 'K3'].includes(value.source) &&
  typeof value.enabled === 'boolean' &&
  fields(value, ['createdAt', 'updatedAt'], date);
export const isVersion = (value: unknown): value is RuleVersion =>
  isRecord(value) &&
  fields(value, ['id', 'ruleId', 'version'], id) &&
  nullable(id)(value.createdBy) &&
  nullable(text)(value.createdByName) &&
  fields(value, ['prefix', 'suffix', 'alphabet'], text) &&
  typeof value.base === 'number' &&
  [10, 16, 32, 36].includes(value.base) &&
  typeof value.length === 'number' &&
  Number.isInteger(value.length) &&
  value.length >= 1 &&
  value.length <= 12 &&
  typeof value.used === 'boolean' &&
  typeof value.contentDigest === 'string' &&
  /^[a-f0-9]{64}$/.test(value.contentDigest) &&
  date(value.createdAt);
export const isRule = (value: unknown): value is SnRule =>
  isRecord(value) &&
  fields(value, ['id', 'revision', 'currentVersion'], id) &&
  fields(value, ['code', 'name', 'bindValue'], text) &&
  text(value.bindScope) &&
  ['GENERAL', 'CUSTOMER', 'PI'].includes(value.bindScope) &&
  isVersion(value.current);
export const isOrder = (value: unknown): value is Order =>
  isRecord(value) &&
  fields(value, ['id', 'snapshotVersion'], id) &&
  fields(
    value,
    [
      'sourceSystem',
      'sourceAccount',
      'sourceBillId',
      'billNo',
      'customer',
      'po',
      'pi',
      'orgId',
      'orgCode',
      'orgName',
    ],
    text,
  ) &&
  nullable(id)(value.factoryId) &&
  nullable(text)(value.factoryCode) &&
  decimal(value.totalQty) &&
  typeof value.quantityReady === 'boolean' &&
  fields(value, ['lineCount', 'generatedQty', 'allocatedQty', 'quota'], decimal) &&
  text(value.statuses) &&
  ['1', '2', '1,2'].includes(value.statuses) &&
  date(value.syncedAt);
export const isOrderPi = (value: unknown): value is OrderPi =>
  isRecord(value) &&
  text(value.pi) &&
  text(value.customer) &&
  decimal(value.totalQty) &&
  fields(value, ['bills', 'generatedQty', 'allocatedQty', 'pendingQuantity', 'quota'], decimal);
export const isLine = (value: unknown): value is OrderLine =>
  isRecord(value) &&
  fields(value, ['id', 'ordinal', 'snapshotVersion'], id) &&
  fields(value, ['sourceEntryId', 'material', 'materialName', 'demandBillNo'], text) &&
  plannedQuantity(value.qty) &&
  fields(value, ['generatedQty', 'allocatedQty', 'quota'], decimal) &&
  text(value.status) &&
  ['1', '2'].includes(value.status);
export const isSync = (value: unknown): value is OrderSync =>
  isRecord(value) &&
  typeof value.id === 'string' &&
  /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(value.id) &&
  text(value.mode) &&
  ['all', 'one', 'scheduled'].includes(value.mode) &&
  text(value.state) &&
  ['FETCHING', 'SUCCESS', 'FAILED'].includes(value.state) &&
  text(value.billNo) &&
  fields(value, ['baseVersion', 'rowsCount', 'billsCount'], decimal) &&
  nullable(id)(value.publishedVersion) &&
  (value.errorCode === null ||
    (typeof value.errorCode === 'number' && Number.isSafeInteger(value.errorCode))) &&
  typeof value.orgSyncFailed === 'boolean' &&
  date(value.startedAt) &&
  nullable(date)(value.finishedAt);
export const isSchedule = (value: unknown): value is OrderSchedule =>
  isRecord(value) &&
  typeof value.enabled === 'boolean' &&
  typeof value.workerEnabled === 'boolean' &&
  typeof value.intervalMinutes === 'number' &&
  Number.isInteger(value.intervalMinutes) &&
  value.intervalMinutes >= 10 &&
  value.intervalMinutes <= 10080 &&
  nullable(id)(value.ownerId) &&
  nullable(text)(value.ownerName) &&
  (value.lastOk === null || typeof value.lastOk === 'boolean') &&
  text(value.lastErrorMessage) &&
  nullable(text)(value.lastRunId) &&
  fields(value, ['nextRunAt', 'lastRunAt', 'updatedAt'], nullable(date)) &&
  fields(value, ['lastBills', 'lastRows'], nullable(decimal)) &&
  (value.lastState === null ||
    (typeof value.lastState === 'string' &&
      ['FETCHING', 'SUCCESS', 'FAILED'].includes(value.lastState))) &&
  (value.lastErrorCode === null ||
    (typeof value.lastErrorCode === 'number' && Number.isSafeInteger(value.lastErrorCode)));
export const isPreview = (value: unknown): value is RulePreview =>
  isRecord(value) &&
  decimal(value.maxSequence) &&
  Array.isArray(value.samples) &&
  value.samples.every(
    (sample: unknown) =>
      isRecord(sample) && decimal(sample.seqDecimal) && text(sample.seqText) && text(sample.sn),
  );
const isMeta = (value: unknown): value is OrderMeta =>
  isRecord(value) &&
  nullable(date)(value.syncedAt) &&
  fields(value, ['bills', 'rows', 'excludedEmptyCustomer'], decimal) &&
  strings(value.unmappedOrganizations);
const isOrganization = (value: unknown): value is Organization =>
  isRecord(value) &&
  id(value.id) &&
  fields(value, ['code', 'name'], text) &&
  nullable(id)(value.factoryId);
const isFactorySync = (value: unknown): value is FactorySync =>
  isRecord(value) &&
  fields(value, ['total', 'inserted', 'updated'], decimal) &&
  strings(value.conflicts);
const isRuleChange = (value: unknown): value is RuleChange =>
  isRecord(value) &&
  text(value.action) &&
  ['RENAMED', 'UPDATED', 'NEW_VERSION', 'UNCHANGED'].includes(value.action) &&
  isRule(value.rule);
/** 外部响应必须经unknown守卫，特别拒绝浮点数ID/额度；分页数字只用于UI位置。 */
export const pageGuard =
  <T>(guard: (value: unknown) => value is T) =>
  (value: unknown): value is Page<T> =>
    isRecord(value) &&
    Array.isArray(value.items) &&
    value.items.every(guard) &&
    decimal(value.total) &&
    typeof value.page === 'number' &&
    Number.isSafeInteger(value.page) &&
    value.page >= 1 &&
    typeof value.size === 'number' &&
    Number.isSafeInteger(value.size) &&
    value.size >= 1 &&
    value.size <= 100;
/** AntD分页位置限安全整数；业务总量始终保留字符串。 */
export const paginationTotal = (total?: string) =>
  BigInt(total ?? '0') > BigInt(Number.MAX_SAFE_INTEGER)
    ? Number.MAX_SAFE_INTEGER
    : Number(total ?? '0');
const url = (path: string, filter: SnFilter) => {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(filter))
    if (value !== undefined && value !== '') params.set(key, String(value));
  return `${path}?${params.toString()}`;
};
// 同步包含ERP读取/可选组织读取和发布；HTTP超时不自动重试，用同步记录核对结果。
const syncTimeout = 450_000;
export const snApi = {
  factories: (filter: SnFilter) => query(url('/sn/factories', filter), pageGuard(isFactory)),
  createFactory: (command: { code: string; name: string }) =>
    submit('/sn/factories', command, isFactory),
  syncFactories: () => submit('/sn/factories/sync', {}, isFactorySync, syncTimeout),
  organizations: (filter: SnFilter) =>
    query(url('/sn/factories/organizations', filter), pageGuard(isOrganization), syncTimeout),
  bindOrganization: (factory: Factory, orgId: string) =>
    submit(
      `/sn/factories/${factory.id}/organization`,
      { orgId, version: factory.version },
      isFactory,
      syncTimeout,
    ),
  rules: (filter: SnFilter) => query(url('/sn/rules', filter), pageGuard(isRule)),
  allRules: (filter: SnFilter, signal: AbortSignal) =>
    loadCatalog(
      (page) => query(url('/sn/rules', { ...filter, page, size: 100 }), pageGuard(isRule)),
      signal,
    ),
  versions: (ruleId: string, filter: SnFilter) =>
    query(url(`/sn/rules/${ruleId}/versions`, filter), pageGuard(isVersion)),
  allVersions: (ruleId: string, signal: AbortSignal) =>
    loadCatalog(
      (page) =>
        query(url(`/sn/rules/${ruleId}/versions`, { page, size: 100 }), pageGuard(isVersion)),
      signal,
    ),
  ruleCustomers: (q: string, signal: AbortSignal) =>
    loadCatalog(
      (page) => query(url('/sn/orders/customers', { q, page, size: 100 }), pageGuard(text)),
      signal,
      200,
    ),
  createRule: (command: RuleCommand) => submit('/sn/rules', command, isRule),
  updateRule: (ruleId: string, command: RuleCommand) =>
    submit(`/sn/rules/${ruleId}/update`, command, isRuleChange),
  preview: (command: PreviewCommand) => submit('/sn/rules/preview', command, isPreview),
  orders: (filter: SnFilter) => query(url('/sn/orders', filter), pageGuard(isOrder)),
  pis: (filter: SnFilter) => query(url('/sn/orders/pis', filter), pageGuard(isOrderPi)),
  lines: (orderId: string, filter: SnFilter) =>
    query(url(`/sn/orders/${orderId}/lines`, filter), pageGuard(isLine)),
  /** 分录区与原型一样连续展示；后台仍逐页读取，取消选单后停止请求后续页。 */
  async allLines(orderId: string, signal: AbortSignal): Promise<OrderLine[]> {
    const items: OrderLine[] = [];
    for (let page = 1; ; page++) {
      signal.throwIfAborted();
      const result = await query(
        url(`/sn/orders/${orderId}/lines`, { page, size: 100 }),
        pageGuard(isLine),
      );
      signal.throwIfAborted();
      items.push(...result.items);
      if (BigInt(items.length) >= BigInt(result.total)) return items;
      if (result.items.length === 0) throw new Error('Order lines changed during loading');
    }
  },
  meta: () => query('/sn/orders/meta', isMeta),
  customers: (filter: SnFilter) => query(url('/sn/orders/customers', filter), pageGuard(text)),
  syncOrders: (billNo?: string) => submit('/sn/orders/sync', { billNo }, isSync, syncTimeout),
  syncs: (filter: SnFilter) => query(url('/sn/orders/syncs', filter), pageGuard(isSync)),
  schedule: () => query('/sn/orders/sync-schedule', isSchedule),
  saveSchedule: (command: OrderScheduleCommand) =>
    replace('/sn/orders/sync-schedule', command, isSchedule),
};
