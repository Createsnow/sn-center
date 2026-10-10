import { ApiError } from '../../api/contract';
import type { Task } from '../../api/tasks';

/** 明确拒绝且原操作查询确认不存在，才释放意图；网络中断/冲突不能据此重建任务。 */
export function rejectedQueryAbsent(submission: unknown, lookup: unknown): boolean {
  if (
    !(submission instanceof ApiError) ||
    !(lookup instanceof ApiError) ||
    lookup.code !== 1000000005 ||
    lookup.status !== 404
  )
    return false;
  return (
    (submission.code === 1000000001 && submission.status === 400) ||
    (submission.code === 1003000004 && submission.status === 403) ||
    (submission.code === 1004000011 && submission.status === 409)
  );
}

/** 文件校验失败会标记 RECOVERING，但终态任务仍需要显式排队恢复原快照。 */
export function queryFileRecoverable(task: Task | undefined): task is Task {
  return (
    !!task &&
    task.operationState === 'SUCCEEDED' &&
    ['SUCCESS', 'FAILED'].includes(task.state) &&
    ['FAILED', 'RECOVERING'].includes(task.fileState ?? '')
  );
}
