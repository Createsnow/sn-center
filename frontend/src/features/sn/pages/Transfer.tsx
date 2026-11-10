import { ArrowRightOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Input, Radio, Table, Tag } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { snApi, type Row } from '../../../api/sn';
import { FactorySelect } from '../components/FactorySelect';
import { PageHead } from '../components/PageHead';
import { Pager } from '../components/Pager';
import { useAccount } from '../lib/auth';
import { useFactories } from '../lib/factories';
import { scopeLabel } from '../lib/labels';
import { num, str } from '../lib/row';
import { trimmed, usePaged } from '../lib/usePaged';
import { TransferDetailDrawer, type TransferRef } from './transfer/TransferDetailDrawer';
import { TransferDialog } from './transfer/TransferDialog';
import { snRange, type Kind } from './transfer/types';

const KINDS: Kind[] = ['BILL', 'ORG'];

/**
 * SN 转移（F-15、F-16、F-17）：转单与转组织合在一张记录表；新建转移不区分类型，系统按目标判断记为转单或转组织。
 * 系统管理员、普通操作员都直接转移（原因必填，立即生效），没有申请、审批。
 */
export default function Transfer() {
  const { t } = useTranslation('sn');
  const account = useAccount();
  const { nameOf } = useFactories();
  const [lq, setLq] = useState({ kind: '', factoryCode: '', pi: '', billNo: '' });
  const list = usePaged('transfers', (q) => snApi.transfers(q), trimmed(lq));
  const [dlg, setDlg] = useState(false);
  const [detail, setDetail] = useState<TransferRef | null>(null);
  const search = (next = lq) => list.submit(trimmed(next));
  const setAndSearch = (patch: Partial<typeof lq>) => {
    const next = { ...lq, ...patch };
    setLq(next);
    search(next);
  };

  const modeLabel = (r: Row) => (str(r.kind) === 'BILL' ? t(`transfer.billMode.${str(r.mode)}`) : t(`transfer.orgMode.${str(r.mode)}`));
  /** 来源范围：整单 / 物料编码 / 号段。转单取范围说明去掉 PI、单据。 */
  const sourceText = (d: Row) => {
    const rest =
      str(d.kind) === 'BILL'
        ? str(d.scopeText).split(' / ').slice(2)
        : [str(d.scope) !== 'BILL' && str(d.fromMaterial) ? `${t('common.material')} ${str(d.fromMaterial)}` : '', str(d.fromSn) ? snRange(d) : ''];
    return [scopeLabel(d.scope), ...rest].filter(Boolean).join(' · ');
  };
  /** 目标：单据 · ERP行号 · 物料编码 · 客户。 */
  const targetText = (d: Row) =>
    [
      str(d.toBill) && (num(d.toSeq) ? `${str(d.toBill)} · ${t('transfer.lineNo', { n: str(d.toSeq) })}` : str(d.toBill)),
      str(d.toMaterial) && `${t('common.material')} ${str(d.toMaterial)}`,
      str(d.toCustomer) && `${t('common.customer')} ${str(d.toCustomer)}`,
    ]
      .filter(Boolean)
      .join(' · ');
  const openDetail = (r: Row) => setDetail({ kind: str(r.kind) as Kind, id: str(r.id) });
  const one = { whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' } as const;

  return (
    <div className="page">
      <PageHead title={t('transfer.title')} desc={t('transfer.desc')}>
        {account.canAct ? (
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setDlg(true)}>
            {t('transfer.new')}
          </Button>
        ) : null}
      </PageHead>

      {/* 转移记录（转单 + 转组织，都直接生效） */}
      <div className="surface">
        <div className="toolbar sn-tf-toolbar">
          <Radio.Group
            optionType="button"
            value={lq.kind}
            onChange={(e) => setAndSearch({ kind: String(e.target.value) })}
            options={[{ value: '', label: t('transfer.allKinds') }, ...KINDS.map((k) => ({ value: k, label: t(`transfer.kindName.${k}`) }))]}
          />
          {account.multiScope ? (
            <FactorySelect value={lq.factoryCode} scoped clearable placeholder={t('app.allFactories')} onChange={(v) => setAndSearch({ factoryCode: v })} />
          ) : null}
          <Input
            allowClear
            placeholder={t('transfer.piInOut')}
            value={lq.pi}
            onChange={(e) => (e.target.value ? setLq({ ...lq, pi: e.target.value }) : setAndSearch({ pi: '' }))}
            onPressEnter={() => search()}
          />
          <Input
            allowClear
            placeholder={t('common.billNo')}
            value={lq.billNo}
            onChange={(e) => (e.target.value ? setLq({ ...lq, billNo: e.target.value }) : setAndSearch({ billNo: '' }))}
            onPressEnter={() => search()}
          />
          <Button type="primary" onClick={() => search()}>
            {t('common.search')}
          </Button>
        </div>
        <Table<Row>
          size="small"
          loading={list.loading}
          rowKey={(r) => `${str(r.kind)}|${str(r.id)}`}
          pagination={false}
          dataSource={list.items}
          locale={{ emptyText: t('common.empty') }}
          scroll={{ x: 'max-content' }}
          columns={[
            {
              title: t('transfer.no'),
              width: 200,
              render: (_, r) => (
                <>
                  <Button type="link" size="small" className="sn-mono" style={{ padding: 0 }} onClick={() => openDetail(r)}>
                    {str(r.transferNo)}
                  </Button>
                  <div className="muted">
                    <Tag variant="outlined" color={str(r.kind) === 'BILL' ? 'blue' : 'warning'}>
                      {t(`transfer.kindName.${str(r.kind)}`)}
                    </Tag>
                    <span>{modeLabel(r)}</span>
                    {str(r.direction) ? (
                      <Tag color={str(r.direction) === 'OUT' ? 'warning' : 'success'} style={{ marginLeft: 8 }}>
                        {t(`transfer.direction.${str(r.direction)}`)}
                      </Tag>
                    ) : null}
                  </div>
                </>
              ),
            },
            {
              title: t('transfer.fromTo'),
              width: 380,
              render: (_, r) => (
                <div className="sn-flow">
                  <div style={{ minWidth: 0 }}>
                    <div style={one} title={str(r.fromBill)}>
                      {str(r.fromFactory) ? `${nameOf(r.fromFactory)} · ` : ''}
                      {str(r.fromPi)}
                      {str(r.fromBill) ? ` · ${str(r.fromBill)}` : ''}
                    </div>
                    <div className="muted" style={one} title={sourceText(r)}>
                      {sourceText(r)}
                    </div>
                  </div>
                  <ArrowRightOutlined style={{ color: 'var(--sn-muted)' }} />
                  <div style={{ minWidth: 0 }}>
                    <div style={one} title={str(r.toFactory) ? nameOf(r.toFactory) : undefined}>
                      <b>
                        {str(r.toFactory) ? `${nameOf(r.toFactory)} · ` : ''}
                        {str(r.toPi)}
                      </b>
                    </div>
                    <div className="muted" style={one} title={targetText(r)}>
                      {targetText(r)}
                    </div>
                  </div>
                </div>
              ),
            },
            { title: t('common.qty'), width: 70, align: 'right', render: (_, r) => <span className="num">{str(r.qty)}</span> },
            { title: t('transfer.reason'), dataIndex: 'reason', width: 220, ellipsis: true },
            {
              title: t('common.operator'),
              width: 150,
              render: (_, r) => (
                <>
                  <div>{str(r.createdBy)}</div>
                  <div className="muted">{account.fmt(r.createdAt)}</div>
                </>
              ),
            },
            {
              title: t('common.actions'),
              width: 80,
              align: 'right',
              render: (_, r) => (
                <Button type="link" size="small" onClick={() => openDetail(r)}>
                  {t('common.detail')}
                </Button>
              ),
            },
          ]}
        />
        <Pager total={list.total} page={list.page} pageSize={list.pageSize} capped={list.capped} onPage={list.setPage} onSize={list.setPageSize} />
      </div>
      <TransferDialog
        open={dlg}
        onClose={() => setDlg(false)}
        onDone={(kind, doc) => {
          search();
          setDetail({ kind, id: str(doc.id), doc });
        }}
      />
      <TransferDetailDrawer target={detail} onClose={() => setDetail(null)} />
    </div>
  );
}
