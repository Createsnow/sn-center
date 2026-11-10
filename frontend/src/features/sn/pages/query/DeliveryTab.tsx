import { Button, Input, Select, Table, Tag } from 'antd';
import { useImperativeHandle, useState, type Ref } from 'react';
import { useTranslation } from 'react-i18next';
import { snApi, type Row } from '../../../../api/sn';
import { FactorySelect } from '../../components/FactorySelect';
import { Pager } from '../../components/Pager';
import { useAccount } from '../../lib/auth';
import { useFactories } from '../../lib/factories';
import {
  BATCH_RESULTS,
  BATCH_RESULT_COLOR,
  BATCH_TYPES,
  batchResultLabel,
  batchSourceLabel,
  batchTypeLabel,
  confirmMethodLabel,
  pushStateLabel,
} from '../../lib/labels';
import { str } from '../../lib/row';
import { trimmed, usePaged } from '../../lib/usePaged';

export interface DeliveryTabHandle {
  reload: () => void;
}

/** 交付批次（推送、拉取、转出）列表；点批次号打开详情。 */
export function DeliveryTab(props: {
  init: { factoryCode: string; pi: string; batchNo: string; transferNo: string };
  onOpen: (no: string) => void;
  ref?: Ref<DeliveryTabHandle>;
}) {
  const { t } = useTranslation('sn');
  const account = useAccount();
  const { nameOf } = useFactories();
  const [f, setF] = useState({ source: '', batchType: '', result: '', ...props.init });
  const list = usePaged('batches', (q) => snApi.deliveryBatches(q), trimmed(f));
  useImperativeHandle(props.ref, () => ({ reload: list.reload }), [list.reload]);
  const search = () => list.submit(trimmed(f));
  const sel = (k: 'source' | 'batchType' | 'result', ph: string, values: string[], label: (v: string) => string) => (
    <Select
      allowClear
      placeholder={ph}
      value={f[k] || undefined}
      options={values.map((v) => ({ value: v, label: label(v) }))}
      onChange={(v?: string) => setF({ ...f, [k]: v ?? '' })}
    />
  );
  return (
    <>
      <div className="toolbar">
        {account.multiScope ? <FactorySelect value={f.factoryCode} onChange={(v) => setF({ ...f, factoryCode: v })} scoped clearable /> : null}
        <Input allowClear placeholder="PI" value={f.pi} onChange={(e) => setF({ ...f, pi: e.target.value })} onPressEnter={search} />
        <Input allowClear placeholder={t('common.batchNo')} value={f.batchNo} onChange={(e) => setF({ ...f, batchNo: e.target.value })} onPressEnter={search} />
        {sel('source', t('query.batchSource'), ['PUSH', 'PULL'], batchSourceLabel)}
        {sel('batchType', t('query.batchType'), BATCH_TYPES, batchTypeLabel)}
        {sel('result', t('query.result'), BATCH_RESULTS, batchResultLabel)}
        <Input allowClear placeholder={t('query.transferNo')} value={f.transferNo} onChange={(e) => setF({ ...f, transferNo: e.target.value })} onPressEnter={search} />
        <Button type="primary" onClick={search}>{t('common.search')}</Button>
      </div>
      <div className="fill-body">
        <Table<Row>
          size="small"
          loading={list.loading}
          pagination={false}
          scroll={{ x: 'max-content', y: 'calc(100% - 40px)' }}
          rowKey={(r) => str(r.batchNo)}
          dataSource={list.items}
          columns={[
            {
              title: t('common.batchNo'),
              width: 220,
              fixed: 'left',
              render: (_, r) => (
                <Button type="link" style={{ padding: 0 }} onClick={() => props.onOpen(str(r.batchNo))}>
                  {str(r.batchNo)}
                </Button>
              ),
            },
            { title: t('query.batchSource'), width: 76, render: (_, r) => batchSourceLabel(r.source) },
            { title: t('query.batchType'), width: 76, render: (_, r) => batchTypeLabel(r.batchType) },
            { title: t('common.factory'), width: 120, ellipsis: true, render: (_, r) => nameOf(r.factoryCode) },
            { title: 'PI', dataIndex: 'piNo', width: 140, ellipsis: true },
            { title: t('common.qty'), dataIndex: 'qty', width: 70, align: 'right' },
            {
              title: t('query.result'),
              width: 130,
              render: (_, r) => <Tag color={BATCH_RESULT_COLOR[str(r.result)] ?? 'default'}>{batchResultLabel(r.result)}</Tag>,
            },
            {
              title: t('query.confirmMethod'),
              width: 150,
              render: (_, r) => (
                <>
                  <div>{confirmMethodLabel(r.confirmMethod)}</div>
                  {r.confirmedAt ? <div className="muted">{account.fmt(r.confirmedAt)}</div> : null}
                </>
              ),
            },
            { title: t('query.acceptedQty'), width: 90, align: 'right', render: (_, r) => <span className="num">{str(r.acceptedQty)}</span> },
            { title: t('query.pushState'), width: 110, render: (_, r) => (str(r.source) === 'PUSH' ? pushStateLabel(r.pushState) : '') },
            { title: t('query.retry'), dataIndex: 'retryCount', width: 80, align: 'right' },
            { title: t('query.nextRetry'), width: 160, render: (_, r) => (r.nextRetryAt ? account.fmt(r.nextRetryAt) : '') },
            { title: t('query.failReason'), dataIndex: 'failReason', width: 180, ellipsis: true },
            { title: t('query.transferNo'), dataIndex: 'transferDocNo', width: 170, ellipsis: true },
            { title: t('common.operator'), dataIndex: 'createdBy', width: 90 },
            { title: t('common.createdAt'), width: 160, render: (_, r) => account.fmt(r.createdAt) },
          ]}
        />
      </div>
      <Pager total={list.total} page={list.page} pageSize={list.pageSize} onPage={list.setPage} onSize={list.setPageSize} />
    </>
  );
}
