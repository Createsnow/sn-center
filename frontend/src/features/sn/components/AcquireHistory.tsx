import { useMutation, useQuery } from '@tanstack/react-query';
import { Alert, Button, Drawer, Space, Table, Tag } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { UserProfile } from '../../../api/contract';
import { acquireApi, type AcquireBatch, type PrintRecord } from '../../../api/snAcquire';
import { paginationTotal } from '../../../api/sn';
import { tasksApi } from '../../../api/tasks';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';

/** 固定ordinal每页独立读取；关闭抽屉清掉游标，身份/权限版本变更用独立query key。 */
export function AcquireDetails({
  profile,
  id,
  onClose,
  result = false,
}: {
  profile: UserProfile;
  id: string;
  onClose: () => void;
  result?: boolean;
}) {
  const { t } = useTranslation();
  const [cursors, setCursors] = useState<(string | undefined)[]>([undefined]);
  const cursor = cursors[cursors.length - 1];
  const key = [
    'sn',
    'acquire-detail',
    profile.id,
    profile.authorizationVersion,
    id,
    result,
    cursor,
  ];
  const batch = useQuery({
    queryKey: [...key, 'batch'],
    queryFn: () => acquireApi.items(id, cursor),
    enabled: !result,
  });
  const outcomes = useQuery({
    queryKey: [...key, 'results'],
    queryFn: () => acquireApi.results(id, cursor),
    enabled: result,
  });
  const next = result ? outcomes.data?.nextCursor : batch.data?.nextCursor;
  return (
    <Drawer
      open
      title={result ? t('snAcquire.resultItems') : t('snAcquire.batchDetail', { no: id })}
      onClose={onClose}
      size={960}
    >
      <ApiErrorAlert error={result ? outcomes.error : batch.error} />
      {result ? (
        <Table
          rowKey="ordinal"
          dataSource={outcomes.data?.items}
          loading={outcomes.isFetching}
          pagination={false}
          scroll={{ x: 650 }}
          columns={[
            { title: t('snAcquire.ordinal'), dataIndex: 'ordinal' },
            { title: t('snPages.common.sn'), dataIndex: 'sn' },
            {
              title: t('snAcquire.outcome'),
              dataIndex: 'outcome',
              render: (value: string) => t('snAcquire.outcomes.' + value),
            },
          ]}
        />
      ) : (
        <>
          <Alert type="info" showIcon title={t('snAcquire.fixedBatch')} />
          <Table
            rowKey="ordinal"
            dataSource={batch.data?.items}
            loading={batch.isFetching}
            pagination={false}
            scroll={{ x: 1250 }}
            columns={[
              { title: t('snAcquire.ordinal'), dataIndex: 'ordinal' },
              { title: t('snPages.common.sn'), render: (_, row) => row.snapshot?.sn },
              {
                title: t('snPages.common.seqText'),
                render: (_, row) => row.snapshot?.seqText ?? '—',
              },
              {
                title: t('snPages.common.seqDec'),
                render: (_, row) => row.snapshot?.seqDec ?? '—',
              },
              { title: 'PI', render: (_, row) => row.snapshot?.pi },
              { title: t('snPages.common.factory'), render: (_, row) => row.snapshot?.factoryCode },
              { title: t('snPages.common.customer'), render: (_, row) => row.snapshot?.customer },
              {
                title: t('snPages.common.material'),
                render: (_, row) => row.snapshot?.material || t('snPages.orders.noMaterial'),
              },
              {
                title: t('snAcquire.currentStatus'),
                render: (_, row) => (
                  <Space orientation="vertical">
                    <Tag>
                      {row.currentStatus ? t('snAcquire.status.' + row.currentStatus) : '—'}
                    </Tag>
                    {!row.currentMember && (
                      <Tag color="warning">{t('snAcquire.noLongerCurrent')}</Tag>
                    )}
                  </Space>
                ),
              },
            ]}
          />
        </>
      )}
      <Space className="sn-operation-actions">
        <Button disabled={cursors.length === 1} onClick={() => setCursors((v) => v.slice(0, -1))}>
          {t('snAcquire.previous')}
        </Button>
        <Button disabled={!next} onClick={() => setCursors((v) => [...v, next ?? undefined])}>
          {t('snAcquire.next')}
        </Button>
      </Space>
    </Drawer>
  );
}

export function AcquireBatches({
  profile,
  factoryId,
  pi,
}: {
  profile: UserProfile;
  factoryId?: string;
  pi: string;
}) {
  const { t, i18n } = useTranslation();
  const [page, setPage] = useState(1),
    [selected, setSelected] = useState<AcquireBatch>();
  const list = useQuery({
    queryKey: [
      'sn',
      'acquire-batches',
      profile.id,
      profile.authorizationVersion,
      factoryId,
      pi,
      page,
    ],
    queryFn: () => acquireApi.batches({ factoryId, pi, page, size: 10 }),
  });
  return (
    <>
      <ApiErrorAlert error={list.error} />
      <Table
        rowKey="id"
        data-testid="acquire-batches"
        dataSource={list.data?.items}
        loading={list.isFetching}
        scroll={{ x: 1150 }}
        pagination={{
          current: page,
          pageSize: 10,
          total: paginationTotal(list.data?.total),
          showSizeChanger: false,
          onChange: setPage,
        }}
        columns={[
          { title: t('snAcquire.batchNo'), dataIndex: 'id' },
          { title: t('snPages.common.factory'), dataIndex: 'factoryCode' },
          { title: 'PI', dataIndex: 'pi' },
          { title: t('snPages.common.qty'), dataIndex: 'quantity' },
          {
            title: t('snAcquire.origin'),
            dataIndex: 'origin',
            render: (v: string) => t('snAcquire.origins.' + v),
          },
          { title: t('snAcquire.requestNo'), dataIndex: 'operationId' },
          { title: t('snPages.common.operator'), dataIndex: 'actorId' },
          {
            title: t('snPages.common.time'),
            dataIndex: 'createdAt',
            render: (v: string) => new Date(v).toLocaleString(i18n.language),
          },
          {
            title: t('snPages.common.actions'),
            render: (_, row) => (
              <Button type="link" onClick={() => setSelected(row)}>
                {t('snPages.common.detail')}
              </Button>
            ),
          },
        ]}
      />
      {selected && (
        <AcquireDetails
          key={selected.id}
          profile={profile}
          id={selected.id}
          onClose={() => setSelected(undefined)}
        />
      )}
    </>
  );
}

export function AcquirePrints({
  profile,
  factoryId,
  pi,
}: {
  profile: UserProfile;
  factoryId?: string;
  pi: string;
}) {
  const { t, i18n } = useTranslation();
  const [page, setPage] = useState(1);
  const list = useQuery({
    queryKey: [
      'sn',
      'acquire-prints',
      profile.id,
      profile.authorizationVersion,
      factoryId,
      pi,
      page,
    ],
    queryFn: () => acquireApi.prints({ factoryId, pi, page, size: 10 }),
    refetchInterval: (q) =>
      q.state.data?.items.some(
        (p) =>
          ['PREPARING', 'RECOVERING'].includes(p.xlsxState ?? '') ||
          ['PREPARING', 'RECOVERING'].includes(p.csvState ?? ''),
      )
        ? 1000
        : false,
  });
  const download = useMutation({
    mutationFn: tasksApi.download,
    onSuccess: (file) => {
      const url = URL.createObjectURL(file.blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = file.filename;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    },
    onError: () => {
      void list.refetch();
    },
  });
  const recover = useMutation({
    mutationFn: tasksApi.recover,
    onSuccess: () => {
      void list.refetch();
    },
  });
  const file = (row: PrintRecord, format: 'xlsx' | 'csv') => {
    const state = format === 'xlsx' ? row.xlsxState : row.csvState;
    const id = format === 'xlsx' ? row.id : row.csvTaskId;
    return (
      <Space wrap>
        <span>{format.toUpperCase()}</span>
        <Tag>{t('export.files.' + state)}</Tag>
        {profile.permissions.includes('sn:print:download') && (
          <Button
            type="link"
            aria-label={`${format.toUpperCase()} ${t('snAcquire.download')}`}
            disabled={state !== 'READY'}
            loading={download.isPending && download.variables === id}
            onClick={() => download.mutate(id!)}
          >
            {t('snAcquire.download')}
          </Button>
        )}
        {profile.permissions.includes('sn:print:execute') &&
          profile.permissions.includes('sn:print:download') &&
          profile.id === row.actorId &&
          ['FAILED', 'RECOVERING'].includes(state ?? '') && (
            <Button loading={recover.isPending} onClick={() => recover.mutate(id!)}>
              {t('snAcquire.recoverFile')}
            </Button>
          )}
      </Space>
    );
  };
  return (
    <>
      <Alert type="info" showIcon title={t('snAcquire.fixedPrint')} />
      <ApiErrorAlert error={list.error ?? download.error ?? recover.error} />
      <Table
        rowKey="id"
        data-testid="acquire-prints"
        dataSource={list.data?.items}
        loading={list.isFetching}
        scroll={{ x: 1300 }}
        pagination={{
          current: page,
          pageSize: 10,
          total: paginationTotal(list.data?.total),
          showSizeChanger: false,
          onChange: setPage,
        }}
        columns={[
          { title: t('snAcquire.printNo'), dataIndex: 'id' },
          { title: t('snPages.common.factory'), dataIndex: 'factoryCode' },
          { title: 'PI', dataIndex: 'pi' },
          { title: t('snPages.common.qty'), dataIndex: 'quantity' },
          { title: t('snPages.common.operator'), dataIndex: 'actorId' },
          {
            title: t('snPages.common.time'),
            dataIndex: 'createdAt',
            render: (v: string) => new Date(v).toLocaleString(i18n.language),
          },
          {
            title: t('snAcquire.file'),
            render: (_, row) => (
              <Space orientation="vertical">
                {file(row, 'xlsx')}
                {file(row, 'csv')}
              </Space>
            ),
          },
        ]}
      />
    </>
  );
}
