import { CheckOutlined, EyeOutlined, SendOutlined } from '@ant-design/icons';
import { useMutation, useQuery } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Descriptions,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Progress,
  Space,
  Table,
  Tabs,
  Tag,
} from 'antd';
import { useEffect, useEffectEvent, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';
import { ApiError, type UserProfile } from '../../../api/contract';
import { snApi, type SnRule } from '../../../api/sn';
import { loadCatalog } from '../../../api/catalog';
import { snWorkApi } from '../../../api/snWork';
import type { Task } from '../../../api/tasks';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';
import { formatSnTime } from '../../../utils/snTime';
import { GenerationOrderPicker } from '../components/GenerationOrderPicker';
import { GenerationRulePicker } from '../components/GenerationRulePicker';
import { GenerationCounter, GenerationRuleDetails } from '../components/GenerationDetails';
import { GenerationHistory } from '../components/GenerationHistory';
import { GenerationSteps } from '../components/GenerationSteps';
import { SnPageHead } from '../components/SnPageHead';
import { SnOperation } from '../components/SnOperation';
import { useSnOperation } from '../useSnOperation';
import { usePrototypeFeedback } from '../usePrototypeFeedback';
import { generationPercent } from '../generationProgress';
import { generationErrorText, generationFailureText } from '../generationErrorText';
import { positiveDecimal, suggestedQuantity } from '../snInput';

export function GeneratePage({ profile }: { profile: UserProfile }) {
  const { t } = useTranslation('prototype');
  const [params, setParams] = useSearchParams();
  const billId = params.get('billId') ?? '';
  const context = useQuery({
    queryKey: ['sn', 'generate', profile.id, profile.authorizationVersion, billId],
    queryFn: () => snWorkApi.context(billId),
    enabled: !!billId,
  });
  return (
    <div className="sn-page sn-generate-page">
      <SnPageHead title={t('generate.title')} description={t('generate.desc')} />
      <GenerationOrderPicker
        profile={profile}
        billId={billId}
        bill={context.data?.bill}
        onChange={(id) => setParams(id ? { billId: id } : {})}
      />
      <GenerationForm key={billId} profile={profile} billId={billId} />
    </div>
  );
}

function GenerationForm({ profile, billId }: { profile: UserProfile; billId: string }) {
  const { t } = useTranslation('prototype'),
    { t: common, i18n } = useTranslation();
  const feedback = usePrototypeFeedback();
  const [modal, modalHolder] = Modal.useModal();
  const [chosenRule, setChosenRule] = useState<SnRule>();
  const [limit, setLimit] = useState('10000'),
    [qty, setQty] = useState('');
  const [allocQty, setAllocQty] = useState(''),
    [start, setStart] = useState('');
  const [consumed, setConsumed] = useState(''),
    [refreshing, setRefreshing] = useState(false);
  const watched = useRef(new Set<string>());
  const reportedErrors = useRef(new Set<unknown>());
  const submittedQuantity = useRef('0');
  const lastGeneratedQuantity = useRef<string | undefined>(undefined);
  const prefix = ['sn', 'generate', profile.id, profile.authorizationVersion];
  const context = useQuery({
    queryKey: [...prefix, billId],
    queryFn: () => snWorkApi.context(billId),
    enabled: !!billId,
    refetchInterval: (q) => (q.state.data?.counter.activeTaskId ? 1000 : false),
  });
  const lines = useQuery({
    queryKey: [...prefix, 'lines', billId],
    queryFn: ({ signal }) => snApi.allLines(billId, signal),
    enabled: !!billId,
  });
  const allocations = useQuery({
    queryKey: [...prefix, 'allocations', billId],
    queryFn: ({ signal }) =>
      loadCatalog((page) => snWorkApi.history(billId, 'ALLOCATE', page), signal),
    enabled: !!billId,
  });
  const preview = useMutation({ mutationFn: snWorkApi.preview });
  const generate = useSnOperation(profile, 'GENERATE', snWorkApi.generate);
  const allocate = useSnOperation(profile, 'ALLOCATE', snWorkApi.allocate);
  const value = context.data,
    p = consumed === preview.data?.id ? undefined : preview.data;
  const maxQty = suggestedQuantity(value?.bill.quota ?? '0', limit);
  const maxAlloc = suggestedQuantity(value?.pendingQuantity ?? '0', limit);
  const quantity = qty || maxQty,
    allocationQuantity = allocQty || maxAlloc;
  const ready =
    !!value &&
    !value.issues.length &&
    !generate.busy &&
    !allocate.busy &&
    !refreshing &&
    (!value.ruleChoiceRequired || !!chosenRule);
  const runningJob = value?.jobs.find((row) => row.state === 'PENDING');
  const change = () => {
    preview.reset();
    setConsumed('');
  };
  const errorText = (error: unknown) => generationErrorText(error, common);
  // 终态事件只处理本页提交或实际观察到运行中的任务；旧已完成操作不能在换单时反复提示。
  const settled = useEffectEvent(async (kind: 'GENERATE' | 'ALLOCATE', task: Task) => {
    setRefreshing(true);
    const work = kind === 'GENERATE' ? generate : allocate;
    try {
      const [result] = await Promise.all([context.refetch(), lines.refetch()]);
      if (task.state === 'SUCCESS') {
        const row = (kind === 'GENERATE' ? result.data?.jobs : result.data?.allocations)?.find(
          (r) => r.taskId === task.taskId,
        );
        if (kind === 'GENERATE') {
          const completedQuantity = row?.quantity ?? submittedQuantity.current;
          lastGeneratedQuantity.current = completedQuantity;
          feedback.success(t('generate.done', { qty: completedQuantity }));
          setAllocQty(
            suggestedQuantity(
              result.data?.pendingQuantity ?? '0',
              suggestedQuantity(completedQuantity, limit),
            ),
          );
          change();
        } else {
          feedback.success(
            t('generate.allocDone', {
              qty: row?.quantity ?? allocationQuantity,
              start: row?.firstSn ?? '',
              end: row?.lastSn ?? '',
            }),
          );
          setAllocQty('');
        }
        setQty('');
      } else if (task.state === 'FAILED') {
        const failed = result.data?.jobs.find((row) => row.taskId === task.taskId);
        feedback.error(
          kind === 'GENERATE'
            ? t('generate.failed', {
                msg: generationFailureText(
                  task.errorCode,
                  failed?.errorParams ?? {},
                  i18n.getFixedT('zh-CN'),
                ),
              })
            : common('errors.' + task.errorCode),
          kind === 'GENERATE' ? 8 : 5,
        );
        if (kind === 'GENERATE') {
          change();
          setQty('');
          const pending = result.data?.pendingQuantity ?? '0';
          setAllocQty(
            suggestedQuantity(
              pending,
              suggestedQuantity(lastGeneratedQuantity.current ?? pending, limit),
            ),
          );
        }
      }
      work.clear();
    } finally {
      setRefreshing(false);
    }
  });
  const rejected = useEffectEvent(async (kind: 'GENERATE' | 'ALLOCATE', error: unknown) => {
    feedback.error(errorText(error));
    // 业务拒绝可能已留下同步失败任务，统一刷新历史；未知结果保留原意图以便同号核对。
    if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
      const operation = kind === 'GENERATE' ? generate : allocate;
      if (operation.operation) watched.current.delete(operation.operation);
      operation.clear();
      if (kind === 'GENERATE') {
        change();
        setRefreshing(true);
        try {
          const [result] = await Promise.all([context.refetch(), lines.refetch()]);
          setQty('');
          const pending = result.data?.pendingQuantity ?? '0';
          setAllocQty(
            suggestedQuantity(
              pending,
              suggestedQuantity(lastGeneratedQuantity.current ?? pending, limit),
            ),
          );
        } finally {
          setRefreshing(false);
        }
      }
    }
  });
  useEffect(() => {
    for (const [kind, task] of [
      ['GENERATE', generate.task.data],
      ['ALLOCATE', allocate.task.data],
    ] as const) {
      if (!task) continue;
      if (['QUEUED', 'RUNNING', 'RETRY_WAIT'].includes(task.state))
        watched.current.add(task.operationId);
      else if (watched.current.delete(task.operationId)) void settled(kind, task);
    }
  }, [generate.task.data, allocate.task.data]);
  useEffect(() => {
    for (const [kind, error] of [
      ['GENERATE', generate.mutation.error],
      ['ALLOCATE', allocate.mutation.error],
    ] as const) {
      if (error && !reportedErrors.current.has(error)) {
        reportedErrors.current.add(error);
        void rejected(kind, error);
      }
    }
  }, [generate.mutation.error, allocate.mutation.error]);
  const doPreview = () => {
    const trimmed = start.trim();
    if (trimmed && !positiveDecimal(trimmed)) {
      feedback.error(t('generate.startInvalid'));
      return;
    }
    preview.mutate(
      {
        billId,
        quantity,
        start: trimmed || undefined,
        ruleId: value?.ruleChoiceRequired ? chosenRule?.id : undefined,
      },
      {
        onError: (error) => feedback.error(errorText(error)),
      },
    );
  };
  const confirmGenerate = () => {
    if (!p) return;
    const fixed = p;
    modal.confirm({
      title: t('generate.generate'),
      content: t('generate.confirm', {
        qty: fixed.quantity,
        start: fixed.firstSn,
        end: fixed.lastSn,
      }),
      centered: true,
      width: 420,
      className: 'sn-generation-confirm',
      okText: common('users.confirm'),
      cancelText: t('common.cancel'),
      onOk: () => {
        const operationId = crypto.randomUUID();
        watched.current.add(operationId);
        submittedQuantity.current = fixed.quantity;
        setConsumed(fixed.id);
        generate.begin({ operationId, previewId: fixed.id });
      },
    });
  };
  const confirmAllocate = () => {
    if (!value) return;
    const fixed = {
      operationId: crypto.randomUUID(),
      billId,
      expectedQuantity: value.pendingQuantity,
      quantity: allocationQuantity,
      factoryCode: value.bill.factoryCode ?? '',
    };
    modal.confirm({
      title: t('generate.allocate'),
      content: t('generate.allocConfirm', {
        qty: fixed.quantity,
        factory: value.factoryName || value.bill.factoryCode,
      }),
      centered: true,
      width: 420,
      className: 'sn-generation-confirm',
      okText: common('users.confirm'),
      cancelText: t('common.cancel'),
      onOk: () => {
        watched.current.add(fixed.operationId);
        allocate.begin(fixed);
      },
    });
  };
  return (
    <>
      {feedback.holder}
      {modalHolder}
      <ApiErrorAlert error={context.error ?? lines.error ?? allocations.error} />
      {!value ? (
        <Empty description={t('generate.empty')} />
      ) : (
        <>
          {value.issues.map((issue) => (
            <Alert
              key={issue}
              className="sn-generation-issue"
              type="error"
              showIcon
              title={
                issue === 'ORG_UNMAPPED'
                  ? t('generate.issue.FACTORY_UNMAPPED', { org: value.bill.orgName })
                  : common('snPages.generate.issue.' + issue, { org: value.bill.orgName })
              }
            />
          ))}
          <div className="sn-generation-grid">
            <section className="sn-surface">
              <div className="sn-surface-title">{t('generate.order')}</div>
              <Descriptions
                size="small"
                bordered
                column={3}
                items={[
                  { key: 'bill', label: t('common.billNo'), children: value.bill.billNo },
                  { key: 'pi', label: 'PI', children: <b>{value.bill.pi}</b> },
                  { key: 'po', label: 'PO', children: value.bill.po },
                  { key: 'customer', label: t('common.customer'), children: value.bill.customer },
                  {
                    key: 'org',
                    label: t('orders.org'),
                    children:
                      value.bill.orgName +
                      '（' +
                      (value.bill.factoryCode ?? t('orders.noFactory')) +
                      '）',
                    span: 2,
                  },
                ]}
              />
              <div className="sn-work-stats">
                {[
                  ['orders.totalQty', value.bill.totalQty],
                  ['orders.generated', value.bill.generatedQty],
                  ['generate.allocated', value.bill.allocatedQty],
                  ['generate.pendingAlloc', value.pendingQuantity],
                  ['orders.quota', value.bill.quota],
                ].map(([label, n]) => (
                  <div key={label}>
                    <span className="sn-muted">{t(label!)}</span>
                    <b>{n}</b>
                  </div>
                ))}
              </div>
              <Table
                size="small"
                rowKey="id"
                dataSource={lines.data ?? []}
                pagination={false}
                columns={[
                  { title: '#', dataIndex: 'ordinal', width: 48 },
                  {
                    title: t('common.material'),
                    dataIndex: 'material',
                    render: (v: string) => v || t('orders.noMaterial'),
                  },
                  { title: t('orders.materialName'), dataIndex: 'materialName', ellipsis: true },
                  {
                    title: t('common.qty'),
                    dataIndex: 'qty',
                    align: 'right',
                    width: 90,
                    render: (v: string) => v.split('.')[0],
                  },
                ]}
              />
            </section>
            <section className="sn-surface">
              <div className="sn-surface-title">{t('generate.rule')}</div>
              {value.ruleChoiceRequired && (
                <GenerationRulePicker
                  profile={profile}
                  value={chosenRule}
                  onChange={(rule) => {
                    setChosenRule(rule);
                    change();
                  }}
                />
              )}
              <GenerationRuleDetails context={value} chosen={chosenRule} />
              <div className="sn-surface-title sn-generation-mt">{t('generate.counter')}</div>
              <GenerationCounter context={value} />
            </section>
          </div>
          <section className="sn-surface">
            <div className="sn-surface-title">
              {t('generate.piSummary', {
                factory: value.bill.factoryCode ?? value.bill.orgName,
                pi: value.bill.pi,
              })}
            </div>
            <Table
              size="small"
              rowKey="billId"
              dataSource={value.piSummary}
              pagination={false}
              tableLayout="fixed"
              columns={[
                {
                  title: t('common.billNo'),
                  dataIndex: 'billNo',
                  width: '20%',
                  render: (_, row) => (
                    <>
                      {row.billId === billId ? <b>{row.billNo}</b> : row.billNo}
                      {!row.inSnapshot && <Tag>{t('generate.leftSnapshot')}</Tag>}
                    </>
                  ),
                },
                {
                  title: t('orders.totalQty'),
                  dataIndex: 'totalQty',
                  align: 'right',
                  width: '20%',
                },
                {
                  title: t('orders.generated'),
                  dataIndex: 'generatedQty',
                  align: 'right',
                  width: '20%',
                },
                {
                  title: t('generate.allocated'),
                  dataIndex: 'allocatedQty',
                  align: 'right',
                  width: '20%',
                },
                { title: t('orders.quota'), dataIndex: 'quota', align: 'right', width: '20%' },
              ]}
            />
          </section>
          <section className="sn-surface">
            <GenerationSteps
              current={
                value.pendingQuantity !== '0'
                  ? 1
                  : value.bill.quota === '0' && value.bill.allocatedQty !== '0'
                    ? 2
                    : 0
              }
            />
            <div className="sn-generation-actions">
              <div>
                <h4>{t('generate.step1')}</h4>
                <Form layout="vertical" className="sn-work-form">
                  <Form.Item
                    label={t('generate.limit')}
                    help={common('snPages.generate.limitHint')}
                  >
                    <InputNumber<string>
                      stringMode
                      aria-label={t('generate.limit')}
                      min="1"
                      step="1000"
                      value={limit}
                      onChange={(v) => {
                        const next = v ?? '1';
                        setLimit(next);
                        setQty(
                          suggestedQuantity(quantity, suggestedQuantity(value.bill.quota, next)),
                        );
                        change();
                      }}
                    />
                  </Form.Item>
                  <Form.Item
                    label={t('generate.qty')}
                    help={t('generate.qtyHint', { quota: value.bill.quota, limit })}
                  >
                    <InputNumber<string>
                      stringMode
                      aria-label={t('generate.qty')}
                      min="1"
                      max={maxQty}
                      value={quantity}
                      onChange={(v) => {
                        setQty(v ?? '1');
                        change();
                      }}
                    />
                  </Form.Item>
                  {!value.counter.startLocked && (
                    <Form.Item label={t('generate.start')} help={t('generate.startHint')}>
                      <Input
                        aria-label={t('generate.start')}
                        value={start}
                        allowClear
                        placeholder={t('generate.startPlaceholder')}
                        onChange={(e) => {
                          setStart(e.target.value);
                          change();
                        }}
                      />
                    </Form.Item>
                  )}
                </Form>
                <Space size={8}>
                  <Button
                    icon={<EyeOutlined aria-hidden="true" />}
                    disabled={!ready || !profile.permissions.includes('sn:generation:preview')}
                    loading={preview.isPending}
                    onClick={doPreview}
                  >
                    {t('generate.preview')}
                  </Button>
                  <Button
                    type="primary"
                    icon={<CheckOutlined aria-hidden="true" />}
                    disabled={
                      !p || !ready || !profile.permissions.includes('sn:generation:execute')
                    }
                    loading={generate.busy || refreshing}
                    onClick={confirmGenerate}
                  >
                    {t('generate.generate')}
                  </Button>
                </Space>
                {!p ? (
                  <Alert
                    className="sn-generation-mt"
                    type="info"
                    title={t('generate.needPreview')}
                  />
                ) : (
                  <>
                    {p.nextStart.adjusted && !start.trim() && (
                      <Alert
                        className="sn-generation-mt"
                        type="warning"
                        showIcon
                        title={t('generate.startAdjusted', {
                          sn: p.nextStart.bySn,
                          start: p.firstSn,
                        })}
                      />
                    )}
                    <Descriptions
                      bordered
                      size="small"
                      column={2}
                      className="sn-generation-mt"
                      items={[
                        {
                          key: 'start',
                          label: t('generate.startSn'),
                          children: <span className="sn-mono">{p.firstSn}</span>,
                        },
                        {
                          key: 'end',
                          label: t('generate.endSn'),
                          children: <span className="sn-mono">{p.lastSn}</span>,
                        },
                        { key: 'qty', label: t('common.qty'), children: <b>{p.quantity}</b> },
                        { key: 'base', label: t('generate.baseLast'), children: p.baseLastSeq },
                        {
                          key: 'range',
                          label: t('generate.seqRange'),
                          children: p.startSeq + ' ~ ' + p.endSeq,
                        },
                        {
                          key: 'expires',
                          label: t('generate.expires'),
                          children: formatSnTime(p.expiresAt),
                        },
                      ]}
                    />
                    <Table
                      size="small"
                      rowKey="sourceEntryId"
                      className="sn-generation-mt"
                      dataSource={p.segments}
                      pagination={false}
                      scroll={{ x: 490 }}
                      columns={[
                        { title: '#', dataIndex: 'ordinal', width: 48 },
                        {
                          title: t('common.material'),
                          dataIndex: 'material',
                          render: (v: string) => v || t('orders.noMaterial'),
                        },
                        {
                          title: t('generate.segment'),
                          minWidth: 220,
                          render: (_, row) => (
                            <span className="sn-mono">
                              {row.firstSn} ~ {row.lastSn}
                            </span>
                          ),
                        },
                        {
                          title: t('common.qty'),
                          dataIndex: 'quantity',
                          align: 'right',
                          width: 80,
                        },
                      ]}
                    />
                  </>
                )}
                {runningJob && (
                  <div className="sn-generation-mt">
                    <div className="sn-muted">
                      {t('generate.running', { id: runningJob.generationNumber ?? '—' })}
                    </div>
                    <Progress
                      percent={generationPercent(runningJob)}
                      strokeWidth={14}
                      status="active"
                    />
                  </div>
                )}
              </div>
              <div>
                <h4>{t('generate.step2')}</h4>
                <Form layout="vertical" className="sn-work-form">
                  <Form.Item
                    label={t('generate.allocQty')}
                    help={t('generate.allocHint', { pending: value.pendingQuantity })}
                  >
                    <InputNumber<string>
                      stringMode
                      aria-label={t('generate.allocQty')}
                      min="1"
                      max={maxAlloc}
                      value={allocationQuantity}
                      onChange={(v) => setAllocQty(v ?? '1')}
                    />
                  </Form.Item>
                  <Form.Item
                    label={t('generate.allocFactory')}
                    help={t('generate.allocFactoryHint')}
                  >
                    <Input
                      value={
                        value.bill.factoryCode
                          ? value.factoryName + '（' + value.bill.factoryCode + '）'
                          : ''
                      }
                      disabled
                    />
                  </Form.Item>
                </Form>
                <Button
                  type="primary"
                  icon={<SendOutlined aria-hidden="true" />}
                  disabled={
                    !profile.permissions.includes('sn:allocation:execute') ||
                    value.pendingQuantity === '0' ||
                    generate.busy ||
                    allocate.busy ||
                    refreshing ||
                    !!value.counter.activeTaskId
                  }
                  loading={allocate.busy}
                  onClick={confirmAllocate}
                >
                  {t('generate.allocate')}
                </Button>
                <Alert className="sn-generation-mt" type="info" title={t('generate.allocNote')} />
              </div>
            </div>
          </section>
          <section className="sn-surface">
            <Tabs
              items={[
                {
                  key: 'jobs',
                  label: t('generate.jobs'),
                  children: <GenerationHistory rows={value.jobs} />,
                },
                {
                  key: 'allocations',
                  label: t('generate.allocations'),
                  children: (
                    <GenerationHistory
                      allocations
                      rows={(allocations.data ?? []).filter((row) => row.state === 'SUCCESS')}
                    />
                  ),
                },
              ]}
            />
          </section>
        </>
      )}
      {generate.operation &&
        !generate.task.data &&
        (generate.mutation.error || generate.task.error) && <SnOperation work={generate} />}
      {allocate.operation &&
        !allocate.task.data &&
        (allocate.mutation.error || allocate.task.error) && <SnOperation work={allocate} />}
    </>
  );
}
