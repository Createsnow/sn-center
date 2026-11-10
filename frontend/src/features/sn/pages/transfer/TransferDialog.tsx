import { ArrowRightOutlined, CheckCircleOutlined } from '@ant-design/icons';
import { AutoComplete, Button, Checkbox, Form, Input, InputNumber, Modal, Radio, Select, Table, Tag } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { snApi, type Row } from '../../../../api/sn';
import { useAccount } from '../../lib/auth';
import { useFactories } from '../../lib/factories';
import { scopeLabel } from '../../lib/labels';
import { showError, showSuccess } from '../../lib/notify';
import { bool, num, obj, rows, str } from '../../lib/row';
import {
  LINE_MODES,
  activeGroups,
  autoSeq,
  candidates,
  defaultCfg,
  groupKey,
  groupsOf,
  lengthBad,
  lineIssue,
  linesBody,
  type Group,
  type GroupCfg,
  type LineMode,
  type SrcRow,
  type TargetLine,
  type TransferScope,
} from '../../lib/transferLines';
import type { Kind } from './types';

const SCOPES: TransferScope[] = ['BILL', 'MATERIAL'];
type Target = TargetLine & { billNo: string; orgCode: string; pi: string };
interface SourceBill {
  billNo: string;
  qty: number;
  rows: SrcRow[];
}
interface TF {
  factoryCode: string;
  piNo: string;
  billNo: string;
  scope: TransferScope;
  toPi: string;
  toBill: string;
  toFactory: string;
  reason: string;
}
interface State {
  tf: TF;
  cfgs: Record<string, GroupCfg>;
  srcBills: SourceBill[];
  sourcesLoaded: boolean;
  toRows: Target[];
  targetsLoaded: boolean;
}
const EMPTY_TF: TF = { factoryCode: '', piNo: '', billNo: '', scope: 'BILL', toPi: '', toBill: '', toFactory: '', reason: '' };
const EMPTY: State = { tf: EMPTY_TF, cfgs: {}, srcBills: [], sourcesLoaded: false, toRows: [], targetsLoaded: false };

const toSource = (b: Row): SourceBill => ({
  billNo: str(b.billNo),
  qty: num(b.qty),
  rows: rows(b.rows).map((r) => ({
    materialCode: str(r.materialCode),
    attachNo: r.attachNo === null || r.attachNo === undefined ? undefined : str(r.attachNo),
    attachNos: Array.isArray(r.attachNos) ? r.attachNos.map(String) : [],
    factoryCode: str(r.factoryCode),
    qty: num(r.qty),
    customers: Array.isArray(r.customers) ? r.customers.map(String) : [],
    lengths: Array.isArray(r.lengths) ? r.lengths.map(String) : [],
    seqs: Array.isArray(r.seqs) ? r.seqs.map(Number) : [],
  })),
});
const toTarget = (r: Row): Target => ({
  seq: num(r.seq),
  materialCode: str(r.materialCode),
  attachNo: str(r.attachNo),
  length: str(r.length),
  billNo: str(r.billNo),
  orgCode: str(r.orgCode),
  pi: str(r.pi),
});

/** 派生：来源单据、来源组织、各组、目标单据与物料行。 */
function derive(s: State) {
  const srcBill = s.srcBills.find((b) => b.billNo === s.tf.billNo) ?? null;
  const orgQty = new Map<string, number>();
  for (const r of srcBill?.rows ?? []) orgQty.set(r.factoryCode, (orgQty.get(r.factoryCode) ?? 0) + r.qty);
  const srcOrgs = [...orgQty].map(([factoryCode, qty]) => ({ factoryCode, qty }));
  const groups = groupsOf(srcBill?.rows ?? [], s.tf.factoryCode);
  const seen = new Map<string, Target>();
  for (const r of s.toRows) if (!seen.has(r.billNo)) seen.set(r.billNo, r);
  const toBills = [...seen.values()];
  const toHead = toBills.find((b) => b.billNo === s.tf.toBill) ?? null;
  const toLines = s.toRows.filter((r) => r.billNo === s.tf.toBill);
  const sameBill = !!s.tf.toBill && s.tf.toBill === s.tf.billNo;
  return { srcBill, srcOrgs, groups, toBills, toHead, toLines, sameBill };
}
const modeOf = (s: State, g: Group): LineMode => (s.tf.scope === 'BILL' ? 'ALL' : (s.cfgs[g.key]?.mode ?? 'ALL'));

/** 各行配置：新出现的行补默认值（只有一行时默认勾选），并重新匹配目标行号（同一单据内默认留在原行）。 */
function sync(s: State): State {
  const { groups, toLines, sameBill } = derive(s);
  const cfgs = { ...s.cfgs };
  for (const g of groups) if (!cfgs[g.key]) cfgs[g.key] = { ...defaultCfg(), checked: groups.length === 1 };
  for (const g of groups) {
    const c = { ...cfgs[g.key]! };
    if (sameBill && !candidates(toLines, g).some((l) => l.seq === c.toSeq)) c.toSeq = undefined;
    else if (!sameBill) c.toSeq = autoSeq(toLines, g, { ...c, mode: modeOf({ ...s, cfgs }, g) });
    cfgs[g.key] = c;
  }
  return { ...s, cfgs };
}
/** 来源生产组织：来源单据的号只在一个组织时自动带出；多个时保留已选的（或快速定位带出的），否则待选。 */
function pickSourceOrg(s: State, prefer?: string): State {
  const orgs = derive(s).srcOrgs.map((o) => o.factoryCode);
  let factoryCode = s.tf.factoryCode;
  if (prefer && orgs.includes(prefer)) factoryCode = prefer;
  else if (!orgs.includes(factoryCode)) factoryCode = orgs.length === 1 ? orgs[0]! : '';
  return { ...s, tf: { ...s.tf, factoryCode } };
}
/** 换目标单据：生产组织回到该单据的。 */
function pickTargetOrg(s: State): State {
  return { ...s, tf: { ...s.tf, toFactory: derive(s).toHead?.orgCode ?? '' } };
}

/**
 * 新建转移（F-15、F-16）：页面不区分转单、转组织，系统按目标判断——目标单据与来源不同、且生产组织就是目标单据的 → 转单，
 * 其余 → 转组织。系统管理员、普通操作员都直接转移、立即生效，原因必填（没有申请、审批）。确认框列出枚数、各行对应、不可转、
 * 转出等，确认后执行；完成后回调 onDone（种类、单据）。
 */
export function TransferDialog(props: { open: boolean; onClose: () => void; onDone: (kind: Kind, doc: Row) => void }) {
  const { t } = useTranslation('sn');
  const account = useAccount();
  const { factories, nameOf } = useFactories();
  const [s, setS] = useState<State>(EMPTY);
  const [loc, setLoc] = useState<{ q: string; by: string }>({ q: '', by: '' });
  const [busy, setBusy] = useState('');
  const [reasonTouched, setReasonTouched] = useState(false);
  const [piOptions, setPiOptions] = useState<{ value: string }[]>([]);
  const tf = s.tf;
  const srcBills = s.srcBills;
  const { srcOrgs, groups, toBills, toHead, toLines, sameBill } = derive(s);
  const active = activeGroups(tf.scope, groups, s.cfgs);
  const allChecked = groups.length > 0 && groups.every((g) => s.cfgs[g.key]?.checked);
  const someChecked = groups.some((g) => s.cfgs[g.key]?.checked);

  /** 系统判断的类型：目标单据与来源不同、且生产组织就是目标单据的 → 转单；其余 → 转组织（与后台一致）。 */
  const kind: Kind | null = !toHead || !tf.toFactory ? null : tf.toBill !== tf.billNo && tf.toFactory === toHead.orgCode ? 'BILL' : 'ORG';
  const orgDiff = !!toHead && !!tf.toFactory && tf.toFactory !== toHead.orgCode;
  const reasonMissing = reasonTouched && !tf.reason.trim();
  const outOfScope = !!tf.toFactory && !account.canWrite(tf.toFactory);
  const issue = (g: Group) => lineIssue(tf.scope, g, { ...(s.cfgs[g.key] ?? defaultCfg()), mode: modeOf(s, g) }, toLines, sameBill);
  const ready =
    !!tf.factoryCode && !!tf.piNo.trim() && !!tf.billNo && !!tf.toPi.trim() && !!tf.toBill && !!tf.toFactory && active.length > 0 && active.every((g) => !issue(g));

  const matLabel = (v: string) => (v === '' ? t('transfer.emptyMaterial') : v);
  const orgLabel = (code: string) => `${nameOf(code)}（${code}）`;
  const join = (vs: (string | number)[] = []) => (vs.length ? vs.join('、') : '—');
  const isActive = (g: Group) => tf.scope === 'BILL' || !!s.cfgs[g.key]?.checked;
  const cands = (g: Group) => candidates(toLines, g);
  /** 该行当前对应的目标物料行：已选行号的那一行；同一单据内未选时即原行。 */
  const toLineOf = (g: Group): Target | null => {
    const c = s.cfgs[g.key];
    const cs = cands(g);
    if (c?.toSeq !== undefined) return cs.find((l) => l.seq === c.toSeq) ?? null;
    return sameBill && cs.length ? cs[0]! : null;
  };
  const matchState = (g: Group) => {
    const i = issue(g);
    return i === 'NO_MATCH' || i === 'LENGTH' || i === 'PICK_SEQ' ? i : 'OK';
  };
  const setTf = (patch: Partial<TF>, resync = true) => setS((p) => (resync ? sync({ ...p, tf: { ...p.tf, ...patch } }) : { ...p, tf: { ...p.tf, ...patch } }));
  const setCfg = (key: string, patch: Partial<GroupCfg>, resync = false) =>
    setS((p) => {
      const next = { ...p, cfgs: { ...p.cfgs, [key]: { ...(p.cfgs[key] ?? defaultCfg()), ...patch } } };
      return resync ? sync(next) : next;
    });

  // 打开时清空
  const [opened, setOpened] = useState(false);
  if (props.open && !opened) {
    setOpened(true);
    setS(EMPTY);
    setLoc({ q: '', by: '' });
    setReasonTouched(false);
  } else if (!props.open && opened) {
    setOpened(false);
  }

  /** 目标 PI 下快照中的单据与物料行；只有一张别的单据时自动选上。 */
  async function loadTargets(toPi: string) {
    const pi = toPi.trim();
    setS((p) => ({ ...p, targetsLoaded: false }));
    let got: Target[] = [];
    try {
      got = pi ? rows(await snApi.orderTargets(undefined, 200, pi)).map(toTarget) : [];
    } catch (e) {
      showError(e);
    }
    setS((p) => {
      let next: State = { ...p, toRows: got, targetsLoaded: !!pi };
      const d = derive(next);
      if (!d.toBills.some((b) => b.billNo === next.tf.toBill)) {
        const others = d.toBills.filter((b) => b.billNo !== next.tf.billNo);
        next = { ...next, tf: { ...next.tf, toBill: others.length === 1 ? others[0]!.billNo : '' } };
      }
      return sync(pickTargetOrg(next));
    });
  }

  /** 来源候选：PI 下各单据、各物料编码 + 附件项次、各生产组织的号数；preset 为快速定位带出的值。 */
  async function loadSources(piIn: string, preset: { bill?: string; factory?: string } = {}) {
    const pi = piIn.trim();
    if (!pi) {
      setS((p) => sync({ ...p, srcBills: [], sourcesLoaded: false, tf: { ...p.tf, billNo: '', factoryCode: '' } }));
      return;
    }
    setS((p) => ({ ...p, sourcesLoaded: false }));
    const r = await snApi.transferSources({ kind: 'AUTO', pi });
    const bills = rows(r.bills).map(toSource);
    const fillTarget = !s.tf.toPi.trim();
    setS((p) => {
      let next: State = { ...p, srcBills: bills, sourcesLoaded: true, tf: { ...p.tf, piNo: pi } };
      // 目标 PI 未填时默认同来源 PI（拆单多在同一 PI 内）
      if (!next.tf.toPi.trim()) {
        next = { ...next, tf: { ...next.tf, toPi: pi } };
      }
      let billNo = preset.bill !== undefined ? preset.bill : next.tf.billNo;
      if (!bills.some((b) => b.billNo === billNo)) billNo = bills.length === 1 ? bills[0]!.billNo : '';
      next = { ...next, tf: { ...next.tf, billNo } };
      return sync(pickSourceOrg(next, preset.factory));
    });
    if (fillTarget) await loadTargets(pi);
  }

  /** 快速定位：SN 带出 PI、单据、物料编码 + 附件项次、生产组织与起号（按物料编码转移、该行按号段）；单据编号带出 PI、单据；PI 带出 PI。 */
  async function locate() {
    const q = loc.q.trim();
    if (!q) return;
    setBusy('loc');
    setLoc((l) => ({ ...l, by: '' }));
    try {
      const r = await snApi.transferLocate(q);
      setS((p) => ({ ...p, tf: { ...p.tf, piNo: str(r.pi) } }));
      await loadSources(str(r.pi), { bill: r.billNo === undefined || r.billNo === null ? undefined : str(r.billNo), factory: str(r.factoryCode) || undefined });
      if (str(r.by) === 'SN') {
        setS((p) => {
          const cfgs: Record<string, GroupCfg> = {};
          for (const [k, c] of Object.entries(p.cfgs)) cfgs[k] = { ...c, checked: false };
          const key = groupKey(str(r.materialCode), str(r.attachNo));
          if (cfgs[key]) cfgs[key] = { ...cfgs[key], checked: true, mode: 'RANGE', startSn: str(r.sn), endSn: '' };
          return sync({ ...p, cfgs, tf: { ...p.tf, scope: 'MATERIAL' } });
        });
      }
      setLoc((l) => ({ ...l, by: str(r.by) }));
    } catch (e) {
      showError(e);
    } finally {
      setBusy('');
    }
  }

  async function suggestPi(q: string) {
    if (!q.trim()) {
      setPiOptions([]);
      return;
    }
    try {
      const got = rows(await snApi.orderTargets(q.trim(), 50));
      setPiOptions([...new Set(got.map((r) => str(r.pi)).filter(Boolean))].map((v) => ({ value: v })));
    } catch {
      setPiOptions([]);
    }
  }

  const body = () => ({
    factoryCode: tf.factoryCode,
    piNo: tf.piNo.trim(),
    billNo: tf.billNo,
    scope: tf.scope,
    lines: linesBody(tf.scope, groups, s.cfgs),
    toPi: tf.toPi.trim(),
    toBill: tf.toBill,
    toFactory: tf.toFactory,
    reason: tf.reason,
  });

  /** 确认框：类型、可转枚数、目标、各行对应的目标行号、不可转及原因、向来源 MES 转出的枚数、原因；确认后直接执行。 */
  async function submit() {
    setReasonTouched(true);
    if (!tf.reason.trim()) return;
    setBusy('submit');
    let p: Row;
    try {
      p = await snApi.transferPreview(body());
    } catch (e) {
      showError(e);
      return;
    } finally {
      setBusy('');
    }
    const blocked = Object.entries(obj(p.blocked))
      .map(([r, n]) => `${t(`transfer.blocked.${r}`)} ${str(n)}`)
      .join('；');
    if (!num(p.transferable)) {
      showError(new Error(t('transfer.nothingTransferable', { detail: blocked || '—' })));
      return;
    }
    const msg = [
      t('transfer.confirm', {
        kind: t(`transfer.kindName.${str(p.kind)}`),
        n: str(p.transferable),
        to: orgLabel(str(p.toOrg)),
        pi: str(p.toPi),
        bill: str(p.toBill),
      }),
      ...rows(p.lines)
        .filter((x) => num(x.transferable))
        .map((x) =>
          t('transfer.confirmLine', {
            material: matLabel(str(x.materialCode)),
            attach: str(x.attachNo) || '—',
            from: str(x.fromSeq),
            seq: str(x.toSeq),
            n: str(x.transferable),
          }),
        ),
      blocked && t('transfer.confirmBlocked', { n: num(p.total) - num(p.transferable), detail: blocked }),
      num(p.xoQty) ? t('transfer.confirmXo', { n: str(p.xoQty) }) : '',
      t('transfer.confirmReason', { reason: tf.reason.trim() }),
    ]
      .filter(Boolean)
      .join('\n');
    const ok = await new Promise<boolean>((resolve) =>
      Modal.confirm({
        title: t('transfer.new'),
        width: 560,
        content: <div style={{ whiteSpace: 'pre-line' }}>{msg}</div>,
        okText: t('common.confirm'),
        cancelText: t('common.cancel'),
        onOk: () => resolve(true),
        onCancel: () => resolve(false),
      }),
    );
    if (!ok) return;
    setBusy('submit');
    try {
      const r = await snApi.transferCreate(body());
      const doc = obj(r.doc);
      showSuccess(t(str(r.kind) === 'BILL' ? 'transfer.billDone' : 'transfer.orgDone', { no: str(doc.transferNo), n: str(doc.qty) }));
      props.onClose();
      props.onDone(str(r.kind) as Kind, doc);
    } catch (e) {
      showError(e);
    } finally {
      setBusy('');
    }
  }

  const srcHead = (title: string) => ({ title, onHeaderCell: () => ({ className: 'sn-grp-src' }) });
  const dstHead = (title: string) => ({ title, onHeaderCell: () => ({ className: 'sn-grp-dst' }) });
  const none = <span className="muted">—</span>;

  return (
    <Modal
      open={props.open}
      title={t('transfer.new')}
      width={1240}
      style={{ top: '4vh' }}
      onCancel={props.onClose}
      footer={
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          {kind ? (
            <>
              <span className="muted">{t('transfer.kindIs')}</span>
              <Tag color={kind === 'BILL' ? 'blue' : 'warning'} variant="outlined">
                {t(`transfer.kindName.${kind}`)}
              </Tag>
            </>
          ) : null}
          <span style={{ flex: 1 }} />
          <Button onClick={props.onClose}>{t('common.cancel')}</Button>
          <Button type="primary" loading={busy === 'submit'} disabled={!ready} onClick={() => void submit()}>
            {t('transfer.submit')}
          </Button>
        </div>
      }
    >
      {/* ① 快速定位：完整 SN / 单据编号 / PI，带出来源 */}
      <div className="sn-tf-locate">
        <span style={{ fontWeight: 600, whiteSpace: 'nowrap' }}>{t('transfer.locate')}</span>
        <Input
          className="sn-mono"
          allowClear
          placeholder={t('transfer.locatePlaceholder')}
          value={loc.q}
          onChange={(e) => setLoc({ ...loc, q: e.target.value })}
          onPressEnter={() => void locate()}
        />
        <Button loading={busy === 'loc'} disabled={!loc.q.trim()} onClick={() => void locate()}>
          {t('transfer.locateBtn')}
        </Button>
        {loc.by ? (
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, color: '#16a34a', fontSize: 12, whiteSpace: 'nowrap' }}>
            <CheckCircleOutlined />
            {t(`transfer.located.${loc.by}`)}
          </span>
        ) : null}
      </div>

      {/* ② 来源 → 转入目标 */}
      <div className="sn-tf-sides">
        <section className="sn-tf-side">
          <h5>{t('transfer.source')}</h5>
          <Form layout="vertical" className="sn-tf-form">
            <Form.Item label="PI" required>
              <Input
                className="sn-mono"
                allowClear
                value={tf.piNo}
                onChange={(e) => {
                  setTf({ piNo: e.target.value }, false);
                  if (!e.target.value) void loadSources('');
                }}
                onBlur={(e) => void loadSources(e.target.value).catch(showError)}
                onPressEnter={() => void loadSources(tf.piNo).catch(showError)}
              />
              {tf.piNo && s.sourcesLoaded && !srcBills.length ? <div className="sn-hint sn-warn-text">{t('transfer.noSnInPi')}</div> : null}
            </Form.Item>
            <Form.Item label={t('common.billNo')} required>
              <Select
                className="sn-mono"
                showSearch
                disabled={!srcBills.length}
                value={tf.billNo || undefined}
                onChange={(v: string) => setS((p) => sync(pickSourceOrg({ ...p, tf: { ...p.tf, billNo: v } })))}
                options={srcBills.map((b) => ({ value: b.billNo, label: b.billNo, qty: b.qty }))}
                optionRender={(o) => (
                  <div className="sn-opt">
                    <span className="sn-mono">{o.value}</span>
                    <span className="muted">{t('transfer.snCount', { n: o.data.qty })}</span>
                  </div>
                )}
              />
            </Form.Item>
            <Form.Item label={t('transfer.scope')}>
              <Radio.Group
                optionType="button"
                value={tf.scope}
                onChange={(e) => setTf({ scope: e.target.value as TransferScope })}
                options={SCOPES.map((sc) => ({ value: sc, label: scopeLabel(sc) }))}
              />
            </Form.Item>
            {/* 号分在多个生产组织时才要选；只有一个时自动带出 */}
            {srcOrgs.length > 1 ? (
              <Form.Item label={t('transfer.fromOrg')} required>
                <Select
                  value={tf.factoryCode || undefined}
                  onChange={(v: string) => setTf({ factoryCode: v })}
                  options={srcOrgs.map((o) => ({ value: o.factoryCode, label: orgLabel(o.factoryCode), qty: o.qty }))}
                  optionRender={(o) => (
                    <div className="sn-opt">
                      <span>{o.label}</span>
                      <span className="muted">{t('transfer.snCount', { n: o.data.qty })}</span>
                    </div>
                  )}
                />
              </Form.Item>
            ) : null}
          </Form>
        </section>

        <div className="sn-tf-arrow">
          <ArrowRightOutlined />
        </div>

        <section className="sn-tf-side">
          <h5>{t('transfer.target')}</h5>
          <Form layout="vertical" className="sn-tf-form">
            <Form.Item label="PI" required>
              <AutoComplete
                className="sn-mono"
                allowClear
                value={tf.toPi}
                options={piOptions}
                onSearch={(v) => void suggestPi(v)}
                onChange={(v: string) => {
                  setTf({ toPi: v ?? '' }, false);
                  if (!v) void loadTargets('');
                }}
                onSelect={(v: string) => void loadTargets(v)}
                onBlur={() => void loadTargets(tf.toPi)}
              />
              <div className="sn-hint">{t('transfer.toPiDefault')}</div>
            </Form.Item>
            <Form.Item label={t('common.billNo')} required>
              <Select
                className="sn-mono"
                showSearch
                disabled={!toBills.length}
                value={tf.toBill || undefined}
                onChange={(v: string) => setS((p) => sync(pickTargetOrg({ ...p, tf: { ...p.tf, toBill: v } })))}
                options={toBills.map((b) => ({ value: b.billNo, label: b.billNo, org: b.orgCode }))}
                optionRender={(o) => (
                  <div className="sn-opt">
                    <span className="sn-mono">{o.value}</span>
                    <span className="muted">{nameOf(o.data.org)}</span>
                  </div>
                )}
              />
              {tf.toPi && s.targetsLoaded && !toBills.length ? <div className="sn-hint sn-warn-text">{t('transfer.noBillInPi')}</div> : null}
            </Form.Item>
            <Form.Item label={t('common.factory')} required>
              <Select
                showSearch={{ optionFilterProp: 'label' }}
                value={tf.toFactory || undefined}
                onChange={(v: string) => setTf({ toFactory: v })}
                options={factories.map((x) => ({
                  value: str(x.factoryCode),
                  label: orgLabel(str(x.factoryCode)),
                  disabled: !bool(x.enabled) && str(x.factoryCode) !== toHead?.orgCode,
                  mes: bool(x.mesEnabled),
                  enabled: bool(x.enabled),
                }))}
                optionRender={(o) => (
                  <div className="sn-opt">
                    <span>{o.label}</span>
                    <span className="muted">
                      {o.data.mes ? 'MES' : ''}
                      {o.data.enabled ? '' : t('common.disabledSuffix')}
                    </span>
                  </div>
                )}
              />
              {orgDiff ? (
                <div className="sn-hint" style={{ color: '#93370d' }}>{t('transfer.orgDiff')}</div>
              ) : (
                <div className="sn-hint">{t('transfer.orgDefault')}</div>
              )}
              {outOfScope ? <div className="sn-hint sn-warn-text">{t('transfer.targetOutOfScope')}</div> : null}
            </Form.Item>
          </Form>
        </section>
      </div>

      {/* ③ 物料编码明细：来源、目标两侧都列附件项次与物料编码逐行对照 */}
      <section className="sn-tf-lines">
        <div className="sn-tf-lines-t">
          <span>{t('transfer.linesTitle')}</span>
          <span className="sn-hint" style={{ width: 'auto', margin: '0 0 0 12px', fontWeight: 400 }}>
            {t('transfer.sameKeyHint')}
          </span>
        </div>
        <Table<Group>
          size="small"
          bordered
          rowKey="key"
          pagination={false}
          dataSource={groups}
          locale={{ emptyText: t('transfer.noLines') }}
          scroll={{ y: 300, x: 'max-content' }}
          rowClassName={(g) => {
            if (!isActive(g) || !tf.toBill) return '';
            const m = matchState(g);
            return m === 'NO_MATCH' || m === 'LENGTH' ? 'sn-row-bad' : '';
          }}
          columns={[
            ...(tf.scope === 'MATERIAL'
              ? [
                  {
                    key: 'chk',
                    width: 40,
                    align: 'center' as const,
                    title: (
                      <Checkbox
                        checked={allChecked}
                        indeterminate={someChecked && !allChecked}
                        disabled={!groups.length}
                        onChange={(e) =>
                          setS((p) => {
                            const cfgs = { ...p.cfgs };
                            for (const g of groups) cfgs[g.key] = { ...(cfgs[g.key] ?? defaultCfg()), checked: e.target.checked };
                            return sync({ ...p, cfgs });
                          })
                        }
                      />
                    ),
                    render: (_: unknown, g: Group) => (
                      <Checkbox checked={!!s.cfgs[g.key]?.checked} onChange={(e) => setCfg(g.key, { checked: e.target.checked }, true)} />
                    ),
                  },
                ]
              : []),
            {
              ...srcHead(t('transfer.source')),
              align: 'center' as const,
              children: [
                { ...srcHead(t('common.attachNo')), key: 'sa', width: 80, render: (_: unknown, g: Group) => <span className="sn-key">{g.attachNo || '—'}</span> },
                { ...srcHead(t('common.material')), key: 'sm', width: 110, render: (_: unknown, g: Group) => <span className="sn-key">{matLabel(g.materialCode)}</span> },
                { ...srcHead(t('common.lineNo')), key: 'ss', width: 72, render: (_: unknown, g: Group) => join(g.seqs) },
                { ...srcHead(t('common.length')), key: 'sl', width: 60, render: (_: unknown, g: Group) => join(g.lengths) },
                {
                  ...srcHead(t('transfer.groupQty')),
                  key: 'sq',
                  width: 52,
                  align: 'right' as const,
                  render: (_: unknown, g: Group) => <span className="num">{g.qty}</span>,
                },
              ],
            },
            {
              title: t('transfer.way'),
              align: 'center' as const,
              children: [
                {
                  title: t('transfer.wayShort'),
                  key: 'wm',
                  width: 106,
                  render: (_: unknown, g: Group) =>
                    tf.scope === 'BILL' ? (
                      <span className="muted">{t('transfer.lineMode.ALL')}</span>
                    ) : (
                      <Select
                        size="small"
                        style={{ width: '100%' }}
                        disabled={!s.cfgs[g.key]?.checked}
                        value={s.cfgs[g.key]?.mode ?? 'ALL'}
                        onChange={(v: LineMode) => setCfg(g.key, { mode: v }, true)}
                        options={LINE_MODES.map((md) => ({ value: md, label: t(`transfer.lineMode.${md}`) }))}
                      />
                    ),
                },
                {
                  title: t('transfer.configure'),
                  key: 'wc',
                  width: 230,
                  render: (_: unknown, g: Group) => {
                    const c = s.cfgs[g.key];
                    if (tf.scope === 'MATERIAL' && c?.checked) {
                      if (c.mode === 'RANGE')
                        return (
                          <div className="sn-pair">
                            <Input size="small" className="sn-mono" allowClear placeholder={t('transfer.startSn')} value={c.startSn} onChange={(e) => setCfg(g.key, { startSn: e.target.value })} />
                            <Input size="small" className="sn-mono" allowClear placeholder={t('transfer.endSn')} value={c.endSn} onChange={(e) => setCfg(g.key, { endSn: e.target.value })} />
                          </div>
                        );
                      if (c.mode === 'QTY')
                        return (
                          <InputNumber
                            size="small"
                            style={{ width: 130 }}
                            min={1}
                            max={g.qty}
                            step={1}
                            precision={0}
                            placeholder={t('transfer.qtyPlaceholder', { n: g.qty })}
                            value={c.qty ?? null}
                            onChange={(v) => setCfg(g.key, { qty: v === null ? undefined : Number(v) })}
                          />
                        );
                      return <span className="muted">{t('transfer.snCount', { n: g.qty })}</span>;
                    }
                    return tf.scope === 'BILL' ? <span className="muted">{t('transfer.snCount', { n: g.qty })}</span> : none;
                  },
                },
              ],
            },
            {
              ...dstHead(t('transfer.target')),
              align: 'center' as const,
              children: [
                {
                  ...dstHead(t('common.attachNo')),
                  key: 'da',
                  width: 80,
                  render: (_: unknown, g: Group) => {
                    const l = isActive(g) ? toLineOf(g) : null;
                    return l ? <span className="sn-key">{str(l.attachNo) || '—'}</span> : none;
                  },
                },
                {
                  ...dstHead(t('common.material')),
                  key: 'dm',
                  width: 110,
                  render: (_: unknown, g: Group) => {
                    const l = isActive(g) ? toLineOf(g) : null;
                    return l ? <span className="sn-key">{matLabel(l.materialCode)}</span> : none;
                  },
                },
                {
                  ...dstHead(t('transfer.toLine')),
                  key: 'ds',
                  width: 130,
                  render: (_: unknown, g: Group) =>
                    isActive(g) && cands(g).length ? (
                      <Select
                        size="small"
                        style={{ width: '100%' }}
                        allowClear
                        placeholder={sameBill ? t('transfer.keepLine') : t('transfer.pickLine')}
                        value={s.cfgs[g.key]?.toSeq}
                        onChange={(v?: number) => setCfg(g.key, { toSeq: v })}
                        popupMatchSelectWidth={false}
                        options={cands(g).map((l) => ({
                          value: l.seq,
                          label: t('transfer.lineNo', { n: l.seq }),
                          disabled: lengthBad(l, g, modeOf(s, g)),
                          line: l,
                        }))}
                        optionRender={(o) => (
                          <span>
                            {t('transfer.lineOption', {
                              seq: o.data.line.seq,
                              material: matLabel(o.data.line.materialCode),
                              attach: str(o.data.line.attachNo) || '—',
                              length: o.data.line.length || '—',
                            })}
                            {o.data.disabled ? <span className="muted"> · {t('transfer.lengthDiff')}</span> : null}
                          </span>
                        )}
                      />
                    ) : (
                      none
                    ),
                },
                {
                  ...dstHead(t('transfer.match')),
                  key: 'dk',
                  width: 160,
                  render: (_: unknown, g: Group) => {
                    if (!isActive(g) || !tf.toBill) return none;
                    const m = matchState(g);
                    if (m === 'OK') return <Tag color="success">{t('transfer.matchOk')}</Tag>;
                    if (m === 'PICK_SEQ') return <Tag color="warning">{t('transfer.lineIssue.PICK_SEQ', { n: cands(g).length })}</Tag>;
                    return <Tag color="error">{t(`transfer.lineIssue.${m}`)}</Tag>;
                  },
                },
              ],
            },
          ]}
        />
      </section>

      {/* ④ 转移原因：直接转移、立即生效，原因必填，记入转移单与操作痕迹 */}
      <section className={`sn-tf-reason${reasonMissing ? ' bad' : ''}`}>
        <div className="sn-tf-lines-t">
          <span style={{ color: '#b42318', marginRight: 4 }}>*</span>
          {t('transfer.reason')}
        </div>
        <Input.TextArea
          rows={2}
          maxLength={500}
          showCount
          placeholder={t('transfer.reasonPlaceholder')}
          value={tf.reason}
          onChange={(e) => setTf({ reason: e.target.value }, false)}
          onBlur={() => setReasonTouched(true)}
        />
        {reasonMissing ? <div className="sn-hint sn-warn-text">{t('transfer.reasonRequired')}</div> : null}
      </section>
    </Modal>
  );
}
