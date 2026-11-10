import { LoadingOutlined, ReloadOutlined, SearchOutlined, SettingOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import {
  Alert,
  Badge,
  Button,
  Descriptions,
  Empty,
  Form,
  Input,
  message,
  Modal,
  Progress,
  Radio,
  Select,
  Spin,
  Table,
  Tabs,
  Tag,
} from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { snApi, type Row } from '../../../api/sn';
import { FactorySelect } from '../components/FactorySelect';
import { PageHead } from '../components/PageHead';
import { Pager } from '../components/Pager';
import { useAccount, useAuth } from '../lib/auth';
import { confirm, prompt } from '../lib/dialogs';
import { leaveStateLabel, suspendLabel } from '../lib/labels';
import { showError, showInfo, showSuccess } from '../lib/notify';
import { usePage } from '../lib/page';
import { bool, num, rows, str } from '../lib/row';
import { firstSyncThisLogin } from '../lib/syncOnce';
import { trimmed, usePaged } from '../lib/usePaged';
import { K3ConfigDrawer } from './orders/K3ConfigDrawer';

const ALERT_TYPES = ['PI', 'ORG', 'CUSTOMER', 'QTY', 'MATERIAL', 'ATTACH', 'REVERSAL', 'DELETED'];
const ALERT_TAG: Record<string, string> = {
  PI: 'error', ORG: 'error', CUSTOMER: 'error', MATERIAL: 'error', REVERSAL: 'error', QTY: 'warning', ATTACH: 'warning', DELETED: 'default',
};
const WARN = '#b54708';
const ERR = '#b42318';
const dash = (v: unknown) => str(v) || '—';

/** 单据状态标签：金蝶单据状态（快照只含已审核）、暂停交付、离开快照、PI 冻结、未处理提醒。 */
function BillTags({ s, docStatus }: { s: Row | null | undefined; docStatus?: unknown }) {
  const { t } = useTranslation('sn');
  const x = s ?? {};
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
      {str(docStatus) ? <Tag color="success" variant="outlined">{t(`orders.docStatus.${str(docStatus)}`)}</Tag> : null}
      {str(x.suspend) ? <Tag color="error">{suspendLabel(x.suspend)}</Tag> : null}
      {str(x.leaveState) ? <Tag color="warning">{leaveStateLabel(x.leaveState)}</Tag> : null}
      {bool(x.piFrozen) ? <Tag color="error">{t('orders.piFrozen')}</Tag> : null}
      {num(x.openAlerts) ? <Tag color="warning" variant="outlined">{t('orders.alertsN', { n: num(x.openAlerts) })}</Tag> : null}
    </div>
  );
}

interface MapDlg {
  open: boolean;
  alert: Row | null;
  lines: Row[];
  target: number | undefined;
  note: string;
  saving: boolean;
}

/** 生产订单（F-03 / F-04）：金蝶已审核生产订单快照、同步与变更提醒。 */
export default function Orders() {
  const { t } = useTranslation('sn');
  const account = useAccount();
  const token = useAuth((s) => s.token);
  const { query, active, open } = usePage();
  const [tab, setTab] = useState<'bills' | 'alerts'>(() => (query.get('tab') === 'alerts' ? 'alerts' : 'bills'));
  const [alertsOpened, setAlertsOpened] = useState(tab === 'alerts');
  const [f, setF] = useState({ q: '', customer: '', factoryCode: '' });
  const [af, setAf] = useState({ status: 'OPEN', alertType: '', billNo: '' });
  const [syncBillNo, setSyncBillNo] = useState('');
  const [syncing, setSyncing] = useState<'' | 'one' | 'compare' | 'rebuild'>('');
  const [current, setCurrent] = useState<string>('');
  const [k3Open, setK3Open] = useState(false);
  const [mapDlg, setMapDlg] = useState<MapDlg>({ open: false, alert: null, lines: [], target: undefined, note: '', saving: false });

  const meta = useQuery({ queryKey: ['sn', 'orderMeta'], queryFn: () => snApi.orderMeta() });
  const k3 = useQuery({ queryKey: ['sn', 'k3Config'], queryFn: () => snApi.k3Config(), enabled: account.isAdmin });
  const bills = usePaged('orders', (q) => snApi.orders(q), trimmed(f));
  const alerts = usePaged('orderAlerts', (q) => snApi.orderAlerts(q), trimmed(af), 20, alertsOpened);
  const detail = useQuery({
    queryKey: ['sn', 'orderLines', current],
    queryFn: () => snApi.orderLines(current),
    enabled: !!current,
    retry: false,
  });
  const m = meta.data;
  const cmp = (m?.compare ?? null) as Row | null;
  const k3Warn = !!k3.data && (bool(k3.data.secretUnreadable) || (k3.data.lastTest as Row | undefined)?.ok === false);
  const d = detail.isError ? null : (detail.data ?? null);

  const refresh = useCallback(async () => {
    await Promise.all([meta.refetch(), Promise.resolve(bills.reload())]);
    if (current) void detail.refetch();
    if (alertsOpened) alerts.reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [current, alertsOpened]);
  const refreshRef = useRef(refresh);
  useEffect(() => {
    refreshRef.current = refresh;
  }, [refresh]);

  // ---------------- 同步进度：同步时每秒刷新，平时每 15 秒看一次；同步结束后刷新列表（只在本页签显示时轮询）
  const [prog, setProg] = useState<Row | null>(null);
  const progRef = useRef<Row | null>(null);
  const pollNow = useRef<() => void>(() => undefined);
  useEffect(() => {
    if (!active) return;
    let alive = true;
    let timer: number | undefined;
    const tick = async () => {
      window.clearTimeout(timer);
      const was = bool(progRef.current?.running);
      const next = await snApi.orderSyncProgress().catch(() => progRef.current);
      if (!alive) return;
      progRef.current = next;
      setProg(next);
      if (was && !bool(next?.running)) void refreshRef.current().catch(() => undefined);
      timer = window.setTimeout(() => void tick(), bool(next?.running) ? 1000 : 15000);
    };
    pollNow.current = () => void tick();
    void tick();
    return () => {
      alive = false;
      window.clearTimeout(timer);
      pollNow.current = () => undefined;
    };
  }, [active]);
  /** 点了同步按钮：请求发出后马上看一次进度 */
  const watchSoon = () => window.setTimeout(() => pollNow.current(), 400);

  // 进入页面先显示现有快照，同时触发一次进页同步（后台比对同步，立即返回）；本页面、本次登录只触发一次，稍后刷新列表。
  useEffect(() => {
    if (!firstSyncThisLogin('orders', token)) return;
    snApi
      .pageSyncOrders()
      .then((r) => {
        if (r && typeof r === 'object' && bool((r as Row).started)) {
          window.setTimeout(() => void refreshRef.current().catch(() => undefined), 4000);
        }
      })
      .catch(() => undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const fmtN = (n: unknown) => num(n).toLocaleString();
  const running = bool(prog?.running);
  const pMode = str(prog?.mode);
  const pStage = str(prog?.stage);
  const progPct = (() => {
    if (!running || pStage !== 'DETAILS' || !num(prog?.total)) return null;
    const pct = Math.round((num(prog?.done) / num(prog?.total)) * 100);
    return pMode === 'REBUILD' ? Math.min(pct, 99) : Math.min(pct, 100);
  })();
  const progText = (() => {
    if (!running) return '';
    if (!pMode) return t('orders.progress.other');
    let step: string;
    if (pMode === 'BILL') step = str(prog?.billNo);
    else if (!pStage) step = t('orders.progress.login');
    else if (pStage === 'HEADS') step = t('orders.progress.heads');
    else if (pStage === 'WRITE') step = t('orders.progress.write');
    else if (pMode === 'REBUILD')
      step = num(prog?.done) ? t('orders.progress.rows', { done: fmtN(prog?.done) }) : t('orders.progress.all');
    else
      step = num(prog?.total)
        ? t('orders.progress.bills', { done: fmtN(prog?.done), total: fmtN(prog?.total) })
        : t('orders.progress.noChange');
    return t('orders.progress.text', { mode: t(`orders.progress.mode.${pMode}`), step });
  })();
  const sec = num(prog?.elapsedSec);
  const elapsed = `${String(Math.floor(sec / 60)).padStart(2, '0')}:${String(sec % 60).padStart(2, '0')}`;

  async function syncOne(bill?: string) {
    const no = (bill ?? syncBillNo).trim();
    if (!no) return;
    setSyncing('one');
    try {
      const req = snApi.syncOrder(no);
      watchSoon();
      const r = await req;
      const msg = t(`orders.outcome.${str(r.outcome)}`, { bill: no, rows: str(r.rows), status: str(r.docStatus) || '—' });
      if (str(r.outcome) === 'REPLACED') showSuccess(msg);
      else void message.warning({ content: msg, duration: 6 });
      await refresh();
    } catch (e) {
      showError(e);
    } finally {
      setSyncing('');
    }
  }
  async function compareNow() {
    setSyncing('compare');
    try {
      const req = snApi.compareOrders();
      watchSoon();
      const r = await req;
      if (bool(r.skipped)) showInfo(t('orders.compareSkipped'));
      else showSuccess(t('orders.compareDone', { replaced: str(r.replaced), removed: str(r.removed), rows: str(r.rows) }));
      await refresh();
    } catch (e) {
      showError(e);
    } finally {
      setSyncing('');
    }
  }
  async function rebuild() {
    if (!(await confirm(t('orders.rebuildConfirm'), t('orders.rebuild')))) return;
    setSyncing('rebuild');
    try {
      const req = snApi.rebuildOrders();
      watchSoon();
      const r = await req;
      showSuccess(t('orders.rebuildDone', { bills: str(r.bills), rows: str(r.rows) }));
      await refresh();
    } catch (e) {
      showError(e);
    } finally {
      setSyncing('');
    }
  }

  async function handle(row: Row, action: string) {
    try {
      if (action === 'MAP') {
        const r = await snApi.orderLines(str(row.billNo));
        setMapDlg({
          open: true,
          alert: row,
          lines: rows(r.lines).filter((l) => num(l.seq) !== num(row.seq)),
          target: undefined,
          note: '',
          saving: false,
        });
        return;
      }
      let note = '';
      if (action === 'VOID') {
        const v = await prompt(t('orders.voidHint'), t('orders.alertActions.VOID'), {
          textarea: true,
          validate: (s) => (!!s.trim() && s.trim().length <= 500) || t('orders.reasonRequired'),
        });
        if (v === null) return;
        note = v.trim();
      } else if (!(await confirm(t(`orders.actionHint.${action}`, { bill: str(row.billNo) }), t(`orders.alertActions.${action}`)))) {
        return;
      }
      await snApi.handleOrderAlert(str(row.id), { action, note: note || undefined });
      showSuccess(t('common.done'));
      await refresh();
      alerts.reload();
    } catch (e) {
      showError(e);
    }
  }
  async function submitMap() {
    if (!mapDlg.alert || !mapDlg.target) return;
    setMapDlg({ ...mapDlg, saving: true });
    try {
      await snApi.handleOrderAlert(str(mapDlg.alert.id), {
        action: 'MAP',
        targetSeq: mapDlg.target,
        note: mapDlg.note.trim() || undefined,
      });
      showSuccess(t('common.done'));
      setMapDlg((x) => ({ ...x, open: false, saving: false }));
      await refresh();
      alerts.reload();
    } catch (e) {
      showError(e);
      setMapDlg((x) => ({ ...x, saving: false }));
    }
  }

  const switchTab = (v: string) => {
    const next = v === 'alerts' ? 'alerts' : 'bills';
    setTab(next);
    if (next === 'alerts') {
      setAlertsOpened(true);
      alerts.submit(trimmed(af));
    }
  };
  const searchBills = (next = f) => bills.submit(trimmed(next));
  const searchAlerts = (next = af) => alerts.submit(trimmed(next));
  const openAlerts = num(m?.openAlerts);

  return (
    <div className="page page--fill">
      <PageHead title={t('orders.title')} desc={t('orders.desc')}>
        {account.canAct ? (
          <>
            <Input
              allowClear
              placeholder={t('common.billNo')}
              style={{ width: 200 }}
              value={syncBillNo}
              onChange={(e) => setSyncBillNo(e.target.value)}
              onPressEnter={() => void syncOne()}
            />
            <Button loading={syncing === 'one'} disabled={!syncBillNo.trim()} onClick={() => void syncOne()}>
              {t('orders.syncOne')}
            </Button>
            <Button type="primary" icon={<ReloadOutlined />} loading={syncing === 'compare'} onClick={() => void compareNow()}>
              {t('orders.compareNow')}
            </Button>
            {account.isAdmin ? (
              <Button loading={syncing === 'rebuild'} onClick={() => void rebuild()}>
                {t('orders.rebuild')}
              </Button>
            ) : null}
            {account.isAdmin ? (
              <Badge dot={k3Warn} offset={[-6, 4]}>
                <Button icon={<SettingOutlined />} onClick={() => setK3Open(true)}>
                  {t('k3.button')}
                </Button>
              </Badge>
            ) : null}
          </>
        ) : null}
      </PageHead>

      {cmp && cmp.lastOk === false ? (
        <Alert
          type="error"
          showIcon
          title={t('orders.syncFailed', { time: account.fmt(cmp.lastRunAt), msg: str(cmp.lastMessage) })}
          action={
            account.isAdmin ? (
              <Button type="link" size="small" onClick={() => setK3Open(true)}>
                {t('k3.check')}
              </Button>
            ) : undefined
          }
        />
      ) : null}

      <div className="surface" style={{ display: 'flex', gap: 20, flexWrap: 'wrap', alignItems: 'center', fontSize: 13, padding: '12px 20px' }}>
        <span>
          {t('orders.syncedAt')}：<b>{account.fmt(m?.syncedAt)}</b>
        </span>
        <span>
          {t('orders.bills')}：<b className="num">{num(m?.bills)}</b>
        </span>
        <span>
          {t('orders.rows')}：<b className="num">{num(m?.rows)}</b>
        </span>
        <span className="muted">{t('orders.emptyCustomer', { n: num(m?.emptyCustomerBills) })}</span>
        <span>
          {t('orders.attachPending')}：
          <b className="num" style={num(m?.attachPending) ? { color: WARN } : undefined}>
            {num(m?.attachPending)}
          </b>
        </span>
        <span>
          {t('orders.compare')}：
          {cmp?.lastRunAt ? (
            <>
              <span>{account.fmt(cmp.lastRunAt)}</span>
              <Tag style={{ marginLeft: 6 }} color={bool(cmp.lastOk) ? 'success' : 'error'}>
                {bool(cmp.lastOk) ? t('orders.compareOk') : t('orders.compareFail')}
              </Tag>
            </>
          ) : (
            <span className="muted">—</span>
          )}
        </span>
        <Button type="link" danger={!!openAlerts} style={{ padding: 0, color: openAlerts ? undefined : '#909399' }} onClick={() => switchTab('alerts')}>
          {t('orders.openAlerts', { n: openAlerts })}
        </Button>
        {running ? (
          <span style={{ flexBasis: '100%', display: 'flex', alignItems: 'center', gap: 8, color: 'var(--sn-primary)' }}>
            <LoadingOutlined />
            <span>{progText}</span>
            {progPct !== null ? <Progress percent={progPct} showInfo={false} size={{ height: 6 }} style={{ width: 200, margin: 0 }} /> : null}
            {pMode ? <span className="muted num">{elapsed}</span> : null}
          </span>
        ) : null}
      </div>

      <Tabs
        activeKey={tab}
        onChange={switchTab}
        style={{ marginBottom: -16 }}
        items={[
          { key: 'bills', label: t('orders.tabBills') },
          {
            key: 'alerts',
            label: (
              <span>
                {t('orders.tabAlerts')}
                {openAlerts ? <Badge count={openAlerts} size="small" style={{ marginLeft: 6 }} /> : null}
              </span>
            ),
          },
        ]}
      />

      {/* 上：生产订单（每张单据一行）；下：所选单据的物料明细 */}
      <div className="sn-orders-stack" style={{ display: tab === 'bills' ? undefined : 'none' }}>
        <div className="surface fill">
          <div className="toolbar">
            <Input
              allowClear
              prefix={<SearchOutlined />}
              placeholder={t('orders.search')}
              style={{ width: 280 }}
              value={f.q}
              onChange={(e) => {
                const next = { ...f, q: e.target.value };
                setF(next);
                if (!e.target.value) searchBills(next);
              }}
              onPressEnter={() => searchBills()}
            />
            <Input
              allowClear
              placeholder={t('common.customer')}
              value={f.customer}
              onChange={(e) => {
                const next = { ...f, customer: e.target.value };
                setF(next);
                if (!e.target.value) searchBills(next);
              }}
              onPressEnter={() => searchBills()}
            />
            {account.multiScope ? (
              <FactorySelect
                value={f.factoryCode}
                scoped
                clearable
                onChange={(v) => {
                  const next = { ...f, factoryCode: v };
                  setF(next);
                  searchBills(next);
                }}
              />
            ) : null}
            <Button type="primary" onClick={() => searchBills()}>
              {t('common.search')}
            </Button>
          </div>
          <div className="fill-body">
            <Table<Row>
              size="small"
              loading={bills.loading}
              rowKey={(r) => str(r.billNo)}
              pagination={false}
              dataSource={bills.items}
              locale={{ emptyText: t('common.empty') }}
              scroll={{ x: 'max-content', y: 'calc(100% - 40px)' }}
              rowClassName={(r) => (str(r.billNo) === current ? 'ant-table-row-selected' : '')}
              onRow={(r) => ({ onClick: () => setCurrent(str(r.billNo)), style: { cursor: 'pointer' } })}
              columns={[
                { title: t('common.billNo'), dataIndex: 'billNo', width: 130, ellipsis: true },
                { title: 'PI', dataIndex: 'pi', width: 130, ellipsis: true },
                { title: t('orders.state'), width: 140, render: (_, r) => <BillTags s={r} docStatus={r.docStatus} /> },
                { title: t('orders.totalQty'), width: 80, align: 'right', render: (_, r) => <span className="num">{str(r.totalQty)}</span> },
                {
                  title: t('common.factory'),
                  width: 120,
                  ellipsis: true,
                  render: (_, r) => (
                    <>
                      {str(r.orgName)}
                      <span className="muted">（{str(r.orgCode)}）</span>
                    </>
                  ),
                },
                { title: t('orders.dept'), width: 110, ellipsis: true, render: (_, r) => dash(r.deptName) },
                { title: 'PO', dataIndex: 'po', width: 110, ellipsis: true },
                { title: t('common.customer'), dataIndex: 'customerCode', width: 90, ellipsis: true },
                { title: t('orders.modifyDate'), width: 160, render: (_, r) => <span className="num">{dash(r.modifyTime)}</span> },
              ]}
            />
          </div>
          <Pager total={bills.total} page={bills.page} pageSize={bills.pageSize} onPage={bills.setPage} onSize={bills.setPageSize} />
        </div>
        <div className="surface fill">
          <div className="surface__title">
            <span>{d ? t('orders.linesOf', { bill: str(d.billNo) }) : t('orders.lines')}</span>
            {d && account.canAct ? (
              <Button type="primary" size="small" onClick={() => open(`/sn/generate?billNo=${encodeURIComponent(str(d.billNo))}`)}>
                {t('orders.toGenerate')}
              </Button>
            ) : null}
          </div>
          {current && (d || detail.isFetching) ? (
            <Spin spinning={detail.isFetching} wrapperClassName="sn-spin-fill">
              {d ? (
                <>
                  <Descriptions column={4} size="small" bordered style={{ marginBottom: 12 }}>
                    <Descriptions.Item label="PI">{str(d.pi)}</Descriptions.Item>
                    <Descriptions.Item label="PO">{dash(d.po)}</Descriptions.Item>
                    <Descriptions.Item label={t('common.customer')}>{dash(d.customerCode)}</Descriptions.Item>
                    <Descriptions.Item label={t('orders.modifyDate')}>{dash(d.modifyTime)}</Descriptions.Item>
                    <Descriptions.Item label={t('common.factory')}>
                      {str(d.orgName)}（{str(d.orgCode)}）
                    </Descriptions.Item>
                    <Descriptions.Item label={t('orders.dept')}>{dash(d.deptName)}</Descriptions.Item>
                    <Descriptions.Item label={t('orders.state')} span={2}>
                      <BillTags s={d.state as Row} docStatus={d.docStatus} />
                    </Descriptions.Item>
                  </Descriptions>
                  <div className="fill-body">
                    <Table<Row>
                      size="small"
                      rowKey={(r) => str(r.seq)}
                      pagination={false}
                      dataSource={rows(d.lines)}
                      locale={{ emptyText: t('common.empty') }}
                      scroll={{ x: 'max-content', y: 'calc(100% - 40px)' }}
                      columns={[
                        {
                          title: t('common.attachNo'),
                          width: 120,
                          render: (_, r) => (
                            <>
                              <div>
                                {str(r.attachSource) === 'EMPTY' ? (
                                  <span style={{ color: ERR }}>{t('orders.attachEmpty')}</span>
                                ) : (
                                  <span>{str(r.attachNo)}</span>
                                )}
                              </div>
                              {bool(r.attachK3Conflict) ? (
                                <div className="muted" style={{ color: WARN }}>
                                  {t('orders.attachConflict', { k3: str(r.attachK3), manual: str(r.attachManual) })}
                                </div>
                              ) : null}
                            </>
                          ),
                        },
                        { title: t('common.material'), dataIndex: 'materialCode', width: 110, ellipsis: true },
                        { title: t('common.lineNo'), dataIndex: 'seq', width: 72 },
                        { title: t('orders.productName'), dataIndex: 'materialName', width: 150, ellipsis: true },
                        { title: t('orders.materialSpec'), width: 160, ellipsis: true, render: (_, r) => dash(r.materialSpec) },
                        { title: t('orders.specDesc'), width: 140, ellipsis: true, render: (_, r) => dash(r.specDesc) },
                        {
                          title: t('common.length'),
                          width: 90,
                          ellipsis: true,
                          render: (_, r) =>
                            str(r.lengthSource) === 'EMPTY' ? <span style={{ color: ERR }}>{t('orders.lengthEmpty')}</span> : str(r.length),
                        },
                        {
                          title: t('common.unit'),
                          width: 70,
                          ellipsis: true,
                          render: (_, r) =>
                            str(r.unitSource) === 'EMPTY' ? <span style={{ color: ERR }}>{t('orders.unitEmpty')}</span> : str(r.unit),
                        },
                        { title: t('common.qty'), width: 70, align: 'right', render: (_, r) => <span className="num">{str(r.qty)}</span> },
                        { title: t('orders.customerPn'), width: 120, ellipsis: true, render: (_, r) => dash(r.customerPn) },
                        { title: t('orders.customerDesc'), width: 160, ellipsis: true, render: (_, r) => dash(r.customerDesc) },
                        { title: t('orders.dept'), width: 110, ellipsis: true, render: (_, r) => dash(r.deptName) },
                      ]}
                    />
                  </div>
                </>
              ) : (
                <div style={{ height: 120 }} />
              )}
            </Spin>
          ) : (
            <Empty description={t('orders.pick')} styles={{ image: { height: 64 } }} />
          )}
        </div>
      </div>

      <div className="surface fill" style={{ display: tab === 'alerts' ? undefined : 'none' }}>
        <div className="toolbar">
          <Radio.Group
            optionType="button"
            value={af.status}
            onChange={(e) => {
              const next = { ...af, status: String(e.target.value) };
              setAf(next);
              searchAlerts(next);
            }}
            options={[
              { value: 'OPEN', label: t('orders.alertOpen') },
              { value: 'CLOSED', label: t('orders.alertClosed') },
              { value: 'ALL', label: t('common.all') },
            ]}
          />
          <Select
            allowClear
            placeholder={t('orders.alertType')}
            value={af.alertType || undefined}
            options={ALERT_TYPES.map((x) => ({ value: x, label: t(`orders.alertTypes.${x}`) }))}
            onChange={(v?: string) => {
              const next = { ...af, alertType: v ?? '' };
              setAf(next);
              searchAlerts(next);
            }}
          />
          <Input
            allowClear
            placeholder={t('common.billNo')}
            value={af.billNo}
            onChange={(e) => {
              const next = { ...af, billNo: e.target.value };
              setAf(next);
              if (!e.target.value) searchAlerts(next);
            }}
            onPressEnter={() => searchAlerts()}
          />
          <Button type="primary" onClick={() => searchAlerts()}>
            {t('common.search')}
          </Button>
        </div>
        <div className="fill-body">
          <Table<Row>
            size="small"
            loading={alerts.loading}
            rowKey={(r) => str(r.id)}
            pagination={false}
            dataSource={alerts.items}
            locale={{ emptyText: t('common.empty') }}
            scroll={{ x: 'max-content', y: 'calc(100% - 40px)' }}
            columns={[
              { title: t('common.time'), width: 150, render: (_, r) => account.fmt(r.createdAt) },
              { title: t('common.billNo'), dataIndex: 'billNo', width: 120, ellipsis: true },
              {
                title: t('orders.alertType'),
                width: 110,
                render: (_, r) => (
                  <>
                    <Tag color={ALERT_TAG[str(r.alertType)] ?? 'default'}>{t(`orders.alertTypes.${str(r.alertType)}`)}</Tag>
                    {num(r.seq) ? <span className="muted">#{str(r.seq)}</span> : null}
                  </>
                ),
              },
              {
                title: t('orders.change'),
                width: 180,
                ellipsis: true,
                render: (_, r) => (
                  <>
                    <span>{dash(r.beforeValue)}</span>
                    <span style={{ margin: '0 6px', color: 'var(--sn-muted)' }}>→</span>
                    <span>{dash(r.afterValue)}</span>
                  </>
                ),
              },
              { title: 'PI', dataIndex: 'pi', width: 120, ellipsis: true },
              { title: t('common.factory'), width: 110, ellipsis: true, render: (_, r) => str(r.orgName) || str(r.orgCode) || '—' },
              { title: t('orders.state'), width: 110, render: (_, r) => <BillTags s={r} /> },
              {
                title: t('orders.counts'),
                width: 150,
                render: (_, r) => (
                  <>
                    <div className="muted">{t('orders.countSuspended', { n: str(r.suspendedQty) })}</div>
                    <div className="muted">{t('orders.countDelivered', { n: str(r.deliveredQty) })}</div>
                    <div className="muted">{t('orders.countInOrg', { n: str(r.inOrgQty) })}</div>
                  </>
                ),
              },
              {
                title: t('common.status'),
                width: 130,
                render: (_, r) =>
                  str(r.status) === 'OPEN' ? (
                    <Tag color="warning">{t('orders.alertOpen')}</Tag>
                  ) : (
                    <>
                      <Tag color="success">{t('orders.alertClosed')}</Tag>
                      <div className="muted">
                        {str(r.closeAction) ? t(`orders.alertActions.${str(r.closeAction)}`) : ''} {str(r.closedBy)}
                      </div>
                      {str(r.note) ? <div className="muted">{str(r.note)}</div> : null}
                    </>
                  ),
              },
              ...(account.canAct
                ? [
                    {
                      title: t('common.actions'),
                      width: 170,
                      fixed: 'right' as const,
                      render: (_: unknown, r: Row) => (
                        <>
                          {(Array.isArray(r.actions) ? (r.actions as unknown[]) : []).map((a) => (
                            <Button key={str(a)} type="link" size="small" onClick={() => void handle(r, str(a))}>
                              {t(`orders.alertActions.${str(a)}`)}
                            </Button>
                          ))}
                          {str(r.status) === 'OPEN' && str(r.alertType) === 'REVERSAL' ? (
                            <Button type="link" size="small" style={{ color: 'inherit' }} onClick={() => void syncOne(str(r.billNo))}>
                              {t('orders.syncNow')}
                            </Button>
                          ) : null}
                        </>
                      ),
                    },
                  ]
                : []),
            ]}
          />
        </div>
        <Pager total={alerts.total} page={alerts.page} pageSize={alerts.pageSize} onPage={alerts.setPage} onSize={alerts.setPageSize} />
      </div>

      <Modal
        open={mapDlg.open}
        title={t('orders.mapTitle')}
        width={520}
        onCancel={() => setMapDlg({ ...mapDlg, open: false })}
        footer={[
          <Button key="c" onClick={() => setMapDlg({ ...mapDlg, open: false })}>
            {t('common.cancel')}
          </Button>,
          <Button key="o" type="primary" disabled={!mapDlg.target} loading={mapDlg.saving} onClick={() => void submitMap()}>
            {t('common.confirm')}
          </Button>,
        ]}
      >
        <p className="muted">
          {t('orders.mapHint', { bill: str(mapDlg.alert?.billNo), seq: str(mapDlg.alert?.seq), material: str(mapDlg.alert?.beforeValue) })}
        </p>
        <Form labelCol={{ flex: '110px' }}>
          <Form.Item label={t('orders.mapTarget')} required>
            <Select
              value={mapDlg.target}
              onChange={(v: number) => setMapDlg({ ...mapDlg, target: v })}
              options={mapDlg.lines.map((l) => ({
                value: num(l.seq),
                label: `#${str(l.seq)} ${str(l.materialCode)} ${str(l.materialName)}（${t('common.qty')} ${str(l.qty)}）`,
              }))}
            />
          </Form.Item>
          <Form.Item label={t('common.note')}>
            <Input.TextArea rows={2} maxLength={500} showCount value={mapDlg.note} onChange={(e) => setMapDlg({ ...mapDlg, note: e.target.value })} />
          </Form.Item>
        </Form>
      </Modal>

      {account.isAdmin ? (
        <K3ConfigDrawer
          open={k3Open}
          onClose={() => {
            setK3Open(false);
            void k3.refetch();
          }}
          onSaved={() => void k3.refetch()}
        />
      ) : null}
    </div>
  );
}
