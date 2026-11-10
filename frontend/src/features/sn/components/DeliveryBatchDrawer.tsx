import { DownloadOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { Button, Descriptions, Drawer, Pagination, Table, Tag } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { snApi, snDownload, type Row } from '../../../api/sn';
import { useAccount } from '../lib/auth';
import { useFactories } from '../lib/factories';
import {
  BATCH_RESULT_COLOR,
  RECEIPT_RESULT_COLOR,
  batchResultLabel,
  batchSourceLabel,
  batchTypeLabel,
  confirmMethodLabel,
  pushStateLabel,
  pushTypeLabel,
  receiptOutcomeLabel,
  receiptResultLabel,
  receiptVerdictLabel,
} from '../lib/labels';
import { run } from '../lib/notify';
import { bool, rows, str, strs } from '../lib/row';
import { BatchManualDialog, type ManualKind } from './BatchManualDialog';
import { StatusTag } from './StatusTag';

/** 未确认或失败的批次可人工确认；可作废：未确认，或失败原因允许作废的失败批次。 */
export const canConfirm = (b: Row) => str(b.result) === 'UNCONFIRMED' || str(b.result) === 'FAILED';
export const canVoid = (b: Row) => str(b.result) === 'UNCONFIRMED' || (str(b.result) === 'FAILED' && bool(b.voidable));

/** 交付批次详情：结果、确认方式、人工处理、号段、回执存档、明细与接收情况；可人工确认、作废（editable）。 */
export function DeliveryBatchDrawer(props: {
  batchNo: string;
  onChange: (no: string) => void;
  onChanged?: () => void;
  editable?: boolean;
}) {
  const { t } = useTranslation('sn');
  const account = useAccount();
  const { nameOf } = useFactories();
  const [itemPage, setItemPage] = useState(1);
  const [manual, setManual] = useState<ManualKind | null>(null);
  const no = props.batchNo;
  const detail = useQuery({ queryKey: ['sn', 'batch', no], queryFn: () => snApi.deliveryBatch(no), enabled: !!no });
  const items = useQuery({
    queryKey: ['sn', 'batchItems', no, itemPage],
    queryFn: () => snApi.deliveryBatchItems(no, { page: itemPage, pageSize: 50 }),
    enabled: !!no,
  });
  const batch = detail.data;
  const editable = props.editable ?? true;
  return (
    <Drawer
      open={!!no}
      size={880}
      title={t('query.batchDetail', { no })}
      onClose={() => {
        setItemPage(1);
        props.onChange('');
      }}
      destroyOnHidden
    >
      {batch ? (
        <>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 12 }}>
            <Button size="small" icon={<DownloadOutlined />} onClick={() => void run(() => snDownload.deliveryExport(no))}>
              {t('common.downloadXlsx')}
            </Button>
            {account.canAct && editable && canConfirm(batch) ? (
              <Button size="small" type="primary" onClick={() => setManual('confirm')}>
                {t('query.confirmBatch')}
              </Button>
            ) : null}
            {account.canAct && editable && canVoid(batch) ? (
              <Button size="small" danger onClick={() => setManual('void')}>
                {t('query.voidBatch')}
              </Button>
            ) : null}
          </div>
          <Descriptions column={2} size="small" bordered style={{ marginBottom: 12 }}>
            <Descriptions.Item label={t('query.batchSource')}>
              {batchSourceLabel(batch.source)} · {batchTypeLabel(batch.batchType)}
            </Descriptions.Item>
            <Descriptions.Item label={t('query.result')}>
              <Tag color={BATCH_RESULT_COLOR[str(batch.result)] ?? 'default'}>{batchResultLabel(batch.result)}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label={t('common.factory')}>
              {nameOf(batch.factoryCode)}（{str(batch.factoryCode)}）
            </Descriptions.Item>
            <Descriptions.Item label="PI">{str(batch.piNo)}</Descriptions.Item>
            <Descriptions.Item label={t('common.qty')}>{str(batch.qty)}</Descriptions.Item>
            <Descriptions.Item label={t('query.acceptedQty')}>{str(batch.acceptedQty)}</Descriptions.Item>
            <Descriptions.Item label={t('query.confirmMethod')}>{confirmMethodLabel(batch.confirmMethod) || '—'}</Descriptions.Item>
            <Descriptions.Item label={t('query.confirmedAt')}>{account.fmt(batch.confirmedAt)}</Descriptions.Item>
            {str(batch.source) === 'PUSH' ? (
              <>
                <Descriptions.Item label={t('query.pushState')}>
                  {pushStateLabel(batch.pushState)} · {t('query.retry')} {str(batch.retryCount)}
                </Descriptions.Item>
                <Descriptions.Item label={t('query.pushCount')}>
                  {str(batch.pushCount)} / {str(batch.repushCount)}
                </Descriptions.Item>
              </>
            ) : null}
            {batch.failReason ? (
              <Descriptions.Item label={t('query.failReason')} span={2}>
                {batch.failCode ? `[${str(batch.failCode)}] ` : ''}
                {str(batch.failReason)}
              </Descriptions.Item>
            ) : null}
            {batch.manualAt ? (
              <Descriptions.Item label={t('query.manual')} span={2}>
                <div>{t('query.manualBy', { by: str(batch.manualBy), at: account.fmt(batch.manualAt) })}</div>
                <div>{t('common.reason')}：{str(batch.manualReason)}</div>
                <div>{t('batch.basis')}：{str(batch.manualBasis)}</div>
              </Descriptions.Item>
            ) : null}
            {batch.rebuiltFrom ? (
              <Descriptions.Item label={t('query.rebuiltFrom')}>
                <Button type="link" style={{ padding: 0 }} onClick={() => props.onChange(str(batch.rebuiltFrom))}>
                  {str(batch.rebuiltFrom)}
                </Button>
              </Descriptions.Item>
            ) : null}
            {batch.rebuiltTo ? (
              <Descriptions.Item label={t('query.rebuiltTo')}>
                <Button type="link" style={{ padding: 0 }} onClick={() => props.onChange(str(batch.rebuiltTo).split(',')[0] ?? '')}>
                  {str(batch.rebuiltTo)}
                </Button>
              </Descriptions.Item>
            ) : null}
            {batch.transferDocNo ? (
              <Descriptions.Item label={t('query.transferNo')}>{str(batch.transferDocNo)}</Descriptions.Item>
            ) : null}
            <Descriptions.Item label={t('query.bills')} span={2}>{strs(batch.bills).join('、')}</Descriptions.Item>
            <Descriptions.Item label={t('query.materials')} span={2}>{strs(batch.materials).join('、')}</Descriptions.Item>
            <Descriptions.Item label={t('query.digest')} span={2}>
              <span className="sn-mono" style={{ wordBreak: 'break-all', fontSize: 12 }}>{str(batch.itemsDigest)}</span>
            </Descriptions.Item>
            <Descriptions.Item label={t('common.operator')}>{str(batch.createdBy)}</Descriptions.Item>
            <Descriptions.Item label={t('common.createdAt')}>{account.fmt(batch.createdAt)}</Descriptions.Item>
          </Descriptions>
          <div className="surface__title">{t('query.segments')}</div>
          <Table<Row>
            size="small"
            pagination={false}
            style={{ marginBottom: 12 }}
            rowKey={(r) => `${str(r.seqPiNo)}|${str(r.startSeq)}`}
            dataSource={rows(batch.segments)}
            columns={[
              { title: t('query.seqOwner'), dataIndex: 'seqPiNo' },
              { title: t('query.segRange'), render: (_, r) => <span className="sn-mono">{str(r.startSn)} ~ {str(r.endSn)}</span> },
              { title: t('common.seqDec'), width: 130, render: (_, r) => `${str(r.startSeq)} ~ ${str(r.endSeq)}` },
              { title: t('common.qty'), dataIndex: 'qty', width: 70, align: 'right' },
            ]}
          />
          <div className="surface__title">{t('query.receipts')}</div>
          <Table<Row>
            size="small"
            pagination={false}
            style={{ marginBottom: 12 }}
            rowKey={(r) => str(r.id)}
            dataSource={rows(batch.receipts)}
            columns={[
              { title: t('common.time'), width: 160, render: (_, r) => account.fmt(r.createdAt) },
              { title: t('query.receiptMethod'), width: 110, render: (_, r) => confirmMethodLabel(r.method) },
              { title: t('query.receiptOutcome'), width: 100, render: (_, r) => receiptOutcomeLabel(r.outcome) },
              { title: t('query.receiptVerdict'), width: 100, render: (_, r) => receiptVerdictLabel(r.verdict) },
              { title: t('query.alertDetail'), dataIndex: 'detail', ellipsis: true },
              { title: t('common.operator'), dataIndex: 'createdBy', width: 90 },
            ]}
          />
        </>
      ) : null}
      <div className="surface__title">{t('query.items')}</div>
      <Table<Row>
        size="small"
        loading={items.isFetching}
        pagination={false}
        rowKey={(r) => str(r.lineNo)}
        dataSource={items.data?.items ?? []}
        columns={[
          { title: t('query.lineNo'), dataIndex: 'lineNo', width: 60 },
          { title: t('common.sn'), render: (_, r) => <span className="sn-mono">{str(r.sn)}</span> },
          { title: 'rev', dataIndex: 'rev', width: 50 },
          { title: t('query.pushType'), width: 80, render: (_, r) => pushTypeLabel(r.pushType) },
          { title: t('common.billNo'), dataIndex: 'billNo' },
          { title: t('common.attachNo'), width: 90, render: (_, r) => str(r.attachNo) || '—' },
          { title: t('common.material'), dataIndex: 'materialCode', width: 100 },
          { title: t('common.lineNo'), dataIndex: 'billSeq', width: 72 },
          { title: t('common.length'), width: 70, render: (_, r) => str(r.length) || '—' },
          {
            title: t('query.receiptResult'),
            width: 100,
            render: (_, r) =>
              r.receiptResult ? (
                <Tag color={RECEIPT_RESULT_COLOR[str(r.receiptResult)] ?? 'default'}>{receiptResultLabel(r.receiptResult)}</Tag>
              ) : null,
          },
          {
            title: t('query.currentState'),
            width: 140,
            render: (_, r) => (
              <>
                {r.status ? <StatusTag status={r.status} /> : null} <span className="muted">{str(r.currentFactory)}</span>
              </>
            ),
          },
        ]}
      />
      <div className="pager">
        <Pagination
          size="small"
          current={itemPage}
          pageSize={50}
          total={Number(items.data?.total ?? 0)}
          showTotal={(n) => t('common.total', { n })}
          showSizeChanger={false}
          onChange={setItemPage}
        />
      </div>
      <BatchManualDialog
        batchNo={no}
        kind={manual}
        onClose={() => setManual(null)}
        onDone={() => {
          void detail.refetch();
          void items.refetch();
          props.onChanged?.();
        }}
      />
    </Drawer>
  );
}
