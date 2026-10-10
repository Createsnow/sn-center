import { useMutation, useQuery } from '@tanstack/react-query';
import { Button, Drawer, Pagination, Space, Table, Tag } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { UserProfile } from '../../../api/contract';
import { acquireApi, type AcquireBatch, type PrintRecord } from '../../../api/snAcquire';
import { paginationTotal } from '../../../api/sn';
import { formatSnTime } from '../../../utils/snTime';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';
import { usePrototypeFeedback } from '../usePrototypeFeedback';
import { printDownloadErrorText } from '../printDownloadErrorText';

type DetailsProps = {
  profile: UserProfile;
  id: string;
  displayNo?: string;
  onClose: () => void;
  result?: boolean;
};

/** 页面详情读取当前成员；任务分类保留独立固定游标，不能复用批次分页。 */
export function AcquireDetails(props: DetailsProps) {
  return props.result ? <AcquireResults {...props} /> : <CurrentBatchDetails {...props} />;
}

function CurrentBatchDetails({ profile, id, displayNo, onClose }: DetailsProps) {
  const { t } = useTranslation('prototype');
  const { t: common } = useTranslation();
  const [page, setPage] = useState(1);
  const batch = useQuery({
    queryKey: ['sn', 'current-batch-items', profile.id, profile.authorizationVersion, id, page],
    queryFn: () => acquireApi.currentItems(id, page),
  });
  return (
    <Drawer
      open
      title={t('acquire.batchDetail', { no: displayNo ?? id })}
      onClose={onClose}
      size={760}
      rootClassName="sn-acquire-detail"
    >
      <ApiErrorAlert error={batch.error} />
      <Table
        rowKey="id"
        size="small"
        data-testid="acquire-current-items"
        locale={{ emptyText: t('common.empty') }}
        dataSource={batch.data?.items}
        loading={batch.isFetching}
        pagination={false}
        scroll={{ x: 720 }}
        tableLayout="auto"
        columns={[
          {
            title: t('common.sn'),
            dataIndex: 'sn',
            minWidth: 170,
            render: (v: string) => <span className="sn-mono">{v}</span>,
          },
          {
            title: t('common.seqText'),
            dataIndex: 'seqText',
            width: 110,
            render: (v: string | null) => <span className="sn-mono">{v}</span>,
          },
          { title: t('common.seqDec'), dataIndex: 'seqDec', width: 100 },
          { title: t('common.material'), dataIndex: 'material', width: 110 },
          {
            title: t('common.status'),
            dataIndex: 'status',
            width: 90,
            render: (v: string) => (
              <Tag
                color={
                  (
                    {
                      TO_ACQUIRE: 'blue',
                      TO_PRINT: 'warning',
                      PRINTED: 'success',
                      APPLYING: 'error',
                    } as Record<string, string>
                  )[v]
                }
              >
                {t('status.' + v)}
              </Tag>
            ),
          },
          { title: 'PI', dataIndex: 'pi', minWidth: 140 },
        ]}
      />
      <Pagination
        className="sn-acquire-pager"
        current={page}
        pageSize={50}
        total={paginationTotal(batch.data?.total)}
        showSizeChanger={false}
        showTotal={() => common('shell.paginationTotal', { total: batch.data?.total ?? '0' })}
        onChange={setPage}
      />
    </Drawer>
  );
}

function AcquireResults({ profile, id, onClose }: DetailsProps) {
  const { t } = useTranslation();
  const [cursors, setCursors] = useState<(string | undefined)[]>([undefined]);
  const cursor = cursors[cursors.length - 1];
  const outcomes = useQuery({
    queryKey: ['sn', 'acquire-results', profile.id, profile.authorizationVersion, id, cursor],
    queryFn: () => acquireApi.results(id, cursor),
  });
  const next = outcomes.data?.nextCursor;
  return (
    <Drawer open title={t('snAcquire.resultItems')} onClose={onClose} size={960}>
      <ApiErrorAlert error={outcomes.error} />
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
            render: (v: string) => t('snAcquire.outcomes.' + v),
          },
        ]}
      />
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
  const { t } = useTranslation();
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
        size="small"
        data-testid="acquire-batches"
        dataSource={list.data?.items}
        loading={list.isFetching}
        scroll={{ x: 1100 }}
        tableLayout="auto"
        pagination={false}
        columns={[
          {
            title: t('snAcquire.batchNo'),
            minWidth: 190,
            render: (_, row) => row.batchNo ?? row.id,
          },
          { title: t('snPages.common.factory'), width: 120, dataIndex: 'factoryName' },
          { title: 'PI', minWidth: 150, dataIndex: 'pi' },
          { title: t('snPages.common.qty'), width: 80, align: 'right', dataIndex: 'quantity' },
          {
            title: t('prototype:audit.source'),
            dataIndex: 'origin',
            width: 80,
            render: (v: string) => t('prototype:audit.src' + v),
          },
          {
            title: t('snAcquire.requestNo'),
            minWidth: 150,
            dataIndex: 'operationId',
            render: (v: string) => (
              <span className="sn-acquire-request" title={v}>
                {v}
              </span>
            ),
          },
          {
            title: t('snPages.common.operator'),
            width: 90,
            render: (_, row) => row.actorName ?? '—',
          },
          {
            title: t('snPages.common.time'),
            dataIndex: 'createdAt',
            width: 160,
            render: (v: string) => formatSnTime(v),
          },
          {
            title: t('snPages.common.actions'),
            width: 80,
            render: (_, row) => (
              <Button type="link" onClick={() => setSelected(row)}>
                {t('snPages.common.detail')}
              </Button>
            ),
          },
        ]}
      />

      <Pagination
        className="sn-acquire-pager"
        current={page}
        pageSize={10}
        total={paginationTotal(list.data?.total)}
        showSizeChanger={false}
        showTotal={() => t('shell.paginationTotal', { total: list.data?.total ?? '0' })}
        onChange={setPage}
      />
      {selected && (
        <AcquireDetails
          key={selected.id}
          profile={profile}
          id={selected.id}
          displayNo={selected.batchNo ?? selected.id}
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
  const { t } = useTranslation();
  const feedback = usePrototypeFeedback();
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
  });
  const download = useMutation({
    mutationFn: acquireApi.download,
    onSuccess: (file) => {
      const url = URL.createObjectURL(file.blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = file.filename;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    },
    onError: (error) => feedback.error(printDownloadErrorText(error, t), 3, false),
  });
  const file = (row: PrintRecord, format: 'xlsx' | 'csv') =>
    profile.permissions.includes('sn:print:download') && (
      <Button
        type="link"
        aria-label={`${format.toUpperCase()} ${t('snAcquire.download')}`}
        loading={
          download.isPending &&
          download.variables?.id === row.id &&
          download.variables?.format === format
        }
        onClick={() => download.mutate({ id: row.id, format })}
      >
        {format}
      </Button>
    );
  return (
    <>
      {feedback.holder}
      <ApiErrorAlert error={list.error} />
      <Table
        rowKey="id"
        size="small"
        data-testid="acquire-prints"
        dataSource={list.data?.items}
        loading={list.isFetching}
        scroll={{ x: 930 }}
        tableLayout="auto"
        pagination={false}
        columns={[
          {
            title: t('prototype:acquire.printNo'),
            minWidth: 190,
            render: (_, row) => row.printNo ?? row.id,
          },
          { title: t('snPages.common.factory'), width: 120, dataIndex: 'factoryName' },
          { title: 'PI', minWidth: 150, dataIndex: 'pi' },
          { title: t('snPages.common.qty'), width: 80, align: 'right', dataIndex: 'quantity' },
          {
            title: t('snPages.common.operator'),
            width: 90,
            render: (_, row) => row.actorName ?? '—',
          },
          {
            title: t('snPages.common.time'),
            dataIndex: 'createdAt',
            width: 160,
            render: (v: string) => formatSnTime(v),
          },
          {
            title: t('prototype:acquire.file'),
            width: 140,
            render: (_, row) => (
              <Space>
                {file(row, 'xlsx')}
                {file(row, 'csv')}
              </Space>
            ),
          },
        ]}
      />
      <Pagination
        className="sn-acquire-pager"
        current={page}
        pageSize={10}
        total={paginationTotal(list.data?.total)}
        showSizeChanger={false}
        showTotal={() => t('shell.paginationTotal', { total: list.data?.total ?? '0' })}
        onChange={setPage}
      />
    </>
  );
}
