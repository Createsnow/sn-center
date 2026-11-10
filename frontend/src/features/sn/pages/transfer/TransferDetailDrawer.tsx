import { DownloadOutlined } from '@ant-design/icons';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Button, Descriptions, Drawer, Table, Tag } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { snApi, snDownload, type Row } from '../../../../api/sn';
import { BatchManualDialog, type ManualKind } from '../../components/BatchManualDialog';
import { DeliveryBatchDrawer, canConfirm, canVoid } from '../../components/DeliveryBatchDrawer';
import { Pager } from '../../components/Pager';
import { StatusTag } from '../../components/StatusTag';
import { useAccount } from '../../lib/auth';
import { useFactories } from '../../lib/factories';
import { BATCH_RESULT_COLOR, batchResultLabel, scopeLabel, statusLabel } from '../../lib/labels';
import { showError, showSuccess } from '../../lib/notify';
import { num, obj, rows, str, strs } from '../../lib/row';
import { snRange, type Kind } from './types';

export interface TransferRef {
  kind: Kind;
  id: string;
  doc?: Row;
}

/** 转单 / 转组织单详情：单据信息（含转移原因）、转出号清单下载、转移后的推送（续推、转出批次人工确认 / 作废）与逐枚明细。 */
export function TransferDetailDrawer(props: { target: TransferRef | null; onClose: () => void }) {
  const { t } = useTranslation('sn');
  const account = useAccount();
  const { nameOf } = useFactories();
  const [busy, setBusy] = useState('');
  const [batchNo, setBatchNo] = useState('');
  const [manual, setManual] = useState<{ no: string; kind: ManualKind } | null>(null);
  const [itemPage, setItemPage] = useState(1);
  const [lastId, setLastId] = useState('');
  const tg = props.target;
  if ((tg?.id ?? '') !== lastId) {
    setLastId(tg?.id ?? '');
    setItemPage(1);
  }
  const detail = useQuery({
    queryKey: ['sn', 'transfer', tg?.kind, tg?.id],
    queryFn: () => (tg!.kind === 'BILL' ? snApi.billTransferOne(tg!.id) : snApi.orgTransfer(tg!.id)),
    enabled: !!tg,
    initialData: tg?.doc,
    staleTime: 0,
  });
  const items = useQuery({
    queryKey: ['sn', 'transferItems', tg?.kind, tg?.id, itemPage],
    queryFn: () =>
      tg!.kind === 'BILL'
        ? snApi.billTransferItems(tg!.id, { page: itemPage, pageSize: 50 })
        : snApi.orgTransferItems(tg!.id, { page: itemPage, pageSize: 50 }),
    enabled: !!tg,
    placeholderData: keepPreviousData,
  });
  const doc = detail.data ?? null;
  const kind = tg?.kind;
  const refresh = () => {
    void detail.refetch();
    void items.refetch();
  };

  async function resume(factory: string, key: string) {
    setBusy(`resume:${factory}:${key}`);
    try {
      await snApi.resumePush(factory, key);
      showSuccess(t('transfer.resumed'));
      refresh();
    } catch (e) {
      showError(e);
    } finally {
      setBusy('');
    }
  }
  async function downloadOutList() {
    if (!doc) return;
    try {
      await snDownload.outList(str(doc.id));
    } catch (e) {
      showError(e);
    }
  }

  const deliveries = rows(doc?.deliveries);
  const outBatches = rows(doc?.outBatches);
  const lineNo = (n: unknown) => t('transfer.lineNo', { n: str(n) });

  return (
    <Drawer
      open={!!tg}
      size={980}
      onClose={props.onClose}
      title={
        doc ? (
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, fontSize: 15 }}>
            <b className="sn-mono">{str(doc.transferNo)}</b>
            <Tag variant="outlined" color={kind === 'BILL' ? 'blue' : 'warning'}>
              {t(`transfer.kindName.${kind ?? 'BILL'}`)}
            </Tag>
            {str(doc.direction) ? <Tag variant="outlined">{t(`transfer.direction.${str(doc.direction)}`)}</Tag> : null}
          </div>
        ) : null
      }
    >
      {doc ? (
        <>
          <Descriptions column={2} size="small" bordered>
            {kind === 'BILL' ? (
              <>
                <Descriptions.Item label={t('transfer.mode')}>{scopeLabel(doc.scope)}</Descriptions.Item>
                <Descriptions.Item label={t('transfer.scope')}>{str(doc.scopeText)}</Descriptions.Item>
                <Descriptions.Item label={t('transfer.fromBill')}>
                  {str(doc.fromBill)} · {str(doc.fromPi)}
                </Descriptions.Item>
                <Descriptions.Item label={t('transfer.toBill')}>
                  {str(doc.toBill)} · {str(doc.toPi)}
                  {num(doc.toSeq) ? ` · ${lineNo(doc.toSeq)}` : ''}
                  {str(doc.toMaterial) ? ` · ${t('common.material')} ${str(doc.toMaterial)}` : ''}
                  {str(doc.toFactory) ? ` · ${nameOf(doc.toFactory)}` : ''}
                </Descriptions.Item>
                <Descriptions.Item label={t('common.operator')}>
                  {str(doc.createdBy)} · {account.fmt(doc.createdAt)}
                </Descriptions.Item>
              </>
            ) : (
              <>
                <Descriptions.Item label={t('transfer.mode')}>{t(`transfer.orgMode.${str(doc.mode)}`)}</Descriptions.Item>
                <Descriptions.Item label={t('transfer.scope')}>
                  {scopeLabel(doc.scope)}
                  {str(doc.fromMaterial) && (str(doc.scope) === 'MATERIAL' || str(doc.scope) === 'RANGE')
                    ? ` · ${t('common.material')} ${str(doc.fromMaterial)}`
                    : ''}
                  {str(doc.fromSn) ? (
                    <>
                      {' · '}
                      <span className="sn-mono">{snRange(doc)}</span>
                    </>
                  ) : null}
                </Descriptions.Item>
                <Descriptions.Item label={t('transfer.source')}>
                  {nameOf(doc.fromFactory)} · {str(doc.fromPi)}
                  {str(doc.fromBill) ? ` · ${str(doc.fromBill)}` : ''}
                </Descriptions.Item>
                <Descriptions.Item label={t('transfer.target')}>
                  {nameOf(doc.toFactory)} · {str(doc.toPi)}
                  {str(doc.toBill) ? ` · ${str(doc.toBill)}${num(doc.toSeq) ? ` · ${lineNo(doc.toSeq)}` : ''}` : ''}
                  {str(doc.toMaterial) ? ` · ${t('common.material')} ${str(doc.toMaterial)}` : ''}
                  {str(doc.toCustomer) ? ` · ${str(doc.toCustomer)}` : ''}
                </Descriptions.Item>
                <Descriptions.Item label={t('common.operator')}>
                  {str(doc.appliedBy)} · {account.fmt(doc.appliedAt)}
                </Descriptions.Item>
              </>
            )}
            <Descriptions.Item label={t('common.qty')}>
              <span className="num">{str(doc.qty)}</span>
            </Descriptions.Item>
            <Descriptions.Item label={t('transfer.reason')}>{str(doc.reason)}</Descriptions.Item>
          </Descriptions>

          {kind === 'ORG' ? (
            <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
              <Button size="small" icon={<DownloadOutlined />} onClick={() => void downloadOutList()}>
                {t('transfer.outList')}
              </Button>
            </div>
          ) : null}

          {deliveries.length || outBatches.length ? (
            <>
              <h4 className="sn-sub">{t('transfer.pushTitle')}</h4>
              {deliveries.length ? (
                <Table<Row>
                  size="small"
                  rowKey={(r) => `${str(r.factoryCode)}|${str(r.piNo)}`}
                  pagination={false}
                  dataSource={deliveries}
                  columns={[
                    { title: t('transfer.pushTo'), width: 160, render: (_, r) => `${nameOf(r.factoryCode)} · ${str(r.piNo)}` },
                    { title: t('common.qty'), width: 70, align: 'right', render: (_, r) => <span className="num">{str(r.qty)}</span> },
                    { title: t('status.TO_PUSH'), width: 80, align: 'right', render: (_, r) => <span className="num">{str(r.toPush)}</span> },
                    {
                      title: t('transfer.batches'),
                      width: 200,
                      render: (_, r) =>
                        strs(r.batches).length
                          ? strs(r.batches).map((no) => (
                              <Button key={no} type="link" size="small" className="sn-mono" style={{ fontSize: 11 }} onClick={() => setBatchNo(no)}>
                                {no}
                              </Button>
                            ))
                          : '—',
                    },
                    {
                      title: t('common.actions'),
                      width: 170,
                      align: 'right',
                      render: (_, r) => {
                        const rem = r.remaining ? obj(r.remaining) : null;
                        if (account.canWrite(str(r.factoryCode)) && rem && (num(rem.batches) || num(rem.unbatched))) {
                          const key = `resume:${str(r.factoryCode)}:${str(r.piNo)}`;
                          return (
                            <Button size="small" loading={busy === key} onClick={() => void resume(str(r.factoryCode), str(r.piNo))}>
                              {t('transfer.resume', { n: str(rem.pushes) })}
                            </Button>
                          );
                        }
                        return !rem ? <span className="muted">{t('transfer.noMes')}</span> : null;
                      },
                    },
                  ]}
                />
              ) : null}
              {outBatches.length ? (
                <Table<Row>
                  size="small"
                  style={{ marginTop: 12 }}
                  rowKey={(r) => str(r.batchNo)}
                  pagination={false}
                  dataSource={outBatches}
                  columns={[
                    {
                      title: t('transfer.outBatch'),
                      width: 220,
                      render: (_, r) => (
                        <Button type="link" size="small" className="sn-mono" style={{ padding: 0 }} onClick={() => setBatchNo(str(r.batchNo))}>
                          {str(r.batchNo)}
                        </Button>
                      ),
                    },
                    { title: t('common.factory'), width: 120, render: (_, r) => nameOf(r.factoryCode) },
                    { title: t('common.qty'), width: 70, align: 'right', render: (_, r) => <span className="num">{str(r.qty)}</span> },
                    {
                      title: t('common.status'),
                      width: 110,
                      render: (_, r) => <Tag color={BATCH_RESULT_COLOR[str(r.result)] ?? 'default'}>{batchResultLabel(r.result)}</Tag>,
                    },
                    { title: t('transfer.failReason'), width: 160, ellipsis: true, render: (_, r) => str(r.failReason) || '—' },
                    {
                      title: t('common.actions'),
                      width: 230,
                      align: 'right',
                      render: (_, r) =>
                        account.canWrite(str(r.factoryCode)) && canConfirm(r) ? (
                          <>
                            <Button
                              type="link"
                              size="small"
                              loading={busy === `resume:${str(r.factoryCode)}:#XO`}
                              onClick={() => void resume(str(r.factoryCode), '#XO')}
                            >
                              {t('transfer.resumeShort')}
                            </Button>
                            <Button type="link" size="small" style={{ color: '#16a34a' }} onClick={() => setManual({ no: str(r.batchNo), kind: 'confirm' })}>
                              {t('transfer.manualConfirm')}
                            </Button>
                            {canVoid(r) ? (
                              <Button type="link" size="small" danger onClick={() => setManual({ no: str(r.batchNo), kind: 'void' })}>
                                {t('transfer.void')}
                              </Button>
                            ) : null}
                          </>
                        ) : null,
                    },
                  ]}
                />
              ) : null}
            </>
          ) : null}

          <h4 className="sn-sub">{t('transfer.itemsTitle')}</h4>
          <Table<Row>
            size="small"
            loading={items.isFetching}
            rowKey={(r) => str(r.sn)}
            pagination={false}
            dataSource={rows(items.data?.items)}
            locale={{ emptyText: t('common.empty') }}
            columns={[
              {
                title: t('common.sn'),
                width: 190,
                render: (_, r) => (
                  <>
                    <div className="sn-mono">{str(r.sn)}</div>
                    <div className="muted">
                      {str(r.seqText)} · {str(r.seqPiNo)}
                    </div>
                  </>
                ),
              },
              {
                title: t('transfer.before'),
                width: 200,
                render: (_, r) => (
                  <>
                    <div>
                      {statusLabel(r.fromStatus)} · {str(r.fromFactory) || '—'} · {str(r.fromPi)}
                    </div>
                    <div className="muted">
                      {str(r.fromBill)} · {t('common.attachNo')} {str(r.attachNo) || '—'} · {t('common.material')} {str(r.fromMaterial) || '—'} ·{' '}
                      {lineNo(r.fromSeq)} · {t('common.length')} {str(r.length) || '—'} · rev {str(r.fromRev)}
                    </div>
                    {str(r.fromDeliveryBatch) ? <div className="muted sn-mono" style={{ fontSize: 11 }}>{str(r.fromDeliveryBatch)}</div> : null}
                  </>
                ),
              },
              {
                title: t('transfer.after'),
                width: 200,
                render: (_, r) => (
                  <>
                    <div>
                      {statusLabel(r.toStatus)} · {str(r.toFactory) || '—'} · {str(r.toPi)}
                    </div>
                    <div className="muted">
                      {str(r.toBill)} · {t('common.material')} {str(r.toMaterial) || '—'} · {lineNo(r.toSeq)} · rev {str(r.toRev)}
                    </div>
                    {str(r.outBatchNo) ? (
                      <div className="muted sn-mono" style={{ fontSize: 11 }}>
                        {t('transfer.outBatch')} {str(r.outBatchNo)}
                      </div>
                    ) : null}
                  </>
                ),
              },
              {
                title: t('transfer.current'),
                width: 150,
                render: (_, r) => (
                  <>
                    {str(r.currentStatus) === 'TRANSFERRED_OUT' ? (
                      <Tag>{t('transfer.transferredOut')}</Tag>
                    ) : str(r.currentStatus) ? (
                      <StatusTag status={r.currentStatus} />
                    ) : null}
                    {str(r.curBatch) ? <div className="muted sn-mono" style={{ fontSize: 11 }}>{str(r.curBatch)}</div> : null}
                  </>
                ),
              },
            ]}
          />
          <Pager total={Number(items.data?.total ?? 0)} page={itemPage} pageSize={50} onPage={setItemPage} />
        </>
      ) : null}

      <BatchManualDialog
        batchNo={manual?.no ?? ''}
        kind={manual?.kind ?? null}
        onClose={() => setManual(null)}
        onDone={() => {
          setManual(null);
          refresh();
        }}
      />
      <DeliveryBatchDrawer batchNo={batchNo} onChange={setBatchNo} onChanged={refresh} />
    </Drawer>
  );
}
