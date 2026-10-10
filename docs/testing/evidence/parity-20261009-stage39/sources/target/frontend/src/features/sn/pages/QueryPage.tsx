import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Input, Pagination, Select, Switch, Table, Tag } from 'antd';
import { useEffect, useEffectEvent, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { UserProfile } from '../../../api/contract';
import { paginationTotal } from '../../../api/sn';
import { snQueryApi, type SnFilter, type SnQueryPage } from '../../../api/snQuery';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';
import { PrototypeIcon } from '../../../components/prototype/PrototypeIcon';
import { formatSnTime } from '../../../utils/snTime';
import { SnPageHead } from '../components/SnPageHead';
import { FactoryPicker } from '../components/FactoryPicker';
import { SnOperation } from '../components/SnOperation';
import { useSnContext } from '../useSnContext';
import { useQueryExport } from '../useQueryExport';
import { usePrototypeFeedback } from '../usePrototypeFeedback';
import { queryErrorText } from '../queryErrorText';

const statuses = ['PENDING_ALLOC', 'TO_ACQUIRE', 'TO_PRINT', 'PRINTED', 'APPLYING'] as const;
const colors = {
  PENDING_ALLOC: 'info',
  TO_ACQUIRE: 'primary',
  TO_PRINT: 'warning',
  PRINTED: 'success',
  APPLYING: 'danger',
};
const clearControl = { clearIcon: <PrototypeIcon name="CircleClose" /> };

/** 查询按按钮/分页提交当前输入；导出独立固定点击时的草稿，不沿用上次查询条件。 */
export function QueryPage({ profile }: { profile: UserProfile }) {
  const { t } = useTranslation('prototype');
  const { t: common } = useTranslation();
  const context = useSnContext(profile);
  const bound =
    !context.data?.allFactories && context.data?.factories.length === 1
      ? context.data.factories[0]
      : undefined;
  const isFactory = context.data?.role === 'factory_operator';
  const [draft, setDraft] = useState<SnFilter>({ pi: '', material: '', piAll: false });
  const [submitted, setSubmitted] = useState({
    filter: {} as SnFilter,
    page: 1,
    size: 20,
    revision: 0,
  });
  const rows = useQuery({
    queryKey: ['sn', 'query-page', profile.id, profile.authorizationVersion, submitted],
    queryFn: () => snQueryApi.page(submitted.filter, submitted.page, submitted.size),
    retry: false,
  });
  const identity = `${profile.id}:${profile.authorizationVersion}`;
  const [lastSuccess, setLastSuccess] = useState<{ identity: string; data?: SnQueryPage }>({
    identity,
  });
  // 失败保留本身份最后成功的结果；账号或授权版本变化立即丢弃旧范围的数据。
  if (lastSuccess.identity !== identity || (rows.data && lastSuccess.data !== rows.data))
    setLastSuccess({ identity, data: rows.data });
  const displayed = rows.data ?? (lastSuccess.identity === identity ? lastSuccess.data : undefined);
  const feedback = usePrototypeFeedback();
  const notified = useRef(new WeakSet<Error>());
  const showError = useEffectEvent((error: Error | null) => {
    if (error && !notified.current.has(error)) {
      notified.current.add(error);
      feedback.error(queryErrorText(error, common));
    }
  });
  useEffect(() => {
    showError(rows.error);
  }, [rows.error]);
  const exporting = useQueryExport(profile);
  const change = (field: keyof SnFilter, value: string | boolean | undefined) =>
    setDraft((current) => ({
      ...current,
      [field]: value,
      ...(field === 'pi' && !String(value ?? '').trim() ? { piAll: false } : {}),
    }));
  const filters = (): SnFilter => {
    const piAll = isFactory && !!draft.piAll && !!draft.pi?.trim();
    const material = draft.material?.trim();
    return {
      ...draft,
      factoryId: piAll ? undefined : (bound?.id ?? draft.factoryId),
      pi: draft.pi?.trim(),
      piAll,
      material,
      materialMode: material ? 'VALUE' : 'ALL',
    };
  };
  const load = (page = 1, size = submitted.size) =>
    setSubmitted((current) => ({ filter: filters(), page, size, revision: current.revision + 1 }));
  const input = (
    field: 'pi' | 'material' | 'customer' | 'sn' | 'billNo' | 'batchNo',
    label: string,
  ) => (
    <Input
      aria-label={label}
      placeholder={label}
      allowClear={clearControl}
      className={field === 'sn' ? 'sn-mono' : undefined}
      value={draft[field] ?? ''}
      onChange={(event) => change(field, event.target.value)}
    />
  );
  return (
    <div className="sn-page sn-query-page">
      {feedback.holder}
      {exporting.holder}
      <SnPageHead title={t('query.title')} description={t('query.desc')}>
        {profile.permissions.includes('sn:query:export') && (
          <>
            <Button
              loading={exporting.busy}
              icon={<PrototypeIcon name="Download" />}
              onClick={() => exporting.begin('XLSX', filters())}
            >
              {t('query.exportXlsx')}
            </Button>
            <Button loading={exporting.busy} onClick={() => exporting.begin('CSV', filters())}>
              CSV
            </Button>
          </>
        )}
      </SnPageHead>
      {exporting.recoveries.map((work) => (
        <SnOperation key={work.operation} work={work} fileMeaning="EXPORT" />
      ))}
      {exporting.fileRecoveries.map((file) => (
        <Button
          key={file.task.taskId}
          aria-label={file.format + ' ' + common('snAcquire.recoverFile')}
          loading={exporting.recover.isPending}
          onClick={() => exporting.recover.mutate(file)}
        >
          {file.format} {common('snAcquire.recoverFile')}
        </Button>
      ))}
      {exporting.download.isError && exporting.fileRecoveries.length === 0 && (
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
      <div className="sn-surface">
        <div className="sn-toolbar">
          {bound ? (
            <Tag className="sn-query-bound">{bound.name || bound.code}</Tag>
          ) : (
            <FactoryPicker
              profile={profile}
              value={draft.factoryId ?? undefined}
              disabled={!!draft.piAll}
              onChange={(value) => change('factoryId', value)}
            />
          )}
          {input('pi', 'PI')}
          {input('material', t('common.material'))}
          {input('customer', t('common.customer'))}
          <Select
            aria-label={t('common.status')}
            virtual={false}
            placeholder={t('common.status')}
            allowClear
            value={draft.status ?? undefined}
            onChange={(value) => change('status', value)}
            options={statuses.map((value) => ({ value, label: t('status.' + value) }))}
          />
          {input('sn', t('common.sn'))}
          {input('billNo', t('query.sourceBill'))}
          {input('batchNo', t('acquire.batchNo'))}
          {isFactory && (
            <label className="sn-acquire-pi-all">
              <Switch
                aria-label={t('acquire.piAll')}
                checked={!!draft.piAll}
                disabled={!draft.pi?.trim()}
                onChange={(value) => change('piAll', value)}
              />
              <span>{t('acquire.piAll')}</span>
            </label>
          )}
          <Button type="primary" onClick={() => load()}>
            {t('common.search')}
          </Button>
        </div>
        {draft.piAll && <Alert type="info" showIcon title={t('acquire.piAllReadOnly')} />}
        <ApiErrorAlert error={context.error} />
        <Table
          size="small"
          rowKey="id"
          data-testid="sn-query-items"
          loading={rows.isFetching}
          dataSource={displayed?.items}
          pagination={false}
          scroll={{ x: 1780 }}
          locale={{ emptyText: t('common.empty') }}
          columns={[
            {
              title: t('common.sn'),
              dataIndex: 'sn',
              fixed: 'left',
              width: 170,
              render: (value: string) => <span className="sn-mono">{value}</span>,
            },
            {
              title: t('common.seqText'),
              dataIndex: 'seqText',
              width: 110,
              render: (value: string) => <span className="sn-mono">{value}</span>,
            },
            { title: t('common.seqDec'), dataIndex: 'seqDec', width: 100 },
            { title: 'PI', dataIndex: 'pi', width: 150 },
            { title: t('common.material'), dataIndex: 'material', width: 110 },
            { title: t('common.customer'), dataIndex: 'customer', width: 90 },
            {
              title: t('common.factory'),
              dataIndex: 'factoryName',
              width: 110,
              render: (value: string | null, row) => value || row.factoryCode || '—',
            },
            {
              title: t('common.status'),
              dataIndex: 'status',
              width: 90,
              render: (value: (typeof statuses)[number]) => (
                <Tag className={'sn-query-status sn-query-status-' + colors[value]}>
                  {t('status.' + value)}
                </Tag>
              ),
            },
            { title: t('acquire.seqOwner'), dataIndex: 'seqPi', width: 150, ellipsis: true },
            { title: t('query.sourceBill'), dataIndex: 'billNo', width: 130 },
            { title: t('acquire.batchNo'), dataIndex: 'currentBatchNo', width: 170 },
            {
              title: t('query.source'),
              dataIndex: 'source',
              width: 80,
              render: (value: string) => t('query.src' + value),
            },
            {
              title: t('query.createdAt'),
              dataIndex: 'createdAt',
              width: 160,
              render: formatSnTime,
            },
            {
              title: t('query.printedAt'),
              dataIndex: 'printedAt',
              width: 160,
              render: (value: string | null) => (value ? formatSnTime(value) : ''),
            },
          ]}
        />
        <div className="sn-query-foot">
          {displayed?.totalCapped && (
            <span className="sn-muted">{t('query.capped', { n: displayed.total })}</span>
          )}
          <Pagination
            className="sn-acquire-pager"
            current={displayed?.page ?? submitted.page}
            pageSize={submitted.size}
            total={paginationTotal(displayed?.total)}
            showTotal={() => common('shell.paginationTotal', { total: displayed?.total ?? '0' })}
            showSizeChanger
            pageSizeOptions={[20, 50, 100, 500]}
            onChange={(page, size) => load(size === submitted.size ? page : 1, size)}
          />
        </div>
      </div>
    </div>
  );
}
