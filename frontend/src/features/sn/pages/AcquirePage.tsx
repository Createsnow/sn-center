import { ExclamationCircleFilled } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Input, Modal, Radio, Switch, Table, Tabs, Tag } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { UserProfile } from '../../../api/contract';
import { loadCatalog } from '../../../api/catalog';
import { acquireApi, type AcquireSummary } from '../../../api/snAcquire';
import { paginationTotal } from '../../../api/sn';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';
import { SnPageHead } from '../components/SnPageHead';
import { FactoryPicker } from '../components/FactoryPicker';
import { SnOperation } from '../components/SnOperation';
import { AcquireBatches, AcquirePrints, AcquireDetails } from '../components/AcquireHistory';
import { useSnContext } from '../useSnContext';
import {
  useAcquireQueue,
  acquireQuantity,
  acquireRowKey,
  type AcquireStep,
} from '../useAcquireQueue';

type Filter = { factoryId?: string; pi: string; piAll: boolean };
const STEPS: AcquireStep[] = ['TO_ACQUIRE', 'TO_PRINT'];
const emptySelection = () => ({ TO_ACQUIRE: [] as string[], TO_PRINT: [] as string[] });

/** 保留工序、批量和聚焦浏览；所有写入按整厂 + PI 执行，号段筛选不能缩小命令范围。 */
export function AcquirePage({ profile }: { profile: UserProfile }) {
  const { t } = useTranslation('prototype');
  const { t: common } = useTranslation();
  const context = useSnContext(profile);
  const [modal, modalHolder] = Modal.useModal();
  const [draft, setDraft] = useState<Filter>({ pi: '', piAll: false });
  const [filter, setFilter] = useState<Filter>(draft);
  const [revision, setRevision] = useState(0);
  const [tab, setTab] = useState('TO_ACQUIRE');
  const [focus, setFocus] = useState<AcquireSummary>();
  const [resultId, setResultId] = useState<string>();
  const [picked, setPicked] = useState(emptySelection);
  const [material, setMaterial] = useState('');
  const [segment, setSegment] = useState({ material: '', status: '', page: 1, size: 20 });
  const actions = useAcquireQueue(profile, () => setPicked(emptySelection()));
  const isFactory = context.data?.role === 'factory_operator';
  const bound =
    !context.data?.allFactories && context.data?.factories.length === 1
      ? context.data.factories[0]
      : undefined;
  const scope = focus
    ? { factoryId: focus.factoryId, pi: focus.pi }
    : { factoryId: filter.factoryId, pi: filter.pi };
  const summary = useQuery({
    queryKey: ['sn', 'acquire-summary', profile.id, profile.authorizationVersion, filter, revision],
    queryFn: ({ signal }) =>
      loadCatalog((page) => acquireApi.summaries({ ...filter, page, size: 100 }), signal),
    enabled: !filter.piAll,
  });
  const rows = filter.piAll ? [] : (summary.data ?? []);
  const totals = rows.reduce(
    (sum, row) => ({
      acquire: sum.acquire + BigInt(row.toAcquire),
      print: sum.print + BigInt(row.toPrint),
      applying: sum.applying + BigInt(row.applying),
    }),
    { acquire: 0n, print: 0n, applying: 0n },
  );
  const segments = useQuery({
    queryKey: [
      'sn',
      'acquire-segments',
      profile.id,
      profile.authorizationVersion,
      scope,
      filter.piAll,
      segment,
      revision,
    ],
    queryFn: () =>
      acquireApi.segments({
        ...scope,
        ...segment,
        piAll: filter.piAll,
        factoryId: filter.piAll ? undefined : scope.factoryId,
        materialMode: segment.material ? 'VALUE' : 'ALL',
      }),
    enabled: tab === 'segments',
  });
  const nameOf = (code: string) =>
    context.data?.factories.find((factory) => factory.code === code)?.name || code;
  const canAct = (step: AcquireStep) =>
    !filter.piAll &&
    profile.permissions.includes(step === 'TO_ACQUIRE' ? 'sn:acquire:take' : 'sn:print:execute');
  const reload = (next = draft) => {
    const applied = {
      ...next,
      pi: next.pi.trim(),
      piAll: isFactory && next.piAll && !!next.pi.trim(),
    };
    setDraft({ ...next, piAll: applied.piAll });
    setFilter(applied);
    setFocus(undefined);
    setSegment((current) => ({ ...current, material: material.trim(), page: 1 }));
    setPicked(emptySelection());
    setRevision((value) => value + 1);
    if (applied.piAll && STEPS.includes(tab as AcquireStep)) setTab('segments');
  };
  const confirm = (step: AcquireStep, selected: AcquireSummary[], bulk: boolean) => {
    if (!selected.length || actions.busy || !canAct(step)) return;
    const row = selected[0]!;
    const quantity = selected
      .reduce((sum, item) => sum + BigInt(acquireQuantity(item, step)), 0n)
      .toString();
    modal.confirm({
      title: t(step === 'TO_ACQUIRE' ? 'acquire.take' : 'acquire.print'),
      icon: null,
      className: 'sn-init-confirm',
      width: 420,
      centered: true,
      closable: true,
      content: (
        <div className="sn-init-confirm-message">
          <ExclamationCircleFilled aria-hidden />
          <span>
            {t(
              bulk
                ? step === 'TO_ACQUIRE'
                  ? 'acquire.bulkTakeConfirm'
                  : 'acquire.bulkPrintConfirm'
                : step === 'TO_ACQUIRE'
                  ? 'acquire.takeConfirm'
                  : 'acquire.printConfirm',
              {
                n: bulk ? selected.length : quantity,
                q: quantity,
                pi: row.pi,
                factory: row.factoryName || row.factoryCode,
              },
            )}
          </span>
        </div>
      ),
      okText: common('snAcquire.confirmButton'),
      cancelText: common('snAcquire.cancelButton'),
      onOk: () => actions.start(step, selected, bulk),
    });
  };
  const focusBar = focus && (
    <div className="sn-acquire-focus">
      <b>
        {focus.factoryName || focus.factoryCode} · PI {focus.pi}
      </b>
      <Button
        size="small"
        onClick={() => {
          setFocus(undefined);
          setSegment((current) => ({ ...current, page: 1 }));
        }}
      >
        {t('acquire.showAll')}
      </Button>
    </div>
  );
  const stepTable = (step: AcquireStep) => {
    const list = rows.filter((row) => BigInt(acquireQuantity(row, step)) > 0n);
    const selection = list.filter((row) => picked[step].includes(acquireRowKey(row)));
    const quantity = selection
      .reduce((sum, row) => sum + BigInt(acquireQuantity(row, step)), 0n)
      .toString();
    return (
      <>
        <Table<AcquireSummary>
          rowKey={acquireRowKey}
          size="small"
          data-testid={`acquire-${step}`}
          dataSource={list}
          loading={summary.isFetching}
          pagination={false}
          locale={{
            emptyText: t(step === 'TO_ACQUIRE' ? 'acquire.emptyAcquire' : 'acquire.emptyPrint'),
          }}
          rowSelection={
            canAct(step)
              ? {
                  selectedRowKeys: picked[step],
                  columnWidth: 40,
                  onChange: (keys) =>
                    setPicked((current) => ({ ...current, [step]: keys.map(String) })),
                  getCheckboxProps: () => ({ disabled: actions.busy }),
                }
              : undefined
          }
          columns={[
            {
              title: 'PI',
              dataIndex: 'pi',
              width: '29%',
              render: (value: string) => <b>{value}</b>,
            },
            {
              title: t('common.factory'),
              width: '19%',
              render: (_, row) => row.factoryName || row.factoryCode,
            },
            { title: t('common.customer'), dataIndex: 'customer' },
            {
              title: t(`status.${step}`),
              width: 100,
              align: 'right',
              render: (_, row) => <b>{acquireQuantity(row, step)}</b>,
            },
            {
              title: t('status.APPLYING'),
              width: 90,
              align: 'right',
              render: (_, row) => (
                <span className={row.applying === '0' ? 'sn-muted' : 'sn-acquire-warn'}>
                  {row.applying}
                </span>
              ),
            },
            {
              title: t('common.actions'),
              width: 170,
              align: 'center',
              render: (_, row) => (
                <>
                  {canAct(step) && (
                    <Button
                      size="small"
                      type="primary"
                      className={step === 'TO_PRINT' ? 'sn-acquire-print' : undefined}
                      loading={actions.active === `${step}:${acquireRowKey(row)}`}
                      disabled={actions.busy && actions.active !== `${step}:${acquireRowKey(row)}`}
                      onClick={() => confirm(step, [row], false)}
                    >
                      {t(step === 'TO_ACQUIRE' ? 'acquire.take' : 'acquire.print')}
                    </Button>
                  )}
                  <Button
                    type="link"
                    onClick={() => {
                      setFocus(row);
                      setTab('segments');
                      setSegment((current) => ({ ...current, page: 1 }));
                    }}
                  >
                    {t('acquire.segments')}
                  </Button>
                </>
              ),
            },
          ]}
        />
        {canAct(step) && list.length > 0 && (
          <div className="sn-acquire-bulk">
            <span>{t('acquire.picked', { n: selection.length, q: quantity })}</span>
            <Button
              size="small"
              type="primary"
              className={step === 'TO_PRINT' ? 'sn-acquire-print' : undefined}
              disabled={!selection.length || actions.busy}
              loading={actions.active === `bulk:${step}`}
              onClick={() => confirm(step, selection, true)}
            >
              {t(step === 'TO_ACQUIRE' ? 'acquire.bulkTake' : 'acquire.bulkPrint')}
            </Button>
            <span className="sn-muted">{t('acquire.bulkNote')}</span>
          </div>
        )}
        {step === 'TO_ACQUIRE' && <p className="sn-muted sn-acquire-rule">{t('acquire.rule')}</p>}
      </>
    );
  };
  return (
    <section className="sn-page sn-acquire-page">
      {modalHolder}
      {actions.holder}
      <SnPageHead title={t('acquire.title')} description={t('acquire.desc')} />
      <div className="sn-surface sn-acquire-filters">
        <div className="sn-toolbar">
          {bound ? (
            <Tag color="warning">{bound.name || bound.code}</Tag>
          ) : (
            <FactoryPicker
              profile={profile}
              value={draft.factoryId}
              placeholder={t('acquire.allFactories')}
              onChange={(factoryId) => reload({ ...draft, factoryId })}
            />
          )}
          <Input
            placeholder="PI"
            aria-label="PI"
            allowClear
            value={draft.pi}
            onChange={(event) => {
              const next = { ...draft, pi: event.target.value };
              setDraft(next);
              if (!next.pi) reload(next);
            }}
            onPressEnter={() => reload()}
          />
          {isFactory && (
            <label className="sn-acquire-pi-all">
              <Switch
                aria-label={t('acquire.piAll')}
                checked={draft.piAll}
                disabled={!draft.pi.trim()}
                onChange={(piAll) => reload({ ...draft, piAll })}
              />
              <span>{t('acquire.piAll')}</span>
            </label>
          )}
          <Button type="primary" onClick={() => reload()}>
            {t('common.search')}
          </Button>
          {!filter.piAll && (
            <span className="sn-muted sn-acquire-summary">
              {t('acquire.summary', {
                n: rows.length,
                a: totals.acquire.toString(),
                p: totals.print.toString(),
              })}
              {totals.applying > 0n && (
                <span className="sn-acquire-warn">
                  {' '}
                  · {t('acquire.summaryApplying', { x: totals.applying.toString() })}
                </span>
              )}
            </span>
          )}
        </div>
        {(context.data?.role === 'query' || filter.piAll) && (
          <Alert
            type="info"
            showIcon
            title={t(
              context.data?.role === 'query' ? 'acquire.queryReadOnly' : 'acquire.piAllReadOnly',
            )}
          />
        )}
      </div>
      <ApiErrorAlert error={summary.error ?? context.error} />
      <div className="sn-surface">
        <Tabs
          activeKey={tab}
          onChange={(key) => {
            setTab(key);
            setSegment((current) => ({ ...current, page: 1 }));
            setRevision((value) => value + 1);
          }}
          items={[
            ...(!filter.piAll
              ? STEPS.map((step) => ({
                  key: step,
                  label: (
                    <>
                      {t(`status.${step}`)}
                      <span className="sn-acquire-count">
                        {rows.filter((row) => BigInt(acquireQuantity(row, step)) > 0n).length}
                      </span>
                    </>
                  ),
                  children: stepTable(step),
                }))
              : []),
            {
              key: 'batches',
              label: t('acquire.batches'),
              children: (
                <>
                  {focusBar}
                  <AcquireBatches
                    key={JSON.stringify([scope, revision])}
                    profile={profile}
                    factoryId={scope.factoryId}
                    pi={scope.pi}
                  />
                </>
              ),
            },
            {
              key: 'prints',
              label: t('acquire.prints'),
              children: (
                <>
                  {focusBar}
                  <AcquirePrints
                    key={JSON.stringify([scope, revision])}
                    profile={profile}
                    factoryId={scope.factoryId}
                    pi={scope.pi}
                  />
                </>
              ),
            },
            {
              key: 'segments',
              label: t('acquire.segments'),
              children: (
                <>
                  {focusBar}
                  <div className="sn-acquire-seg-bar">
                    <Radio.Group
                      size="small"
                      optionType="button"
                      value={segment.status}
                      onChange={(event) =>
                        setSegment((current) => ({
                          ...current,
                          status: String(event.target.value),
                          page: 1,
                        }))
                      }
                      options={['', 'TO_ACQUIRE', 'TO_PRINT', 'APPLYING'].map((value) => ({
                        value,
                        label: value ? t(`status.${value}`) : t('common.all'),
                      }))}
                    />
                    <Input
                      size="small"
                      placeholder={t('common.material')}
                      aria-label={t('common.material')}
                      allowClear
                      value={material}
                      onChange={(event) => {
                        setMaterial(event.target.value);
                        if (!event.target.value)
                          setSegment((current) => ({ ...current, material: '', page: 1 }));
                      }}
                      onPressEnter={() =>
                        setSegment((current) => ({
                          ...current,
                          material: material.trim(),
                          page: 1,
                        }))
                      }
                    />
                    <span className="sn-muted">{t('acquire.segmentsHint')}</span>
                  </div>
                  <ApiErrorAlert error={segments.error} />
                  <Table
                    rowKey={(row) =>
                      [
                        row.factoryId,
                        row.pi,
                        row.material,
                        row.status,
                        row.seqPi,
                        row.groupId,
                      ].join(':')
                    }
                    size="small"
                    data-testid="acquire-segments"
                    dataSource={segments.data?.items}
                    loading={segments.isFetching}
                    locale={{ emptyText: t('common.empty') }}
                    pagination={{
                      current: segment.page,
                      pageSize: segment.size,
                      total: paginationTotal(segments.data?.total),
                      showSizeChanger: true,
                      pageSizeOptions: [20, 50, 100],
                      onChange: (page, size) =>
                        setSegment((current) => ({
                          ...current,
                          page: current.size === size ? page : 1,
                          size,
                        })),
                    }}
                    columns={[
                      {
                        title: t('common.factory'),
                        width: 120,
                        render: (_, row) => nameOf(row.factoryCode),
                      },
                      { title: 'PI', dataIndex: 'pi' },
                      {
                        title: t('common.material'),
                        dataIndex: 'material',
                        render: (value: string) => value || t('orders.noMaterial'),
                      },
                      {
                        title: t('common.status'),
                        dataIndex: 'status',
                        render: (value: string) => (
                          <Tag
                            color={
                              value === 'TO_ACQUIRE'
                                ? 'blue'
                                : value === 'TO_PRINT'
                                  ? 'warning'
                                  : value === 'APPLYING'
                                    ? 'error'
                                    : 'success'
                            }
                          >
                            {t(`status.${value}`)}
                          </Tag>
                        ),
                      },
                      {
                        title: t('acquire.range'),
                        render: (_, row) => (
                          <span className="sn-mono">
                            {row.startSn} ~ {row.endSn}
                          </span>
                        ),
                      },
                      {
                        title: t('acquire.decRange'),
                        render: (_, row) => (
                          <span className="sn-mono">
                            {row.startSeq ?? '—'} ~ {row.endSeq ?? '—'}
                          </span>
                        ),
                      },
                      { title: t('common.qty'), dataIndex: 'quantity', align: 'right' },
                      { title: t('acquire.seqOwner'), dataIndex: 'seqPi' },
                    ]}
                  />
                </>
              ),
            },
          ]}
        />
      </div>
      {(actions.take.task.isError || actions.take.mutation.isError) && (
        <SnOperation work={actions.take} />
      )}
      {(actions.print.task.isError || actions.print.mutation.isError) && (
        <SnOperation work={actions.print} />
      )}
      {actions.completion.isError && (
        <Alert
          type="warning"
          showIcon
          title={common('export.unknown')}
          action={
            <Button
              onClick={() =>
                actions.completion.variables &&
                actions.completion.mutate(actions.completion.variables)
              }
            >
              {common('export.check')}
            </Button>
          }
        />
      )}
      {actions.exceptions.map((result) => (
        <Alert
          key={result.taskId}
          type="warning"
          showIcon
          title={`${result.pi} · ${common('snAcquire.result', { eligible: result.eligible, blocked: result.blocked })}`}
          action={
            <Button onClick={() => setResultId(result.taskId)}>
              {common('snAcquire.resultItems')}
            </Button>
          }
        />
      ))}
      {resultId && (
        <AcquireDetails
          key={resultId}
          profile={profile}
          id={resultId}
          result
          onClose={() => setResultId(undefined)}
        />
      )}
    </section>
  );
}
