import { expect, test } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { paceComparisonLogin } from './login-budget';
import zh from '../../src/i18n/prototype/zh-CN.json' with { type: 'json' };
import words from '../../src/i18n/locales/zh-CN.json' with { type: 'json' };
import {
  customer,
  run,
  source,
  sns,
  fixture,
  login,
  sync,
  query,
  expectRows,
  capture,
  saveDownload,
} from './query-support';

type ExportFact = {
  id: string;
  operation_id: string;
  task_state: string;
  file_state: string;
  sha256: string;
  snapshot_digest: string;
  execution_epoch: number;
};
function recovery(action: string, task?: string) {
  return execFileSync(
    resolve('../../sn-center-python/.venv/Scripts/python.exe'),
    [
      '-X',
      'utf8',
      resolve('../tools/testing/parity-query-recovery-fixture.py'),
      action,
      ...(task ? ['--task', task] : []),
    ],
    { windowsHide: true, encoding: 'utf8' },
  );
}
const facts = () => JSON.parse(recovery('inspect')) as ExportFact[];

test('P11 durable export: same intent retry, response loss with reload and exact file recovery', async ({
  page,
}, info) => {
  test.skip(
    source(info),
    'Prototype streams exports synchronously; durable recovery is verified on migration only.',
  );
  test.setTimeout(300_000);
  page.setDefaultTimeout(25_000);
  expect(JSON.parse(readFileSync(resolve(run, 'extra-orders.json'), 'utf8'))).toEqual([]);
  fixture(info, 'begin');
  await login(
    page,
    'admin',
    readFileSync(
      resolve('../deploy/.secrets/comparison-20261008/java-admin-password'),
      'utf8',
    ).trim(),
  );
  let recoveryStarted = false;
  let complete = false;
  try {
    await sync(page, true);
    await paceComparisonLogin(page);
    fixture(info, 'seed');
    recovery('begin');
    recoveryStarted = true;
    const baseline = JSON.parse(fixture(info, 'observe'));
    await page.getByRole('menuitem', { name: zh.nav.query, exact: true }).click();
    await expect(page.getByRole('heading', { name: zh.query.title, exact: true })).toBeVisible();
    await page.getByPlaceholder(zh.common.customer, { exact: true }).fill(customer);
    await query(page, info);
    const expected = [...sns('A', 1, 2, 3), ...sns('B', 1, 2, 3)];
    await expectRows(page, info, [...sns('B', 3, 2, 1), ...sns('A', 3, 2, 1)]);
    const posted: { operationId: string; filters: { customer: string } }[] = [];
    page.on('request', (request) => {
      if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/v1/sn/exports')
        posted.push(request.postDataJSON() as (typeof posted)[number]);
    });
    // 请求未到服务端，原操作查询为空；通过页面的原号重试保留原始筛选。
    await page.route('**/api/v1/sn/exports', (route) => route.abort('failed'), { times: 1 });
    await page.getByRole('button', { name: 'CSV', exact: true }).click();
    await expect(page.getByText(words.export.unknown, { exact: true })).toBeVisible();
    await expect.poll(() => facts().length).toBe(0);
    await page
      .getByPlaceholder(zh.common.customer, { exact: true })
      .fill('CHANGED-DRAFT-NOT-SUBMITTED');
    await capture(page, info, '01-unaccepted-original-intent');
    let ready = page.waitForEvent('download');
    await page.getByRole('button', { name: words.export.sameIntent, exact: true }).click();
    await saveDownload(await ready, info, '02-same-intent', expected);
    expect(posted).toHaveLength(2);
    expect(posted[0]).toEqual(posted[1]);
    expect(posted[1]!.filters.customer).toBe(customer);
    expect(facts()).toHaveLength(1);
    const first = facts()[0]!;
    expect(first.operation_id).toBe(posted[0]!.operationId);

    // 服务端已接受但响应丢失；刷新只查询已保存的操作，不再次 POST 导出。
    await page.getByPlaceholder(zh.common.customer, { exact: true }).fill(customer);
    let acceptedId = '';
    await page.route(
      '**/api/v1/sn/exports',
      async (route) => {
        const response = await route.fetch();
        expect(response.status()).toBe(202);
        acceptedId = ((await response.json()) as { data: { taskId: string } }).data.taskId;
        await route.abort('failed');
      },
      { times: 1 },
    );
    await page.route('**/api/v1/sn/exports/operations/XLSX/*', (route) => route.abort('failed'), {
      times: 1,
    });
    await page.getByRole('button', { name: zh.query.exportXlsx, exact: true }).click();
    await expect(page.getByText(words.export.unknown, { exact: true })).toBeVisible();
    await expect.poll(() => acceptedId).not.toBe('');
    await capture(page, info, '03-accepted-response-lost');
    ready = page.waitForEvent('download');
    await page.reload();
    await saveDownload(await ready, info, '04-reload-original-export', expected, 'xlsx');
    expect(posted).toHaveLength(3);
    expect(facts()).toHaveLength(2);
    expect(facts().some((item) => item.id === acceptedId)).toBe(true);
    await capture(page, info, '04-reloaded');

    // 只移走本轮新建且已核验摘要的文件，使真实文件端点报告丢失；原有文件不动。
    await page.getByPlaceholder(zh.common.customer, { exact: true }).fill(customer);
    await query(page, info);
    let hidden: ExportFact | undefined;
    await page.route(
      '**/api/v1/files/*',
      async (route) => {
        const id = new URL(route.request().url()).pathname.split('/').at(-1)!;
        hidden = JSON.parse(recovery('hide', id)) as ExportFact;
        await route.continue();
      },
      { times: 1 },
    );
    await page.getByRole('button', { name: 'CSV', exact: true }).click();
    const repair = page.getByRole('button', {
      name: 'CSV ' + words.snAcquire.recoverFile,
      exact: true,
    });
    await expect(repair).toBeVisible();
    expect(hidden).toBeDefined();
    await expectRows(page, info, [...sns('B', 3, 2, 1), ...sns('A', 3, 2, 1)]);
    await capture(page, info, '05-missing-file');
    await page.reload();
    await expect(repair).toBeVisible();
    await capture(page, info, '06-recovery-after-reload');
    ready = page.waitForEvent('download');
    const repairResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' && r.url().endsWith('/tasks/' + hidden!.id + '/recover'),
    );
    await repair.click();
    expect((await repairResponse).ok()).toBe(true);
    await saveDownload(await ready, info, '07-recovered-original-file', expected);
    expect(posted).toHaveLength(4);
    const after = facts();
    expect(after).toHaveLength(3);
    const repaired = after.find((item) => item.id === hidden!.id)!;
    expect(repaired.sha256).toBe(hidden!.sha256);
    expect(repaired.operation_id).toBe(hidden!.operation_id);
    expect(repaired.snapshot_digest).toBe(hidden!.snapshot_digest);
    expect(Number(repaired.execution_epoch)).toBeGreaterThan(Number(hidden!.execution_epoch));
    await expect(repair).toBeHidden();
    await capture(page, info, '07-recovery-complete');
    // Excel 同样恢复固定文件；恢复请求已成功但响应丢失时，查询原任务即可自动完成下载。
    await page.getByPlaceholder(zh.common.customer, { exact: true }).fill(customer);
    await query(page, info);
    let excelHidden: ExportFact | undefined;
    await page.route(
      '**/api/v1/files/*',
      async (route) => {
        const id = new URL(route.request().url()).pathname.split('/').at(-1)!;
        excelHidden = JSON.parse(recovery('hide', id)) as ExportFact;
        await route.continue();
      },
      { times: 1 },
    );
    await page.getByRole('button', { name: zh.query.exportXlsx, exact: true }).click();
    const repairExcel = page.getByRole('button', {
      name: 'XLSX ' + words.snAcquire.recoverFile,
      exact: true,
    });
    await expect(repairExcel).toBeVisible();
    await capture(page, info, '08-missing-excel');
    let recoveryAccepted = false;
    await page.route(
      '**/tasks/' + excelHidden!.id + '/recover',
      async (route) => {
        const response = await route.fetch();
        expect(response.ok()).toBe(true);
        const body = (await response.json()) as { data: { taskId: string } };
        expect(body.data.taskId).toBe(excelHidden!.id);
        recoveryAccepted = true;
        await route.abort('failed');
      },
      { times: 1 },
    );
    ready = page.waitForEvent('download');
    await repairExcel.click();
    await saveDownload(await ready, info, '09-excel-recovery-response-lost', expected, 'xlsx');
    expect(recoveryAccepted).toBe(true);
    expect(posted).toHaveLength(5);
    const final = facts();
    expect(final).toHaveLength(4);
    const repairedExcel = final.find((item) => item.id === excelHidden!.id)!;
    expect(repairedExcel.sha256).toBe(excelHidden!.sha256);
    expect(repairedExcel.operation_id).toBe(excelHidden!.operation_id);
    expect(repairedExcel.snapshot_digest).toBe(excelHidden!.snapshot_digest);
    await expect(repairExcel).toBeHidden();
    await capture(page, info, '09-excel-recovery-complete');
    expect(JSON.parse(fixture(info, 'observe'))).toEqual(baseline);
    writeFileSync(
      info.outputPath('recovery-proof.json'),
      JSON.stringify(
        { posted, first, acceptedId, hidden, after, excelHidden, recoveryAccepted, final },
        null,
        2,
      ),
    );
    writeFileSync(info.outputPath('files-preserved.json'), recovery('finish'));
    complete = true;
  } finally {
    // 非终态保留夹具与私有备份用于排错，不能把收尾恢复冒充界面通过。
    if (!recoveryStarted || complete) {
      await expect.poll(() => Number(fixture(info, 'active')), { timeout: 60_000 }).toBe(0);
      await sync(page, false);
      fixture(info, 'clean');
      writeFileSync(
        info.outputPath('baseline-preserved.json'),
        readFileSync(resolve(run, 'acquire-clean-java.json')),
      );
    }
  }
});
