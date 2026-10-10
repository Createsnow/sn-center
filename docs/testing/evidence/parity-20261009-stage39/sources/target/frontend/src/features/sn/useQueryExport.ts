import { useMutation } from '@tanstack/react-query';
import { useEffect, useEffectEvent, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError, type UserProfile } from '../../api/contract';
import { snQueryApi, type SnFilter } from '../../api/snQuery';
import { tasksApi, type Task } from '../../api/tasks';
import { useSnOperation } from './useSnOperation';
import { usePrototypeFeedback } from './usePrototypeFeedback';
import { queryErrorText } from './queryErrorText';
import { queryFileRecoverable, rejectedQueryAbsent } from './queryExportState';

/** 点击时固定筛选，任务完成后自动下载；未知响应保留原操作，下载失败只重下原文件。 */
export function useQueryExport(profile: UserProfile) {
  const { t } = useTranslation();
  const feedback = usePrototypeFeedback();
  const csv = useSnOperation(profile, 'QUERY_CSV', snQueryApi.export);
  const xlsx = useSnOperation(profile, 'QUERY_XLSX', snQueryApi.export);
  const handled = useRef(new Set<string>());
  const errors = useRef(new WeakSet<object>());
  const download = useMutation({
    mutationFn: async ({ task, format }: { task: Task; format: 'CSV' | 'XLSX' }) => {
      const file = await tasksApi.download(task.taskId);
      const url = URL.createObjectURL(file.blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = file.filename;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      return format;
    },
    onSuccess: (format) => (format === 'CSV' ? csv : xlsx).clear(),
    onError: (error, { format }) => {
      feedback.error(queryErrorText(error, t), 3, false);
      void (format === 'CSV' ? csv : xlsx).task.refetch();
    },
  });
  const recover = useMutation({
    mutationFn: ({ task }: { task: Task; format: 'CSV' | 'XLSX' }) => tasksApi.recover(task.taskId),
    onSuccess: (task, { format }) => {
      handled.current.delete(task.taskId);
      download.reset();
      (format === 'CSV' ? csv : xlsx).update(task);
    },
    onError: (error, { task, format }) => {
      // 恢复响应也可能中断；重新查询原任务，绝不另建导出快照。
      handled.current.delete(task.taskId);
      feedback.error(queryErrorText(error, t), 3, false);
      void (format === 'CSV' ? csv : xlsx).task.refetch();
    },
  });
  const observe = useEffectEvent((task: Task | undefined, format: 'CSV' | 'XLSX') => {
    if (!task || handled.current.has(task.taskId)) return;
    if (task.state === 'SUCCESS' && task.fileState === 'READY') {
      handled.current.add(task.taskId);
      download.mutate({ task, format });
    } else if (task.state === 'FAILED' || task.state === 'CANCELLED') {
      handled.current.add(task.taskId);
      feedback.error(
        queryErrorText(new ApiError(task.errorCode ?? 1000000000, 409, ''), t),
        3,
        false,
      );
    }
  });
  useEffect(() => {
    observe(csv.task.data, 'CSV');
  }, [csv.task.data]);
  useEffect(() => {
    observe(xlsx.task.data, 'XLSX');
  }, [xlsx.task.data]);
  const reject = useEffectEvent(() => {
    for (const work of [csv, xlsx]) {
      const error = work.mutation.error;
      if (error && !errors.current.has(error)) {
        errors.current.add(error);
        feedback.error(queryErrorText(error, t), 3, false);
      }
      if (work.operation && !work.task.data && rejectedQueryAbsent(error, work.task.error))
        work.clear();
    }
  });
  useEffect(() => {
    reject();
  }, [csv.mutation.error, xlsx.mutation.error, csv.task.error, xlsx.task.error]);
  const fileRecoveries = (
    [
      { work: csv, format: 'CSV' },
      { work: xlsx, format: 'XLSX' },
    ] as const
  ).flatMap(({ work, format }) =>
    queryFileRecoverable(work.task.data) ? [{ task: work.task.data, format }] : [],
  );
  const recoveries = [csv, xlsx].filter(
    (work) =>
      work.operation &&
      !work.mutation.isPending &&
      (work.task.isError ||
        (!work.task.data && work.mutation.isError) ||
        ['FAILED', 'CANCELLED'].includes(work.task.data?.state ?? '')),
  );
  return {
    holder: feedback.holder,
    busy: csv.busy || xlsx.busy || download.isPending || recover.isPending,
    recoveries,
    fileRecoveries,
    recover,
    download,
    begin: (format: 'CSV' | 'XLSX', filters: SnFilter) => {
      download.reset();
      (format === 'CSV' ? csv : xlsx).begin({ operationId: crypto.randomUUID(), format, filters });
    },
  };
}
