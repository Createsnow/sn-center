import { useMutation, useQuery } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Descriptions,
  Empty,
  Form,
  Input,
  Popconfirm,
  Space,
  Steps,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';
import type { UserProfile } from '../../../api/contract';
import { snApi, paginationTotal, type SnRule } from '../../../api/sn';
import { snWorkApi } from '../../../api/snWork';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';
import { GenerationOrderPicker } from '../components/GenerationOrderPicker';
import { GenerationRulePicker } from '../components/GenerationRulePicker';
import { NextSequenceHint } from '../components/NextSequenceHint';
import { SnPageHead } from '../components/SnPageHead';
import { SnOperation } from '../components/SnOperation';
import { useSnOperation } from '../useSnOperation';
import { SnCounter, SnRuleDetails, SnBillHistory } from '../components/SnWorkViews';
import { positiveDecimal, suggestedQuantity } from '../snInput';

export function GeneratePage({ profile }: { profile: UserProfile }) {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const billId = params.get('billId') ?? '';
  const context = useQuery({
    queryKey: ['sn', 'generate', profile.id, profile.authorizationVersion, billId],
    queryFn: () => snWorkApi.context(billId),
    enabled: !!billId,
  });
  // 地址栏和前进/后退切换订单也必须清空本地输入、规则选择及预演。
  return (
    <>
      <SnPageHead title={t('snPages.generate.title')} description={t('snPages.generate.desc')}>
        <GenerationOrderPicker
          profile={profile}
          billId={billId}
          bill={context.data?.bill}
          onChange={(id) => setParams(id ? { billId: id } : {})}
        />
      </SnPageHead>
      <GenerationForm key={billId} profile={profile} />
    </>
  );
}

function GenerationForm({ profile }: { profile: UserProfile }) {
  const { t, i18n } = useTranslation(),
    [params] = useSearchParams();
  const billId = params.get('billId') ?? '';
  const [chosenRule, setChosenRule] = useState<SnRule>();
  const [limit, setLimit] = useState('10000'),
    [qty, setQty] = useState(''),
    [start, setStart] = useState(''),
    [linePage, setLinePage] = useState(1),
    [consumed, setConsumed] = useState('');
  const prefix = ['sn', 'generate', profile.id, profile.authorizationVersion];
  const context = useQuery({
    queryKey: [...prefix, billId],
    queryFn: () => snWorkApi.context(billId),
    enabled: !!billId,
  });
  const lines = useQuery({
    queryKey: [...prefix, 'lines', billId, linePage],
    queryFn: () => snApi.lines(billId, { page: linePage, size: 20 }),
    enabled: !!billId,
  });
  const preview = useMutation({ mutationFn: snWorkApi.preview });
  const generate = useSnOperation(profile, 'GENERATE', snWorkApi.generate),
    allocate = useSnOperation(profile, 'ALLOCATE', snWorkApi.allocate);
  const value = context.data,
    quantity = qty || suggestedQuantity(value?.bill.quota ?? '0', limit),
    p = preview.data;
  const valid =
    positiveDecimal(quantity) &&
    positiveDecimal(limit) &&
    (start === '' || positiveDecimal(start)) &&
    BigInt(quantity) <= BigInt(limit);
  const ready =
    !!value &&
    !value.issues.length &&
    valid &&
    !generate.busy &&
    !allocate.busy &&
    (!value.ruleChoiceRequired || !!chosenRule);
  const change = () => {
    preview.reset();
    setConsumed('');
  };
  return (
    <div className="sn-page">
      <ApiErrorAlert error={context.error ?? lines.error ?? preview.error} />
      {!value ? (
        <Empty description={t('snPages.generate.empty')} />
      ) : (
        <>
          {value.issues.map((issue) => (
            <Alert
              key={issue}
              type="error"
              showIcon
              title={t('snPages.generate.issue.' + issue, { org: value.bill.orgName })}
            />
          ))}
          <div className="sn-generation-grid">
            <section className="sn-surface">
              <Typography.Title level={4}>{t('snPages.generate.order')}</Typography.Title>
              <Descriptions
                size="small"
                bordered
                column={{ xs: 1, sm: 3 }}
                items={[
                  { key: 'bill', label: t('snPages.common.billNo'), children: value.bill.billNo },
                  { key: 'pi', label: 'PI', children: <b>{value.bill.pi}</b> },
                  { key: 'po', label: 'PO', children: value.bill.po },
                  {
                    key: 'customer',
                    label: t('snPages.common.customer'),
                    children: value.bill.customer,
                  },
                  {
                    key: 'org',
                    label: t('snPages.orders.org'),
                    children: `${value.bill.orgName} (${value.bill.factoryCode ?? '—'})`,
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
                    <span className="sn-muted">{t('snPages.' + label)}</span>
                    <b>{n}</b>
                  </div>
                ))}
              </div>
              <Table
                size="small"
                rowKey="id"
                dataSource={lines.data?.items ?? []}
                scroll={{ x: 450 }}
                columns={[
                  { title: '#', dataIndex: 'ordinal', width: 60 },
                  {
                    title: t('snPages.common.material'),
                    dataIndex: 'material',
                    render: (s: string) => s || t('snPages.orders.noMaterial'),
                  },
                  { title: t('snPages.orders.materialName'), dataIndex: 'materialName' },
                  { title: t('snPages.common.qty'), dataIndex: 'qty', align: 'right' },
                ]}
                pagination={{
                  current: linePage,
                  pageSize: 20,
                  total: paginationTotal(lines.data?.total),
                  onChange: setLinePage,
                  showSizeChanger: false,
                }}
              />
            </section>
            <section className="sn-surface">
              <Typography.Title level={4}>{t('snPages.generate.rule')}</Typography.Title>
              {value.ruleChoiceRequired ? (
                <>
                  <GenerationRulePicker
                    profile={profile}
                    value={chosenRule}
                    onChange={(rule) => {
                      setChosenRule(rule);
                      change();
                    }}
                  />
                  {chosenRule && (
                    <SnRuleDetails
                      rule={chosenRule.current}
                      details={{
                        ruleCode: chosenRule.code,
                        ruleScope: chosenRule.bindScope,
                        ruleBinding: chosenRule.bindValue,
                      }}
                    />
                  )}
                </>
              ) : (
                <>
                  <Tag>{t('snPages.generate.ruleSource.' + value.ruleSource)}</Tag>
                  <SnRuleDetails rule={value.rule} details={value} />
                </>
              )}
              <Typography.Title level={4}>{t('snPages.generate.counter')}</Typography.Title>
              <SnCounter value={value.counter} />
              <NextSequenceHint value={p && consumed !== p.id ? p.nextStart : value.nextStart} />
            </section>
          </div>
          <section className="sn-surface">
            <Typography.Title level={4}>
              {t('snPages.generate.piSummary', {
                factory: value.bill.factoryCode ?? value.bill.orgName,
                pi: value.bill.pi,
              })}
            </Typography.Title>
            <Table
              size="small"
              rowKey="billId"
              dataSource={value.piSummary}
              scroll={{ x: 650 }}
              pagination={false}
              columns={[
                {
                  title: t('snPages.common.billNo'),
                  dataIndex: 'billNo',
                  render: (_, r) => (
                    <>
                      <b>{r.billNo}</b>
                      {!r.inSnapshot && <Tag>{t('snPages.generate.leftSnapshot')}</Tag>}
                    </>
                  ),
                },
                { title: t('snPages.orders.totalQty'), dataIndex: 'totalQty' },
                { title: t('snPages.orders.generated'), dataIndex: 'generatedQty' },
                { title: t('snPages.generate.allocated'), dataIndex: 'allocatedQty' },
                { title: t('snPages.orders.quota'), dataIndex: 'quota' },
              ]}
            />
          </section>
          <section className="sn-surface">
            <Steps
              size="small"
              current={
                BigInt(value.pendingQuantity) > 0n
                  ? 1
                  : BigInt(value.bill.allocatedQty) > 0n
                    ? 2
                    : 0
              }
              items={[
                { title: t('snPages.generate.step1') },
                { title: t('snPages.generate.step2') },
              ]}
            />
            <div className="sn-generation-grid">
              <div>
                <Typography.Title level={4}>{t('snPages.generate.step1')}</Typography.Title>
                <Form layout="vertical" className="sn-work-form">
                  <Form.Item
                    label={t('snPages.generate.limit')}
                    help={t('snPages.generate.limitHint')}
                  >
                    <Input
                      aria-label={t('snPages.generate.limit')}
                      value={limit}
                      onChange={(e) => {
                        setLimit(e.target.value);
                        change();
                      }}
                      inputMode="numeric"
                    />
                  </Form.Item>
                  <Form.Item
                    label={t('snPages.generate.qty')}
                    help={t('snPages.generate.qtyHint', { quota: value.bill.quota, limit })}
                  >
                    <Input
                      aria-label={t('snPages.generate.qty')}
                      value={quantity}
                      onChange={(e) => {
                        setQty(e.target.value);
                        change();
                      }}
                      inputMode="numeric"
                    />
                  </Form.Item>
                  {!value.counter.startLocked && (
                    <Form.Item
                      label={t('snPages.generate.start')}
                      help={t('snPages.generate.startHint')}
                    >
                      <Input
                        aria-label={t('snPages.generate.start')}
                        value={start}
                        onChange={(e) => {
                          setStart(e.target.value);
                          change();
                        }}
                        placeholder={t('snPages.generate.startPlaceholder')}
                        inputMode="numeric"
                      />
                    </Form.Item>
                  )}
                </Form>
                <Space wrap>
                  <Button
                    disabled={!ready || !profile.permissions.includes('sn:generation:preview')}
                    loading={preview.isPending}
                    onClick={() =>
                      preview.mutate({
                        billId,
                        quantity,
                        start: start || undefined,
                        ruleId: value.ruleChoiceRequired ? chosenRule?.id : undefined,
                      })
                    }
                  >
                    {t('snPages.generate.preview')}
                  </Button>
                  <Popconfirm
                    title={t('snPages.generate.confirm', {
                      qty: p?.quantity,
                      start: p?.firstSn,
                      end: p?.lastSn,
                    })}
                    okText={t('users.confirm')}
                    cancelText={t('snPages.common.cancel')}
                    onConfirm={() => {
                      if (p) {
                        setConsumed(p.id);
                        generate.begin({ operationId: crypto.randomUUID(), previewId: p.id });
                      }
                    }}
                    disabled={!p || !ready || consumed === p.id}
                  >
                    <Button
                      type="primary"
                      disabled={
                        !p ||
                        !ready ||
                        consumed === p.id ||
                        !profile.permissions.includes('sn:generation:execute')
                      }
                      loading={generate.mutation.isPending}
                    >
                      {t('snPages.generate.generate')}
                    </Button>
                  </Popconfirm>
                </Space>
                {!p ? (
                  <Alert
                    className="sn-operation-actions"
                    type="info"
                    title={t('snPages.generate.needPreview')}
                  />
                ) : (
                  <>
                    <Descriptions
                      bordered
                      size="small"
                      column={2}
                      className="sn-operation-actions"
                      items={[
                        {
                          key: 'start',
                          label: t('snPages.generate.startSn'),
                          children: <span className="sn-mono">{p.firstSn}</span>,
                        },
                        {
                          key: 'end',
                          label: t('snPages.generate.endSn'),
                          children: <span className="sn-mono">{p.lastSn}</span>,
                        },
                        { key: 'qty', label: t('snPages.common.qty'), children: p.quantity },
                        {
                          key: 'base',
                          label: t('snPages.generate.baseLast'),
                          children: p.baseLastSeq,
                        },
                        {
                          key: 'range',
                          label: t('snPages.generate.seqRange'),
                          children: `${p.startSeq} ~ ${p.endSeq}`,
                        },
                        {
                          key: 'expires',
                          label: t('snPages.generate.expires'),
                          children: new Date(p.expiresAt).toLocaleString(i18n.language),
                        },
                      ]}
                    />
                    <Table
                      size="small"
                      rowKey="sourceEntryId"
                      dataSource={p.segments}
                      pagination={false}
                      scroll={{ x: 500 }}
                      columns={[
                        { title: '#', dataIndex: 'ordinal' },
                        {
                          title: t('snPages.common.material'),
                          dataIndex: 'material',
                          render: (s: string) => s || t('snPages.orders.noMaterial'),
                        },
                        {
                          title: t('snPages.generate.segment'),
                          render: (_, s) => (
                            <span className="sn-mono">
                              {s.firstSn} ~ {s.lastSn}
                            </span>
                          ),
                        },
                        { title: t('snPages.common.qty'), dataIndex: 'quantity' },
                      ]}
                    />
                  </>
                )}
                <SnOperation work={generate} />
              </div>
              <div>
                <Typography.Title level={4}>{t('snPages.generate.step2')}</Typography.Title>
                <Form layout="vertical" className="sn-work-form">
                  <Form.Item
                    label={t('snPages.generate.allocQty')}
                    help={t('snPages.generate.allocHint', { pending: value.pendingQuantity })}
                  >
                    <Input
                      aria-label={t('snPages.generate.allocQty')}
                      value={value.pendingQuantity}
                      readOnly
                    />
                  </Form.Item>
                  <Form.Item
                    label={t('snPages.generate.allocFactory')}
                    help={t('snPages.generate.allocFactoryHint')}
                  >
                    <Input
                      value={`${value.factoryName} (${value.bill.factoryCode ?? '—'})`}
                      disabled
                    />
                  </Form.Item>
                </Form>
                <Popconfirm
                  title={t('snPages.generate.allocConfirm', {
                    qty: value.pendingQuantity,
                    factory: value.factoryName,
                  })}
                  okText={t('users.confirm')}
                  cancelText={t('snPages.common.cancel')}
                  onConfirm={() =>
                    allocate.begin({
                      operationId: crypto.randomUUID(),
                      billId,
                      expectedQuantity: value.pendingQuantity,
                    })
                  }
                  disabled={value.pendingQuantity === '0' || allocate.busy || generate.busy}
                >
                  <Button
                    type="primary"
                    disabled={
                      !profile.permissions.includes('sn:allocation:execute') ||
                      value.pendingQuantity === '0' ||
                      allocate.busy ||
                      generate.busy ||
                      !!value.counter.activeTaskId
                    }
                    loading={allocate.mutation.isPending}
                  >
                    {t('snPages.generate.allocate')}
                  </Button>
                </Popconfirm>
                <Alert
                  className="sn-operation-actions"
                  type="info"
                  title={t('snPages.generate.allocNote')}
                />
                <SnOperation work={allocate} />
              </div>
            </div>
          </section>
          <section className="sn-surface">
            <Tabs
              items={[
                {
                  key: 'jobs',
                  label: t('snPages.generate.jobs'),
                  children: (
                    <SnBillHistory
                      key={billId + 'GENERATE'}
                      profile={profile}
                      billId={billId}
                      kind="GENERATE"
                    />
                  ),
                },
                {
                  key: 'allocations',
                  label: t('snPages.generate.allocations'),
                  children: (
                    <SnBillHistory
                      key={billId + 'ALLOCATE'}
                      profile={profile}
                      billId={billId}
                      kind="ALLOCATE"
                    />
                  ),
                },
              ]}
            />
          </section>
        </>
      )}
      {!value && (
        <>
          <SnOperation work={generate} />
          <SnOperation work={allocate} />
        </>
      )}
    </div>
  );
}
