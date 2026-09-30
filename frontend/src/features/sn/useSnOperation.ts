import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import type { UserProfile } from '../../api/contract';
import { snWorkApi, type WorkKind } from '../../api/snWork';
import { isOperationId, tasksApi, type Task } from '../../api/tasks';
/** 浏览器只存分账号/动作的意图UUID；未知响应和刷新查询原操作，重试保留原载荷。 */
export function useSnOperation<T extends { operationId: string }>(
  profile: UserProfile,
  kind: WorkKind,
  send: (input: T) => Promise<Task>,
) {
  const client = useQueryClient(),
    storageKey = `adtek-sn-operation:${profile.id}:${kind}`;
  const [operation, setOperation] = useState<string | undefined>(() => {
    const value = localStorage.getItem(storageKey);
    return isOperationId(value) ? value : undefined;
  });
  const [pending, setPending] = useState<T>();
  const key = ['sn-operation', profile.id, profile.authorizationVersion, kind, operation];
  const update = (value: Task) =>
    client.setQueryData(
      ['sn-operation', profile.id, profile.authorizationVersion, kind, value.operationId],
      value,
    );
  const mutation = useMutation({ mutationFn: send, onSuccess: update });
  const task = useQuery({
    queryKey: key,
    queryFn: () => snWorkApi.operation(kind, operation!),
    enabled: !!operation && !mutation.isPending,
    retry: false,
    refetchInterval: (q) =>
      q.state.data && ['QUEUED', 'RUNNING', 'RETRY_WAIT'].includes(q.state.data.state)
        ? 1000
        : false,
  });
  const cancel = useMutation({ mutationFn: tasksApi.cancel, onSuccess: update });
  const state = task.data?.state;
  const operationState = task.data?.operationState;
  useEffect(() => {
    if (
      operationState === 'SUCCEEDED' ||
      (state && ['SUCCESS', 'FAILED', 'CANCELLED'].includes(state))
    )
      void client.invalidateQueries({ queryKey: ['sn'] });
  }, [state, operationState, task.data?.taskId, client]);
  const begin = (input: T) => {
    setPending(input);
    localStorage.setItem(storageKey, input.operationId);
    setOperation(input.operationId);
    mutation.mutate(input);
  };
  const busy =
    mutation.isPending ||
    (!!operation && (!task.data || ['QUEUED', 'RUNNING', 'RETRY_WAIT'].includes(task.data.state)));
  return {
    operation,
    task,
    mutation,
    cancel,
    begin,
    busy,
    retry: () => {
      if (pending) mutation.mutate(pending);
    },
    canRetry: !!pending,
    clear: () => {
      localStorage.removeItem(storageKey);
      setOperation(undefined);
      setPending(undefined);
      mutation.reset();
    },
  };
}
