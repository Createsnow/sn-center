import { DownloadOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { Button, DatePicker, Descriptions, Input, Select, Table, Tag, Tooltip } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { snApi, snDownload, type Row } from '../../../api/sn';
import { FactorySelect } from '../components/FactorySelect';
import { PageHead } from '../components/PageHead';
import { Pager } from '../components/Pager';
import { useAccount } from '../lib/auth';
import { confirm } from '../lib/dialogs';
import { useFactories } from '../lib/factories';
import { AUDIT_ACTIONS, actionLabel, roleLabel, statusLabel } from '../lib/labels';
import { showError } from '../lib/notify';
import { bool, str } from '../lib/row';
import { trimmed, usePaged } from '../lib/usePaged';

export default function Audit() {
  const { t } = useTranslation('sn');
  const account = useAccount();
  const { nameOf } = useFactories();
  const offset = Number(account.profile?.tzOffsetMin ?? 480);
  const localDay = (ms: number) => new Date(ms + offset * 60_000).toISOString().slice(0, 10);
  const [range, setRange] = useState<[string, string] | null>([localDay(Date.now() - 29 * 86400_000), localDay(Date.now())]);
  const blank = { action: '', result: '', source: '', operator: '', factoryCode: '', pi: '', billNo: '', batchNo: '', keyword: '' };
  const [f, setF] = useState(blank);
  const [exporting, setExporting] = useState(false);
  const params = () => ({ ...trimmed(f), from: range?.[0], to: range?.[1] });
  const list = usePaged('audits', (q) => snApi.audits(q), params());
  const meta = useQuery({ queryKey: ['sn', 'auditMeta'], queryFn: () => snApi.auditMeta() });
  const m = meta.data;
  const retention = !m
    ? ''
    : bool(m.permanent)
      ? t('audit.retentionForever')
      : `${t('audit.retention', { days: str(m.retentionDays) })}${
          m.archiveRetentionDays && str(m.archiveRetentionDays) !== '0'
            ? t('audit.archiveKeep', { days: str(m.archiveRetentionDays) })
            : t('audit.archiveForever')
        }${m.archiveFrom ? t('audit.archiveSpan', { from: str(m.archiveFrom).slice(0, 10), to: str(m.archiveTo).slice(0, 10) }) : ''}`;
  const statusText = (s: unknown) => (s === 'PREVIOUS' ? t('audit.previous') : statusLabel(s));
  const sourceLabel = (s: unknown) =>
    s === 'PAGE' || s === 'API' || s === 'SYSTEM' ? t(`audit.src${String(s)}`) : str(s);
  const set = (k: keyof typeof blank) => (v: string) => setF({ ...f, [k]: v });
  const search = () => list.submit(params());

  async function exportFile() {
    setExporting(true);
    try {
      const p = params();
      const c = await snApi.auditExportCheck(p);
      if (bool(c.truncated) && !(await confirm(t('common.exportTruncated', { limit: str(c.limit) }), t('common.export'), false))) {
        return;
      }
      await snDownload.auditExport(p);
    } catch (e) {
      showError(e);
    } finally {
      setExporting(false);
    }
  }

  const text = (k: keyof typeof blank, placeholder: string, width?: number) => (
    <Input
      allowClear
      placeholder={placeholder}
      style={width ? { width } : undefined}
      value={f[k]}
      onChange={(e) => set(k)(e.target.value)}
      onPressEnter={search}
    />
  );

  return (
    <div className="page page--fill">
      <PageHead title={t('audit.title')} desc={retention}>
        <Button icon={<DownloadOutlined />} loading={exporting} onClick={() => void exportFile()}>
          {t('audit.exportXlsx')}
        </Button>
      </PageHead>
      <div className="surface fill">
        <div className="toolbar">
          <Tooltip title={t('audit.tzTip', { tz: account.tz })}>
            <DatePicker.RangePicker
              value={range ? [dayjs(range[0]), dayjs(range[1])] : null}
              placeholder={[t('common.dateFrom'), t('common.dateTo')]}
              onChange={(v) =>
                setRange(v && v[0] && v[1] ? [(v[0] as Dayjs).format('YYYY-MM-DD'), (v[1] as Dayjs).format('YYYY-MM-DD')] : null)
              }
            />
          </Tooltip>
          <Select
            allowClear
            showSearch={{ optionFilterProp: 'label' }}
            placeholder={t('audit.action')}
            value={f.action || undefined}
            options={AUDIT_ACTIONS.map((a) => ({ value: a, label: actionLabel(a) }))}
            onChange={(v?: string) => set('action')(v ?? '')}
          />
          <Select
            allowClear
            placeholder={t('audit.result')}
            value={f.result || undefined}
            options={[
              { value: 'OK', label: t('audit.ok') },
              { value: 'FAIL', label: t('audit.fail') },
            ]}
            onChange={(v?: string) => set('result')(v ?? '')}
          />
          <Select
            allowClear
            placeholder={t('audit.source')}
            value={f.source || undefined}
            options={['PAGE', 'API', 'SYSTEM'].map((s) => ({ value: s, label: t(`audit.src${s}`) }))}
            onChange={(v?: string) => set('source')(v ?? '')}
          />
          {text('operator', t('audit.operator'))}
          {account.multiScope ? <FactorySelect value={f.factoryCode} onChange={set('factoryCode')} scoped clearable /> : null}
          {text('pi', 'PI')}
          {text('billNo', t('common.billNo'))}
          {text('batchNo', t('common.batchNo'))}
          {text('keyword', t('audit.keyword'), 240)}
          <Button type="primary" onClick={search}>
            {t('common.search')}
          </Button>
        </div>
        <div className="fill-body">
          <Table<Row>
            size="small"
            loading={list.loading}
            rowKey={(r) => str(r.id)}
            pagination={false}
            dataSource={list.items}
            scroll={{ x: 'max-content', y: 'calc(100% - 40px)' }}
            expandable={{
              expandedRowRender: (r) => (
                <Descriptions column={3} size="small" bordered style={{ padding: '4px 48px' }}>
                  <Descriptions.Item label={t('common.customer')}>{str(r.customerCode)}</Descriptions.Item>
                  <Descriptions.Item label={t('common.material')}>{str(r.materialCode)}</Descriptions.Item>
                  <Descriptions.Item label={t('common.billNo')}>{str(r.billNo)}</Descriptions.Item>
                  <Descriptions.Item label={t('common.batchNo')}>{str(r.batchNo)}</Descriptions.Item>
                  <Descriptions.Item label={t('audit.requestNo')}>{str(r.requestNo)}</Descriptions.Item>
                  <Descriptions.Item label={t('audit.transferNo')}>{str(r.transferNo)}</Descriptions.Item>
                  <Descriptions.Item label={t('common.reason')} span={3}>{str(r.reason)}</Descriptions.Item>
                  <Descriptions.Item label={t('audit.error')} span={3}>{str(r.errorMsg)}</Descriptions.Item>
                  <Descriptions.Item label={t('audit.detail')} span={3}>
                    <span style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>{str(r.detail)}</span>
                  </Descriptions.Item>
                  <Descriptions.Item label="Trace ID" span={3}>{str(r.traceId)}</Descriptions.Item>
                </Descriptions>
              ),
            }}
            columns={[
              {
                title: t('common.time'),
                width: 200,
                render: (_, r) => (
                  <>
                    {account.fmt(r.createdAt)}
                    {bool(r.archived) ? <Tag style={{ marginLeft: 6 }}>{t('audit.archived')}</Tag> : null}
                  </>
                ),
              },
              { title: t('common.operator'), width: 150, ellipsis: true, render: (_, r) => `${str(r.operator)} ${str(r.operatorName)}` },
              { title: t('audit.role'), width: 110, ellipsis: true, render: (_, r) => roleLabel(r.role) },
              { title: t('audit.source'), width: 70, render: (_, r) => sourceLabel(r.source) },
              { title: t('audit.action'), width: 130, ellipsis: true, render: (_, r) => actionLabel(r.action) },
              {
                title: t('audit.result'),
                width: 70,
                render: (_, r) => (
                  <Tag color={str(r.result) === 'OK' ? 'success' : 'error'}>
                    {str(r.result) === 'OK' ? t('audit.ok') : t('audit.fail')}
                  </Tag>
                ),
              },
              { title: t('common.factory'), width: 120, ellipsis: true, render: (_, r) => nameOf(r.factoryCode) },
              { title: 'PI', dataIndex: 'piNo', width: 140, ellipsis: true },
              { title: t('common.billNo'), dataIndex: 'billNo', width: 130, ellipsis: true },
              {
                title: t('audit.range'),
                width: 240,
                render: (_, r) =>
                  r.startSn ? (
                    <span className="sn-mono">
                      {str(r.startSn)}
                      {r.endSn && str(r.endSn) !== str(r.startSn) ? ` ~ ${str(r.endSn)}` : ''}
                    </span>
                  ) : null,
              },
              { title: t('common.qty'), dataIndex: 'qty', align: 'right', width: 70 },
              {
                title: t('audit.status'),
                width: 160,
                render: (_, r) =>
                  r.beforeStatus || r.afterStatus ? `${statusText(r.beforeStatus) || '—'} → ${statusText(r.afterStatus) || '—'}` : null,
              },
              {
                title: t('common.batchNo'),
                width: 180,
                ellipsis: true,
                render: (_, r) => str(r.batchNo) || str(r.requestNo) || str(r.transferNo),
              },
              { title: t('common.reason'), dataIndex: 'reason', width: 140, ellipsis: true },
              { title: t('audit.error'), dataIndex: 'errorMsg', width: 160, ellipsis: true },
              { title: t('audit.detail'), dataIndex: 'detail', width: 200, ellipsis: true },
            ]}
          />
        </div>
        <Pager
          total={list.total}
          page={list.page}
          pageSize={list.pageSize}
          capped={list.capped}
          onPage={list.setPage}
          onSize={list.setPageSize}
        />
      </div>
    </div>
  );
}
