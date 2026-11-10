import { ArrowRightOutlined, RightOutlined, SearchOutlined } from '@ant-design/icons';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Input, Progress, Radio, Spin, Table, Tabs, Tag, Tooltip } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { snApi, type Row } from '../../../api/sn';
import { DeliveryBatchDrawer } from '../components/DeliveryBatchDrawer';
import { PageHead } from '../components/PageHead';
import { Pager } from '../components/Pager';
import { useAccount } from '../lib/auth';
import { colWidth, textWidth } from '../lib/colWidth';
import { confirm } from '../lib/dialogs';
import { useFactories } from '../lib/factories';
import { lineSort, type LineRef } from '../lib/genScope';
import {
  BATCH_RESULT_COLOR,
  BILL_FOLD_OVER,
  batchResultLabel,
  batchSourceLabel,
  pushStateLabel,
  taskStateLabel,
  type TagColor,
} from '../lib/labels';
import { showError, showSuccess, showWarning } from '../lib/notify';
import { usePage } from '../lib/page';
import { bool, num, obj, rows, str } from '../lib/row';
import { useFitRows } from '../lib/useFitRows';
import { AllocatedPane } from './allocate/AllocatedPane';

type Tab = 'TO_ALLOCATE' | 'ALLOCATED';
/** 单据状态：待分配 / 待处理（已分配但推送未完成，含推送失败）/ 已分配。 */
type BillState = 'PENDING' | 'PROCESSING' | 'ALLOCATED';
/** 物料行、单据的推送进度：待分配 / 待推送 / 推送失败 / 已完成。 */
type ProgState = 'PENDING' | 'TO_PUSH' | 'FAILED' | 'DONE';
interface Counts {
  qty: number;
  pendingAlloc: number;
  toPush: number;
  failed: number;
}
interface BillGroup extends Counts {
  billNo: string;
  state: BillState;
  lines: Row[];
}

const TABS: Tab[] = ['TO_ALLOCATE', 'ALLOCATED'];
const STATE_TAG: Record<string, TagColor> = { PENDING: 'success', PROCESSING: 'warning', ALLOCATED: 'default' };
const PROG_STATUS: Record<ProgState, 'normal' | 'success' | 'exception'> = {
  PENDING: 'normal',
  TO_PUSH: 'normal',
  FAILED: 'exception',
  DONE: 'success',
};
const TASK_TAG: Record<string, TagColor> = { QUEUED: 'processing', RUNNING: 'processing', WAITING: 'warning', STOPPED: 'error', DONE: 'success' };
const ACTIVE_TASK = ['QUEUED', 'RUNNING', 'WAITING'];
const ERR = '#b42318';
const WARN = '#93370d';

const n = (v: unknown) => (v === null || v === undefined ? '—' : Number(v).toLocaleString());
const keyOf = (l: Row) => `${str(l.billNo)}/${str(l.seq)}`;
const counts = (x: Row): Counts => ({ qty: num(x.qty), pendingAlloc: num(x.pendingAlloc), toPush: num(x.toPush), failed: num(x.failed) });

/** 推送进度：已完成 = 已推送或已分配（未对接 MES）的号；有失败批次时标红。 */
function progressOf(x: Counts): { pct: number; state: ProgState } {
  const done = x.qty - x.pendingAlloc - x.toPush;
  const pct = x.qty ? Math.floor((done * 100) / x.qty) : 0;
  const state: ProgState = x.failed ? 'FAILED' : x.toPush ? 'TO_PUSH' : x.pendingAlloc ? 'PENDING' : 'DONE';
  return { pct, state };
}
/** 物料行的十进制流水号：起 ~ 止，只有一枚时只写一个；没有流水（导入的号）为「—」。 */
function decRange(r: Row): string {
  if (r.startSeq === null || r.startSeq === undefined) return '—';
  return str(r.startSeq) === str(r.endSeq) ? str(r.startSeq) : `${str(r.startSeq)} ~ ${str(r.endSeq)}`;
}
const snRange = (r: Row) => `${str(r.startSn)} ~ ${str(r.endSn)}`;

/** SN 分配（F-11 ~ F-14）：左侧单据列表，右侧所选单据所在 PI 的分配与推送 / 已分配的号。 */
export default function Allocate() {
  const { t } = useTranslation('sn');
  const account = useAccount();
  const { nameOf } = useFactories();
  const { query, open } = usePage();
  const queryClient = useQueryClient();
  const root = useRef<HTMLDivElement>(null);
  const [busy, setBusy] = useState('');

  // ================================================================ 单据列表
  const [tab, setTab] = useState<Tab>(() => (query.get('tab') === 'ALLOCATED' ? 'ALLOCATED' : 'TO_ALLOCATE'));
  const [q, setQ] = useState('');
  const [page, setPage] = useState(1);
  const [listLoading, setListLoading] = useState(false);
  const [list, setList] = useState<Row>({ items: [], total: 0, summary: { pendingAlloc: 0, unbatched: 0 } });
  const listBox = useRef<HTMLDivElement>(null);
  const listRows = useFitRows(listBox);
  const [sel, setSel] = useState(() => ({ pi: query.get('pi') ?? '', bill: query.get('bill') ?? '' }));
  const selRef = useRef(sel);
  const pick = (r: Row) => {
    const next = { pi: str(r.piNo), bill: str(r.billNo) };
    selRef.current = next;
    setSel(next);
  };
  const isCurrent = (r: Row) => str(r.piNo) === sel.pi && str(r.billNo) === sel.bill;
  const listItems = rows(list.items);

  /** 分页取单据（处理时间近的在前）；silent = 刷新当前页，不改右侧所选。 */
  const loadList = useCallback(
    async (opts: { silent?: boolean; p?: number; tab: Tab; q: string }) => {
      if (!opts.silent) setListLoading(true);
      let got: Row;
      try {
        got = await snApi.allocBills({ tab: opts.tab, q: opts.q.trim(), page: opts.silent ? page : (opts.p ?? 1), pageSize: listRows });
      } catch (e) {
        showError(e);
        return;
      } finally {
        setListLoading(false);
      }
      setList(got);
      setPage(num(got.page) || 1);
      if (opts.silent) return;
      // 右侧跟随左侧列表：所选单据在本页时保持；不在时待分配页签改选第一张（只带 PI 进来时先找该 PI 的单据），
      // 已分配页签等左侧点单据；列表为空时右侧也为空
      const items = rows(got.items);
      const cur = selRef.current;
      if (items.some((r) => str(r.piNo) === cur.pi && str(r.billNo) === cur.bill)) return;
      const hit =
        opts.tab === 'TO_ALLOCATE' ? ((!cur.bill && items.find((r) => str(r.piNo) === cur.pi)) || items[0]) : undefined;
      if (hit) pick(hit);
      else if (cur.pi && opts.tab === 'ALLOCATED') {
        selRef.current = { pi: '', bill: '' };
        setSel(selRef.current);
      }
    },
    [listRows, page],
  );
  const loadListRef = useRef(loadList);
  useEffect(() => {
    loadListRef.current = loadList;
  }, [loadList]);
  const qRef = useRef(q);
  useEffect(() => {
    qRef.current = q;
  }, [q]);
  // 单据列表在量出每页能放几行后加载；切换页签重新加载
  useEffect(() => {
    void loadListRef.current({ tab, q: qRef.current });
  }, [listRows, tab]);

  // ================================================================ 详情（所选单据所在的 PI）
  const [ctx, setCtx] = useState<Row | null>(null);
  const [ctxLoading, setCtxLoading] = useState(false);
  /** 非静默加载一次详情加 1：单据表格随之重建，勾选一起清空。 */
  const [ctxVer, setCtxVer] = useState(0);
  /** 待分配页签下本 PI 列出过的单据：推送完成变为已分配后仍留在原处，换 PI 时清空。 */
  const [shownBills, setShownBills] = useState<Set<string>>(new Set());
  const [folded, setFolded] = useState<Set<string>>(new Set());
  const [selByBill, setSelByBill] = useState<Record<string, string[]>>({});
  const [alloc, setAlloc] = useState<{ loading: boolean; data: Row | null }>({ loading: false, data: null });
  const [batchNo, setBatchNo] = useState('');
  const [pollTick, setPollTick] = useState(0);
  const tabRef = useRef(tab);
  useEffect(() => {
    tabRef.current = tab;
  }, [tab]);
  const ctxPi = useRef('');
  const ctxSeq = useRef(0);
  const ctxTimer = useRef<number | undefined>(undefined);
  const allocTimer = useRef<number | undefined>(undefined);
  const [batchPage, setBatchPage] = useState(1);
  const [batchTick, setBatchTick] = useState(0);
  const batchPi = tab === 'TO_ALLOCATE' ? str(ctx?.piNo) : '';
  const batchQuery = useQuery({
    queryKey: ['sn', 'allocBatches', batchPi, batchPage, batchTick],
    queryFn: () => snApi.deliveryBatches({ pi: batchPi, batchType: 'DELIVERY', asc: true, page: batchPage, pageSize: 20 }),
    enabled: !!batchPi,
    placeholderData: keepPreviousData,
  });
  const batches = {
    items: batchQuery.data?.items ?? [],
    total: Number(batchQuery.data?.total ?? 0),
    loading: batchQuery.isFetching,
    page: batchPage,
    pageSize: 20,
  };

  const bills = useMemo<BillGroup[]>(() => {
    const m = new Map<string, BillGroup>();
    for (const l of rows(ctx?.lines)) {
      const no = str(l.billNo);
      if (!shownBills.has(no)) continue;
      let b = m.get(no);
      if (!b) {
        b = { billNo: no, state: str(l.billState) as BillState, qty: 0, pendingAlloc: 0, toPush: 0, failed: 0, lines: [] };
        m.set(no, b);
      }
      const c = counts(l);
      b.qty += c.qty;
      b.pendingAlloc += c.pendingAlloc;
      b.toPush += c.toPush;
      b.failed += c.failed;
      b.lines.push(l);
    }
    for (const b of m.values()) b.lines = lineSort(b.lines as (Row & { seq: unknown })[]);
    return [...m.values()];
  }, [ctx, shownBills]);

  /** 定位所选单据：展开并滚到可见处；others = 其余单据是否收起。 */
  const focusBill = useCallback((bill: string, allBills: string[] | null, others?: boolean) => {
    setFolded((cur) => {
      const s = others === undefined ? new Set(cur) : new Set(others && allBills ? allBills : []);
      s.delete(bill);
      return s;
    });
    window.setTimeout(() => {
      root.current?.querySelector(`.sn-bill[data-bill="${CSS.escape(bill)}"]`)?.scrollIntoView({ block: 'nearest' });
    }, 0);
  }, []);

  const loadAlloc = useCallback(async (piNo: string, picked: LineRef[]) => {
    setAlloc((a) => ({ ...a, loading: true }));
    try {
      const data = await snApi.allocPreview({ piNo, ...(picked.length ? { lines: picked } : {}) });
      setAlloc({ loading: false, data });
    } catch {
      setAlloc({ loading: false, data: null });
    }
  }, []);

  const loadCtx = useCallback(
    async (pi: string, curTab: Tab, silent = false) => {
      window.clearTimeout(ctxTimer.current);
      const my = ++ctxSeq.current;
      // 已分配页签右侧只看 SN 明细，不取分配详情
      if (!pi || curTab !== 'TO_ALLOCATE') {
        ctxPi.current = '';
        setCtx(null);
        return;
      }
      if (!silent) setCtxLoading(true);
      let c: Row;
      try {
        c = await snApi.allocContext(pi);
      } catch (e) {
        if (my === ctxSeq.current) setCtxLoading(false);
        if (!silent) showError(e);
        return;
      }
      if (my !== ctxSeq.current) return;
      setCtxLoading(false);
      const fresh = str(c.piNo) !== ctxPi.current;
      ctxPi.current = str(c.piNo);
      let shown: Set<string> = new Set();
      setShownBills((prev) => {
        shown = new Set(fresh ? [] : prev);
        for (const l of rows(c.lines)) if (str(l.billState) !== 'ALLOCATED') shown.add(str(l.billNo));
        return shown;
      });
      setCtx(c);
      if (fresh) {
        const shownNos = [...new Set(rows(c.lines).map((l) => str(l.billNo)))].filter(
          (no) => shown.has(no) || rows(c.lines).some((l) => str(l.billNo) === no && str(l.billState) !== 'ALLOCATED'),
        );
        // 换了 PI：单据多时只展开所选单据；同一 PI 刷新保持原样
        focusBill(selRef.current.bill, shownNos, shownNos.length > BILL_FOLD_OVER);
      }
      if (!silent) {
        setCtxVer((v) => v + 1);
        setSelByBill({});
        void loadAlloc(str(c.piNo), []);
        setBatchPage(1);
      }
      setBatchTick((x) => x + 1);
      // 有推送任务在跑时，定时刷新推送进度
      if (rows(c.push).some((p) => ACTIVE_TASK.includes(str(obj(p.task).state)))) {
        ctxTimer.current = window.setTimeout(() => setPollTick((x) => x + 1), 5000);
      }
    },
    [focusBill, loadAlloc],
  );

  useEffect(() => {
    const id = window.setTimeout(() => void loadCtx(sel.pi, tab), 0);
    return () => window.clearTimeout(id);
  }, [sel.pi, tab, loadCtx]);
  // 推送任务在跑时的定时静默刷新
  useEffect(() => {
    if (!pollTick) return;
    const id = window.setTimeout(() => void loadCtx(selRef.current.pi, tabRef.current, true), 0);
    return () => window.clearTimeout(id);
  }, [pollTick, loadCtx]);
  useEffect(() => {
    if (tab === 'TO_ALLOCATE' && ctxPi.current === sel.pi && sel.bill) focusBill(sel.bill, null);
  }, [sel.bill, sel.pi, tab, focusBill]);
  useEffect(
    () => () => {
      window.clearTimeout(ctxTimer.current);
      window.clearTimeout(allocTimer.current);
    },
    [],
  );

  async function reload() {
    await loadCtx(sel.pi, tab);
    void queryClient.invalidateQueries({ queryKey: ['sn', 'allocItems'] });
    void loadList({ silent: true, tab, q });
  }

  const pushRows = rows(ctx?.push);
  /** 已分配、还没开始推送的号（组织后开启对接 MES）：点「分配」一并重新分配推送。 */
  const idleQty = pushRows.reduce((s, p) => s + num(p.idle), 0);
  /** 本 PI 推送中断的组织：有时底部「分配」按钮变为「继续推送（剩余 N 次）」。 */
  const resumable = pushRows.filter((p) => bool(p.interrupted));
  const resumeLeft = resumable.reduce((s, p) => s + num(obj(p.remaining).pushes), 0);
  const lineByKey = useMemo(() => new Map(rows(ctx?.lines).map((l) => [keyOf(l), l])), [ctx]);
  const picked: LineRef[] = bills.flatMap((b) =>
    (selByBill[b.billNo] ?? []).map((k) => {
      const l = lineByKey.get(k) ?? {};
      return { billNo: str(l.billNo), seq: num(l.seq) };
    }),
  );
  const summary = obj(ctx?.summary);

  function onSelect(billNo: string, keys: string[]) {
    const next = { ...selByBill, [billNo]: keys };
    setSelByBill(next);
    const nextPicked = bills.flatMap((b) =>
      (next[b.billNo] ?? []).map((k) => {
        const l = lineByKey.get(k) ?? {};
        return { billNo: str(l.billNo), seq: num(l.seq) };
      }),
    );
    window.clearTimeout(allocTimer.current);
    const piNo = str(ctx?.piNo);
    allocTimer.current = window.setTimeout(() => void loadAlloc(piNo, nextPicked), 300);
  }
  function clearSel() {
    setSelByBill({});
    window.clearTimeout(allocTimer.current);
    void loadAlloc(str(ctx?.piNo), []);
  }
  const progLabel = (x: Counts) => {
    const p = progressOf(x);
    if (p.state === 'FAILED') return t('allocate.prog.FAILED', { n: n(x.failed) });
    return `${t(`allocate.prog.${p.state}`)} ${p.pct}%`;
  };
  const isFolded = (no: string) => folded.has(no);
  const toggleFold = (no: string) =>
    setFolded((cur) => {
      const s = new Set(cur);
      if (!s.delete(no)) s.add(no);
      return s;
    });
  const foldAll = (on: boolean) => setFolded(new Set(on ? bills.map((b) => b.billNo) : []));

  async function doAllocate() {
    const d = alloc.data;
    if (!d || !ctx) return;
    const msg = num(d.pushes)
      ? t('allocate.allocConfirmPush', { n: n(d.qty), m: str(d.pushes) })
      : t('allocate.allocConfirm', { n: n(d.qty) });
    if (!(await confirm(msg, t('allocate.allocTitle')))) return;
    setBusy('alloc');
    try {
      const r = await snApi.allocate({ piNo: str(ctx.piNo), ...(picked.length ? { lines: picked } : {}) });
      showSuccess(t('allocate.allocDone', { n: n(r.qty) }));
      if (rows(r.skipped).length) showWarning(t('allocate.allocSkipped', { n: rows(r.skipped).length }));
      // 分配后留在原处看推送进度条
      await reload();
    } catch (e) {
      showError(e);
    } finally {
      setBusy('');
    }
  }
  /** 继续推送：本 PI 推送中断的组织逐个续推（各组织的推送任务独立）。 */
  async function resumeAll() {
    if (!ctx) return;
    setBusy('resume');
    try {
      let total = 0;
      for (const p of resumable) total += num((await snApi.resumePush(str(p.factoryCode), str(ctx.piNo))).pushes);
      showSuccess(t('allocate.resumed', { n: total }));
      window.setTimeout(() => void reload(), 800);
    } catch (e) {
      showError(e);
    } finally {
      setBusy('');
    }
  }

  // 单据物料行的列宽（本 PI 各单据的表同宽、列对齐）
  const ctxLines = rows(ctx?.lines);
  const pnW = colWidth(t('orders.customerPn'), ctxLines.map((l) => textWidth(str(l.customerPn) || '—')), { min: 56 });
  const rangeW = colWidth(t('generate.snRange'), ctxLines.map((l) => textWidth(snRange(l), { mono: true })), { min: 104 });
  const attachW = colWidth(t('common.attachNo'), ctxLines.map((l) => textWidth(str(l.attachNo) || '—')), { min: 56 });
  const decW = colWidth(t('generate.seqDec'), ctxLines.map((l) => textWidth(decRange(l), { mono: true })), { min: 64 });
  const qtyW = colWidth(t('allocate.snQty'), ctxLines.map((l) => textWidth(n(l.qty))));
  /** 左侧单据列表 SN 数量列宽：原 86px，各语言表头更长时按表头放宽，多出的宽度从单据编号列扣。 */
  const listQtyW = Math.max(86, colWidth(t('allocate.snQty'), []));

  const prog = (x: Counts, height: number, row = false) => {
    const p = progressOf(x);
    return (
      <span className={row ? 'sn-prog sn-prog-row' : 'sn-prog'}>
        <Progress percent={p.pct} status={PROG_STATUS[p.state]} showInfo={false} size={{ height }} style={{ width: 72, margin: 0 }} />
        <span className="sn-prog-t" style={p.state === 'FAILED' ? { color: ERR } : undefined}>
          {progLabel(x)}
        </span>
      </span>
    );
  };
  const empty = <p className="muted" style={{ padding: 40, textAlign: 'center' }}>{listItems.length ? t('allocate.pickBill') : t('common.empty')}</p>;
  const nowrap = { className: 'sn-nowrap' };

  return (
    <div className="page page--fill" ref={root}>
      <PageHead title={t('allocate.title')} desc={t('allocate.desc')}>
        <span className="muted">
          {t('allocate.summary', { pending: n(obj(list.summary).pendingAlloc), unbatched: n(obj(list.summary).unbatched) })}
        </span>
      </PageHead>

      <div className="fill sn-split">
        {/* 左：单据列表（按 PI / 单据编号筛选） */}
        <div className="surface sn-pane" style={{ width: 380, flex: 'none' }}>
          <div className="toolbar">
            <Radio.Group
              size="small"
              optionType="button"
              value={tab}
              onChange={(e) => setTab(e.target.value as Tab)}
              options={TABS.map((v) => ({ value: v, label: t(`allocate.tab.${v}`) }))}
            />
            <Input
              size="small"
              allowClear
              style={{ width: 180 }}
              prefix={<SearchOutlined />}
              placeholder={t('allocate.search')}
              value={q}
              onChange={(e) => {
                setQ(e.target.value);
                if (!e.target.value) void loadList({ tab, q: '' });
              }}
              onPressEnter={() => void loadList({ tab, q })}
            />
          </div>
          {/* 每页条数按高度算，正好放满，不出滚动条；处理时间近的在前 */}
          <div ref={listBox} style={{ flex: 1, minHeight: 240, overflow: 'hidden' }}>
            <Table<Row>
              size="small"
              className="fit-table sn-pick-table"
              loading={listLoading}
              rowKey={(r) => `${str(r.piNo)}|${str(r.billNo)}`}
              pagination={false}
              dataSource={listItems}
              locale={{ emptyText: t('common.empty') }}
              rowClassName={(r) => (isCurrent(r) ? 'ant-table-row-selected' : '')}
              onRow={(r) => ({ onClick: () => pick(r) })}
              columns={[
                { title: t('common.billNo'), width: 150 - (listQtyW - 86), ellipsis: true, render: (_, r) => <b>{str(r.billNo)}</b> },
                { title: t('allocate.snQty'), width: listQtyW, align: 'right', render: (_, r) => <span className="num">{n(r.qty)}</span> },
                {
                  title: t('common.status'),
                  width: 100,
                  render: (_, r) => <Tag color={STATE_TAG[str(r.state)]}>{t(`allocate.state.${str(r.state)}`)}</Tag>,
                },
              ]}
            />
          </div>
          <Pager total={num(list.total)} page={page} pageSize={listRows} onPage={(p) => void loadList({ tab, q, p })} />
        </div>

        {/* 右：所选单据所在 PI 的分配与推送（待分配页签）/ 已分配的号（已分配页签） */}
        <div className="surface sn-pane sn-detail">
          {tab === 'ALLOCATED' ? (
            sel.pi && sel.bill ? (
              <AllocatedPane key={`${sel.pi}|${sel.bill}`} pi={sel.pi} bill={sel.bill} />
            ) : (
              empty
            )
          ) : !ctx ? (
            ctxLoading ? <Spin style={{ margin: 40 }} /> : empty
          ) : (
            <Spin spinning={ctxLoading} wrapperClassName="sn-spin-fill">
              <div className="sn-detail-body">
                <div style={{ display: 'flex', flexDirection: 'column', gap: 6, flex: 'none' }}>
                  <div style={{ display: 'flex', gap: 12, alignItems: 'center', fontSize: 16 }}>
                    <b>PI {str(ctx.piNo)}</b>
                    <span className="muted">
                      {t('common.customer')} {str(ctx.customerCode) || '—'}
                    </span>
                    <span style={{ marginLeft: 'auto' }} />
                    {num(summary.quota) ? (
                      <a className="sn-go" onClick={() => open(`/sn/generate?pi=${encodeURIComponent(str(ctx.piNo))}`)}>
                        {t('allocate.toGenerate', { n: n(summary.quota) })}
                        <ArrowRightOutlined />
                      </a>
                    ) : null}
                  </div>
                  <div className="sn-kv">
                    <span>
                      {t('generate.generated')} <b className="num">{n(summary.generatedQty)}</b>
                    </span>
                    {num(summary.transferredIn) ? (
                      <span>
                        {t('generate.transferredIn')} <b className="num">{n(summary.transferredIn)}</b>
                      </span>
                    ) : null}
                    <span>
                      {t('generate.pendingAlloc')} <b className="num">{n(summary.pendingAlloc)}</b>
                    </span>
                    <span>
                      {t('generate.allocated')} <b className="num">{n(summary.allocatedQty)}</b>
                    </span>
                    <span>
                      {t('allocate.unbatched')} <b className="num">{n(summary.unbatched)}</b>
                    </span>
                    {num(summary.suspended) ? (
                      <span style={{ color: WARN }}>{t('generate.suspendedQty', { n: n(summary.suspended) })}</span>
                    ) : null}
                  </div>
                </div>

                {/* 单据物料行：勾选有待分配号的物料行为范围，不勾选 = 整张 PI；单据头、物料行都带推送进度条 */}
                {bills.length ? (
                  <section className="sn-box sn-lines-box">
                    <div className="sn-box-h">
                      <b>{t('allocate.linesTitle')}</b>
                      <span style={{ marginLeft: 'auto' }} />
                      <Button type="link" size="small" onClick={() => foldAll(false)}>
                        {t('common.expandAll')}
                      </Button>
                      <Button type="link" size="small" onClick={() => foldAll(true)}>
                        {t('common.collapseAll')}
                      </Button>
                    </div>
                    {/* 按单据分组，点单据头展开 / 收起；收起只隐藏，勾选保留 */}
                    <div className="sn-bills">
                      {bills.map((b) => (
                        <div
                          key={`${ctxVer}:${b.billNo}`}
                          className={`sn-bill${b.billNo === sel.bill ? ' focus' : ''}`}
                          data-bill={b.billNo}
                        >
                          <div
                            className="sn-bill-h"
                            title={isFolded(b.billNo) ? t('common.expand') : t('common.collapse')}
                            onClick={() => toggleFold(b.billNo)}
                          >
                            <RightOutlined className={`sn-fold${isFolded(b.billNo) ? '' : ' open'}`} />
                            <b>{b.billNo}</b>
                            <Tag color={STATE_TAG[b.state]}>{t(`allocate.state.${b.state}`)}</Tag>
                            <span className="muted num">{t('allocate.billCounts', { n: b.lines.length, qty: n(b.qty) })}</span>
                            {selByBill[b.billNo]?.length ? <Tag>{t('allocate.billPicked', { n: selByBill[b.billNo]!.length })}</Tag> : null}
                            <span style={{ marginLeft: 'auto' }} />
                            {prog(b, 8)}
                          </div>
                          <div style={{ display: isFolded(b.billNo) ? 'none' : undefined }}>
                            <Table<Row>
                              size="small"
                              rowKey={keyOf}
                              pagination={false}
                              dataSource={b.lines}
                              scroll={{ x: 'max-content' }}
                              rowSelection={{
                                columnWidth: 40,
                                selectedRowKeys: selByBill[b.billNo] ?? [],
                                onChange: (keys) => onSelect(b.billNo, keys.map(String)),
                                getCheckboxProps: (r) => ({ disabled: !(num(r.pendingAlloc) > 0 && !str(r.skip)) }),
                              }}
                              columns={[
                                { title: t('common.attachNo'), width: attachW, onHeaderCell: () => nowrap, onCell: () => nowrap, render: (_, r) => str(r.attachNo) || '—' },
                                { title: t('common.material'), dataIndex: 'materialCode', width: 72, ellipsis: { showTitle: true } },
                                {
                                  title: t('common.lineNo'),
                                  width: 72,
                                  render: (_, r) =>
                                    bool(r.imported) ? <Tag color="warning" variant="outlined">{t('allocate.imported')}</Tag> : str(r.seq),
                                },
                                { title: t('generate.orderQty'), width: 64, align: 'right', render: (_, r) => <span className="num">{n(r.orderQty)}</span> },
                                { title: t('orders.customerPn'), width: pnW, onHeaderCell: () => nowrap, onCell: () => nowrap, render: (_, r) => str(r.customerPn) || '—' },
                                {
                                  title: t('generate.seqDec'),
                                  width: decW,
                                  onHeaderCell: () => nowrap,
                                  onCell: () => nowrap,
                                  render: (_, r) => <span className="sn-mono">{decRange(r)}</span>,
                                },
                                {
                                  title: t('allocate.snQty'),
                                  width: qtyW,
                                  align: 'right',
                                  onHeaderCell: () => nowrap,
                                  onCell: () => nowrap,
                                  render: (_, r) => <span className="num">{n(r.qty)}</span>,
                                },
                                {
                                  title: t('generate.snRange'),
                                  width: rangeW,
                                  onHeaderCell: () => nowrap,
                                  onCell: () => nowrap,
                                  render: (_, r) => <span className="sn-mono">{snRange(r)}</span>,
                                },
                                {
                                  title: t('allocate.tabPush'),
                                  width: 100,
                                  render: (_, r) =>
                                    str(r.skip) ? (
                                      <Tag color="error" variant="outlined">
                                        {t(`allocate.skip.${str(r.skip)}`)}
                                      </Tag>
                                    ) : (
                                      prog(counts(r), 6, true)
                                    ),
                                },
                                {
                                  title: t('allocate.toOrg'),
                                  width: 56,
                                  ellipsis: true,
                                  render: (_, r) => (str(r.orgCode) ? str(r.orgName) || nameOf(r.orgCode) : '—'),
                                },
                              ]}
                            />
                          </div>
                        </div>
                      ))}
                    </div>
                    {/* 底部按钮：推送中断时「分配」变为「继续推送（剩余 N 次）」；推送恢复后变回「分配」 */}
                    {resumable.length ? (
                      <div className="sn-bar">
                        <span style={{ marginLeft: 'auto' }} />
                        <Button color="orange" variant="solid" loading={busy === 'resume'} onClick={() => void resumeAll()}>
                          {t('allocate.resumeBtn', { n: resumeLeft })}
                        </Button>
                      </div>
                    ) : num(summary.pendingAlloc) || idleQty ? (
                      <div className="sn-bar">
                        <span>
                          <span className="muted">{t('generate.scope')}</span>{' '}
                          <b>{picked.length ? t('generate.scopeLinesN', { n: picked.length }) : t('generate.scopeWholePi')}</b>
                        </span>
                        {picked.length ? (
                          <Button type="link" onClick={clearSel}>
                            {t('generate.clearSel')}
                          </Button>
                        ) : null}
                        <span style={{ marginLeft: 'auto' }} />
                        <Spin spinning={alloc.loading} size="small">
                          <span className="sn-orgs">
                            {rows(alloc.data?.orgs).map((o) => (
                              <span key={str(o.orgCode)} className="muted">
                                {bool(o.mesEnabled)
                                  ? t('allocate.orgPush', { org: str(o.orgName) || nameOf(o.orgCode), n: n(o.qty), m: str(o.pushes) })
                                  : t('allocate.orgExport', { org: str(o.orgName) || nameOf(o.orgCode), n: n(o.qty) })}
                              </span>
                            ))}
                            {rows(alloc.data?.skipped).length ? (
                              <span style={{ color: WARN }}>{t('allocate.skippedN', { n: rows(alloc.data?.skipped).length })}</span>
                            ) : null}
                          </span>
                        </Spin>
                        <Button
                          color="green"
                          variant="solid"
                          disabled={!num(alloc.data?.qty)}
                          loading={busy === 'alloc'}
                          onClick={() => void doAllocate()}
                        >
                          {num(alloc.data?.pushes)
                            ? t('allocate.allocBtnPush', { m: str(alloc.data?.pushes) })
                            : t('allocate.allocBtn', { n: n(alloc.data?.qty ?? 0) })}
                        </Button>
                      </div>
                    ) : null}
                  </section>
                ) : null}

                {/* 推送进度：始终显示；每个组织一行：状态、第 i / N 次、失败原因；下面分页列出推送批次（只读） */}
                <Tabs
                  activeKey="push"
                  className="sn-box sn-tabs-box"
                  items={[
                    {
                      key: 'push',
                      label: t('allocate.tabPush'),
                      children: (
                        <>
                          {pushRows.map((p) => {
                            const task = p.task ? obj(p.task) : null;
                            const problem = p.lastProblem ? obj(p.lastProblem) : null;
                            return (
                              <div key={str(p.factoryCode)} className="sn-push-row">
                                <b>{nameOf(p.factoryCode)}</b>
                                {task ? (
                                  <Tag color={bool(p.waitingReceipt) ? 'processing' : (TASK_TAG[str(task.state)] ?? 'default')}>
                                    {bool(p.waitingReceipt) ? taskStateLabel('WAITING_RECEIPT') : taskStateLabel(task.state)}
                                  </Tag>
                                ) : null}
                                <span className="muted num">
                                  {t('allocate.pushProgress', { i: str(p.currentPush), n: str(p.totalBatches), qty: n(p.pushedQty) })}
                                </span>
                                {problem ? (
                                  <span style={{ color: ERR, fontSize: 12 }}>
                                    {str(problem.batchNo)}：{str(problem.failReason) || pushStateLabel(problem.pushState)}
                                  </span>
                                ) : bool(p.waitingReceipt) ? (
                                  <span className="muted">{t('allocate.waitingReceipt')}</span>
                                ) : task && str(task.message) ? (
                                  <span className="muted">{str(task.message)}</span>
                                ) : null}
                              </div>
                            );
                          })}
                          <Table<Row>
                            size="small"
                            loading={batches.loading}
                            rowKey={(r) => str(r.batchNo)}
                            pagination={false}
                            dataSource={batches.items}
                            locale={{ emptyText: t('common.empty') }}
                            scroll={{ x: 'max-content' }}
                            columns={[
                              {
                                title: t('common.batchNo'),
                                width: 226,
                                render: (_, r) => (
                                  <Button type="link" size="small" className="sn-mono" style={{ padding: 0 }} onClick={() => setBatchNo(str(r.batchNo))}>
                                    {str(r.batchNo)}
                                  </Button>
                                ),
                              },
                              { title: t('common.factory'), width: 90, ellipsis: true, render: (_, r) => nameOf(r.factoryCode) },
                              { title: t('common.qty'), width: 56, align: 'right', render: (_, r) => <span className="num">{n(r.qty)}</span> },
                              { title: t('allocate.batchSource'), width: 76, render: (_, r) => batchSourceLabel(r.source) },
                              {
                                title: t('common.status'),
                                width: 110,
                                render: (_, r) => (
                                  <>
                                    <Tooltip title={str(r.failReason) || undefined}>
                                      <Tag color={BATCH_RESULT_COLOR[str(r.result)] ?? 'default'}>{batchResultLabel(r.result)}</Tag>
                                    </Tooltip>
                                    {str(r.pushState) && str(r.pushState) !== 'IDLE' ? (
                                      <div className="muted">{pushStateLabel(r.pushState)}</div>
                                    ) : null}
                                  </>
                                ),
                              },
                              { title: t('common.time'), width: 146, render: (_, r) => account.fmt(r.createdAt) },
                            ]}
                          />
                          {batches.total > batches.pageSize ? (
                            <Pager total={batches.total} page={batches.page} pageSize={batches.pageSize} onPage={setBatchPage} />
                          ) : null}
                        </>
                      ),
                    },
                  ]}
                />
              </div>
            </Spin>
          )}
        </div>
      </div>

      {/* 批次详情只读：分配页不做人工确认、作废 */}
      <DeliveryBatchDrawer batchNo={batchNo} onChange={setBatchNo} editable={false} onChanged={() => void reload()} />
    </div>
  );
}
