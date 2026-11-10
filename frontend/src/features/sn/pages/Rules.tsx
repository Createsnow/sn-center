import { PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Drawer, Form, Input, InputNumber, Modal, Radio, Select, Switch, Table, Tag, Tooltip } from 'antd';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { snApi, type Row } from '../../../api/sn';
import { FactorySelect } from '../components/FactorySelect';
import { PageHead } from '../components/PageHead';
import { Pager } from '../components/Pager';
import { useAccount } from '../lib/auth';
import { confirm } from '../lib/dialogs';
import { useFactories } from '../lib/factories';
import { BASES, CHARSETS, errorText } from '../lib/labels';
import { run, showError, showSuccess } from '../lib/notify';
import { bool, num, obj, rows, str } from '../lib/row';

interface RuleForm {
  ruleCode: string;
  ruleName: string;
  factoryCode: string;
  pi: string;
  prefix: string;
  suffix: string;
  base: number;
  seqLen: number;
  charset: string;
  withLength: boolean;
  lengthPad: number;
  sepBefore: string;
  sepAfter: string;
  withUnit: boolean;
}
/** 规则库流水位数默认 5（自定义规则默认 4，在生成页）。 */
const EMPTY: RuleForm = {
  ruleCode: '', ruleName: '', factoryCode: '', pi: '', prefix: '', suffix: '', base: 10, seqLen: 5, charset: '',
  withLength: true, lengthPad: 0, sepBefore: '', sepAfter: '', withUnit: false,
};

/** 规则库：规则只绑定 PI；点规则行在右侧抽屉看示例号。 */
export default function Rules() {
  const { t } = useTranslation('sn');
  const account = useAccount();
  const { factories } = useFactories();
  const [q, setQ] = useState('');
  const [submitted, setSubmitted] = useState('');
  const [piFilter, setPiFilter] = useState('');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const list = useQuery({ queryKey: ['sn', 'rules', submitted], queryFn: () => snApi.rules({ q: submitted }) });
  const all = list.data ?? [];
  const piOf = (r: Row) => {
    const rule = obj(r.rule);
    return str(rule.categoryScope) === 'PI' ? str(rule.categoryValue) : '';
  };
  const piValues = [...new Set(all.map(piOf))].filter(Boolean).sort();
  const shown = piFilter ? all.filter((r) => piOf(r) === piFilter) : all;
  const maxPage = Math.max(1, Math.ceil(shown.length / pageSize));
  const cur = Math.min(page, maxPage);
  const paged = shown.slice((cur - 1) * pageSize, cur * pageSize);
  const factoryName = (code: string) => {
    const f = factories.find((x) => str(x.factoryCode) === code);
    return f ? `${str(f.factoryName)}（${code}）` : code;
  };
  const editable = (r: Row) => {
    const fc = str(obj(r.rule).factoryCode);
    return account.isAdmin || (!!fc && account.canWrite(fc));
  };
  const deletable = (r: Row) => !bool(r.used) && !rows(r.pis).length && !rows(obj(r.bindings).pi).length;

  const [dialog, setDialog] = useState(false);
  const [editing, setEditing] = useState<Row | null>(null);
  const [form, setForm] = useState<RuleForm>(EMPTY);
  const [saving, setSaving] = useState(false);
  const [sample, setSample] = useState<Row | null>(null);
  const [sampleError, setSampleError] = useState('');
  const [sampleLength, setSampleLength] = useState('');
  const [sampleUnit, setSampleUnit] = useState('');
  const spec = (f = form) => ({
    prefix: f.prefix, suffix: f.suffix, base: f.base, seqLen: f.seqLen, charset: f.charset, withLength: f.withLength,
    lengthPad: f.lengthPad || 0, sepBefore: f.sepBefore, sepAfter: f.sepAfter, withUnit: f.withUnit,
  });

  useEffect(() => {
    if (!dialog) return;
    const timer = window.setTimeout(() => {
      snApi
        .previewRule({ ...spec(), length: sampleLength, unit: sampleUnit })
        .then((r) => {
          setSample(r);
          setSampleError('');
        })
        .catch((e: unknown) => {
          setSample(null);
          setSampleError(errorText(e));
        });
    }, 250);
    return () => window.clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [form, sampleLength, sampleUnit, dialog]);

  const c = editing ? obj(editing.current) : null;
  let editHint = '';
  if (c && editing) {
    const charset = form.charset || CHARSETS[form.base];
    const len = form.withLength;
    const changed =
      str(c.prefix) !== form.prefix || str(c.suffix) !== form.suffix || num(c.base) !== form.base || num(c.seqLen) !== form.seqLen ||
      str(c.charset) !== charset || bool(c.withLength) !== len || bool(c.withUnit) !== form.withUnit ||
      num(c.lengthPad) !== (len ? form.lengthPad || 0 : 0) ||
      str(c.lengthSepBefore) !== (len ? form.sepBefore : '') || str(c.lengthSepAfter) !== (len ? form.sepAfter : '');
    editHint = !changed ? (bool(c.used) ? 'USED' : '') : bool(c.used) ? 'NEW_VERSION' : 'IN_PLACE';
  }

  function openCreate() {
    setEditing(null);
    setForm({ ...EMPTY, factoryCode: account.boundFactory });
    setDialog(true);
  }
  function openEdit(r: Row) {
    setEditing(r);
    const v = obj(r.current);
    setForm({
      ...EMPTY,
      ruleName: str(obj(r.rule).ruleName),
      pi: piOf(r),
      prefix: str(v.prefix),
      suffix: str(v.suffix),
      base: num(v.base),
      seqLen: num(v.seqLen),
      withLength: bool(v.withLength),
      lengthPad: num(v.lengthPad),
      sepBefore: str(v.lengthSepBefore),
      sepAfter: str(v.lengthSepAfter),
      withUnit: bool(v.withUnit),
      charset: str(v.charset) === CHARSETS[num(v.base)] ? '' : str(v.charset),
    });
    setDialog(true);
  }
  async function save() {
    setSaving(true);
    try {
      const category = { categoryScope: 'PI', categoryValue: form.pi };
      if (editing) {
        const r = await snApi.updateRule(str(obj(editing.rule).id), { ruleName: form.ruleName, ...spec(), ...category });
        showSuccess(t(`rules.result${str(r.action)}`, { v: str(obj(obj(r.rule).rule).currentVersion) }));
      } else {
        await snApi.createRule({ ruleCode: form.ruleCode, ruleName: form.ruleName, factoryCode: form.factoryCode || null, ...spec(), ...category });
        showSuccess(t('common.saved'));
      }
      setDialog(false);
      void list.refetch();
    } catch (e) {
      showError(e);
    } finally {
      setSaving(false);
    }
  }
  async function remove(r: Row) {
    const rule = obj(r.rule);
    if (!(await confirm(t('rules.deleteConfirm', { code: str(rule.ruleCode), name: str(rule.ruleName) }), t('common.delete')))) return;
    if (await run(() => snApi.deleteRule(str(rule.id)))) {
      showSuccess(t('rules.deleted', { code: str(rule.ruleCode) }));
      void list.refetch();
    }
  }

  // ---------------- 示例号抽屉
  const [detail, setDetail] = useState<Row | null>(null);
  const dv = detail ? obj(detail.current) : null;
  const detailQ = useQuery({
    queryKey: ['sn', 'ruleSample', str(dv?.id)],
    enabled: !!dv && !!dv.id,
    queryFn: () =>
      snApi.previewRule({
        prefix: str(dv?.prefix), suffix: str(dv?.suffix), base: num(dv?.base), seqLen: num(dv?.seqLen), charset: str(dv?.charset),
        withLength: bool(dv?.withLength), lengthPad: num(dv?.lengthPad), sepBefore: str(dv?.lengthSepBefore),
        sepAfter: str(dv?.lengthSepAfter), withUnit: bool(dv?.withUnit),
      }),
  });
  const samples = rows(detailQ.data?.samples);
  const detailItems = samples.map((s, i) => ({
    ...s,
    max: i === samples.length - 1 && num(detailQ.data?.maxSeq) > 5,
    length: bool(dv?.withLength) ? str(detailQ.data?.sampleLength) : '',
    unit: bool(dv?.withUnit) ? str(detailQ.data?.sampleUnit) : '',
  }));

  const set = <K extends keyof RuleForm>(k: K, v: RuleForm[K]) => setForm({ ...form, [k]: v });

  return (
    <div className="page">
      <PageHead title={t('rules.title')} desc={t('rules.desc')}>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>{t('rules.create')}</Button>
      </PageHead>
      <div className="surface">
        <div className="toolbar">
          <Select
            allowClear
            showSearch={{ optionFilterProp: 'label' }}
            placeholder={t('rules.filterPi')}
            value={piFilter || undefined}
            options={piValues.map((v) => ({ value: v, label: v }))}
            onChange={(v?: string) => {
              setPiFilter(v ?? '');
              setPage(1);
            }}
          />
          <Input
            allowClear
            prefix={<SearchOutlined />}
            placeholder={t('rules.search')}
            value={q}
            onChange={(e) => {
              setQ(e.target.value);
              if (!e.target.value) setSubmitted('');
            }}
            onPressEnter={() => {
              setPage(1);
              setSubmitted(q);
            }}
          />
          <Button
            onClick={() => {
              setPage(1);
              setSubmitted(q);
              void list.refetch();
            }}
          >
            {t('common.search')}
          </Button>
        </div>
        <Table<Row>
          size="small"
          loading={list.isFetching}
          pagination={false}
          rowKey={(r) => str(obj(r.rule).id)}
          dataSource={paged}
          onRow={(r) => ({ style: { cursor: 'pointer' }, onClick: () => setDetail(r) })}
          columns={[
            { title: t('rules.name'), ellipsis: true, render: (_, r) => str(obj(r.rule).ruleName) },
            { title: t('rules.version'), width: 64, render: (_, r) => `v${str(obj(r.rule).currentVersion)}` },
            { title: 'PI', ellipsis: true, render: (_, r) => (piOf(r) ? piOf(r) : <span className="muted">—</span>) },
            { title: t('rules.base'), width: 72, render: (_, r) => str(obj(r.current).base) },
            { title: t('rules.seqCol'), width: 88, render: (_, r) => str(obj(r.current).seqLen) },
            { title: t('rules.withLength'), width: 80, render: (_, r) => (bool(obj(r.current).withLength) ? t('common.yes') : t('common.no')) },
            { title: t('rules.withUnit'), width: 80, render: (_, r) => (bool(obj(r.current).withUnit) ? t('common.yes') : t('common.no')) },
            {
              title: t('rules.used'),
              width: 80,
              render: (_, r) => <Tag color={bool(r.used) ? 'success' : 'default'}>{bool(r.used) ? t('common.yes') : t('common.no')}</Tag>,
            },
            {
              title: t('rules.factory'),
              ellipsis: true,
              render: (_, r) => {
                const fc = str(obj(r.rule).factoryCode);
                return !fc ? <Tag>{t('rules.ownerGlobal')}</Tag> : factoryName(fc);
              },
            },
            {
              title: t('common.actions'),
              width: 130,
              fixed: 'right',
              render: (_, r) => (
                <span onClick={(e) => e.stopPropagation()}>
                  <Tooltip title={editable(r) ? '' : t('rules.globalReadonly')}>
                    <Button type="link" style={{ padding: 0 }} disabled={!editable(r)} onClick={() => openEdit(r)}>
                      {t('common.edit')}
                    </Button>
                  </Tooltip>
                  <Tooltip title={editable(r) && deletable(r) ? '' : editable(r) ? t('rules.deleteBlocked') : t('rules.globalReadonly')}>
                    <Button type="link" danger style={{ padding: 0, marginLeft: 6 }} disabled={!editable(r) || !deletable(r)} onClick={() => void remove(r)}>
                      {t('common.delete')}
                    </Button>
                  </Tooltip>
                </span>
              ),
            },
          ]}
        />
        <Pager
          total={shown.length}
          page={cur}
          pageSize={pageSize}
          onPage={setPage}
          onSize={(n) => {
            setPageSize(n);
            setPage(1);
          }}
        />
      </div>

      <Modal
        open={dialog}
        width={660}
        title={editing ? t('rules.editTitle', { code: str(obj(editing.rule).ruleCode) }) : t('rules.create')}
        onCancel={() => setDialog(false)}
        onOk={() => void save()}
        okText={t('common.save')}
        cancelText={t('common.cancel')}
        confirmLoading={saving}
        destroyOnHidden
      >
        {editing && editHint ? (
          <Alert type={editHint === 'NEW_VERSION' ? 'warning' : 'info'} showIcon title={t(`rules.hint${editHint}`)} style={{ marginBottom: 12 }} />
        ) : null}
        <Form labelCol={{ flex: '120px' }} labelWrap>
          {!editing ? (
            <Form.Item label={t('rules.code')} required>
              <Input placeholder={t('rules.codeHint')} value={form.ruleCode} onChange={(e) => set('ruleCode', e.target.value.trim())} />
            </Form.Item>
          ) : null}
          <Form.Item label={t('rules.name')} required>
            <Input maxLength={128} value={form.ruleName} onChange={(e) => set('ruleName', e.target.value)} />
          </Form.Item>
          {!editing ? (
            <Form.Item label={t('rules.owner')} required={!account.isAdmin} extra={account.isAdmin ? t('rules.ownerHint') : t('rules.ownerOperatorHint')}>
              <FactorySelect
                value={form.factoryCode}
                onChange={(v) => set('factoryCode', v)}
                scoped
                clearable={account.isAdmin}
                disabled={!!account.boundFactory}
                placeholder={account.isAdmin ? t('rules.ownerGlobal') : t('rules.owner')}
              />
            </Form.Item>
          ) : null}
          <Form.Item label="PI" required extra={t('rules.piHint')}>
            <Input maxLength={64} value={form.pi} onChange={(e) => set('pi', e.target.value.trim())} />
          </Form.Item>
          <Form.Item label={t('rules.prefix')} required extra={t('rules.prefixHint')}>
            <Input className="sn-mono" value={form.prefix} onChange={(e) => set('prefix', e.target.value.trim())} />
          </Form.Item>
          <Form.Item label={t('rules.base')}>
            <Radio.Group
              optionType="button"
              value={form.base}
              onChange={(e) => setForm({ ...form, base: Number(e.target.value), charset: '' })}
              options={BASES.map((b) => ({ value: b, label: String(b) }))}
            />
          </Form.Item>
          <Form.Item label={t('rules.seqLen')}>
            <InputNumber min={1} max={12} value={form.seqLen} onChange={(v) => set('seqLen', v ?? 1)} />
          </Form.Item>
          <Form.Item label={t('rules.charset')} extra={t('rules.charsetHint')}>
            <Input className="sn-mono" placeholder={CHARSETS[form.base]} value={form.charset} onChange={(e) => set('charset', e.target.value.trim())} />
          </Form.Item>
          <Form.Item label={t('rules.withLength')}>
            <Switch checked={form.withLength} onChange={(v) => set('withLength', v)} />
            <span className="muted" style={{ marginLeft: 6 }}>{t('rules.withLengthHint')}</span>
          </Form.Item>
          {form.withLength ? (
            <>
              <Form.Item label={t('rules.sepBefore')}>
                <Input className="sn-mono" style={{ width: 160 }} maxLength={16} placeholder={t('common.optional')} value={form.sepBefore} onChange={(e) => set('sepBefore', e.target.value.trim())} />
                <span className="muted" style={{ marginLeft: 6 }}>{t('rules.sepHint')}</span>
              </Form.Item>
              <Form.Item label={t('rules.lengthPad')} extra={t('rules.lengthPadHint')}>
                <InputNumber min={0} max={32} value={form.lengthPad} onChange={(v) => set('lengthPad', v ?? 0)} />
              </Form.Item>
              <Form.Item label={t('rules.sepAfter')}>
                <Input className="sn-mono" style={{ width: 160 }} maxLength={16} placeholder={t('common.optional')} value={form.sepAfter} onChange={(e) => set('sepAfter', e.target.value.trim())} />
              </Form.Item>
            </>
          ) : null}
          <Form.Item label={t('rules.withUnit')}>
            <Switch checked={form.withUnit} onChange={(v) => set('withUnit', v)} />
            <span className="muted" style={{ marginLeft: 6 }}>{t('rules.withUnitHint')}</span>
          </Form.Item>
          <Form.Item label={t('rules.suffix')} extra={t('rules.suffixHint')}>
            <Input className="sn-mono" placeholder={t('common.optional')} value={form.suffix} onChange={(e) => set('suffix', e.target.value.trim())} />
          </Form.Item>
          {form.withLength ? (
            <Form.Item label={t('rules.sampleLength')}>
              <Input style={{ width: 160 }} placeholder="100" value={sampleLength} onChange={(e) => setSampleLength(e.target.value.trim())} />
            </Form.Item>
          ) : null}
          {form.withUnit ? (
            <Form.Item label={t('rules.sampleUnit')}>
              <Input style={{ width: 160 }} placeholder="M" value={sampleUnit} onChange={(e) => setSampleUnit(e.target.value.trim())} />
            </Form.Item>
          ) : null}
          <Form.Item label={t('rules.sample')}>
            {sample ? (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                <div className="muted">{str(sample.format)}</div>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 12px' }}>
                  {rows(sample.samples).map((s, i, a) => (
                    <span key={str(s.seqDec)} className="sn-mono" style={i === a.length - 1 && num(sample.maxSeq) > 5 ? { color: '#c27803' } : undefined}>
                      {str(s.sn)}
                    </span>
                  ))}
                </div>
                <div className="muted">{t('rules.maxSeq', { n: num(sample.maxSeq).toLocaleString() })}</div>
              </div>
            ) : (
              <span style={{ color: '#b42318', fontSize: 12 }}>{sampleError}</span>
            )}
          </Form.Item>
        </Form>
      </Modal>

      <Drawer
        open={!!detail}
        size={900}
        onClose={() => setDetail(null)}
        title={
          detail ? (
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <b>{str(obj(detail.rule).ruleName)}</b>
              <Tag>v{str(obj(detail.rule).currentVersion)}</Tag>
              {piOf(detail) ? <span className="muted">PI {piOf(detail)}</span> : null}
            </div>
          ) : null
        }
      >
        {dv ? <div className="muted" style={{ fontSize: 12, marginBottom: 12 }}>{str(dv.format)}</div> : null}
        <Table<Row & { max: boolean; length: string; unit: string }>
          size="small"
          loading={detailQ.isFetching}
          pagination={false}
          rowKey={(r) => str(r.seqDec)}
          dataSource={detailItems}
          locale={{ emptyText: detailQ.error ? errorText(detailQ.error) : t('common.empty') }}
          columns={[
            { title: t('rules.sampleSn'), render: (_, r) => <span className="sn-mono" style={r.max ? { color: '#c27803' } : undefined}>{str(r.sn)}</span> },
            { title: t('rules.prefix'), ellipsis: true, render: () => <span className="sn-mono">{str(dv?.prefix)}</span> },
            { title: t('rules.seqNo'), render: (_, r) => <span className="sn-mono">{str(r.seqText)}</span> },
            { title: t('rules.seqDigits'), width: 80, render: () => str(dv?.seqLen) },
            { title: t('rules.base'), width: 64, render: () => str(dv?.base) },
            { title: t('common.length'), width: 80, render: (_, r) => r.length || '—' },
            { title: t('common.unit'), width: 80, render: (_, r) => r.unit || '—' },
            {
              title: t('rules.suffixCol'),
              ellipsis: true,
              render: () => (dv?.suffix ? <span className="sn-mono">{str(dv.suffix)}</span> : <span className="muted">—</span>),
            },
          ]}
        />
        <div className="muted" style={{ fontSize: 12, marginTop: 10 }}>{t('rules.detailNote')}</div>
      </Drawer>
    </div>
  );
}
