import { expect, request, type APIRequestContext } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { protectTestSecrets } from '../privacy';

type Row = Record<string, unknown>;
type Batch = { id: string; number: string; factory: string; operation: string };
type Selection = { sns: string[] } | { ranges: { startSn: string; endSn: string }[] };

/** 真实MES调用端：凭据只在内存中，记录业务结果与HTTP状态，不记录认证响应。 */
export async function createMesProbe(
  source: boolean,
  username: string,
  password: string,
  pi: string,
  sns: string[],
  observations: unknown[],
) {
  const baseURL = source ? 'http://127.0.0.1:8001' : 'http://127.0.0.1:18081';
  const admin = await request.newContext({ baseURL });
  const client = await request.newContext({ baseURL });
  let personToken = '',
    token = '',
    serviceId = '',
    serviceVersion = '';
  let factoryIds: string[] = [];
  const factories = new Map<string, string>();
  const permissions = [
    'sn:acquire:view',
    'sn:acquire:take',
    'sn:acquire:callback',
    'sn:items:read',
  ];
  const raw = async (
    context: APIRequestContext,
    method: string,
    path: string,
    body: unknown,
    bearer: string,
    csrf?: string,
  ) =>
    context.fetch(path, {
      method,
      data: body,
      headers: {
        ...(bearer ? { Authorization: 'Bearer ' + bearer } : {}),
        ...(csrf ? { 'X-XSRF-TOKEN': csrf } : {}),
      },
    });
  const call = async (method: string, path: string, body?: unknown): Promise<Row> => {
    const response = await raw(client, method, path, body, token);
    expect(response.status(), method + ' ' + path).toBeGreaterThanOrEqual(200);
    expect(response.status(), method + ' ' + path).toBeLessThan(300);
    const value = await response.json();
    return (source ? value : value.data) as Row;
  };
  const manage = async (path: string, body: unknown): Promise<Row> => {
    const response = await admin.get('/api/v1/system/auth/csrf');
    const csrf = String((await response.json()).data.token);
    protectTestSecrets(csrf);
    const result = await raw(admin, 'POST', path, body, personToken, csrf);
    expect(result.status()).toBeGreaterThanOrEqual(200);
    expect(result.status()).toBeLessThan(300);
    return (await result.json()).data as Row;
  };
  if (source) {
    const auth = await admin.post('/api/auth/login', { data: { emp_no: username, password } });
    expect(auth.status()).toBe(200);
    token = String((await auth.json()).token);
    protectTestSecrets(token);
  } else {
    const auth = await manage('/api/v1/system/auth/login', { username, password });
    personToken = String(auth.accessToken);
    protectTestSecrets(personToken);
    const response = await raw(
      admin,
      'GET',
      '/api/v1/sn/factories?page=1&size=100',
      undefined,
      personToken,
    );
    expect(response.status()).toBe(200);
    const context = (await response.json()).data;
    for (const f of context.items as { id: string; code: string }[]) factories.set(f.code, f.id);
    factoryIds = ['CMP-A', 'CMP-B'].map((code) => factories.get(code)!);
    expect(factoryIds.every(Boolean)).toBe(true);
    const credential = await manage('/api/v1/system/service-clients', {
      displayName: 'MES闭环 ' + username,
      factoryIds,
      permissions,
    });
    const service = credential.client as { id: string; clientId: string; version: string };
    serviceId = service.id;
    serviceVersion = service.version;
    const secret = String(credential.clientSecret);
    protectTestSecrets(secret);
    const exchanged = await client.post('/api/v1/integrations/auth/token', {
      data: { clientId: service.clientId, clientSecret: secret },
    });
    expect(exchanged.status()).toBe(200);
    token = String((await exchanged.json()).data.accessToken);
    protectTestSecrets(token);
    const uiDenied = await raw(client, 'GET', '/api/v1/system/auth/me', undefined, token);
    expect(uiDenied.status()).toBe(403);
    observations.push({
      stage: 'service-identity',
      cannotUsePersonnelApi: true,
      boundFactories: ['CMP-A', 'CMP-B'],
    });
  }
  const finish = async (task: Row): Promise<Row> => {
    let result: Row = {};
    await expect
      .poll(
        async () => {
          result = await call('GET', '/api/v1/integrations/sn/results/' + task.taskId);
          return result.state;
        },
        { timeout: 30_000, intervals: [200, 400, 800] },
      )
      .not.toBe('PENDING');
    expect(result.state).toBe('SUCCESS');
    return result;
  };
  const pageAll = async (path: string): Promise<Row[]> => {
    const rows: Row[] = [];
    let cursor: string | undefined;
    for (let number = 1; number < 20; number++) {
      const separator = path.includes('?') ? '&' : '?';
      const params = source
        ? `page=${number}&page_size=2`
        : 'size=2' + (cursor ? '&cursor=' + encodeURIComponent(cursor) : '');
      const response = await call('GET', path + separator + params);
      const items = response.items as Row[];
      rows.push(...items);
      if (source ? rows.length >= Number(response.total) : !response.nextCursor) return rows;
      cursor = String(response.nextCursor);
    }
    throw new Error('MES pagination did not terminate');
  };
  const take = async (factory: string): Promise<Batch> => {
    const operation = randomUUID();
    const path = source ? '/api/open/acquire' : '/api/v1/integrations/sn/acquisitions';
    const body = source
      ? { factory_code: factory, pi, request_no: operation, page_size: 2 }
      : { factoryId: factories.get(factory), pi, operationId: operation };
    const first = await call('POST', path, body);
    const result = source ? (first.batch as Row) : await finish(first);
    const batch: Batch = {
      id: String(result.batchId ?? result.batch_no),
      number: String(result.batchNo ?? result.batch_no),
      factory,
      operation,
    };
    expect(Number(result.quantity ?? result.qty)).toBe(sns.length);
    const repeated = await call('POST', path, body);
    expect(source ? (repeated.batch as Row).batch_no : repeated.taskId).toBe(
      source ? batch.id : first.taskId,
    );
    const members = await pageAll(
      source
        ? '/api/open/batches/' + batch.id + '/items'
        : '/api/v1/integrations/sn/acquire-batches/' + batch.id + '/items',
    );
    const numbers = members.map((r) => (source ? r.sn : (r.snapshot as Row).sn));
    expect(numbers.sort()).toEqual(sns);
    observations.push({
      stage: 'take-' + factory,
      quantity: sns.length,
      members: numbers,
      replaySameBatch: true,
    });
    return batch;
  };
  const callback = async (
    batch: Batch,
    selection: Selection,
    stage: string,
    expected: { updated: number; already: number; blocked: number },
  ) => {
    const operationId = randomUUID();
    const body = source
      ? {
          batch_no: batch.id,
          target_status: 'PRINTED',
          ...('sns' in selection
            ? selection
            : { ranges: selection.ranges.map((r) => ({ start_sn: r.startSn, end_sn: r.endSn })) }),
        }
      : { operationId, batchId: batch.id, targetStatus: 'PRINTED', ...selection };
    const path = source ? '/api/open/callback' : '/api/v1/integrations/sn/print-confirmations';
    const first = await call('POST', path, body);
    const result = source ? first : await finish(first);
    const counts = {
      updated: Number(result.updated ?? result.updatedQty),
      already: Number(result.already_printed ?? result.alreadyQty),
      blocked: source ? (result.skipped as Row[]).length : Number(result.blocked),
    };
    expect(counts).toEqual(expected);
    const outcomes = source
      ? result.skipped
      : await pageAll('/api/v1/integrations/sn/results/' + first.taskId + '/items');
    if (!source) {
      const replay = await call('POST', path, body);
      expect(replay.taskId).toBe(first.taskId);
      expect(await finish(replay)).toEqual(result);
    }
    observations.push({ stage, ...counts, outcomes, immutableReplayChecked: !source });
  };
  const read = async (factory: string, expectedStatuses: string[]) => {
    const path = source
      ? '/api/open/sn?factory_code=' + factory + '&pi=' + pi
      : '/api/v1/integrations/sn/items?factoryId=' + factories.get(factory) + '&pi=' + pi;
    const values = await pageAll(path);
    const normalized = values
      .map((r) => ({
        sn: String(r.sn),
        status: String(r.status),
        seq: String(r.seq_dec ?? r.seqDec),
        pi: String(r.pi_no ?? r.pi),
      }))
      .sort((a, b) => a.sn.localeCompare(b.sn));
    expect(normalized.map((r) => r.sn)).toEqual(sns);
    expect(normalized.map((r) => r.status)).toEqual(expectedStatuses);
    observations.push({ stage: 'read-' + factory, members: normalized });
  };
  const rejectInvalid = async (batch: Batch) => {
    const path = source ? '/api/open/callback' : '/api/v1/integrations/sn/print-confirmations';
    const body = source
      ? { batch_no: batch.id, target_status: 'TO_PRINT', sns: [sns[0]] }
      : { operationId: randomUUID(), batchId: batch.id, targetStatus: 'TO_PRINT', sns: [sns[0]] };
    const response = await raw(client, 'POST', path, body, token);
    expect(response.status()).toBeGreaterThanOrEqual(400);
    expect(response.status()).toBeLessThan(500);
    observations.push({ stage: 'invalid-target-rejected', status: response.status() });
  };
  const close = async () => {
    try {
      if (!source && serviceId)
        await manage('/api/v1/system/service-clients/' + serviceId + '/update', {
          version: serviceVersion,
          enabled: false,
          grant: { displayName: 'MES闭环 ' + username, factoryIds, permissions },
        });
    } finally {
      await admin.dispose();
      await client.dispose();
    }
  };
  return { take, callback, read, rejectInvalid, close };
}
