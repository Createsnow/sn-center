import { Alert, Button, Space, Typography, Popconfirm } from 'antd';
import { useTranslation } from 'react-i18next';
import { ApiError } from '../../../api/contract';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';
import type { useSnOperation } from '../useSnOperation';
type OperationView = Pick<
  ReturnType<typeof useSnOperation>,
  'operation' | 'task' | 'cancel' | 'busy' | 'retry' | 'canRetry' | 'clear'
> & { mutation: { isPending: boolean; error: unknown } };
export function SnOperation({
  work,
  fileMeaning = 'PRINT',
}: {
  work: OperationView;
  fileMeaning?: 'PRINT' | 'EXPORT';
}) {
  const { t } = useTranslation();
  const value = work.task.data;
  if (!work.operation) return null;
  return (
    <div className="sn-operation" aria-live="polite">
      <Typography.Paragraph>
        {t('export.operation')}:{' '}
        <Typography.Text copyable data-testid="sn-operation">
          {work.operation}
        </Typography.Text>
      </Typography.Paragraph>
      {value ? (
        <Alert
          type={value.state === 'FAILED' ? 'error' : value.state === 'SUCCESS' ? 'success' : 'info'}
          showIcon
          title={<span data-testid="sn-task-state">{t('export.states.' + value.state)}</span>}
          description={
            <>
              {value.operationState === 'SUCCEEDED' && value.fileState
                ? t(
                    fileMeaning === 'PRINT'
                      ? 'snAcquire.printCommitted'
                      : 'snQuery.snapshotCommitted',
                    { state: t('export.files.' + value.fileState) },
                  )
                : value.state === 'SUCCESS'
                  ? t('snPages.work.completed')
                  : t('snPages.work.progress', { n: value.processed })}
              {value.errorCode !== null && <p>{t(`errors.${value.errorCode}`)}</p>}
            </>
          }
        />
      ) : (
        <Alert type="warning" showIcon title={t('export.unknown')} />
      )}
      <Space wrap className="sn-operation-actions">
        <Button
          onClick={() => {
            void work.task.refetch();
          }}
          loading={work.task.isFetching}
        >
          {t('export.check')}
        </Button>
        {!value && work.canRetry && (
          <Button onClick={work.retry} loading={work.mutation.isPending}>
            {t('export.sameIntent')}
          </Button>
        )}
        {value &&
          value.operationState === 'PENDING' &&
          ['QUEUED', 'RETRY_WAIT'].includes(value.state) && (
            <Button
              onClick={() => work.cancel.mutate(value.taskId)}
              loading={work.cancel.isPending}
            >
              {t('snPages.common.cancel')}
            </Button>
          )}
        {!work.mutation.isPending &&
          value &&
          ['SUCCESS', 'FAILED', 'CANCELLED'].includes(value.state) && (
            <Button onClick={work.clear}>{t('snPages.work.clear')}</Button>
          )}
        {!value &&
          !work.mutation.isPending &&
          work.task.error instanceof ApiError &&
          work.task.error.status === 404 && (
            <Popconfirm
              title={t('snPages.work.absentConfirm')}
              okText={t('users.confirm')}
              cancelText={t('snPages.common.cancel')}
              onConfirm={work.clear}
            >
              <Button>{t('snPages.work.clear')}</Button>
            </Popconfirm>
          )}
      </Space>
      <ApiErrorAlert
        error={
          (value ? work.task.error : (work.mutation.error ?? work.task.error)) ?? work.cancel.error
        }
      />
    </div>
  );
}
