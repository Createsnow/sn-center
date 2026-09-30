import { useQuery } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Descriptions,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { UserProfile } from '../../../api/contract';
import { snAuditApi, type SnAudit, type SnAuditFilter } from '../../../api/snAudit';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';
import { actionKeys } from '../../audit/auditLabels';
import { FactoryPicker } from '../components/FactoryPicker';
import { SnPageHead } from '../components/SnPageHead';
import { SnOperation } from '../components/SnOperation';
import { SnExportHistory } from '../components/SnExportHistory';
import { useSnOperation } from '../useSnOperation';

function initialFilter(): SnAuditFilter {
  const day = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  });
  const now = Date.now();
  return {
    storage: 'ALL',
    dateFrom: day.format(new Date(now - 30 * 86400000)),
    dateTo: day.format(new Date(now)),
  };
}

/** 操作痕迹保留原筛选、SN边界、状态和展开详情；归档沿用同范围，导出保存当前提交过滤的固定事实。 */
export function SnAuditPage({ profile }: { profile: UserProfile }) {
  const { t, i18n } = useTranslation();
  const [draft, setDraft] = useState<SnAuditFilter>(initialFilter),
    [filter, setFilter] = useState<SnAuditFilter>(initialFilter),
    [cursors, setCursors] = useState<(string | undefined)[]>([undefined]),
    [size, setSize] = useState(50),
    [selected, setSelected] = useState<SnAudit>();
  const key = ['sn', 'audit', profile.id, profile.authorizationVersion, filter],
    cursor = cursors.at(-1);
  const rows = useQuery({
      queryKey: [...key, cursor, size],
      queryFn: () => snAuditApi.items(filter, cursor, size),
    }),
    count = useQuery({ queryKey: [...key, 'count'], queryFn: () => snAuditApi.count(filter) }),
    meta = useQuery({
      queryKey: ['sn', 'audit-meta', profile.id, profile.authorizationVersion],
      queryFn: snAuditApi.meta,
    });
  const csv = useSnOperation(profile, 'AUDIT_CSV', snAuditApi.export),
    xlsx = useSnOperation(profile, 'AUDIT_XLSX', snAuditApi.export);
  const action = (value: string) =>
    actionKeys[value] ? t('audit.actions.' + actionKeys[value]) : value;
  const status = (value: string | null) =>
    value
      ? value
          .split(',')
          .map((v) => t('snAcquire.status.' + v, { defaultValue: v }))
          .join(' / ')
      : '—';
  const change = (field: keyof SnAuditFilter, value: string | undefined) =>
    setDraft((s) => ({ ...s, [field]: value || undefined }));
  const invalidDates = !!draft.dateFrom && !!draft.dateTo && draft.dateFrom > draft.dateTo;
  const search = () => {
    if (invalidDates) return;
    setCursors([undefined]);
    setFilter({ ...draft });
    void rows.refetch();
    void count.refetch();
  };
  return (
    <div className="sn-page">
      <SnPageHead title={t('snAudit.title')} description={t('snAudit.intro')}>
        <div className="sn-toolbar">
          {(['dateFrom', 'dateTo'] as const).map((field) => (
            <Input
              key={field}
              type="date"
              aria-label={t('snAudit.' + field)}
              value={draft[field] ?? ''}
              onChange={(e) => change(field, e.target.value)}
            />
          ))}
          <Select
            aria-label={t('snAudit.action')}
            placeholder={t('snAudit.action')}
            allowClear
            showSearch
            optionFilterProp="label"
            value={draft.action ?? undefined}
            onChange={(v) => change('action', v)}
            options={Object.keys(actionKeys)
              .filter((a) => a.startsWith('sn:'))
              .map((value) => ({ value, label: action(value) }))}
          />
          <Select
            aria-label={t('snAudit.result')}
            placeholder={t('snAudit.result')}
            allowClear
            value={draft.result ?? undefined}
            onChange={(v) => change('result', v)}
            options={['SUCCESS', 'FAILED'].map((value) => ({
              value,
              label: t('snAudit.results.' + value),
            }))}
          />
          <Select
            aria-label={t('snAudit.source')}
            placeholder={t('snAudit.source')}
            allowClear
            value={draft.source ?? undefined}
            onChange={(v) => change('source', v)}
            options={['PAGE', 'MES', 'API', 'SYSTEM'].map((value) => ({
              value,
              label: t('snAudit.sources.' + value),
            }))}
          />
          <FactoryPicker
            profile={profile}
            value={draft.factoryId ?? undefined}
            onChange={(v) => change('factoryId', v)}
          />
          {(['operator', 'pi', 'batchId', 'keyword'] as const).map((field) => (
            <Input
              key={field}
              aria-label={t('snAudit.' + field)}
              placeholder={t('snAudit.' + field)}
              value={draft[field] ?? ''}
              onChange={(e) => change(field, e.target.value)}
              onPressEnter={search}
              allowClear
            />
          ))}
          <Select
            aria-label={t('snAudit.storage')}
            value={draft.storage}
            onChange={(v) => change('storage', v)}
            options={['ALL', 'ONLINE', 'ARCHIVE'].map((value) => ({
              value,
              label: t('snAudit.storages.' + value),
            }))}
          />
          <Button
            type="primary"
            aria-label={t('snPages.common.search')}
            disabled={invalidDates}
            onClick={search}
          >
            {t('snPages.common.search')}
          </Button>
        </div>
      </SnPageHead>
      {invalidDates && <Alert type="warning" showIcon title={t('snAudit.invalidDates')} />}
      <ApiErrorAlert error={meta.error} />
      {meta.data && (
        <Typography.Paragraph type="secondary">
          {t('snAudit.retentionForever')} {t('snAudit.exportLimit', { n: meta.data.exportLimit })}
        </Typography.Paragraph>
      )}
      <SnOperation work={csv} fileMeaning="EXPORT" />
      <SnOperation work={xlsx} fileMeaning="EXPORT" />
      <Tabs
        items={[
          {
            key: 'items',
            label: t('snAudit.title'),
            children: (
              <>
                <Space wrap className="sn-toolbar">
                  <Typography.Text>
                    {t('snAudit.total', { n: count.data?.quantity ?? '—' })}
                  </Typography.Text>
                  {profile.permissions.includes('sn:audit:export') &&
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
                <Table<SnAudit>
                  rowKey={(row) => row.createdAt + row.id}
                  data-testid="sn-audit-items"
                  dataSource={rows.data?.items}
                  loading={rows.isFetching}
                  pagination={false}
                  scroll={{ x: 1850 }}
                  columns={[
                    {
                      title: t('snPages.common.time'),
                      dataIndex: 'createdAt',
                      width: 180,
                      render: (v: string) => new Date(v).toLocaleString(i18n.language),
                    },
                    {
                      title: t('snAudit.operator'),
                      width: 160,
                      render: (_, r) => r.operator + ' ' + r.operatorName,
                    },
                    {
                      title: t('snAudit.source'),
                      dataIndex: 'source',
                      render: (v: string) => t('snAudit.sources.' + v, { defaultValue: v }),
                    },
                    { title: t('snAudit.action'), dataIndex: 'action', render: action },
                    {
                      title: t('snAudit.result'),
                      dataIndex: 'result',
                      render: (v: string) => (
                        <Tag color={v === 'SUCCESS' ? 'green' : 'red'}>
                          {t('snAudit.results.' + v)}
                        </Tag>
                      ),
                    },
                    {
                      title: t('snPages.common.factory'),
                      render: (_, r) =>
                        [r.factoryCode, r.factoryName].filter(Boolean).join(' · ') || '—',
                    },
                    { title: 'PI', dataIndex: 'pi' },
                    {
                      title: t('snAudit.range'),
                      width: 200,
                      render: (_, r) => (
                        <span className="sn-mono">
                          {r.startSn ?? '—'}
                          {r.endSn && r.endSn !== r.startSn ? ' ~ ' + r.endSn : ''}
                        </span>
                      ),
                    },
                    { title: t('snPages.common.qty'), dataIndex: 'quantity' },
                    {
                      title: t('snAudit.status'),
                      width: 180,
                      render: (_, r) => status(r.beforeStatus) + ' → ' + status(r.afterStatus),
                    },
                    {
                      title: t('snAudit.error'),
                      dataIndex: 'errorCode',
                      render: (v: string | null) =>
                        v ? t('errors.' + v, { defaultValue: v }) : '—',
                    },
                    {
                      title: t('snPages.common.actions'),
                      fixed: 'right',
                      width: 90,
                      render: (_, r) => (
                        <Button
                          aria-label={t('snPages.common.detail')}
                          onClick={() => setSelected(r)}
                        >
                          {t('snPages.common.detail')}
                        </Button>
                      ),
                    },
                  ]}
                />
                <Space wrap className="sn-toolbar">
                  <Button
                    disabled={cursors.length === 1}
                    onClick={() => setCursors((c) => c.slice(0, -1))}
                  >
                    {t('snAcquire.previous')}
                  </Button>
                  <Button
                    disabled={!rows.data?.nextCursor}
                    onClick={() => setCursors((c) => [...c, rows.data!.nextCursor!])}
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
                    options={[20, 50, 100].map((value) => ({
                      value,
                      label: t('snQuery.rows', { n: value }),
                    }))}
                  />
                </Space>
              </>
            ),
          },
          ...(profile.permissions.includes('sn:audit:export')
            ? [
                {
                  key: 'exports',
                  label: t('snQuery.exports'),
                  children: (
                    <SnExportHistory profile={profile} scope="audit" load={snAuditApi.history} />
                  ),
                },
              ]
            : []),
        ]}
      />
      {selected && (
        <AuditDetails profile={profile} selected={selected} close={() => setSelected(undefined)} />
      )}
    </div>
  );
}
function AuditDetails({
  profile,
  selected,
  close,
}: {
  profile: UserProfile;
  selected: SnAudit;
  close: () => void;
}) {
  const { t } = useTranslation();
  const detail = useQuery({
    queryKey: [
      'sn',
      'audit-detail',
      profile.id,
      profile.authorizationVersion,
      selected.id,
      selected.createdAt,
    ],
    queryFn: () => snAuditApi.detail(selected),
  });
  const row = detail.data;
  const fields = [
    'customer',
    'material',
    'billNo',
    'batchId',
    'operationId',
    'transferId',
    'reason',
    'roles',
    'factoryCode',
    'factoryName',
    'toFactoryCode',
    'toPi',
    'traceId',
    'errorCode',
    'detail',
  ] as const;
  return (
    <Modal open width={900} title={t('snAudit.detail')} footer={null} onCancel={close}>
      <ApiErrorAlert error={detail.error} />
      {row && (
        <Descriptions
          bordered
          column={{ xs: 1, sm: 2 }}
          size="small"
          items={fields.map((field) => ({
            key: field,
            label: t('snAudit.fields.' + field),
            span: ['detail', 'reason'].includes(field) ? 2 : 1,
            children: (
              <Typography.Text
                style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}
                copyable={['operationId', 'transferId', 'traceId'].includes(field) && !!row[field]}
              >
                {row[field] ?? '—'}
              </Typography.Text>
            ),
          }))}
        />
      )}
    </Modal>
  );
}
