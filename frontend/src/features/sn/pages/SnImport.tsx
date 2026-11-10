import { DownloadOutlined, InboxOutlined, UploadOutlined } from '@ant-design/icons';
import { Button, Input, Modal, Table, Tag, Tooltip, Upload } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { snApi, snDownload, type Row } from '../../../api/sn';
import { PageHead } from '../components/PageHead';
import { Pager } from '../components/Pager';
import { StatusTag } from '../components/StatusTag';
import { useAccount } from '../lib/auth';
import { confirm } from '../lib/dialogs';
import { useFactories } from '../lib/factories';
import { run, showError, showSuccess } from '../lib/notify';
import { usePage } from '../lib/page';
import { bool, num, obj, rows, str, strs } from '../lib/row';
import { usePaged } from '../lib/usePaged';

/** 列字段名（seqIn）→ 文案键（seq_in）。 */
const snake = (s: string) => s.replace(/[A-Z]/g, (c) => `_${c.toLowerCase()}`);

/** SN 导入：按导入模板导入本系统之外已有的 SN；先检查（逐行列出不能导入的原因），没有问题行才能确定导入。 */
export default function SnImport() {
  const { t } = useTranslation('sn');
  const account = useAccount();
  const { nameOf } = useFactories();
  const { open } = usePage();
  const blank = { pi: '', billNo: '', sn: '', importNo: '' };
  const [lq, setLq] = useState(blank);
  const list = usePaged('imports', (q) => snApi.snImportItems(q), blank);
  const search = (x = lq) =>
    list.submit({ pi: x.pi.trim(), billNo: x.billNo.trim(), sn: x.sn.trim(), importNo: x.importNo.trim() });
  const [deleting, setDeleting] = useState('');
  const [downloading, setDownloading] = useState(false);
  const [dlg, setDlg] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [checking, setChecking] = useState(false);
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<Row | null>(null);

  const issueText = (x: Row) => {
    const p = { ...obj(x.params) };
    if (typeof p.column === 'string') p.column = t(`snImport.col.${snake(p.column)}`);
    return t(`snImport.issue.${str(x.code)}`, p);
  };
  const byImportNo = (no: string) => {
    const x = { ...blank, importNo: no };
    setLq(x);
    search(x);
  };

  async function deleteImport(no: string) {
    setDeleting(no);
    try {
      const s = await snApi.snImportSummary(no);
      if (!bool(s.deletable)) {
        Modal.warning({ content: t('snImport.cannotDelete', { no, n: str(s.allocated) }) });
        return;
      }
      if (!(await confirm(t('snImport.deleteConfirm', { no, qty: str(s.qty), bills: strs(s.bills).length })))) return;
      const r = await snApi.snImportDelete(no);
      showSuccess(t('snImport.deletedDone', { no, qty: str(r.qty) }));
      list.reload();
    } catch (e) {
      showError(e);
    } finally {
      setDeleting('');
    }
  }

  async function pick(f: File) {
    setFile(f);
    setResult(null);
    setChecking(true);
    try {
      setResult(await snApi.snImportCheck(f));
    } catch (e) {
      setFile(null);
      showError(e);
    } finally {
      setChecking(false);
    }
  }

  async function confirmImport() {
    if (!file) return;
    setRunning(true);
    try {
      const r = await snApi.snImportRun(file, file.name);
      showSuccess(t('snImport.done', { qty: str(r.qty), no: str(r.importNo) }));
      setDlg(false);
      byImportNo(str(r.importNo));
    } catch (e) {
      showError(e);
      // 确定时重新检查发现问题（别人刚生成或导入了同样的号）：重新检查一遍，列出原因
      setResult(await snApi.snImportCheck(file).catch(() => result));
    } finally {
      setRunning(false);
    }
  }

  const text = (k: keyof typeof blank, ph: string) => (
    <Input
      allowClear
      style={{ width: 170 }}
      placeholder={ph}
      value={lq[k]}
      onChange={(e) => {
        const x = { ...lq, [k]: e.target.value };
        setLq(x);
        if (!e.target.value) search(x);
      }}
      onPressEnter={() => search()}
    />
  );
  const issues = rows(result?.issues);

  return (
    <div className="page">
      <PageHead title={t('snImport.title')} desc={t('snImport.desc')}>
        <Button
          icon={<DownloadOutlined />}
          loading={downloading}
          onClick={() => {
            setDownloading(true);
            void run(() => snDownload.snImportTemplate()).finally(() => setDownloading(false));
          }}
        >
          {t('snImport.template')}
        </Button>
        <Button
          type="primary"
          icon={<UploadOutlined />}
          onClick={() => {
            setFile(null);
            setResult(null);
            setDlg(true);
          }}
        >
          {t('snImport.import')}
        </Button>
      </PageHead>
      <div className="surface">
        <div className="toolbar">
          {text('pi', 'PI')}
          {text('billNo', t('common.billNo'))}
          {text('sn', t('snImport.snPrefix'))}
          {text('importNo', t('snImport.importNo'))}
          <Button type="primary" onClick={() => search()}>{t('common.search')}</Button>
        </div>
        <Table<Row>
          size="small"
          loading={list.loading}
          pagination={false}
          scroll={{ x: 'max-content' }}
          rowKey={(r) => str(r.id)}
          dataSource={list.items}
          locale={{ emptyText: t('snImport.empty') }}
          columns={[
            { title: 'PI', dataIndex: 'piNo', ellipsis: true },
            { title: t('common.billNo'), dataIndex: 'billNo', ellipsis: true },
            { title: t('common.sn'), render: (_, r) => <span className="sn-mono">{str(r.sn)}</span> },
            { title: t('common.attachNo'), dataIndex: 'attachNo', ellipsis: true },
            { title: t('common.material'), dataIndex: 'materialCode', ellipsis: true },
            {
              title: t('snImport.seq'),
              width: 72,
              align: 'right',
              render: (_, r) =>
                str(r.curBillSeq) !== str(r.billSeq) ? (
                  <Tooltip title={`${str(r.billSeq)} → ${str(r.curBillSeq)}`}>
                    <span className="num">{str(r.curBillSeq)}</span>
                  </Tooltip>
                ) : (
                  <span className="num">{str(r.billSeq)}</span>
                ),
            },
            { title: t('common.length'), dataIndex: 'length', ellipsis: true },
            { title: t('common.customer'), dataIndex: 'customerCode', ellipsis: true },
            { title: t('snImport.serial'), render: (_, r) => <span className="sn-mono">{str(r.serialText)}</span> },
            {
              title: t('snImport.status'),
              width: 140,
              render: (_, r) =>
                r.deletedAt ? (
                  <Tooltip title={`${str(r.deletedBy)} ${account.fmt(r.deletedAt)}`}>
                    <Tag>{t('snImport.deleted')}</Tag>
                  </Tooltip>
                ) : r.status ? (
                  <>
                    <StatusTag status={r.status} />
                    {str(r.status) === 'PENDING_ALLOC' ? (
                      <a
                        style={{ marginLeft: 8 }}
                        onClick={() => open(`/sn/allocate?pi=${encodeURIComponent(str(r.piNo))}&bill=${encodeURIComponent(str(r.billNo))}`)}
                      >
                        {t('snImport.toAllocate')}
                      </a>
                    ) : null}
                    {r.curBill && (str(r.curBill) !== str(r.billNo) || str(r.curPi) !== str(r.piNo)) ? (
                      <div className="muted">{t('snImport.current', { pi: str(r.curPi), bill: str(r.curBill) })}</div>
                    ) : null}
                  </>
                ) : (
                  <span className="muted">—</span>
                ),
            },
            {
              title: t('common.factory'),
              ellipsis: true,
              render: (_, r) => (
                <>
                  {nameOf(r.factoryCode) || '—'}
                  {nameOf(r.factoryCode) !== str(r.factoryCode) ? <span className="muted">（{str(r.factoryCode)}）</span> : null}
                </>
              ),
            },
            {
              title: t('snImport.importNo'),
              render: (_, r) => (
                <Button type="link" className="sn-mono" style={{ padding: 0 }} onClick={() => byImportNo(str(r.importNo))}>
                  {str(r.importNo)}
                </Button>
              ),
            },
            { title: t('snImport.fileRow'), ellipsis: true, render: (_, r) => `${str(r.fileName) || '—'} / ${str(r.rowNo)}` },
            {
              title: t('snImport.importedBy'),
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
              width: 90,
              align: 'right',
              render: (_, r) =>
                !r.deletedAt ? (
                  <Button type="link" danger style={{ padding: 0 }} loading={deleting === str(r.importNo)} onClick={() => void deleteImport(str(r.importNo))}>
                    {t('snImport.deleteAll')}
                  </Button>
                ) : null,
            },
          ]}
        />
        <Pager total={list.total} page={list.page} pageSize={list.pageSize} onPage={list.setPage} onSize={list.setPageSize} />
      </div>

      <Modal
        open={dlg}
        width={760}
        title={t('snImport.dialogTitle')}
        maskClosable={false}
        destroyOnHidden
        onCancel={() => setDlg(false)}
        footer={[
          <Button key="c" onClick={() => setDlg(false)}>{t('common.cancel')}</Button>,
          <Button key="o" type="primary" loading={running} disabled={!file || !bool(result?.importable)} onClick={() => void confirmImport()}>
            {t('snImport.confirm')}
          </Button>,
        ]}
      >
        <Upload.Dragger
          accept=".xlsx"
          showUploadList={false}
          beforeUpload={(f) => {
            void pick(f);
            return false;
          }}
        >
          <p className="ant-upload-drag-icon">
            <InboxOutlined />
          </p>
          <div>{file ? file.name : t('snImport.drop')}</div>
        </Upload.Dragger>
        {checking ? (
          <div className="muted" style={{ margin: '12px 0 8px' }}>{t('snImport.checking')}</div>
        ) : result ? (
          <>
            <div style={{ margin: '12px 0 8px' }}>
              <Tag color={bool(result.importable) ? 'success' : 'error'}>
                {t('snImport.summary', { total: str(result.total), ok: str(result.ok), bad: str(result.bad) })}
              </Tag>
              <span className="muted" style={{ marginLeft: 8 }}>
                {t('snImport.scope', { pis: str(result.pis), bills: str(result.bills) })}
              </span>
            </div>
            {issues.length ? (
              <>
                <Table<Row>
                  size="small"
                  pagination={false}
                  scroll={{ y: 320 }}
                  rowKey={(r) => str(r.row)}
                  dataSource={issues}
                  columns={[
                    { title: t('snImport.rowNo'), dataIndex: 'row', width: 70, align: 'right' },
                    { title: 'PI', dataIndex: 'pi', ellipsis: true },
                    { title: t('common.billNo'), dataIndex: 'billNo', ellipsis: true },
                    { title: t('common.sn'), ellipsis: true, render: (_, r) => <span className="sn-mono">{str(r.sn)}</span> },
                    {
                      title: t('snImport.problem'),
                      render: (_, r) =>
                        rows(r.issues).map((x, i) => (
                          <div key={i} style={{ color: '#b42318' }}>{issueText(x)}</div>
                        )),
                    },
                  ]}
                />
                {num(result.bad) > issues.length ? (
                  <div className="muted" style={{ marginTop: 6 }}>{t('snImport.listed', { n: issues.length })}</div>
                ) : null}
              </>
            ) : null}
          </>
        ) : null}
      </Modal>
    </div>
  );
}
