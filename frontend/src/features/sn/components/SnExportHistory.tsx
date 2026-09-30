import { useMutation, useQuery } from '@tanstack/react-query';
import { Alert, Button, Space, Table } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { UserProfile } from '../../../api/contract';
import { paginationTotal } from '../../../api/sn';
import type { snQueryApi } from '../../../api/snQuery';
import { tasksApi } from '../../../api/tasks';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';
/** 查询和痕迹导出共用固定快照文件历史，恢复沿用原任务/操作号。 */
export function SnExportHistory({
  profile,
  scope,
  load,
}: {
  profile: UserProfile;
  scope: 'query' | 'audit';
  load: typeof snQueryApi.history;
}) {
  const { t } = useTranslation();
  const [page, setPage] = useState(1);
  const rows = useQuery({
    queryKey: ['sn', scope + '-exports', profile.id, profile.authorizationVersion, page],
    queryFn: () => load(page),
    refetchInterval: (q) =>
      q.state.data?.items.some((r) => ['PREPARING', 'RECOVERING'].includes(r.fileState))
        ? 1500
        : false,
  });
  const download = useMutation({
    mutationFn: tasksApi.download,
    onSuccess: (value) => {
      const url = URL.createObjectURL(value.blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = value.filename;
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    },
    onError: () => {
      void rows.refetch();
    },
  });
  const recover = useMutation({
    mutationFn: tasksApi.recover,
    onSuccess: () => {
      void rows.refetch();
    },
  });
  return (
    <>
      <Alert type="info" showIcon title={t('snQuery.fixed')} />
      <ApiErrorAlert error={rows.error ?? download.error ?? recover.error} />
      <Table
        rowKey="taskId"
        data-testid={'sn-' + scope + '-exports'}
        dataSource={rows.data?.items}
        loading={rows.isFetching}
        scroll={{ x: 900 }}
        pagination={{
          current: page,
          pageSize: 20,
          total: paginationTotal(rows.data?.total),
          onChange: setPage,
          showSizeChanger: false,
        }}
        columns={[
          { title: t('snQuery.exportId'), dataIndex: 'taskId' },
          { title: t('snPages.common.qty'), dataIndex: 'quantity' },
          { title: t('snQuery.format'), dataIndex: 'format' },
          {
            title: t('snPages.common.status'),
            dataIndex: 'fileState',
            render: (v: string) => t('export.files.' + v),
          },
          {
            title: t('snPages.common.time'),
            dataIndex: 'createdAt',
            render: (v: string) => new Date(v).toLocaleString(),
          },
          {
            title: t('snPages.common.actions'),
            render: (_, r) => (
              <Space>
                <Button
                  aria-label={r.format + ' ' + t('snAcquire.download')}
                  disabled={r.fileState !== 'READY'}
                  loading={download.isPending && download.variables === r.taskId}
                  onClick={() => download.mutate(r.taskId)}
                >
                  {t('snAcquire.download')}
                </Button>
                {r.fileState === 'FAILED' && (
                  <Button onClick={() => recover.mutate(r.taskId)} loading={recover.isPending}>
                    {t('snAcquire.recoverFile')}
                  </Button>
                )}
              </Space>
            ),
          },
        ]}
      />
    </>
  );
}
