import { DownloadOutlined } from '@ant-design/icons';
import { Alert, Button, Descriptions, Input, Modal, Select, Switch, Table, Tag } from 'antd';
import { useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { snApi, snDownload, type Row } from '../../../../api/sn';
import { FactorySelect } from '../../components/FactorySelect';
import { Pager } from '../../components/Pager';
import { StatusTag } from '../../components/StatusTag';
import { useAccount } from '../../lib/auth';
import { confirm, copyText } from '../../lib/dialogs';
import { useFactories } from '../../lib/factories';
import { STATUS_CODES, statusLabel, suspendLabel } from '../../lib/labels';
import { showError, showSuccess } from '../../lib/notify';
import { bool, obj, str } from '../../lib/row';
import { trimmed, usePaged } from '../../lib/usePaged';

const blank = () => ({
  factoryCode: '', pi: '', customerCode: '', billNo: '', materialCode: '', length: '', status: '', sn: '', seqFrom: '', seqTo: '', piAll: false,
});

/** SN 明细：按组织 / PI / 单据 / 完整 SN / 状态，及客户 / 物料 / 米数 / 流水范围筛选，导出；点行弹出该枚 SN 的详情。 */
export function SnTab({ init }: { init: { factoryCode: string; pi: string; billNo: string } }) {
  const { t } = useTranslation('sn');
  const account = useAccount();
  const { nameOf } = useFactories();
  const [f, setF] = useState({ ...blank(), ...init });
  const [exporting, setExporting] = useState(false);
  const [info, setInfo] = useState<Row | null>(null);
  const infoBox = useRef<HTMLDivElement>(null);
  const params = (x = f) => ({ ...trimmed(x), piAll: x.piAll || undefined });
  const list = usePaged('sn-items', (q) => snApi.items(q), params());
  const range = list.data?.range ? obj(list.data.range) : null;
  const hasRange = !!(f.seqFrom.trim() || f.seqTo.trim());
  const set = (k: keyof ReturnType<typeof blank>, v: string | boolean) => {
    const next = { ...f, [k]: v };
    if (k === 'pi' && !String(v).trim()) next.piAll = false;
    setF(next);
  };
  const search = () => list.submit(params());
  const text = (k: 'pi' | 'billNo' | 'sn' | 'customerCode' | 'materialCode' | 'length' | 'seqFrom' | 'seqTo', ph: string, style?: React.CSSProperties, mono = false) => (
    <Input
      allowClear
      placeholder={ph}
      className={mono ? 'sn-mono' : undefined}
      style={style}
      value={f[k]}
      onChange={(e) => set(k, e.target.value)}
      onPressEnter={search}
    />
  );

  async function exportSn() {
    setExporting(true);
    try {
      const p = params();
      const c = await snApi.snExportCheck(p);
      if (bool(c.truncated) && !(await confirm(t('common.exportTruncated', { limit: str(c.limit) }), t('common.export'), false))) {
        return;
      }
      await snDownload.snExport(p);
    } catch (e) {
      showError(e);
    } finally {
      setExporting(false);
    }
  }

  const tagAfter = (show: unknown, label: string, color: string) =>
    show ? (
      <Tag color={color} style={{ marginLeft: 4 }}>
        {label}
      </Tag>
    ) : null;

  return (
    <>
      <div className="toolbar">
        {account.multiScope ? <FactorySelect value={f.factoryCode} onChange={(v) => set('factoryCode', v)} scoped clearable /> : null}
        {text('pi', 'PI')}
        {text('billNo', t('common.billNo'))}
        {text('sn', t('common.sn'), { width: 240 }, true)}
        <Select
          allowClear
          placeholder={t('common.status')}
          value={f.status || undefined}
          onChange={(v?: string) => set('status', v ?? '')}
          options={[
            ...STATUS_CODES.map((s) => ({ value: s, label: statusLabel(s) })),
            { value: 'SUSPENDED', label: t('query.suspended') },
          ]}
        />
        <Button type="primary" onClick={search}>{t('common.search')}</Button>
        <Button
          onClick={() => {
            setF(blank());
            list.submit(params(blank()));
          }}
        >
          {t('common.reset')}
        </Button>
        <span style={{ marginLeft: 'auto' }} />
        <Button icon={<DownloadOutlined />} loading={exporting} onClick={() => void exportSn()}>
          {t('common.downloadXlsx')}
        </Button>
      </div>
      <div className="toolbar">
        {text('customerCode', t('common.customer'))}
        {text('materialCode', t('common.material'))}
        {text('length', t('common.length'), { width: 120 })}
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <span style={{ whiteSpace: 'nowrap' }}>{t('query.seqRange')}</span>
          {text('seqFrom', t('query.seqFrom'), { width: 150 }, true)}
          <span>–</span>
          {text('seqTo', t('query.seqTo'), { width: 150 }, true)}
        </div>
        {account.isOperator ? (
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
            <Switch checked={f.piAll} disabled={!f.pi.trim()} onChange={(v) => set('piAll', v)} />
            {t('query.piAll')}
          </span>
        ) : null}
      </div>
      {f.piAll ? <Alert type="info" showIcon title={t('query.piAllTip')} style={{ marginBottom: 12 }} /> : null}
      {hasRange ? <Alert type="info" showIcon title={t('query.seqRangeTip')} style={{ marginBottom: 12 }} /> : null}
      {range ? (
        <div
          style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: '8px 16px', padding: '8px 12px', borderRadius: 4, background: '#f5f7fa', fontSize: 13, marginBottom: 12 }}
        >
          <span>{t('query.rangeFound', { n: str(range.found) })}</span>
          {Object.entries(obj(range.byStatus)).map(([s, n]) => (
            <span key={s}>
              <StatusTag status={s} /> <b className="num">{str(n)}</b>
            </span>
          ))}
          {range.expected !== null && range.expected !== undefined ? (
            <span className="muted">
              {Number(range.missing)
                ? t('query.rangeMissing', { expected: str(range.expected), n: str(range.missing) })
                : t('query.rangeFull')}
            </span>
          ) : null}
        </div>
      ) : null}
      <div className="fill-body">
        <Table<Row>
          size="small"
          className="fit-table"
          loading={list.loading}
          pagination={false}
          tableLayout="auto"
          scroll={{ x: 'max-content', y: 'calc(100% - 40px)' }}
          rowKey={(r) => str(r.id)}
          dataSource={list.items}
          onRow={(r) => ({
            style: { cursor: 'pointer' },
            onClick: () => {
              if (window.getSelection()?.toString()) return;
              setInfo(r);
            },
          })}
          columns={[
            { title: 'PI', fixed: 'left', render: (_, r) => <>{str(r.piNo)}{tagAfter(r.piChanged, t('query.piChanged'), 'warning')}</> },
            { title: t('common.attachNo'), fixed: 'left', render: (_, r) => str(r.attachNo) || '—' },
            { title: t('common.material'), fixed: 'left', render: (_, r) => str(r.materialCode) || '—' },
            { title: t('common.lineNo'), fixed: 'left', dataIndex: 'billSeq' },
            { title: t('common.sn'), fixed: 'left', render: (_, r) => <span className="sn-mono">{str(r.sn)}</span> },
            { title: t('rules.seqDigits'), align: 'right', render: (_, r) => str(r.seqLen) || '—' },
            { title: t('common.seqBase'), align: 'right', render: (_, r) => str(r.base) || '—' },
            { title: t('rules.seqNo'), render: (_, r) => <span className="sn-mono">{str(r.seqText)}</span> },
            { title: t('generate.seqDec'), align: 'right', render: (_, r) => <span className="sn-mono">{str(r.seqDec) || '—'}</span> },
            { title: t('common.length'), render: (_, r) => (r.length === null || r.length === undefined ? '—' : str(r.length)) },
            { title: t('common.unit'), render: (_, r) => str(r.unit) || '—' },
            {
              title: t('common.status'),
              render: (_, r) => <><StatusTag status={r.status} />{tagAfter(r.suspend, suspendLabel(r.suspend), 'error')}</>,
            },
            { title: t('common.billNo'), dataIndex: 'billNo' },
            { title: t('query.seqOwner'), render: (_, r) => <>{str(r.seqPiNo)}{tagAfter(bool(r.transferredIn), t('query.transferredIn'), 'default')}</> },
            { title: t('common.factoryCode'), render: (_, r) => str(r.factoryCode) || '—' },
            { title: t('common.factory'), render: (_, r) => nameOf(r.factoryCode) },
            { title: t('common.customer'), render: (_, r) => str(r.customerCode) || '—' },
            { title: t('query.generatedAt'), render: (_, r) => account.fmt(r.createdAt) },
          ]}
        />
      </div>
      <Pager
        total={list.total}
        page={list.page}
        pageSize={list.pageSize}
        capped={list.capped}
        sizes={[20, 50, 100, 500]}
        onPage={list.setPage}
        onSize={list.setPageSize}
      />
      <Modal open={!!info} title={t('query.snInfo')} width="min(640px, 96vw)" footer={null} onCancel={() => setInfo(null)}>
        {info ? (
          <div ref={infoBox}>
            <Descriptions column={1} bordered size="small" styles={{ label: { width: 120, whiteSpace: 'nowrap' }, content: { wordBreak: 'break-all' } }}>
              <Descriptions.Item label="PI">{str(info.piNo)}{tagAfter(info.piChanged, t('query.piChanged'), 'warning')}</Descriptions.Item>
              <Descriptions.Item label={t('common.attachNo')}>{str(info.attachNo) || '—'}</Descriptions.Item>
              <Descriptions.Item label={t('common.material')}>{str(info.materialCode) || '—'}</Descriptions.Item>
              <Descriptions.Item label={t('common.lineNo')}>{str(info.billSeq)}</Descriptions.Item>
              <Descriptions.Item label={t('common.sn')}>
                <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
                  <span className="sn-mono" style={{ flex: 1 }}>{str(info.sn)}</span>
                  <Button
                    size="small"
                    type="link"
                    onClick={() =>
                      void copyText(str(info.sn), infoBox.current ?? undefined).then((ok) =>
                        ok ? showSuccess(t('common.copied')) : showError(new Error(t('common.copyFailed'))),
                      )
                    }
                  >
                    {t('common.copy')}
                  </Button>
                </div>
              </Descriptions.Item>
              <Descriptions.Item label={t('rules.seqDigits')}>{str(info.seqLen) || '—'}</Descriptions.Item>
              <Descriptions.Item label={t('common.seqBase')}>{str(info.base) || '—'}</Descriptions.Item>
              <Descriptions.Item label={t('rules.seqNo')}><span className="sn-mono">{str(info.seqText)}</span></Descriptions.Item>
              <Descriptions.Item label={t('generate.seqDec')}><span className="sn-mono">{str(info.seqDec) || '—'}</span></Descriptions.Item>
              <Descriptions.Item label={t('common.length')}>{info.length === null || info.length === undefined ? '—' : str(info.length)}</Descriptions.Item>
              <Descriptions.Item label={t('common.unit')}>{str(info.unit) || '—'}</Descriptions.Item>
              <Descriptions.Item label={t('common.status')}><StatusTag status={info.status} />{tagAfter(info.suspend, suspendLabel(info.suspend), 'error')}</Descriptions.Item>
              <Descriptions.Item label={t('common.billNo')}>{str(info.billNo)}</Descriptions.Item>
              <Descriptions.Item label={t('query.seqOwner')}>{str(info.seqPiNo)}{tagAfter(bool(info.transferredIn), t('query.transferredIn'), 'default')}</Descriptions.Item>
              <Descriptions.Item label={t('common.factoryCode')}>{str(info.factoryCode) || '—'}</Descriptions.Item>
              <Descriptions.Item label={t('common.factory')}>{nameOf(info.factoryCode)}</Descriptions.Item>
              <Descriptions.Item label={t('common.customer')}>{str(info.customerCode) || '—'}</Descriptions.Item>
              <Descriptions.Item label={t('query.generatedAt')}>{account.fmt(info.createdAt)}</Descriptions.Item>
            </Descriptions>
          </div>
        ) : null}
      </Modal>
    </>
  );
}
