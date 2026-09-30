import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Descriptions, Popconfirm, Space, Typography } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { AuditFilter } from '../../../api/audit';
import type { UserProfile } from '../../../api/contract';
import { isOperationId, tasksApi, type ExportCommand, type Task } from '../../../api/tasks';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';

/** 只持久化分账号的操作UUID；载荷留在本次内存，网络未知或刷新均优先查询原操作。 */
export function AuditExportPanel({
  profile,
  filter,
}: {
  profile: UserProfile;
  filter: AuditFilter;
}) {
  const { t } = useTranslation();
  const client = useQueryClient();
  const storageKey = 'adtek-audit-operation:' + profile.id;
  const [operation, setOperation] = useState<string | undefined>(() => {
    const saved = localStorage.getItem(storageKey);
    return isOperationId(saved) ? saved : undefined;
  });
  const [pending, setPending] = useState<ExportCommand>();
  const key = ['audit-export', profile.id, profile.authorizationVersion, operation];
  const update = (task: Task) =>
    client.setQueryData(
      ['audit-export', profile.id, profile.authorizationVersion, task.operationId],
      task,
    );
  const submit = useMutation({ mutationFn: tasksApi.export, onSuccess: update });
  const task = useQuery({
    queryKey: key,
    queryFn: () => tasksApi.operation(operation!),
    enabled: !!operation && !submit.isPending,
    retry: false,
    refetchInterval: (query) =>
      query.state.data && ['QUEUED', 'RUNNING', 'RETRY_WAIT'].includes(query.state.data.state)
        ? 1000
        : false,
  });
  const cancel = useMutation({ mutationFn: tasksApi.cancel, onSuccess: update });
  const recover = useMutation({ mutationFn: tasksApi.recover, onSuccess: update });
  const download = useMutation({
    mutationFn: tasksApi.download,
    onSuccess: ({ blob, filename }) => {
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = filename;
      anchor.click();
      setTimeout(() => URL.revokeObjectURL(url), 0);
    },
    onError: () => {
      void task.refetch();
    },
  });
  const start = () => {
    const now = new Date();
    const input: ExportCommand = {
      ...filter,
      operationId: crypto.randomUUID(),
      from: filter.from ?? new Date(now.getTime() - 7 * 86400000).toISOString(),
      to: filter.to ?? now.toISOString(),
    };
    cancel.reset();
    recover.reset();
    download.reset();
    setPending(input);
    localStorage.setItem(storageKey, input.operationId);
    setOperation(input.operationId);
    submit.mutate(input);
  };
  const value = task.data;
  return (
    <section className="audit-export-panel">
      <Typography.Title level={4}>{t('export.title')}</Typography.Title>
      <Typography.Paragraph type="secondary">{t('export.help')}</Typography.Paragraph>
      <Space wrap>
        {operation ? (
          <Popconfirm
            title={t('export.newConfirm')}
            onConfirm={start}
            okText={t('users.confirm')}
            cancelText={t('users.cancel')}
          >
            <Button loading={submit.isPending}>{t('export.new')}</Button>
          </Popconfirm>
        ) : (
          <Button onClick={start} loading={submit.isPending}>
            {t('export.create')}
          </Button>
        )}
        {operation && (
          <Button
            onClick={() => {
              void task.refetch();
            }}
            loading={task.isFetching}
          >
            {t('export.check')}
          </Button>
        )}
        {submit.isError && !value && pending && (
          <Button onClick={() => submit.mutate(pending)} loading={submit.isPending}>
            {t('export.sameIntent')}
          </Button>
        )}
      </Space>
      {submit.isError && !value && (
        <Alert style={{ marginTop: 12 }} type="warning" showIcon title={t('export.unknown')} />
      )}
      <ApiErrorAlert
        error={
          (value ? task.error : (submit.error ?? task.error)) ??
          cancel.error ??
          recover.error ??
          download.error
        }
      />
      {operation && (
        <Typography.Paragraph style={{ marginTop: 12 }}>
          {t('export.operation')}:{' '}
          <Typography.Text copyable data-testid="export-operation">
            {operation}
          </Typography.Text>
        </Typography.Paragraph>
      )}
      {value && (
        <>
          <Descriptions
            column={{ xs: 1, sm: 2, lg: 3 }}
            size="small"
            items={[
              {
                key: 'task',
                label: t('export.task'),
                children: <span data-testid="export-task">{value.taskId}</span>,
              },
              {
                key: 'state',
                label: t('export.state'),
                children: t('export.states.' + value.state),
              },
              {
                key: 'operation',
                label: t('export.operationState'),
                children: t('export.operations.' + value.operationState),
              },
              {
                key: 'file',
                label: t('export.file'),
                children: value.fileState ? t('export.files.' + value.fileState) : '—',
              },
              { key: 'rows', label: t('export.rows'), children: value.rowCount ?? '—' },
              { key: 'progress', label: t('export.progress'), children: value.processed },
            ]}
          />
          {value.errorCode && (
            <Alert
              type="warning"
              title={t('errors.' + value.errorCode, { defaultValue: String(value.errorCode) })}
            />
          )}
          <Space wrap>
            {value.state === 'QUEUED' &&
              value.attempt === '0' &&
              value.operationState === 'PENDING' && (
                <Button onClick={() => cancel.mutate(value.taskId)} loading={cancel.isPending}>
                  {t('export.cancel')}
                </Button>
              )}
            {value.fileState === 'READY' && value.state === 'SUCCESS' && value.fileId && (
              <Button
                type="primary"
                onClick={() => download.mutate(value.fileId!)}
                loading={download.isPending}
              >
                {t('export.download')}
              </Button>
            )}
            {value.operationState === 'SUCCEEDED' &&
              (value.fileState === 'RECOVERING' || value.fileState === 'FAILED') && (
                <Button
                  onClick={() => {
                    download.reset();
                    recover.mutate(value.taskId);
                  }}
                  loading={recover.isPending}
                >
                  {t('export.recover')}
                </Button>
              )}
          </Space>
        </>
      )}
    </section>
  );
}
