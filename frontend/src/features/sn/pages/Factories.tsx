import { ReloadOutlined, WarningFilled } from '@ant-design/icons';
import { Alert, Button, Form, InputNumber, Input, Modal, Radio, Select, Switch, Table, Tag, Tooltip } from 'antd';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { snApi, type Row } from '../../../api/sn';
import { PageHead } from '../components/PageHead';
import { useAccount } from '../lib/auth';
import { useFactories } from '../lib/factories';
import { batchResultLabel, cfgStatusLabel } from '../lib/labels';
import { run, showError, showSuccess } from '../lib/notify';
import { bool, num, obj, rows, str } from '../lib/row';
import { tzLabel } from '../lib/time';

const CFG_COLOR: Record<string, string> = { OK: 'success', FAILED: 'error', UNCONFIGURED: 'default' };
/** UTC-12:00 … UTC+14:00，按 15 分钟。 */
const TZ_OPTIONS = Array.from({ length: (840 + 720) / 15 + 1 }, (_, i) => -720 + i * 15);

interface MesForm {
  mesUrl: string;
  mesAuthType: string;
  mesCredential: string;
  mesGzip: boolean;
  batchSize: number;
  pushTimeoutSec: number;
  pushConcurrency: number;
  tzOffsetMin: number;
}

export default function Factories() {
  const { t } = useTranslation('sn');
  const account = useAccount();
  const { factories, reload } = useFactories();
  const [syncing, setSyncing] = useState(false);
  const [busy, setBusy] = useState('');

  async function sync(silent: boolean) {
    setSyncing(true);
    try {
      const r = await snApi.syncFactories();
      if (!silent) {
        showSuccess(
          t('factories.synced', { total: str(r.total), inserted: str(r.inserted), renamed: str(r.renamed), disabled: str(r.disabled) }),
        );
      }
    } catch (e) {
      if (!silent) showError(e);
    } finally {
      setSyncing(false);
      void reload();
    }
  }
  // 打开页签先显示现有列表，再自动同步一次（失败只保留现有列表）
  useEffect(() => {
    void sync(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ---------------- MES 对接配置
  const [editing, setEditing] = useState<Row | null>(null);
  const [form, setForm] = useState<MesForm>({
    mesUrl: '', mesAuthType: 'TOKEN', mesCredential: '', mesGzip: false, batchSize: 5000, pushTimeoutSec: 60, pushConcurrency: 2, tzOffsetMin: 480,
  });
  const [testing, setTesting] = useState(false);
  const [saving, setSaving] = useState(false);
  const [testResult, setTestResult] = useState<Row | null>(null);

  function openConfig(r: Row) {
    setEditing(r);
    setTestResult(null);
    setForm({
      mesUrl: str(r.mesUrl),
      mesAuthType: str(r.mesAuthType) || 'TOKEN',
      mesCredential: '',
      mesGzip: bool(r.mesGzip),
      batchSize: r.batchSize === null || r.batchSize === undefined ? 5000 : num(r.batchSize),
      pushTimeoutSec: r.pushTimeoutSec === null || r.pushTimeoutSec === undefined ? 60 : num(r.pushTimeoutSec),
      pushConcurrency: r.pushConcurrency === null || r.pushConcurrency === undefined ? 2 : num(r.pushConcurrency),
      tzOffsetMin: r.tzOffsetMin === null || r.tzOffsetMin === undefined ? 480 : num(r.tzOffsetMin),
    });
  }
  /** 凭证留空 = 沿用已存的（不回显）；无认证不发凭证（后端清空已存的）。 */
  const payload = (): Row => ({
    ...form,
    mesCredential: (form.mesAuthType !== 'NONE' && form.mesCredential) || undefined,
  });
  async function test() {
    if (!editing) return;
    setTesting(true);
    try {
      setTestResult(await snApi.testMes(str(editing.factoryCode), payload()));
      void reload();
    } catch (e) {
      showError(e);
    } finally {
      setTesting(false);
    }
  }
  async function save() {
    if (!editing) return;
    setSaving(true);
    try {
      await snApi.saveMesConfig(str(editing.factoryCode), payload());
      showSuccess(t('common.saved'));
      setEditing(null);
      void reload();
    } catch (e) {
      showError(e);
    } finally {
      setSaving(false);
    }
  }

  // ---------------- 开启 / 关闭「对接 MES」：先弹出受影响枚数 / 阻塞项，确认后再切
  const [toggle, setToggle] = useState<Row | null>(null);
  const b = obj(toggle?.blockers);
  const blocked = num(b.tasks) > 0 || num(b.batchTotal) > 0 || num(b.reassignQty) > 0;
  const canToggle = !!toggle && (bool(toggle.mesEnabled) ? !blocked : bool(toggle.canEnable));
  async function openToggle(r: Row) {
    setBusy(str(r.factoryCode));
    const p = await run(() => snApi.mesTogglePreview(str(r.factoryCode)));
    setBusy('');
    if (p) setToggle(p);
  }
  async function doToggle() {
    if (!toggle) return;
    setBusy(str(toggle.factoryCode));
    const on = bool(toggle.mesEnabled);
    const ok = await run(() => snApi.toggleMes(str(toggle.factoryCode), !on));
    setBusy('');
    if (ok) {
      showSuccess(on ? t('factories.mesOff') : t('factories.mesOn'));
      setToggle(null);
      void reload();
    }
  }

  return (
    <div className="page">
      <PageHead title={t('factories.title')} desc={t('factories.desc')}>
        <Button type="primary" icon={<ReloadOutlined />} loading={syncing} onClick={() => void sync(false)}>
          {t('factories.sync')}
        </Button>
      </PageHead>
      <div className="surface">
        <Table<Row>
          size="small"
          rowKey={(r) => str(r.factoryCode)}
          pagination={false}
          dataSource={factories}
          rowClassName={(r) => (bool(r.enabled) ? '' : 'muted')}
          columns={[
            { title: t('factories.code'), dataIndex: 'factoryCode', width: 150 },
            { title: t('factories.name'), dataIndex: 'factoryName', ellipsis: true },
            {
              title: t('factories.k3Status'),
              width: 100,
              render: (_, r) => (
                <Tag color={bool(r.enabled) ? 'success' : 'default'}>
                  {bool(r.enabled) ? t('factories.enabled') : t('factories.disabled')}
                </Tag>
              ),
            },
            {
              title: t('common.mes'),
              width: 110,
              render: (_, r) => (
                <Switch checked={bool(r.mesEnabled)} loading={busy === str(r.factoryCode)} onClick={() => void openToggle(r)} />
              ),
            },
            {
              title: t('factories.cfgStatus'),
              render: (_, r) => (
                <>
                  <Tag color={CFG_COLOR[str(r.cfgStatus)] ?? 'default'}>{cfgStatusLabel(r.cfgStatus)}</Tag>
                  {r.cfgMessage ? (
                    <Tooltip title={str(r.cfgMessage)}>
                      <WarningFilled style={{ color: '#b42318', marginLeft: 6 }} />
                    </Tooltip>
                  ) : null}
                  {r.cfgCheckedAt ? (
                    <div className="muted">
                      {t('factories.checkedAt')} {account.fmt(r.cfgCheckedAt)}
                    </div>
                  ) : null}
                </>
              ),
            },
            {
              title: t('factories.mesUrl'),
              ellipsis: true,
              render: (_, r) => (r.mesUrl ? <span className="sn-mono">{str(r.mesUrl)}</span> : <span className="muted">—</span>),
            },
            { title: t('factories.batchSize'), align: 'right', width: 100, render: (_, r) => <span className="num">{str(r.batchSize)}</span> },
            { title: t('factories.tz'), width: 110, render: (_, r) => tzLabel(num(r.tzOffsetMin)) },
            { title: t('factories.boundUsers'), align: 'right', width: 110, render: (_, r) => <span className="num">{str(r.boundUsers) || '0'}</span> },
            {
              title: t('common.actions'),
              width: 110,
              align: 'right',
              render: (_, r) => (
                <Button type="link" style={{ padding: 0 }} onClick={() => openConfig(r)}>
                  {t('factories.config')}
                </Button>
              ),
            },
          ]}
        />
        <p className="muted" style={{ margin: '8px 0 0', lineHeight: 1.5 }}>
          {t('factories.disableHint')}
        </p>
      </div>

      <Modal
        open={!!editing}
        width={620}
        title={t('factories.configTitle', { name: str(editing?.factoryName), code: str(editing?.factoryCode) })}
        onCancel={() => setEditing(null)}
        footer={[
          <Button key="c" onClick={() => setEditing(null)}>{t('common.cancel')}</Button>,
          <Button key="t" loading={testing} onClick={() => void test()}>{t('factories.test')}</Button>,
          <Button key="s" type="primary" loading={saving} onClick={() => void save()}>{t('common.save')}</Button>,
        ]}
      >
        {bool(editing?.mesEnabled) ? (
          <Alert type="warning" showIcon title={t('factories.enabledEditHint')} style={{ marginBottom: 12 }} />
        ) : null}
        <Form labelCol={{ flex: '130px' }} labelWrap>
          <Form.Item label={t('factories.mesUrl')} required>
            <Input
              placeholder="https://mes.example.com/sn/batches"
              value={form.mesUrl}
              onChange={(e) => setForm({ ...form, mesUrl: e.target.value.trim() })}
            />
          </Form.Item>
          <Form.Item label={t('factories.authType')} required extra={form.mesAuthType === 'NONE' ? t('factories.authNoneHint') : undefined}>
            <Radio.Group
              optionType="button"
              value={form.mesAuthType}
              onChange={(e) => setForm({ ...form, mesAuthType: String(e.target.value) })}
              options={['TOKEN', 'HMAC', 'NONE'].map((v) => ({ value: v, label: t(`factories.auth${v}`) }))}
            />
          </Form.Item>
          {form.mesAuthType !== 'NONE' ? (
            <Form.Item label={t('factories.credential')} required={!bool(editing?.credentialSet)} extra={t('factories.credentialHint')}>
              <Input.Password
                autoComplete="new-password"
                placeholder={bool(editing?.credentialSet) ? t('factories.credentialKeep') : ''}
                value={form.mesCredential}
                onChange={(e) => setForm({ ...form, mesCredential: e.target.value })}
              />
            </Form.Item>
          ) : null}
          <Form.Item label={t('factories.gzip')} extra={t('factories.gzipHint')}>
            <Switch checked={form.mesGzip} onChange={(v) => setForm({ ...form, mesGzip: v })} />
          </Form.Item>
          <Form.Item label={t('factories.batchSize')}>
            <InputNumber min={1} max={50000} step={500} value={form.batchSize} onChange={(v) => setForm({ ...form, batchSize: v ?? 1 })} />
            <span className="muted" style={{ marginLeft: 6 }}>{t('factories.batchSizeHint')}</span>
          </Form.Item>
          <Form.Item label={t('factories.timeout')}>
            <InputNumber min={5} max={600} value={form.pushTimeoutSec} onChange={(v) => setForm({ ...form, pushTimeoutSec: v ?? 5 })} />
            <span className="muted" style={{ marginLeft: 6 }}>{t('factories.seconds')}</span>
          </Form.Item>
          <Form.Item label={t('factories.concurrency')}>
            <InputNumber min={1} max={20} value={form.pushConcurrency} onChange={(v) => setForm({ ...form, pushConcurrency: v ?? 1 })} />
            <span className="muted" style={{ marginLeft: 6 }}>{t('factories.concurrencyHint')}</span>
          </Form.Item>
          <Form.Item label={t('factories.tz')} extra={t('factories.tzHint')}>
            <Select
              showSearch={{ optionFilterProp: 'label' }}
              style={{ width: 200 }}
              value={form.tzOffsetMin}
              onChange={(v: number) => setForm({ ...form, tzOffsetMin: v })}
              options={TZ_OPTIONS.map((o) => ({ value: o, label: tzLabel(o) }))}
            />
          </Form.Item>
          {testResult ? (
            <Form.Item label={t('factories.testResult')}>
              <Alert
                type={bool(testResult.ok) ? 'success' : 'error'}
                showIcon
                title={
                  bool(testResult.ok)
                    ? t('factories.testOk', { batch: str(testResult.batchNo) })
                    : t('factories.testFail', { message: str(testResult.message) })
                }
              />
            </Form.Item>
          ) : null}
        </Form>
      </Modal>

      <Modal
        open={!!toggle}
        width={560}
        title={bool(toggle?.mesEnabled) ? t('factories.disableMes') : t('factories.enableMes')}
        onCancel={() => setToggle(null)}
        footer={[
          <Button key="c" onClick={() => setToggle(null)}>{t('common.cancel')}</Button>,
          <Button key="o" type="primary" disabled={!canToggle} loading={busy === str(toggle?.factoryCode)} onClick={() => void doToggle()}>
            {t('common.confirm')}
          </Button>,
        ]}
      >
        {toggle && !bool(toggle.mesEnabled) ? (
          !bool(toggle.canEnable) ? (
            <Alert type="error" showIcon title={t('factories.enableBlocked')} />
          ) : (
            <p>{t('factories.enableEffect', { n: str(toggle.allocated) })}</p>
          )
        ) : null}
        {toggle && bool(toggle.mesEnabled) ? (
          blocked ? (
            <>
              <Alert type="error" showIcon title={t('factories.disableBlocked')} style={{ marginBottom: 12 }} />
              <ul style={{ margin: '0 0 8px', paddingLeft: 20, lineHeight: 1.8 }}>
                {num(b.tasks) ? <li>{t('factories.blockTasks', { n: str(b.tasks) })}</li> : null}
                {num(b.batchTotal) ? <li>{t('factories.blockBatches', { n: str(b.batchTotal), qty: str(b.batchQty) })}</li> : null}
                {num(b.reassignQty) ? <li>{t('factories.blockReassign', { n: str(b.reassignQty) })}</li> : null}
              </ul>
              {rows(b.batches).length ? (
                <Table<Row>
                  size="small"
                  pagination={false}
                  scroll={{ y: 220 }}
                  rowKey={(r) => str(r.batchNo)}
                  dataSource={rows(b.batches)}
                  columns={[
                    { title: t('common.batchNo'), dataIndex: 'batchNo' },
                    { title: t('common.qty'), dataIndex: 'qty', width: 80, align: 'right' },
                    { title: t('common.status'), width: 100, render: (_, r) => batchResultLabel(r.result) },
                  ]}
                />
              ) : null}
              <p className="muted">{t('factories.disableBlockedHint')}</p>
            </>
          ) : (
            <p>{t('factories.disableEffect', { n: str(toggle.unbatched) })}</p>
          )
        ) : null}
      </Modal>
    </div>
  );
}
