import { ApiOutlined, QuestionCircleFilled, RollbackOutlined } from '@ant-design/icons';
import { Alert, Button, Collapse, Drawer, Form, Input, InputNumber, Modal, Select, Spin, Tag, Tooltip } from 'antd';
import { useEffect, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { snApi, type Row } from '../../../../api/sn';
import { useAccount } from '../../lib/auth';
import { showError, showSuccess } from '../../lib/notify';
import { bool, num, obj, rows, str, strs } from '../../lib/row';

const LCIDS = [2052, 1033, 3076];
const snake = (s: string) => s.replace(/[A-Z]/g, (c) => `_${c.toLowerCase()}`);
interface K3Form {
  serverUrl: string;
  acctId: string;
  appId: string;
  appSecret: string;
  username: string;
  lcid: number;
  loginTimeout: number;
  queryTimeout: number;
  viewTimeout: number;
  pageRetries: number;
  pageRetrySeconds: number;
}

/** 生产订单页「金蝶连接」（只有系统管理员）：保存时后端先用新参数连接测试，通过才保存；应用密钥不回显。 */
export function K3ConfigDrawer(props: { open: boolean; onClose: () => void; onSaved: (cfg: Row) => void }) {
  const { t } = useTranslation('sn');
  const account = useAccount();
  const [cfg, setCfg] = useState<Row | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<'' | 'test' | 'save' | 'reset'>('');
  const [result, setResult] = useState<Row | null>(null);
  const [form, setForm] = useState<K3Form>({
    serverUrl: '', acctId: '', appId: '', appSecret: '', username: '', lcid: 2052,
    loginTimeout: 30, queryTimeout: 120, viewTimeout: 60, pageRetries: 3, pageRetrySeconds: 10,
  });

  const fill = (c: Row) => {
    setCfg(c);
    setForm({
      serverUrl: str(c.serverUrl), acctId: str(c.acctId), appId: str(c.appId), appSecret: '', username: str(c.username),
      lcid: LCIDS.includes(num(c.lcid)) ? num(c.lcid) : 2052, loginTimeout: num(c.loginTimeout), queryTimeout: num(c.queryTimeout),
      viewTimeout: num(c.viewTimeout), pageRetries: num(c.pageRetries), pageRetrySeconds: num(c.pageRetrySeconds),
    });
  };
  useEffect(() => {
    if (!props.open) return;
    let live = true;
    snApi
      .k3Config()
      .then((c) => live && fill(c))
      .catch(showError)
      .finally(() => live && setLoading(false));
    return () => {
      live = false;
    };
  }, [props.open]);
  /** 关闭时复位，下次打开重新读取。 */
  const close = () => {
    setResult(null);
    setLoading(true);
    props.onClose();
  };

  const refreshCfg = async () => {
    const c = await snApi.k3Config().catch(() => ({}));
    setCfg((cur) => ({ ...(cur ?? {}), ...c }));
  };
  const payload = (): Row => ({ ...form, appSecret: form.appSecret.trim() || undefined });
  const historyText = (x: Row) => {
    if (bool(x.reset)) return t('k3.historyReset');
    if (bool(x.fromEnv)) return t('k3.historyFromEnv');
    const fields = strs(x.fields);
    if (!fields.length) return t('k3.historyNoChange');
    return t('k3.historyChanged', { fields: fields.map((f) => t(`k3.field.${snake(f)}`)).join(t('k3.sep')) });
  };

  async function test() {
    setBusy('test');
    try {
      setResult(await snApi.testK3Config(payload()));
      await refreshCfg();
    } catch (e) {
      showError(e);
    } finally {
      setBusy('');
    }
  }
  async function save() {
    setBusy('save');
    try {
      const c = await snApi.saveK3Config(payload());
      fill(c);
      setResult(null);
      showSuccess(t('k3.saved'));
      props.onSaved(c);
    } catch (e) {
      showError(e);
      await refreshCfg();
    } finally {
      setBusy('');
    }
  }
  async function reset() {
    const env = obj(cfg?.env);
    const items: [string, string][] = [
      [t('k3.serverUrl'), str(env.serverUrl) || '—'],
      [t('k3.acctId'), str(env.acctId) || '—'],
      [t('k3.appId'), str(env.appId) || '—'],
      [t('k3.username'), str(env.username) || '—'],
      [t('k3.appSecret'), bool(env.secretSet) ? t('k3.envSecretSet') : t('k3.envSecretMissing')],
    ];
    const ok = await new Promise<boolean>((resolve) =>
      Modal.confirm({
        title: t('k3.reset'),
        okText: t('k3.reset'),
        cancelText: t('common.cancel'),
        content: (
          <div>
            <p style={{ margin: '0 0 8px' }}>{t('k3.resetConfirm')}</p>
            <div style={{ display: 'grid', gridTemplateColumns: 'auto 1fr', gap: '4px 12px', fontSize: 13 }}>
              {items.flatMap(([k, v]) => [
                <span key={`k${k}`} className="muted">{k}</span>,
                <span key={`v${k}`} style={{ wordBreak: 'break-all' }}>{v}</span>,
              ])}
            </div>
          </div>
        ),
        onOk: () => resolve(true),
        onCancel: () => resolve(false),
      }),
    );
    if (!ok) return;
    setBusy('reset');
    try {
      const c = await snApi.resetK3Config();
      fill(c);
      setResult(null);
      showSuccess(t('k3.resetDone'));
      props.onSaved(c);
    } catch (e) {
      showError(e);
      await refreshCfg();
    } finally {
      setBusy('');
    }
  }

  const lbl = (text: string, tip?: string): ReactNode => (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
      {text}
      {tip ? (
        <Tooltip title={tip}>
          <QuestionCircleFilled style={{ color: '#94a3b8', cursor: 'help', fontSize: 13 }} />
        </Tooltip>
      ) : null}
    </span>
  );
  const last = cfg?.lastTest ? obj(cfg.lastTest) : null;
  const set = <K extends keyof K3Form>(k: K, v: K3Form[K]) => setForm({ ...form, [k]: v });
  const src = str(cfg?.source);

  return (
    <Drawer
      open={props.open}
      size={600}
      onClose={close}
      title={
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <span style={{ fontSize: 16, fontWeight: 600 }}>{t('k3.title')}</span>
          <Tooltip title={t('k3.desc')} placement="bottom">
            <QuestionCircleFilled style={{ color: 'var(--sn-muted)', cursor: 'help' }} />
          </Tooltip>
        </div>
      }
      footer={
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <Tooltip title={src === 'PAGE' ? t('k3.resetTip') : t('k3.resetDisabled')}>
            <span style={{ marginRight: 'auto' }}>
              <Button type="text" icon={<RollbackOutlined />} loading={busy === 'reset'} disabled={!!busy || src !== 'PAGE'} onClick={() => void reset()}>
                {t('k3.reset')}
              </Button>
            </span>
          </Tooltip>
          <Button onClick={close}>{t('common.cancel')}</Button>
          <Button icon={<ApiOutlined />} loading={busy === 'test'} disabled={!!busy} onClick={() => void test()}>{t('k3.test')}</Button>
          <Button type="primary" loading={busy === 'save'} disabled={!!busy} onClick={() => void save()}>{t('common.save')}</Button>
        </div>
      }
    >
      <Spin spinning={loading}>
        {cfg ? (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', marginBottom: 18, border: '1px solid var(--sn-line)', borderRadius: 8, background: '#f8fafc' }}>
            <div style={{ padding: '10px 14px' }}>
              <div className="muted" style={{ marginBottom: 6 }}>{t('k3.source')}</div>
              <Tooltip title={t(`k3.sourceHint${src}`)}>
                <Tag color={src === 'PAGE' ? 'blue' : 'default'}>{t(`k3.source${src}`)}</Tag>
              </Tooltip>
            </div>
            <div style={{ padding: '10px 14px', borderLeft: '1px solid var(--sn-line)' }}>
              <div className="muted" style={{ marginBottom: 6 }}>{t('k3.lastTest')}</div>
              {last ? (
                <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12 }}>
                  <Tag color={bool(last.ok) ? 'success' : 'error'}>{bool(last.ok) ? t('k3.testPassed') : t('k3.testFailedTag')}</Tag>
                  <span className="muted">{account.fmt(last.at, false)}</span>
                </div>
              ) : (
                <div className="muted">{t('k3.never')}</div>
              )}
            </div>
            <div style={{ padding: '10px 14px', borderLeft: '1px solid var(--sn-line)', fontSize: 12 }}>
              <div className="muted" style={{ marginBottom: 6 }}>{t('k3.updated')}</div>
              <div>{cfg.updatedAt ? `${account.fmt(cfg.updatedAt, false)} · ${str(cfg.updatedBy)}` : '—'}</div>
            </div>
          </div>
        ) : null}
        {bool(cfg?.secretUnreadable) ? <Alert type="error" showIcon title={t('k3.secretUnreadable')} style={{ marginBottom: 14 }} /> : null}
        {result ? (
          <Alert
            type={bool(result.ok) ? 'success' : 'error'}
            showIcon
            style={{ marginBottom: 14 }}
            title={
              bool(result.ok)
                ? t('k3.testOk', { orgs: str(result.orgs), sec: (num(result.elapsedMs) / 1000).toFixed(1) })
                : t('k3.testFail', { message: str(result.message) })
            }
          />
        ) : null}
        <Form layout="vertical">
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', columnGap: 16 }}>
            <Form.Item style={{ gridColumn: 'span 2' }} required label={lbl(t('k3.serverUrl'), t('k3.serverUrlHint'))}>
              <Input className="sn-mono" placeholder="http://10.0.0.1/k3cloud/" value={form.serverUrl} onChange={(e) => set('serverUrl', e.target.value.trim())} />
            </Form.Item>
            <Form.Item required label={lbl(t('k3.acctId'), t('k3.acctIdHint'))}>
              <Input className="sn-mono" value={form.acctId} onChange={(e) => set('acctId', e.target.value.trim())} />
            </Form.Item>
            <Form.Item required label={lbl(t('k3.username'), t('k3.usernameHint'))}>
              <Input value={form.username} onChange={(e) => set('username', e.target.value.trim())} />
            </Form.Item>
            <Form.Item style={{ gridColumn: 'span 2' }} required label={t('k3.appId')}>
              <Input className="sn-mono" value={form.appId} onChange={(e) => set('appId', e.target.value.trim())} />
            </Form.Item>
            <Form.Item required={!bool(cfg?.secretSet) || bool(cfg?.secretUnreadable)} label={lbl(t('k3.appSecret'), t('k3.secretHint'))}>
              <Input.Password
                autoComplete="new-password"
                placeholder={bool(cfg?.secretSet) && !bool(cfg?.secretUnreadable) ? t('k3.secretKeep') : ''}
                value={form.appSecret}
                onChange={(e) => set('appSecret', e.target.value)}
              />
            </Form.Item>
            <Form.Item label={t('k3.lcid')}>
              <Select value={form.lcid} onChange={(v: number) => set('lcid', v)} options={LCIDS.map((l) => ({ value: l, label: t(`k3.lcid${l}`) }))} />
            </Form.Item>
          </div>
        </Form>
        <Collapse
          ghost
          items={[
            {
              key: 'adv',
              label: (
                <span>
                  {t('k3.advanced')}
                  <span className="muted">{t('k3.advancedHint')}</span>
                </span>
              ),
              children: (
                <Form layout="vertical">
                  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', columnGap: 16 }}>
                    <Form.Item label={t('k3.loginTimeoutSec')}><InputNumber style={{ width: '100%' }} min={5} max={300} value={form.loginTimeout} onChange={(v) => set('loginTimeout', v ?? 5)} /></Form.Item>
                    <Form.Item label={t('k3.queryTimeoutSec')}><InputNumber style={{ width: '100%' }} min={10} max={600} value={form.queryTimeout} onChange={(v) => set('queryTimeout', v ?? 10)} /></Form.Item>
                    <Form.Item label={t('k3.viewTimeoutSec')}><InputNumber style={{ width: '100%' }} min={5} max={300} value={form.viewTimeout} onChange={(v) => set('viewTimeout', v ?? 5)} /></Form.Item>
                    <Form.Item label={t('k3.retryTimes')}><InputNumber style={{ width: '100%' }} min={0} max={10} value={form.pageRetries} onChange={(v) => set('pageRetries', v ?? 0)} /></Form.Item>
                    <Form.Item label={t('k3.retryInterval')}><InputNumber style={{ width: '100%' }} min={0} max={120} value={form.pageRetrySeconds} onChange={(v) => set('pageRetrySeconds', v ?? 0)} /></Form.Item>
                  </div>
                </Form>
              ),
            },
            {
              key: 'history',
              label: (
                <span>
                  {t('k3.history')}
                  {rows(cfg?.history).length ? <span className="muted">（{rows(cfg?.history).length}）</span> : null}
                </span>
              ),
              children: (
                <>
                  {rows(cfg?.history).length ? (
                    <ul style={{ margin: 0, paddingLeft: 18, lineHeight: 1.9, fontSize: 13 }}>
                      {rows(cfg?.history).map((x, i) => (
                        <li key={i}>
                          <span className="muted" style={{ marginRight: 8 }}>{account.fmt(x.at, false)} · {str(x.by)}</span>
                          <span>{historyText(x)}</span>
                        </li>
                      ))}
                    </ul>
                  ) : (
                    <div className="muted">{t('k3.historyEmpty')}</div>
                  )}
                  <div className="muted" style={{ marginTop: 8 }}>{t('k3.historyHint')}</div>
                </>
              ),
            },
          ]}
        />
      </Spin>
    </Drawer>
  );
}
