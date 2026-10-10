import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Input, Modal, Pagination, Select, Table, Tag } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { UserProfile } from '../../../api/contract';
import { usersApi, type ManagedUser } from '../../../api/users';
import { paginationTotal } from '../../../api/sn';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';
import { PrototypeIcon } from '../../../components/prototype/PrototypeIcon';
import { formatSnTime } from '../../../utils/snTime';
import { UserEditor, type Editor } from '../../users/pages/UsersPage';
import { FactoryPicker } from '../components/FactoryPicker';
import { SnPageHead } from '../components/SnPageHead';
import { SnUserDialog } from '../components/SnUserDialog';
import { canonicalAccountRole } from '../accountRoles';
import { usePrototypeFeedback } from '../usePrototypeFeedback';

const administrators = ['PLATFORM_ADMIN', 'SN_HQ'];
const roles = ['admin', 'factory_operator', 'query'] as const;
type Role = (typeof roles)[number];
function businessRole(user: ManagedUser): Role | undefined {
  if (user.roleCodes.some((code) => administrators.includes(code))) return 'admin';
  if (user.roleCodes.includes('FACTORY_OPERATOR')) return 'factory_operator';
  if (user.roleCodes.some((code) => ['SN_QUERY_ALL', 'SN_QUERY_FACTORY'].includes(code)))
    return 'query';
  return undefined;
}
function roleFilter(role?: Role) {
  if (role === 'admin') return { roleCodes: administrators };
  if (role === 'factory_operator')
    return { roleCodes: ['FACTORY_OPERATOR'], excludedRoleCodes: administrators };
  if (role === 'query')
    return {
      roleCodes: ['SN_QUERY_ALL', 'SN_QUERY_FACTORY'],
      excludedRoleCodes: [...administrators, 'FACTORY_OPERATOR'],
    };
  return {};
}

/** SN 账户目录按工号排序；角色/状态/工厂在服务端组合筛选后分页。 */
export function SnUsersPage({ profile }: { profile: UserProfile }) {
  const { t } = useTranslation('prototype');
  const { t: common } = useTranslation();
  const [modal, modalContext] = Modal.useModal();
  const cache = useQueryClient();
  const feedback = usePrototypeFeedback();
  const [draft, setDraft] = useState('');
  const [filter, setFilter] = useState<{
    keyword: string;
    role?: Role;
    enabled?: boolean;
    factoryId?: string;
    page: number;
  }>({ keyword: '', page: 1 });
  const [editor, setEditor] = useState<Editor>();
  const [revision, setRevision] = useState(0);
  const key = ['sn', 'users', profile.id, profile.authorizationVersion];
  const list = useQuery({
    queryKey: [...key, filter, revision],
    queryFn: () => usersApi.list({ ...filter, ...roleFilter(filter.role), size: 20 }),
    retry: false,
  });
  const done = async () => {
    await cache.invalidateQueries({ queryKey: key });
  };
  const status = useMutation({ mutationFn: usersApi.status, onSuccess: done });
  const can = (permission: string) => profile.permissions.includes(`system:user:${permission}`);
  const apply = (change: Partial<typeof filter>) => {
    setFilter({ ...filter, keyword: draft, ...change, page: 1 });
    // 相同条件再次查询也读取最新资料，避免另一窗口编辑后继续使用旧版本。
    setRevision((current) => current + 1);
  };
  const changeStatus = (user: ManagedUser) => {
    if (!user.enabled) {
      status.mutate(user);
      return;
    }
    modal.confirm({
      title: t('users.disable'),
      content: t('users.disableConfirm', { emp: user.username, name: user.displayName }),
      okText: t('common.yes'),
      cancelText: t('common.cancel'),
      onOk: () => status.mutateAsync(user),
    });
  };
  return (
    <section className="sn-page sn-users-page">
      {modalContext}
      {feedback.holder}
      <SnPageHead title={t('users.title')} description={t('users.desc')}>
        {can('add') && (
          <Button
            type="primary"
            icon={<PrototypeIcon name="Plus" />}
            onClick={() => setEditor({ kind: 'add' })}
          >
            {t('users.create')}
          </Button>
        )}
      </SnPageHead>
      <ApiErrorAlert error={list.error ?? status.error} />
      <div className="sn-surface">
        <div className="sn-toolbar">
          <Input
            aria-label={t('users.search')}
            placeholder={t('users.search')}
            prefix={<PrototypeIcon name="Search" />}
            allowClear
            value={draft}
            onChange={(event) => {
              setDraft(event.target.value);
              if (!event.target.value) apply({ keyword: '' });
            }}
            onPressEnter={() => apply({})}
          />
          <Select
            virtual={false}
            aria-label={t('users.role')}
            placeholder={t('users.role')}
            allowClear
            value={filter.role}
            options={roles.map((role) => ({ value: role, label: t(`roles.${role}`) }))}
            onChange={(role?: Role) => apply({ role })}
          />
          <Select
            virtual={false}
            aria-label={t('common.status')}
            placeholder={t('common.status')}
            allowClear
            value={
              filter.enabled === undefined ? undefined : filter.enabled ? 'ACTIVE' : 'DISABLED'
            }
            options={[
              { value: 'ACTIVE', label: t('users.active') },
              { value: 'DISABLED', label: t('users.disabled') },
            ]}
            onChange={(value?: string) =>
              apply({ enabled: value === undefined ? undefined : value === 'ACTIVE' })
            }
          />
          <FactoryPicker
            profile={profile}
            value={filter.factoryId}
            onChange={(factoryId) => apply({ factoryId })}
          />
        </div>
        <Table<ManagedUser>
          rowKey="id"
          size="small"
          loading={list.isFetching}
          dataSource={list.data?.items ?? []}
          pagination={false}
          scroll={{ x: 1160 }}
          locale={{ emptyText: t('common.empty') }}
          columns={[
            { title: t('users.empNo'), dataIndex: 'username', width: 120 },
            { title: t('users.name'), dataIndex: 'displayName', width: 120 },
            {
              title: t('users.role'),
              width: 120,
              render: (_, user) => {
                const role = businessRole(user);
                return role ? t(`roles.${role}`) : user.roleCodes.join('、');
              },
            },
            {
              title: t('common.factory'),
              render: (_, user) =>
                user.factories.length
                  ? user.factories.map((factory) => `${factory.name}（${factory.code}）`).join('、')
                  : t('users.noFactory'),
            },
            {
              title: t('common.status'),
              width: 120,
              render: (_, user) => (
                <>
                  <Tag color={user.enabled ? 'success' : 'default'}>
                    {t(user.enabled ? 'users.active' : 'users.disabled')}
                  </Tag>
                  {user.mustChangePassword && <Tag color="warning">{t('users.mustChange')}</Tag>}
                </>
              ),
            },
            {
              title: t('users.lastLogin'),
              width: 160,
              render: (_, user) => (user.lastLoginAt ? formatSnTime(user.lastLoginAt) : ''),
            },
            {
              title: t('users.created'),
              width: 160,
              render: (_, user) =>
                `${user.createdByName ?? ''} · ${user.createdAt ? formatSnTime(user.createdAt) : ''}`,
            },
            {
              title: t('common.actions'),
              width: 220,
              fixed: 'right',
              render: (_, user) => (
                <div className="sn-users-actions">
                  {can('update') && (
                    <Button type="link" onClick={() => setEditor({ kind: 'update', user })}>
                      {t('common.edit')}
                    </Button>
                  )}
                  {can('reset') && (
                    <Button
                      type="link"
                      disabled={user.id === profile.id}
                      onClick={() => setEditor({ kind: 'reset', user })}
                    >
                      {t('users.resetPwd')}
                    </Button>
                  )}
                  {can('status') && (
                    <Button
                      type="link"
                      danger={user.enabled}
                      className={user.enabled ? undefined : 'sn-user-enable'}
                      disabled={user.id === profile.id}
                      onClick={() => changeStatus(user)}
                    >
                      {t(user.enabled ? 'users.disable' : 'users.enable')}
                    </Button>
                  )}
                </div>
              ),
            },
          ]}
        />
        <div className="sn-query-foot">
          <Pagination
            className="sn-acquire-pager"
            current={filter.page}
            pageSize={20}
            total={paginationTotal(list.data?.total)}
            showSizeChanger={false}
            showTotal={() => common('shell.paginationTotal', { total: list.data?.total ?? '0' })}
            onChange={(page) => setFilter({ ...filter, keyword: draft, page })}
          />
        </div>
      </div>
      {editor &&
      (editor.kind === 'add' || (editor.kind === 'update' && canonicalAccountRole(editor.user))) ? (
        <SnUserDialog
          user={editor.kind === 'update' ? editor.user : undefined}
          profile={profile}
          done={async () => {
            feedback.success(t('common.saved'));
            await done();
          }}
          failed={feedback.error}
          close={() => setEditor(undefined)}
        />
      ) : (
        editor && (
          <UserEditor
            editor={editor}
            profile={profile}
            done={done}
            close={() => setEditor(undefined)}
          />
        )
      )}
    </section>
  );
}
