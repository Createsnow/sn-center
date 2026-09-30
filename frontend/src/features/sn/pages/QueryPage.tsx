import { useQuery } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Input,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tabs,
  Typography,
} from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { UserProfile } from '../../../api/contract';
import { snQueryApi, type SnFilter } from '../../../api/snQuery';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';
import { SnExportHistory } from '../components/SnExportHistory';
import { SnPageHead } from '../components/SnPageHead';
import { FactoryPicker } from '../components/FactoryPicker';
import { SnOperation } from '../components/SnOperation';
import { useSnOperation } from '../useSnOperation';

/** 当前视图和固定导出分开；切换过滤会清游标，导出复用当前已提交过滤及持久操作号。 */
export function QueryPage({ profile }: { profile: UserProfile }) {
  const { t } = useTranslation();
  const [draft, setDraft] = useState<SnFilter>({ materialMode: 'ALL', piAll: false }),
    [filter, setFilter] = useState<SnFilter>({ materialMode: 'ALL', piAll: false }),
    [size, setSize] = useState(50),
    [cursors, setCursors] = useState<(string | undefined)[]>([undefined]);
  const cursor = cursors.at(-1),
    key = ['sn', 'query', profile.id, profile.authorizationVersion, filter];
  const rows = useQuery({
    queryKey: [...key, cursor, size],
    queryFn: () => snQueryApi.items(filter, cursor, size),
  });
  const count = useQuery({ queryKey: [...key, 'count'], queryFn: () => snQueryApi.count(filter) });
  const csv = useSnOperation(profile, 'QUERY_CSV', snQueryApi.export),
    xlsx = useSnOperation(profile, 'QUERY_XLSX', snQueryApi.export);
  const change = (field: keyof SnFilter, value: string | boolean | undefined) =>
    setDraft((s) => ({ ...s, [field]: value }));
  const search = () => {
    setCursors([undefined]);
    setFilter({
      ...draft,
      factoryId: draft.piAll ? undefined : draft.factoryId,
      piAll: !!draft.piAll && !!draft.pi?.trim(),
    });
    void rows.refetch();
    void count.refetch();
  };
  return (
    <div className="sn-page">
      <SnPageHead title={t('snQuery.title')} description={t('snQuery.desc')}>
        <div className="sn-toolbar">
          <FactoryPicker
            profile={profile}
            value={draft.factoryId ?? undefined}
            onChange={(value) => change('factoryId', value)}
          />
          {(['customer', 'pi', 'sn', 'billNo', 'batchId'] as const).map((field) => (
            <Input
              key={field}
              aria-label={t('snQuery.fields.' + field)}
              placeholder={t('snQuery.fields.' + field)}
              value={draft[field] ?? ''}
              onChange={(e) => change(field, e.target.value)}
              onPressEnter={search}
              allowClear
            />
          ))}
          <Select
            aria-label={t('snAcquire.materialMode')}
            value={draft.materialMode}
            onChange={(v) => change('materialMode', v)}
            options={['ALL', 'EMPTY', 'VALUE'].map((value) => ({
              value,
              label: t('snAcquire.materialModes.' + value),
            }))}
          />
          {draft.materialMode === 'VALUE' && (
            <Input
              aria-label={t('snPages.common.material')}
              placeholder={t('snPages.common.material')}
              value={draft.material ?? ''}
              onChange={(e) => change('material', e.target.value)}
              onPressEnter={search}
            />
          )}
          <Select
            aria-label={t('snPages.common.status')}
            placeholder={t('snPages.common.status')}
            value={draft.status ?? undefined}
            allowClear
            onChange={(v) => change('status', v)}
            options={['PENDING_ALLOC', 'TO_ACQUIRE', 'TO_PRINT', 'PRINTED', 'APPLYING'].map(
              (value) => ({ value, label: t('snAcquire.status.' + value) }),
            )}
          />
          <Switch
            checked={draft.piAll}
            disabled={!draft.pi?.trim()}
            onChange={(v) => change('piAll', v)}
            checkedChildren={t('snAcquire.piAll')}
            unCheckedChildren={t('snAcquire.piAll')}
          />
          <Button
            type="primary"
            aria-label={t('snPages.common.search')}
            onClick={search}
            disabled={draft.materialMode === 'VALUE' && !draft.material}
          >
            {t('snPages.common.search')}
          </Button>
        </div>
      </SnPageHead>
      <SnOperation work={csv} fileMeaning="EXPORT" />
      <SnOperation work={xlsx} fileMeaning="EXPORT" />
      <Tabs
        items={[
          {
            key: 'items',
            label: t('snQuery.items'),
            children: (
              <>
                {filter.piAll && (
                  <Alert type="info" showIcon title={t('snAcquire.piAllReadOnly')} />
                )}
                <Space wrap className="sn-toolbar">
                  <Typography.Text>
                    {t('snQuery.total', { n: count.data?.quantity ?? '—' })}
                  </Typography.Text>
                  {profile.permissions.includes('sn:query:export') &&
                    (['CSV', 'XLSX'] as const).map((format) => (
                      <Popconfirm
                        key={format}
                        title={t('snQuery.exportConfirm')}
                        okText={t('users.confirm')}
                        cancelText={t('snPages.common.cancel')}
                        onConfirm={() =>
                          (format === 'CSV' ? csv : xlsx).begin({
                            operationId: crypto.randomUUID(),
                            format,
                            filters: filter,
                          })
                        }
                      >
                        <Button disabled={csv.busy || xlsx.busy}>
                          {t('snQuery.export', { format })}
                        </Button>
                      </Popconfirm>
                    ))}
                </Space>
                <ApiErrorAlert error={rows.error ?? count.error} />
                <Table
                  rowKey="id"
                  data-testid="sn-query-items"
                  dataSource={rows.data?.items}
                  loading={rows.isFetching}
                  pagination={false}
                  scroll={{ x: 2200 }}
                  columns={[
                    {
                      title: 'SN',
                      dataIndex: 'sn',
                      fixed: 'left',
                      width: 200,
                      render: (v: string) => (
                        <Typography.Text copyable className="sn-mono">
                          {v}
                        </Typography.Text>
                      ),
                    },
                    { title: t('snPages.common.seqText'), dataIndex: 'seqText' },
                    { title: t('snPages.common.seqDec'), dataIndex: 'seqDec' },
                    { title: 'PI', dataIndex: 'pi' },
                    { title: t('snPages.common.material'), dataIndex: 'material' },
                    { title: t('snPages.common.customer'), dataIndex: 'customer' },
                    {
                      title: t('snPages.common.factory'),
                      dataIndex: 'factoryCode',
                      render: (v: string | null) => v ?? '—',
                    },
                    {
                      title: t('snPages.common.status'),
                      dataIndex: 'status',
                      render: (v: string) => t('snAcquire.status.' + v),
                    },
                    { title: t('snAcquire.seqOwner'), dataIndex: 'seqPi' },
                    { title: t('snPages.common.billNo'), dataIndex: 'billNo' },
                    { title: t('snAcquire.batchNo'), dataIndex: 'currentBatchId' },
                    {
                      title: t('snQuery.source'),
                      dataIndex: 'source',
                      render: (v: string) => t('snQuery.sources.' + v),
                    },
                    {
                      title: t('snQuery.createdAt'),
                      dataIndex: 'createdAt',
                      render: (v: string) => new Date(v).toLocaleString(),
                    },
                    {
                      title: t('snQuery.printedAt'),
                      dataIndex: 'printedAt',
                      render: (v: string | null) => (v ? new Date(v).toLocaleString() : '—'),
                    },
                  ]}
                />
                <Space wrap className="sn-toolbar">
                  <Button
                    disabled={cursors.length === 1}
                    onClick={() => setCursors((v) => v.slice(0, -1))}
                  >
                    {t('snAcquire.previous')}
                  </Button>
                  <span>{cursors.length}</span>
                  <Button
                    disabled={!rows.data?.nextCursor}
                    onClick={() => setCursors((v) => [...v, rows.data!.nextCursor!])}
                  >
                    {t('snAcquire.next')}
                  </Button>
                  <Select
                    aria-label={t('snQuery.pageSize')}
                    value={size}
                    onChange={(v) => {
                      setSize(v);
                      setCursors([undefined]);
                    }}
                    options={[20, 50, 100, 500].map((value) => ({
                      value,
                      label: t('snQuery.rows', { n: value }),
                    }))}
                  />
                </Space>
              </>
            ),
          },
          ...(profile.permissions.includes('sn:query:export')
            ? [
                {
                  key: 'exports',
                  label: t('snQuery.exports'),
                  children: (
                    <SnExportHistory profile={profile} scope="query" load={snQueryApi.history} />
                  ),
                },
              ]
            : []),
        ]}
      />
    </div>
  );
}
