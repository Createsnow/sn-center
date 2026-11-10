import { PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Descriptions,
  Form,
  Input,
  Modal,
  Pagination,
  Radio,
  Select,
  Table,
  Tabs,
  Tag,
  Tooltip,
} from 'antd';
import { useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { snApi, type Row } from '../../../api/sn';
import { FactorySelect } from '../components/FactorySelect';
import { PageHead } from '../components/PageHead';
import { useAccount } from '../lib/auth';
import { confirm, copyText, prompt } from '../lib/dialogs';
import { useFactories } from '../lib/factories';
import { ROLE_CODES, roleLabel } from '../lib/labels';
import { run, showError, showSuccess, showWarning } from '../lib/notify';
import { bool, str, strs } from '../lib/row';
import { useFitRows } from '../lib/useFitRows';

const PWD = /^(?=.*[A-Za-z])(?=.*\d)[A-Za-z0-9!@#$&*-]{4,64}$/;
interface UserForm {
  empNo: string;
  name: string;
  role: string;
  factoryCode: string;
  factoryCodes: string[];
  password: string;
  ipWhitelist: string;
}
const EMPTY: UserForm = { empNo: '', name: '', role: 'operator', factoryCode: '', factoryCodes: [], password: '', ipWhitelist: '' };

export default function Users() {
  const { t } = useTranslation('sn');
  const account = useAccount();
  const { factories } = useFactories();
  const [tab, setTab] = useState<'page' | 'mes'>('page');
  const [f, setF] = useState({ q: '', role: '', status: '', factoryCode: '' });
  const [qText, setQText] = useState('');
  const [page, setPage] = useState(1);
  const box = useRef<HTMLDivElement>(null);
  const size = useFitRows(box);
  const list = useQuery({
    queryKey: ['sn', 'users', tab, f, page, size],
    queryFn: () =>
      snApi.users({ ...f, kind: tab, role: tab === 'mes' ? '' : f.role, page, pageSize: size }),
  });
  const items = list.data?.items ?? [];

  const [dialog, setDialog] = useState(false);
  const [editing, setEditing] = useState<Row | null>(null);
  const [form, setForm] = useState<UserForm>(EMPTY);
  const [saving, setSaving] = useState(false);
  const [secret, setSecret] = useState<{ empNo: string; value: string } | null>(null);
  const isMes = form.role === 'mes';
  const isScoped = form.role === 'operator' || form.role === 'auditor';
  const enabledFactories = factories.filter((x) => bool(x.enabled));
  const mesFactories = factories.filter((x) => bool(x.enabled) || str(x.factoryCode) === form.factoryCode);
  const isLocked = (r: Row) => !!r.lockedUntil && Date.parse(str(r.lockedUntil)) > Date.now();
  const reload = () => void list.refetch();

  const factoryText = (r: Row) => {
    const codes = strs(r.factoryCodes);
    if (!codes.length) return str(r.role) === 'admin' ? t('app.allFactories') : t('users.noFactory');
    const names = strs(r.factoryNames);
    return codes.map((c, i) => `${names[i] ?? ''}（${c}）`).join('、');
  };

  function openCreate() {
    setEditing(null);
    setForm({ ...EMPTY, role: tab === 'mes' ? 'mes' : 'operator' });
    setDialog(true);
  }
  function openEdit(r: Row) {
    setEditing(r);
    const role = str(r.role);
    setForm({
      empNo: str(r.empNo),
      name: str(r.name),
      role,
      factoryCode: str(r.factoryCode),
      factoryCodes: role === 'operator' || role === 'auditor' ? strs(r.factoryCodes) : [],
      password: '',
      ipWhitelist: str(r.ipWhitelist),
    });
    setDialog(true);
  }

  async function save() {
    if (!editing && !isMes && !PWD.test(form.password)) {
      showError(new Error(t('password.policy')));
      return;
    }
    if (isScoped && !form.factoryCodes.length) {
      showError(new Error(t('users.allFactoriesOption')));
      return;
    }
    setSaving(true);
    const binding = {
      factoryCode: isMes ? form.factoryCode || null : null,
      factoryCodes: isScoped ? form.factoryCodes : [],
    };
    const extra = isMes ? { ipWhitelist: form.ipWhitelist } : {};
    try {
      if (editing) {
        await snApi.updateUser(str(editing.id), { name: form.name, role: form.role, ...binding, ...extra });
        showSuccess(t('common.saved'));
      } else {
        const r = await snApi.createUser({
          empNo: form.empNo,
          name: form.name,
          role: form.role,
          ...binding,
          ...extra,
          ...(isMes ? {} : { password: form.password }),
        });
        showSuccess(t('common.saved'));
        if (r.secret) setSecret({ empNo: str(r.empNo), value: str(r.secret) });
      }
      setDialog(false);
      reload();
    } catch (e) {
      showError(e);
    } finally {
      setSaving(false);
    }
  }

  async function disable(r: Row) {
    if (!(await confirm(t('users.disableConfirm', { emp: str(r.empNo), name: str(r.name) }), t('users.disable')))) return;
    if ((await run(() => snApi.disableUser(str(r.id)))) !== undefined) {
      showSuccess(t('common.done'));
      reload();
    }
  }
  async function enable(r: Row) {
    if ((await run(() => snApi.enableUser(str(r.id)))) !== undefined) {
      showSuccess(t('common.done'));
      reload();
    }
  }
  async function reset(r: Row) {
    const value = await prompt(t('users.resetPrompt', { emp: str(r.empNo) }), t('users.resetPwd'), {
      password: true,
      validate: (v) => (PWD.test(v) ? true : t('password.policy')),
    });
    if (value === null) return;
    if ((await run(() => snApi.resetPassword(str(r.id), value))) !== undefined) showSuccess(t('users.resetDone'));
  }
  async function viewSecret(r: Row) {
    const x = await run(() => snApi.viewSecret(str(r.id)));
    if (x) setSecret({ empNo: str(x.empNo), value: str(x.secret) });
  }
  async function resetSecret(r: Row) {
    if (!(await confirm(t('users.resetSecretConfirm', { emp: str(r.empNo) }), t('users.resetSecret')))) return;
    const x = await run(() => snApi.resetSecret(str(r.id)));
    if (x) {
      setSecret({ empNo: str(x.empNo), value: str(x.secret) });
      reload();
    }
  }

  return (
    <div className="page page--fill">
      <PageHead title={t('users.title')} desc={t('users.desc')}>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
          {tab === 'mes' ? t('users.createMes') : t('users.create')}
        </Button>
      </PageHead>
      <div className="surface fill">
        <Tabs
          activeKey={tab}
          onChange={(k) => {
            setTab(k as 'page' | 'mes');
            setF({ ...f, role: '' });
            setPage(1);
          }}
          items={[
            { key: 'page', label: t('users.tabAll') },
            { key: 'mes', label: t('users.tabMes') },
          ]}
        />
        <div className="toolbar">
          <Input
            allowClear
            prefix={<SearchOutlined />}
            placeholder={t('users.search')}
            value={qText}
            onChange={(e) => {
              setQText(e.target.value);
              if (!e.target.value) {
                setF({ ...f, q: '' });
                setPage(1);
              }
            }}
            onPressEnter={() => {
              setF({ ...f, q: qText });
              setPage(1);
            }}
          />
          {tab === 'page' ? (
            <Select
              allowClear
              placeholder={t('users.role')}
              value={f.role || undefined}
              options={ROLE_CODES.map((r) => ({ value: r, label: roleLabel(r) }))}
              onChange={(v?: string) => {
                setF({ ...f, role: v ?? '' });
                setPage(1);
              }}
            />
          ) : null}
          <Select
            allowClear
            placeholder={t('common.status')}
            value={f.status || undefined}
            options={[
              { value: 'ACTIVE', label: t('users.active') },
              { value: 'DISABLED', label: t('users.disabled') },
            ]}
            onChange={(v?: string) => {
              setF({ ...f, status: v ?? '' });
              setPage(1);
            }}
          />
          <FactorySelect
            value={f.factoryCode}
            clearable
            onChange={(v) => {
              setF({ ...f, factoryCode: v });
              setPage(1);
            }}
          />
        </div>
        <div ref={box} className="fill-body">
          <Table<Row>
            className="fit-table"
            size="small"
            tableLayout="auto"
            scroll={{ x: 'max-content' }}
            loading={list.isFetching}
            pagination={false}
            rowKey={(r) => str(r.id)}
            dataSource={items}
            columns={[
              { title: t('users.empNo'), dataIndex: 'empNo' },
              { title: t('users.name'), dataIndex: 'name' },
              {
                title: t('users.role'),
                render: (_, r) => (bool(r.superAdmin) ? <Tag>{t('app.superAdmin')}</Tag> : roleLabel(r.role)),
              },
              { title: t('common.factory'), render: (_, r) => factoryText(r) },
              ...(tab === 'mes'
                ? [
                    {
                      title: t('users.ipWhitelist'),
                      render: (_: unknown, r: Row) =>
                        r.ipWhitelist ? (
                          <span className="sn-mono">{str(r.ipWhitelist)}</span>
                        ) : (
                          <span className="muted">{t('users.ipAny')}</span>
                        ),
                    },
                  ]
                : []),
              {
                title: t('common.status'),
                render: (_, r) => (
                  <>
                    <Tag color={str(r.status) === 'ACTIVE' ? 'success' : 'default'}>
                      {str(r.status) === 'ACTIVE' ? t('users.active') : t('users.disabled')}
                    </Tag>
                    {isLocked(r) ? (
                      <Tooltip title={t('users.lockedUntil', { time: account.fmt(r.lockedUntil) })}>
                        <Tag color="error">{t('users.locked')}</Tag>
                      </Tooltip>
                    ) : null}
                  </>
                ),
              },
              {
                title: tab === 'mes' ? t('users.lastToken') : t('users.lastLogin'),
                render: (_, r) => account.fmt(r.lastLoginAt),
              },
              { title: t('users.created'), render: (_, r) => `${str(r.createdBy)} · ${account.fmt(r.createdAt)}` },
              {
                title: t('common.actions'),
                fixed: 'right',
                render: (_, r) => (
                  <span style={{ display: 'inline-flex', gap: 12 }}>
                    <Button type="link" size="small" style={{ padding: 0 }} onClick={() => openEdit(r)}>
                      {t('common.edit')}
                    </Button>
                    {str(r.role) === 'mes' ? (
                      <>
                        <Button type="link" size="small" style={{ padding: 0 }} onClick={() => void viewSecret(r)}>
                          {t('users.viewSecret')}
                        </Button>
                        <Button type="text" size="small" style={{ padding: 0 }} onClick={() => void resetSecret(r)}>
                          {t('users.resetSecret')}
                        </Button>
                      </>
                    ) : (
                      <Button type="text" size="small" style={{ padding: 0 }} onClick={() => void reset(r)}>
                        {t('users.resetPwd')}
                      </Button>
                    )}
                    {str(r.status) === 'ACTIVE' ? (
                      <Button
                        type="link"
                        danger
                        size="small"
                        style={{ padding: 0 }}
                        disabled={str(r.id) === str(account.profile?.id) || bool(r.superAdmin)}
                        onClick={() => void disable(r)}
                      >
                        {t('users.disable')}
                      </Button>
                    ) : (
                      <Button type="link" size="small" style={{ padding: 0, color: '#3f7d5a' }} onClick={() => void enable(r)}>
                        {t('users.enable')}
                      </Button>
                    )}
                  </span>
                ),
              },
            ]}
          />
        </div>
        <div className="pager">
          <Pagination
            current={page}
            pageSize={size}
            total={Number(list.data?.total ?? 0)}
            showTotal={(n) => t('common.total', { n })}
            showSizeChanger={false}
            onChange={setPage}
          />
        </div>
      </div>

      <Modal
        open={dialog}
        width={560}
        title={
          editing ? t('users.editTitle', { emp: str(editing.empNo) }) : isMes ? t('users.createMes') : t('users.create')
        }
        onCancel={() => setDialog(false)}
        onOk={() => void save()}
        okText={t('common.save')}
        cancelText={t('common.cancel')}
        confirmLoading={saving}
        destroyOnHidden
      >
        <Form labelCol={{ flex: '120px' }} labelWrap>
          <Form.Item label={t('users.empNo')} required extra={!editing ? t('users.empNoHint') : undefined}>
            <Input
              maxLength={32}
              disabled={!!editing}
              value={form.empNo}
              onChange={(e) => setForm({ ...form, empNo: e.target.value.trim() })}
            />
          </Form.Item>
          <Form.Item label={t('users.name')} required>
            <Input maxLength={64} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          </Form.Item>
          {isMes ? (
            <>
              <Form.Item label={t('common.factory')} required extra={t('users.mesFactoryHint')}>
                <Select
                  showSearch={{ optionFilterProp: 'label' }}
                  placeholder={t('users.mesFactoryPlaceholder')}
                  value={form.factoryCode || undefined}
                  onChange={(v: string) => setForm({ ...form, factoryCode: v })}
                  options={mesFactories.map((x) => ({
                    value: str(x.factoryCode),
                    label: `${str(x.factoryName)}（${str(x.factoryCode)}）${bool(x.mesEnabled) ? ' · MES' : t('users.mesOff')}${bool(x.enabled) ? '' : t('common.disabledSuffix')}`,
                  }))}
                />
              </Form.Item>
              <Form.Item label={t('users.ipWhitelist')} extra={t('users.ipHint')}>
                <Input.TextArea
                  rows={2}
                  placeholder="10.0.0.5, 10.1.0.0/16"
                  value={form.ipWhitelist}
                  onChange={(e) => setForm({ ...form, ipWhitelist: e.target.value })}
                />
              </Form.Item>
              {!editing ? <Alert type="info" showIcon title={t('users.secretWillShow')} /> : null}
            </>
          ) : (
            <>
              <Form.Item label={t('users.role')} required extra={t(`users.roleHint.${form.role}`)}>
                <Radio.Group
                  optionType="button"
                  disabled={bool(editing?.superAdmin)}
                  value={form.role}
                  onChange={(e) => setForm({ ...form, role: String(e.target.value) })}
                  options={(bool(editing?.superAdmin) ? ['admin'] : ROLE_CODES).map((r) => ({
                    value: r,
                    label: roleLabel(r),
                  }))}
                />
              </Form.Item>
              {isScoped ? (
                <Form.Item label={t('users.queryFactories')} required>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <Select
                      mode="multiple"
                      allowClear
                      maxTagCount={3}
                      style={{ flex: 1 }}
                      placeholder={t('users.allFactoriesOption')}
                      value={form.factoryCodes}
                      onChange={(v: string[]) => setForm({ ...form, factoryCodes: v })}
                      options={factories.map((x) => ({
                        value: str(x.factoryCode),
                        disabled: !bool(x.enabled) && !form.factoryCodes.includes(str(x.factoryCode)),
                        label: `${str(x.factoryName)}（${str(x.factoryCode)}）${bool(x.enabled) ? '' : t('common.disabledSuffix')}`,
                      }))}
                    />
                    <Button
                      type="link"
                      onClick={() =>
                        setForm({ ...form, factoryCodes: enabledFactories.map((x) => str(x.factoryCode)) })
                      }
                    >
                      {t('users.selectAll')}
                    </Button>
                  </div>
                </Form.Item>
              ) : null}
              {!editing ? (
                <Form.Item label={t('users.initPwd')} required extra={t('password.policy')}>
                  <Input.Password
                    autoComplete="new-password"
                    value={form.password}
                    onChange={(e) => setForm({ ...form, password: e.target.value })}
                  />
                </Form.Item>
              ) : null}
            </>
          )}
        </Form>
      </Modal>

      <Modal
        open={!!secret}
        width={520}
        title={t('users.secretTitle')}
        maskClosable={false}
        onCancel={() => setSecret(null)}
        footer={
          <Button type="primary" onClick={() => setSecret(null)}>
            {t('users.secretClose')}
          </Button>
        }
      >
        <Alert type="info" showIcon title={t('users.secretKeep')} style={{ marginBottom: 12 }} />
        <Descriptions column={1} bordered size="small">
          <Descriptions.Item label={t('users.empNo')}>
            <span className="sn-mono">{secret?.empNo}</span>
          </Descriptions.Item>
          <Descriptions.Item label={t('users.secret')}>
            <span className="sn-mono" style={{ wordBreak: 'break-all', userSelect: 'all' }}>
              {secret?.value}
            </span>
            <Button
              type="link"
              onClick={(e) =>
                void copyText(secret?.value ?? '', (e.currentTarget as HTMLElement).parentElement ?? undefined).then(
                  (ok) => (ok ? showSuccess(t('common.copied')) : showWarning(t('common.copyFailed'))),
                )
              }
            >
              {t('common.copy')}
            </Button>
          </Descriptions.Item>
        </Descriptions>
        <p className="muted">{t('users.secretUsage')}</p>
      </Modal>
    </div>
  );
}
