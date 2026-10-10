import { useMutation } from '@tanstack/react-query';
import { useEffect, useEffectEvent, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError, type UserProfile } from '../../api/contract';
import { snAuditApi, type SnAuditFilter } from '../../api/snAudit';
import { tasksApi } from '../../api/tasks';
import { useSnOperation } from './useSnOperation';
import { usePrototypeFeedback } from './usePrototypeFeedback';
import { printDownloadErrorText } from './printDownloadErrorText';
import { rejectedQueryAbsent } from './queryExportState';

/** 点击时固定审计草稿，正常完成直接下载；响应未知保留原意图和通用恢复入口。 */
export function useAuditExport(profile: UserProfile) {
  const { t } = useTranslation();
  const feedback = usePrototypeFeedback();
  const work = useSnOperation(profile, 'AUDIT_XLSX', snAuditApi.export);
  const handled = useRef(new Set<string>());
  const errors = useRef(new WeakSet<Error>());
  const download = useMutation({
    mutationFn: async (id: string) => {
      const file = await tasksApi.download(id);
      const url = URL.createObjectURL(file.blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = file.filename;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    },
    onSuccess: () => work.clear(),
    onError: (error) => {
      feedback.error(printDownloadErrorText(error, t), 3, false);
      void work.task.refetch();
    },
  });
  const observe = useEffectEvent(() => {
    const task = work.task.data;
    if (task && !handled.current.has(task.taskId)) {
      if (task.state === 'SUCCESS' && task.fileState === 'READY') {
        handled.current.add(task.taskId);
        download.mutate(task.taskId);
      } else if (task.state === 'FAILED' || task.state === 'CANCELLED') {
        handled.current.add(task.taskId);
        feedback.error(
          printDownloadErrorText(new ApiError(task.errorCode ?? 1000000000, 409, ''), t),
          3,
          false,
        );
      }
    }
    const error = work.mutation.error;
    if (error && !errors.current.has(error)) {
      errors.current.add(error);
      feedback.error(printDownloadErrorText(error, t), 3, false);
    }
    if (work.operation && !task && rejectedQueryAbsent(error, work.task.error)) work.clear();
  });
  useEffect(() => {
    observe();
  }, [work.task.data, work.task.error, work.mutation.error]);
  return {
    holder: feedback.holder,
    work,
    download,
    busy: work.busy || download.isPending,
    recovery:
      work.operation &&
      !work.mutation.isPending &&
      (work.task.isError ||
        work.mutation.isError ||
        ['FAILED', 'CANCELLED'].includes(work.task.data?.state ?? '') ||
        download.isError),
    begin: (filters: SnAuditFilter) => {
      download.reset();
      work.begin({ operationId: crypto.randomUUID(), format: 'XLSX', filters });
    },
  };
}
