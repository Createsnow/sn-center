import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useEffectEvent, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError, type UserProfile } from '../../api/contract';
import { acquireApi, type AcquireSummary, type AcquireWork } from '../../api/snAcquire';
import { type Task } from '../../api/tasks';
import { useSnOperation } from './useSnOperation';
import { usePrototypeFeedback } from './usePrototypeFeedback';
import { syncErrorText } from './syncErrorText';
import { printDownloadErrorText } from './printDownloadErrorText';

export type AcquireStep = 'TO_ACQUIRE' | 'TO_PRINT';
export const acquireRowKey = (row: AcquireSummary) => `${row.factoryId}:${row.pi}`;
export const acquireQuantity = (row: AcquireSummary, step: AcquireStep) =>
  step === 'TO_ACQUIRE' ? row.toAcquire : row.toPrint;

type Queue = {
  step: AcquireStep;
  bulk: boolean;
  rows: AcquireSummary[];
  index: number;
  ok: number;
  quantity: bigint;
  failed: string[];
};

/** 每张 PI 一个持久意图，明确终态后才提交下一张；未知响应停留原操作，不能重建请求。 */
export function useAcquireQueue(profile: UserProfile, refreshed: () => void) {
  const { t } = useTranslation('prototype');
  const { t: common } = useTranslation();
  const feedback = usePrototypeFeedback();
  const client = useQueryClient();
  const take = useSnOperation(profile, 'TAKE', acquireApi.take);
  const print = useSnOperation(profile, 'PRINT', acquireApi.print);
  const queue = useRef<Queue | null>(null);
  const [active, setActive] = useState('');
  const [exceptions, setExceptions] = useState<AcquireWork[]>([]);
  const handled = useRef(new Set<string>());
  const rejected = useRef(new WeakSet<object>());
  const errorText = (error: unknown) => syncErrorText(error, common);
  const next = (plan: Queue) => {
    const row = plan.rows[plan.index]!;
    setActive(plan.bulk ? `bulk:${plan.step}` : `${plan.step}:${acquireRowKey(row)}`);
    const work = plan.step === 'TO_ACQUIRE' ? take : print;
    work.begin({ operationId: crypto.randomUUID(), factoryId: row.factoryId, pi: row.pi });
  };
  const finishRow = async (
    result?: { quantity: string; batch?: string; blocked?: string },
    error?: unknown,
  ) => {
    const plan = queue.current;
    if (!plan) return;
    const row = plan.rows[plan.index]!;
    if (result) {
      plan.ok += 1;
      plan.quantity += BigInt(result.quantity);
      if (!plan.bulk)
        feedback.success(
          plan.step === 'TO_ACQUIRE'
            ? result.batch
              ? t('acquire.taken', { n: result.quantity, batch: result.batch })
              : common('snAcquire.result', {
                  eligible: result.quantity,
                  blocked: result.blocked ?? '0',
                })
            : t('acquire.printed', { n: result.quantity }),
        );
    } else {
      const message = errorText(error);
      plan.failed.push(`${row.pi}: ${message}`);
      if (!plan.bulk) feedback.error(message);
    }
    (plan.step === 'TO_ACQUIRE' ? take : print).clear();
    plan.index += 1;
    if (plan.index < plan.rows.length) next(plan);
    else {
      if (plan.bulk) {
        if (plan.ok)
          feedback.success(t('acquire.bulkDone', { n: plan.ok, q: plan.quantity.toString() }));
        if (plan.failed.length)
          feedback.error(
            t('acquire.bulkFailed', { n: plan.failed.length, list: plan.failed.join('; ') }),
            8,
          );
      }
      queue.current = null;
      setActive('');
      refreshed();
      await client.invalidateQueries({ queryKey: ['sn'] });
    }
  };
  const completion = useMutation({
    mutationFn: async ({ task, step }: { task: Task; step: AcquireStep }) => {
      if (task.state !== 'SUCCESS' && task.operationState !== 'SUCCEEDED')
        return { error: new ApiError(task.errorCode ?? 1000000000, 409, '') };
      if (step === 'TO_ACQUIRE') {
        const result = await acquireApi.work(task.taskId);
        if (result.blocked !== '0')
          setExceptions((values) => [
            ...values.filter((value) => value.taskId !== result.taskId),
            result,
          ]);
        return {
          result: {
            quantity: result.eligible,
            batch: result.batchNo ?? result.batchId ?? undefined,
            blocked: result.blocked,
          },
        };
      }
      const record = await acquireApi.printRecord(task.taskId);
      // 下载读取该打印单当前成员；失败不重新打印，可从历史记录再次下载。
      try {
        const file = await acquireApi.download({ id: record.id, format: 'xlsx' });
        const url = URL.createObjectURL(file.blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = file.filename;
        link.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
      } catch (error) {
        feedback.error(printDownloadErrorText(error, common), 3, false);
      }
      return { result: { quantity: record.quantity } };
    },
    onSuccess: (outcome) => finishRow(outcome.result, outcome.error),
    onError: (error) => feedback.error(errorText(error)),
  });
  const observe = useEffectEvent((task: Task, step: AcquireStep) => {
    if (!queue.current) {
      (step === 'TO_ACQUIRE' ? take : print).clear();
      void client.invalidateQueries({ queryKey: ['sn'] });
      return;
    }
    completion.mutate({ task, step });
  });
  useEffect(() => {
    for (const [task, step] of [
      [take.task.data, 'TO_ACQUIRE'],
      [print.task.data, 'TO_PRINT'],
    ] as const) {
      if (
        task &&
        ((step === 'TO_PRINT' && task.operationState === 'SUCCEEDED') ||
          ['SUCCESS', 'FAILED', 'CANCELLED'].includes(task.state)) &&
        !handled.current.has(task.taskId)
      ) {
        handled.current.add(task.taskId);
        observe(task, step);
      }
    }
  }, [take.task.data, print.task.data]);
  const reject = useEffectEvent((error: Error) => {
    if (error instanceof ApiError && error.status >= 400 && error.status < 500)
      void finishRow(undefined, error);
    else feedback.error(errorText(error));
  });
  useEffect(() => {
    for (const error of [take.mutation.error, print.mutation.error]) {
      if (error && !rejected.current.has(error)) {
        rejected.current.add(error);
        reject(error);
      }
    }
  }, [take.mutation.error, print.mutation.error]);
  const abandon = () => {
    queue.current = null;
    setActive('');
    completion.reset();
    refreshed();
  };
  return {
    holder: feedback.holder,
    exceptions,
    active,
    busy: !!active || take.busy || print.busy || completion.isPending || completion.isError,
    completion,
    take: {
      ...take,
      clear: () => {
        take.clear();
        abandon();
      },
    },
    print: {
      ...print,
      clear: () => {
        print.clear();
        abandon();
      },
    },
    start: (step: AcquireStep, rows: AcquireSummary[], bulk: boolean) => {
      if (queue.current || take.busy || print.busy || !rows.length) return;
      const plan: Queue = {
        step,
        rows: [...rows],
        bulk,
        index: 0,
        ok: 0,
        quantity: 0n,
        failed: [],
      };
      queue.current = plan;
      completion.reset();
      next(plan);
    },
  };
}
