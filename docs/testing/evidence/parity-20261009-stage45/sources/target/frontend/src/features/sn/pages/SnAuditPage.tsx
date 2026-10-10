import { useQuery } from '@tanstack/react-query';
import { Button, DatePicker, Descriptions, Input, Pagination, Select, Table, Tag } from 'antd';
import dayjs from 'dayjs';
import { useEffect, useEffectEvent, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { UserProfile } from '../../../api/contract';
import { paginationTotal, type Page } from '../../../api/sn';
import { snAuditApi, type SnAudit, type SnAuditFilter } from '../../../api/snAudit';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';
import { PrototypeIcon } from '../../../components/prototype/PrototypeIcon';
import { formatSnTime } from '../../../utils/snTime';
import { FactoryPicker } from '../components/FactoryPicker';
import { SnPageHead } from '../components/SnPageHead';
import { SnOperation } from '../components/SnOperation';
import { useSnContext } from '../useSnContext';
import { useSnFactories } from '../useSnFactories';
import { useAuditExport } from '../useAuditExport';
import { usePrototypeFeedback } from '../usePrototypeFeedback';
import { printDownloadErrorText } from '../printDownloadErrorText';
import { auditActions, auditActionCode } from '../auditPresentation';

function initialFilter(): SnAuditFilter {
  return {
    storage: 'ALL',
    dateFrom: dayjs().subtract(30, 'day').format('YYYY-MM-DD'),
    dateTo: dayjs().format('YYYY-MM-DD'),
  };
}
const clearControl = { clearIcon: <PrototypeIcon name="CircleClose" /> };

/** 页面筛选/翻页按当前草稿提交；展开历史行，不把查看详情变成新的业务操作。 */
export function SnAuditPage({ profile }: { profile: UserProfile }) {
  const { t } = useTranslation('prototype');
  const { t: common } = useTranslation();
  const context = useSnContext(profile);
  const factories = useSnFactories(profile);
  const surface = useRef<HTMLDivElement>(null);
  const [tableWidth, setTableWidth] = useState(1468);
  useEffect(() => {
    const observer = new ResizeObserver(([entry]) => {
      if (entry) setTableWidth(Math.max(1468, Math.floor(entry.contentRect.width)));
    });
    if (surface.current) observer.observe(surface.current);
    return () => observer.disconnect();
  }, []);
  // 原型只向PI、号段和失败原因分配宽屏余量；取整余量归第一列。
  const extra = tableWidth - 1468;
  const rangeExtra = Math.floor((extra * 230) / 530);
  const errorExtra = Math.floor((extra * 160) / 530);
  const bound = !context.data?.allFactories && context.data?.factories.length === 1;
  const [draft, setDraft] = useState<SnAuditFilter>(initialFilter);
  const [submitted, setSubmitted] = useState(() => ({
    filter: initialFilter(),
    page: 1,
    size: 20,
    revision: 0,
  }));
  const rows = useQuery({
    queryKey: ['sn', 'audit-page', profile.id, profile.authorizationVersion, submitted],
    queryFn: () => snAuditApi.page(submitted.filter, submitted.page, submitted.size),
    // 离开页面再返回、重新提交相同筛选也重新读取，不能命中默认30秒旧结果。
    staleTime: 0,
    refetchOnWindowFocus: false,
    retry: false,
  });
  const identity = `${profile.id}:${profile.authorizationVersion}`;
  const [lastSuccess, setLastSuccess] = useState<{ identity: string; data?: Page<SnAudit> }>({
    identity,
  });
  if (lastSuccess.identity !== identity || (rows.data && lastSuccess.data !== rows.data))
    setLastSuccess({ identity, data: rows.data });
  const displayed = rows.data ?? (lastSuccess.identity === identity ? lastSuccess.data : undefined);
  const meta = useQuery({
    queryKey: ['sn', 'audit-meta', profile.id, profile.authorizationVersion],
    queryFn: snAuditApi.meta,
  });
  const feedback = usePrototypeFeedback();
  const notified = useRef(new WeakSet<Error>());
  const showError = useEffectEvent(() => {
    if (rows.error && !notified.current.has(rows.error)) {
      notified.current.add(rows.error);
      feedback.error(printDownloadErrorText(rows.error, common));
    }
  });
  useEffect(() => {
    showError();
  }, [rows.error]);
  const exporting = useAuditExport(profile);
  const change = (field: keyof SnAuditFilter, value: string | undefined) =>
    setDraft((current) => ({ ...current, [field]: value || undefined }));
  const load = (page = 1, size = submitted.size) =>
    setSubmitted((current) => ({
      filter: { ...draft },
      page,
      size,
      revision: current.revision + 1,
    }));
  const input = (field: 'operator' | 'pi' | 'batchId' | 'keyword', label: string) => (
    <Input
      aria-label={label}
      placeholder={label}
      allowClear={clearControl}
      value={draft[field] ?? ''}
      onChange={(event) => change(field, event.target.value)}
    />
  );
  const actionLabel = (value: string) =>
    t('actions.' + auditActionCode(value), { defaultValue: value });
  const statusLabel = (value: string | null) =>
    !value
      ? ''
      : value === 'PREVIOUS'
        ? t('audit.previous')
        : value
            .split(',')
            .map((part) => t('status.' + part, { defaultValue: part }))
            .join(' / ');
  const errorLabel = (value: string | null) =>
    value ? common('errors.' + value, { defaultValue: value }) : '';
  return (
    <div className="sn-page sn-query-page sn-audit-page">
      {feedback.holder}
      {exporting.holder}
      <SnPageHead
        title={t('audit.title')}
        description={meta.data ? t('audit.retentionForever') : ''}
      >
        {profile.permissions.includes('sn:audit:export') && (
          <Button
            loading={exporting.busy}
            icon={<PrototypeIcon name="Download" />}
            onClick={() => exporting.begin({ ...draft })}
          >
            {t('query.exportXlsx')}
          </Button>
        )}
      </SnPageHead>
      {exporting.recovery && <SnOperation work={exporting.work} fileMeaning="EXPORT" />}
      {exporting.download.isError && (
        <Button
          loading={exporting.download.isPending}
          onClick={() => {
            if (exporting.download.variables)
              exporting.download.mutate(exporting.download.variables);
          }}
        >
          {common('snAcquire.download')}
        </Button>
      )}
      <ApiErrorAlert error={meta.error ?? context.error} />
      <div className="sn-surface" ref={surface}>
        <div className="sn-toolbar">
          <DatePicker.RangePicker
            className="sn-audit-range"
            allowClear={clearControl}
            suffixIcon={<PrototypeIcon name="Calendar" />}
            format="YYYY-MM-DD"
            separator="-"
            placeholder={[t('audit.from'), t('audit.to')]}
            value={
              draft.dateFrom && draft.dateTo ? [dayjs(draft.dateFrom), dayjs(draft.dateTo)] : null
            }
            onChange={(dates) =>
              setDraft((current) => ({
                ...current,
                dateFrom: dates?.[0]?.format('YYYY-MM-DD'),
                dateTo: dates?.[1]?.format('YYYY-MM-DD'),
              }))
            }
          />
          <Select
            virtual={false}
            aria-label={t('audit.action')}
            placeholder={t('audit.action')}
            allowClear
            showSearch
            optionFilterProp="label"
            value={draft.action}
            onChange={(value) => change('action', value)}
            options={Object.entries(auditActions).map(([code, value]) => ({
              value,
              label: t('actions.' + code),
            }))}
          />
          <Select
            virtual={false}
            aria-label={t('audit.result')}
            placeholder={t('audit.result')}
            allowClear
            value={draft.result}
            onChange={(value) => change('result', value)}
            options={[
              { value: 'SUCCESS', label: t('audit.ok') },
              { value: 'FAILED', label: t('audit.fail') },
            ]}
          />
          <Select
            virtual={false}
            aria-label={t('audit.source')}
            placeholder={t('audit.source')}
            allowClear
            value={draft.source}
            onChange={(value) => change('source', value)}
            options={['PAGE', 'API', 'SYSTEM'].map((value) => ({
              value,
              label: t('audit.src' + value),
            }))}
          />
          {input('operator', t('audit.operator'))}
          {!bound && (
            <FactoryPicker
              profile={profile}
              value={draft.factoryId ?? undefined}
              onChange={(value) => change('factoryId', value)}
            />
          )}
          {input('pi', 'PI')}
          {input('batchId', t('acquire.batchNo'))}
          {input('keyword', t('audit.keyword'))}
          <Button type="primary" onClick={() => load()}>
            {t('common.search')}
          </Button>
        </div>
        <Table<SnAudit>
          size="small"
          rowKey={(row) => row.createdAt + row.id}
          data-testid="sn-audit-items"
          dataSource={displayed?.items}
          loading={rows.isFetching}
          pagination={false}
          scroll={{ x: tableWidth }}
          locale={{ emptyText: t('common.empty') }}
          expandable={{
            columnWidth: 48,
            expandedRowRender: (row) => (
              <Descriptions
                bordered
                column={3}
                size="small"
                className="sn-audit-details"
                items={[
                  { key: 'customer', label: t('common.customer'), children: row.customer },
                  { key: 'material', label: t('common.material'), children: row.material },
                  { key: 'bill', label: t('query.sourceBill'), children: row.billNo },
                  { key: 'batch', label: t('acquire.batchNo'), children: row.batchNo },
                  { key: 'request', label: t('acquire.requestNo'), children: row.operationId },
                  { key: 'transfer', label: t('transfer.no'), children: row.transferNo },
                  { key: 'reason', label: t('transfer.reason'), children: row.reason, span: 3 },
                  {
                    key: 'error',
                    label: t('audit.error'),
                    children: row.errorMessage ?? errorLabel(row.errorCode),
                    span: 3,
                  },
                  {
                    key: 'detail',
                    label: t('audit.detail'),
                    children: row.detail === '{}' ? '' : row.detail,
                    span: 3,
                  },
                  { key: 'trace', label: 'Trace ID', children: row.traceId, span: 3 },
                ]}
              />
            ),
            expandIcon: ({ expanded, onExpand, record }) => (
              <button
                type="button"
                className={'sn-audit-expand' + (expanded ? ' is-expanded' : '')}
                aria-label={t('audit.detail')}
                aria-expanded={expanded}
                onClick={(event) => onExpand(record, event)}
              >
                <PrototypeIcon name="ArrowDown" />
              </button>
            ),
          }}
          columns={[
            { title: t('common.time'), dataIndex: 'createdAt', width: 170, render: formatSnTime },
            {
              title: t('audit.operator'),
              width: 130,
              render: (_, row) => (
                <span className="sn-audit-actor">{row.operator + ' ' + row.operatorName}</span>
              ),
            },
            {
              title: t('audit.source'),
              dataIndex: 'source',
              width: 70,
              render: (value: string) => t('audit.src' + value, { defaultValue: value }),
            },
            { title: t('audit.action'), dataIndex: 'action', width: 120, render: actionLabel },
            {
              title: t('audit.result'),
              dataIndex: 'result',
              width: 70,
              render: (value: string) => (
                <Tag
                  className={
                    'sn-query-status sn-query-status-' +
                    (value === 'SUCCESS' ? 'success' : 'danger')
                  }
                >
                  {t(value === 'SUCCESS' ? 'audit.ok' : 'audit.fail')}
                </Tag>
              ),
            },
            {
              title: t('common.factory'),
              width: 110,
              render: (_, row) => (
                <span className="sn-audit-factory">
                  {factories.data?.find((factory) => factory.code === row.factoryCode)?.name ||
                    row.factoryCode ||
                    ''}
                </span>
              ),
            },
            {
              title: 'PI',
              dataIndex: 'pi',
              width: 140 + extra - rangeExtra - errorExtra,
              ellipsis: true,
            },
            {
              title: t('audit.range'),
              width: 230 + rangeExtra,
              render: (_, row) =>
                row.startSn ? (
                  <span className="sn-mono">
                    {row.startSn}
                    {row.endSn && row.endSn !== row.startSn ? ' ~ ' + row.endSn : ''}
                  </span>
                ) : (
                  ''
                ),
            },
            { title: t('common.qty'), dataIndex: 'quantity', align: 'right', width: 70 },
            {
              title: t('audit.status'),
              width: 150,
              render: (_, row) =>
                row.beforeStatus || row.afterStatus
                  ? (statusLabel(row.beforeStatus) || '—') +
                    ' → ' +
                    (statusLabel(row.afterStatus) || t('audit.previous'))
                  : '',
            },
            {
              title: t('audit.error'),
              dataIndex: 'errorCode',
              width: 160 + errorExtra,
              ellipsis: true,
              render: (_, row) => row.errorMessage ?? errorLabel(row.errorCode),
            },
          ]}
        />
        <div className="sn-query-foot">
          <Pagination
            className="sn-acquire-pager"
            current={displayed?.page ?? submitted.page}
            pageSize={submitted.size}
            total={paginationTotal(displayed?.total)}
            showTotal={() => common('shell.paginationTotal', { total: displayed?.total ?? '0' })}
            showSizeChanger
            pageSizeOptions={[20, 50, 100]}
            onChange={(page, size) => load(size === submitted.size ? page : 1, size)}
          />
        </div>
      </div>
    </div>
  );
}
