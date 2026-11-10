import { ReloadOutlined } from '@ant-design/icons';
import { Button, Input, Modal, Radio, Table, Tag } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { snApi, type Row } from '../../../../api/sn';
import { Pager } from '../../components/Pager';
import { useAccount } from '../../lib/auth';
import { useFactories } from '../../lib/factories';
import { run, showSuccess } from '../../lib/notify';
import { str } from '../../lib/row';
import { usePaged } from '../../lib/usePaged';

/** 交付告警：每日自检发现的问题；核查后填写结论并关闭。 */
export function AlertsTab({ onOpen }: { onOpen: (no: string) => void }) {
  const { t, i18n } = useTranslation('sn');
  const account = useAccount();
  const { nameOf } = useFactories();
  const [status, setStatus] = useState('OPEN');
  const list = usePaged('dalerts', (q) => snApi.deliveryAlerts(q), { status });
  const [handle, setHandle] = useState<Row | null>(null);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const code = (prefix: string, c: unknown) =>
    c && i18n.exists(`${prefix}.${str(c)}`, { ns: 'sn' }) ? t(`${prefix}.${str(c)}`) : str(c);
  async function submit() {
    if (!handle) return;
    setBusy(true);
    const r = await run(() => snApi.handleDeliveryAlert(str(handle.id), note.trim()));
    setBusy(false);
    if (r) {
      showSuccess(t('common.done'));
      setHandle(null);
      list.reload();
    }
  }
  return (
    <>
      <div className="toolbar">
        <Radio.Group
          optionType="button"
          value={status}
          onChange={(e) => {
            const v = String(e.target.value);
            setStatus(v);
            list.submit({ status: v });
          }}
          options={[
            { value: 'OPEN', label: t('query.alertStatus.OPEN') },
            { value: 'CLOSED', label: t('query.alertStatus.CLOSED') },
            { value: 'ALL', label: t('common.all') },
          ]}
        />
        <Button icon={<ReloadOutlined />} onClick={() => list.submit({ status })}>{t('common.refresh')}</Button>
      </div>
      <div className="fill-body">
        <Table<Row>
          size="small"
          loading={list.loading}
          pagination={false}
          scroll={{ x: 'max-content', y: 'calc(100% - 40px)' }}
          rowKey={(r) => str(r.id)}
          dataSource={list.items}
          columns={[
            { title: t('common.time'), width: 160, render: (_, r) => account.fmt(r.createdAt) },
            { title: t('query.alertTypeCol'), width: 130, render: (_, r) => <Tag color="warning">{code('query.alertType', r.alertType)}</Tag> },
            { title: t('common.factory'), width: 120, ellipsis: true, render: (_, r) => nameOf(r.factoryCode) },
            {
              title: t('common.batchNo'),
              width: 210,
              render: (_, r) =>
                r.batchNo ? (
                  <Button type="link" style={{ padding: 0 }} onClick={() => onOpen(str(r.batchNo))}>{str(r.batchNo)}</Button>
                ) : null,
            },
            { title: t('common.sn'), width: 170, render: (_, r) => <span className="sn-mono">{str(r.sn)}</span> },
            { title: t('query.alertDetail'), dataIndex: 'detail', width: 220, ellipsis: true },
            {
              title: t('common.status'),
              width: 90,
              render: (_, r) => <Tag color={str(r.status) === 'OPEN' ? 'warning' : 'default'}>{code('query.alertStatus', r.status)}</Tag>,
            },
            {
              title: t('query.handledBy'),
              width: 220,
              render: (_, r) =>
                str(r.status) === 'CLOSED' ? (
                  <>
                    <div>{str(r.closedBy)} · {account.fmt(r.closedAt)}</div>
                    <div className="muted">{str(r.note)}</div>
                  </>
                ) : null,
            },
            ...(account.canAct
              ? [
                  {
                    title: t('common.actions'),
                    width: 120,
                    fixed: 'right' as const,
                    render: (_: unknown, r: Row) =>
                      str(r.status) === 'OPEN' ? (
                        <Button type="link" style={{ padding: 0 }} onClick={() => { setNote(''); setHandle(r); }}>
                          {t('query.closeAlert')}
                        </Button>
                      ) : null,
                  },
                ]
              : []),
          ]}
        />
      </div>
      <Pager total={list.total} page={list.page} pageSize={list.pageSize} onPage={list.setPage} onSize={list.setPageSize} />
      <Modal
        open={!!handle}
        width={520}
        title={t('query.handleTitle', { sn: str(handle?.sn) || str(handle?.batchNo) })}
        onCancel={() => setHandle(null)}
        footer={[
          <Button key="c" onClick={() => setHandle(null)}>{t('common.cancel')}</Button>,
          <Button key="o" type="primary" loading={busy} disabled={!note.trim()} onClick={() => void submit()}>
            {t('query.closeAlert')}
          </Button>,
        ]}
      >
        <Input.TextArea rows={3} maxLength={500} showCount placeholder={t('query.notePlaceholder')} value={note} onChange={(e) => setNote(e.target.value)} />
      </Modal>
    </>
  );
}
