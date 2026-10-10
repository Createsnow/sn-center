import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { UserProfile } from '../../../api/contract';
import { clearSession } from '../../../api/client';
import { usersApi, type ManagedUser } from '../../../api/users';
import { FactoryPicker } from '../../sn/components/FactoryPicker';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';

export type Editor = { kind: 'add' } | { kind: 'update' | 'reset'; user: ManagedUser };
type FormValues = {
  username: string;
  displayName: string;
  deptId: string;
  roleIds: string[];
  factoryIds: string[];
  password: string;
};

export function UserEditor({
  editor,
  profile,
  done,
  close,
}: {
  editor: Editor;
  profile: UserProfile;
  done: () => Promise<void>;
  close: () => void;
}) {
  const { t } = useTranslation();
  const [form] = Form.useForm<FormValues>();
  const options = useQuery({
    queryKey: ['user-options', profile.id, profile.authorizationVersion, editor.kind],
    queryFn: () => usersApi.options(editor.kind === 'add' ? 'add' : 'update'),
    enabled: editor.kind !== 'reset',
  });
  const canBindRoles = profile.permissions.includes('system:security:administer');
  const canBindFactories =
    canBindRoles &&
    profile.permissions.includes('sn:factory:assign') &&
    profile.permissions.includes('sn:factory:list');
  const mutation = useMutation({
    mutationFn: async (values: FormValues) => {
      if (editor.kind === 'add') {
        await usersApi.create({
          ...values,
          roleIds: values.roleIds ?? [],
          factoryIds: canBindFactories ? (values.factoryIds ?? []) : undefined,
        });
      } else if (editor.kind === 'reset') {
        await usersApi.reset(editor.user, values.password);
      } else {
        await usersApi.update(editor.user, {
          displayName: values.displayName,
          deptId: values.deptId,
          roleIds: canBindRoles ? values.roleIds : editor.user.roleIds,
          factoryIds: canBindFactories ? (values.factoryIds ?? []) : undefined,
          version: editor.user.version,
        });
      }
    },
    onSuccess: async () => {
      form.resetFields();
      if (editor.kind === 'update' && editor.user.id === profile.id) {
        clearSession(true);
      } else {
        await done();
        close();
      }
    },
  });
  const required = { required: true, message: t('required') };
  return (
    <Modal
      open
      title={t(`users.${editor.kind}`)}
      onCancel={close}
      destroyOnHidden
      maskClosable={false}
      footer={null}
      closable={!mutation.isPending}
    >
      <ApiErrorAlert error={mutation.error ?? options.error} />
      {editor.kind === 'reset' && <Alert type="info" title={t('users.resetHelp')} showIcon />}
      <Form<FormValues>
        form={form}
        layout="vertical"
        requiredMark={false}
        disabled={mutation.isPending}
        initialValues={
          editor.kind === 'update'
            ? { ...editor.user, deptId: editor.user.deptId ?? undefined }
            : { roleIds: [] }
        }
        onFinish={(values) => mutation.mutate(values)}
      >
        {editor.kind === 'add' && (
          <Form.Item
            name="username"
            label={t('username')}
            rules={[
              required,
              { pattern: /^[A-Za-z0-9_-]{2,32}$/, message: t('users.usernameRule') },
            ]}
          >
            <Input autoComplete="off" maxLength={32} />
          </Form.Item>
        )}
        {editor.kind !== 'reset' && (
          <>
            <Form.Item
              name="displayName"
              label={t('displayName')}
              rules={[required, { whitespace: true, message: t('required') }]}
            >
              <Input maxLength={100} />
            </Form.Item>
            <Form.Item name="deptId" label={t('users.department')} rules={[required]}>
              <Select
                loading={options.isPending}
                options={options.data?.departments.map((dept) => ({
                  value: dept.id,
                  label: dept.name,
                }))}
              />
            </Form.Item>
            {canBindRoles && (
              <Form.Item name="roleIds" label={t('users.roles')} extra={t('users.roleHelp')}>
                <Select
                  mode="multiple"
                  loading={options.isPending}
                  options={options.data?.roles.map((role) => ({
                    value: role.id,
                    label: `${role.name} · ${t(`users.scope${String(role.dataScope)}`)}`,
                  }))}
                />
              </Form.Item>
            )}
            {canBindFactories && (
              <Form.Item
                name="factoryIds"
                label={t('snPages.common.factory')}
                extra={t('snPages.factoryAssignmentHelp')}
              >
                <FactoryPicker profile={profile} multiple />
              </Form.Item>
            )}
            {editor.kind === 'update' && editor.user.id === profile.id && (
              <Alert type="warning" title={t('users.selfUpdateHelp')} showIcon />
            )}
          </>
        )}
        {editor.kind !== 'update' && (
          <Form.Item
            name="password"
            label={t('newPassword')}
            extra={t('passwordPolicy')}
            rules={[
              required,
              { pattern: /^(?=.*[A-Za-z])(?=.*\d)[^\p{Cc}]{8,64}$/u, message: t('passwordPolicy') },
            ]}
          >
            <Input.Password autoComplete="new-password" maxLength={128} />
          </Form.Item>
        )}
        <Space>
          <Button
            type="primary"
            aria-label={t('save')}
            htmlType="submit"
            loading={mutation.isPending}
            disabled={editor.kind !== 'reset' && !options.data}
          >
            {t('save')}
          </Button>
          <Button onClick={close} disabled={mutation.isPending}>
            {t('users.cancel')}
          </Button>
        </Space>
      </Form>
    </Modal>
  );
}

export function UsersPage({ profile }: { profile: UserProfile }) {
  const { t } = useTranslation();
  const cache = useQueryClient();
  const [filter, setFilter] = useState<{
    page: number;
    size: number;
    keyword: string;
    enabled?: boolean;
  }>({ page: 1, size: 20, keyword: '' });
  const [editor, setEditor] = useState<Editor>();
  const [saved, setSaved] = useState(false);
  const key = ['users', profile.id, profile.authorizationVersion];
  const users = useQuery({ queryKey: [...key, filter], queryFn: () => usersApi.list(filter) });
  const can = (permission: string) => profile.permissions.includes(`system:user:${permission}`);
  const done = async () => {
    setSaved(true);
    await cache.invalidateQueries({ queryKey: key });
  };
  const status = useMutation({ mutationFn: usersApi.status, onSuccess: done });
  const edit = (value: Editor) => {
    setSaved(false);
    setEditor(value);
  };
  // AntD 页码只接受安全整数；后端总数保持文本展示，不把业务数量转换成不精确 number。
  const count = users.data?.total ?? '0';
  const pageTotal =
    BigInt(count) > BigInt(Number.MAX_SAFE_INTEGER) ? Number.MAX_SAFE_INTEGER : Number(count);
  return (
    <section>
      <Typography.Title level={2}>{t('users.title')}</Typography.Title>
      <Typography.Paragraph type="secondary">{t('users.intro')}</Typography.Paragraph>
      <ApiErrorAlert error={users.error ?? status.error} />
      {saved && (
        <Alert
          type="success"
          showIcon
          title={t('saved')}
          closable
          onClose={() => setSaved(false)}
        />
      )}
      <Card>
        <Space wrap className="users-toolbar">
          <Input.Search
            aria-label={t('users.search')}
            placeholder={t('users.search')}
            allowClear
            maxLength={64}
            onSearch={(keyword) => setFilter({ ...filter, keyword, page: 1 })}
          />
          <Select
            aria-label={t('users.status')}
            style={{ minWidth: 140 }}
            value={filter.enabled === undefined ? 'all' : String(filter.enabled)}
            options={[
              { value: 'all', label: t('users.all') },
              { value: 'true', label: t('users.enabled') },
              { value: 'false', label: t('users.disabled') },
            ]}
            onChange={(value) =>
              setFilter({
                ...filter,
                enabled: value === 'all' ? undefined : String(value) === 'true',
                page: 1,
              })
            }
          />
          <Button
            onClick={() => {
              void users.refetch();
            }}
          >
            {t('retry')}
          </Button>
          {can('add') && (
            <Button type="primary" onClick={() => edit({ kind: 'add' })}>
              {t('users.add')}
            </Button>
          )}
        </Space>
        <Table<ManagedUser>
          rowKey="id"
          dataSource={users.data?.items}
          loading={users.isPending || users.isFetching}
          scroll={{ x: 850 }}
          pagination={{
            current: filter.page,
            pageSize: filter.size,
            total: pageTotal,
            pageSizeOptions: [10, 20, 50, 100],
            showSizeChanger: true,
            showTotal: () => t('users.total', { total: count }),
            onChange: (page, size) => setFilter({ ...filter, page, size }),
          }}
          columns={[
            { title: t('username'), dataIndex: 'username', width: 160 },
            { title: t('displayName'), dataIndex: 'displayName' },
            {
              title: t('users.department'),
              dataIndex: 'deptName',
              render: (value: string | null) => value ?? '—',
            },
            {
              title: t('users.status'),
              render: (_, user) => (
                <Space wrap>
                  <Tag color={user.enabled ? 'green' : 'default'}>
                    {t(user.enabled ? 'users.enabled' : 'users.disabled')}
                  </Tag>
                  {user.mustChangePassword && <Tag color="gold">{t('users.mustChange')}</Tag>}
                </Space>
              ),
            },
            {
              title: t('users.actions'),
              width: 280,
              render: (_, user) => (
                <Space wrap>
                  {can('update') && (
                    <Button
                      size="small"
                      aria-label={t('users.update')}
                      onClick={() => edit({ kind: 'update', user })}
                    >
                      {t('users.update')}
                    </Button>
                  )}
                  {can('reset') && user.id !== profile.id && (
                    <Button size="small" onClick={() => edit({ kind: 'reset', user })}>
                      {t('users.reset')}
                    </Button>
                  )}
                  {can('status') && user.id !== profile.id && (
                    <Popconfirm
                      title={t(user.enabled ? 'users.disableConfirm' : 'users.enableConfirm')}
                      onConfirm={() =>
                        status.mutateAsync(user).catch(() => {
                          /* mutation.error 已交给统一错误组件。 */
                        })
                      }
                      okText={t('users.confirm')}
                      okButtonProps={{ 'aria-label': t('users.confirm') }}
                      cancelText={t('users.cancel')}
                    >
                      <Button
                        size="small"
                        aria-label={t(user.enabled ? 'users.disable' : 'users.enable')}
                        danger={user.enabled}
                        loading={status.isPending}
                      >
                        {t(user.enabled ? 'users.disable' : 'users.enable')}
                      </Button>
                    </Popconfirm>
                  )}
                </Space>
              ),
            },
          ]}
        />
      </Card>
      {editor && (
        <UserEditor
          editor={editor}
          profile={profile}
          done={done}
          close={() => setEditor(undefined)}
        />
      )}
    </section>
  );
}
