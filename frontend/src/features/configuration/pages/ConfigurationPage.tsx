import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { UserProfile } from '../../../api/contract';
import { clearSession } from '../../../api/client';
import {
  configurationApi,
  type ConfigurationRow,
  type FormValues,
  type Kind,
} from '../../../api/configuration';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';

const permissionGroup: Record<Kind, string> = {
  roles: 'role',
  departments: 'dept',
  menus: 'menu',
  'dictionary-types': 'dict',
  'dictionary-items': 'dict',
};
const components = [
  { value: 'system.users', path: '/system/users', permission: 'system:user:list' },
  { value: 'system.roles', path: '/system/roles', permission: 'system:role:list' },
  { value: 'system.departments', path: '/system/departments', permission: 'system:dept:list' },
  { value: 'system.menus', path: '/system/menus', permission: 'system:menu:list' },
  { value: 'system.dictionaries', path: '/system/dictionaries', permission: 'system:dict:list' },
  { value: 'system.audit', path: '/system/audit', permission: 'system:audit:list' },
];

function ConfigurationEditor({
  kind,
  row,
  profile,
  typeId,
  close,
  done,
}: {
  kind: Kind;
  row?: ConfigurationRow;
  profile: UserProfile;
  typeId?: string;
  close: () => void;
  done: () => Promise<void>;
}) {
  const { t } = useTranslation();
  const [form] = Form.useForm<FormValues>();
  const scope = Form.useWatch('dataScope', form) as number | undefined;
  const menuType = Form.useWatch('menuType', form) as string | undefined;
  const departments = useQuery({
    queryKey: ['config-options', 'departments', profile.id, profile.authorizationVersion],
    queryFn: configurationApi.departments,
    enabled: kind === 'roles' || kind === 'departments',
  });
  const menus = useQuery({
    queryKey: ['config-options', 'menus', profile.id, profile.authorizationVersion],
    queryFn: configurationApi.menus,
    enabled: kind === 'roles' || kind === 'menus',
  });
  const mutation = useMutation({
    mutationFn: (values: FormValues) => configurationApi.save(kind, row, values, typeId),
    onSuccess: async (result) => {
      if (result.reauthenticate) {
        clearSession(true);
      } else {
        await done();
        close();
      }
    },
  });
  const optionsPending =
    ((kind === 'roles' || kind === 'departments') && departments.isPending) ||
    ((kind === 'roles' || kind === 'menus') && menus.isPending);
  const optionsError =
    ((kind === 'roles' || kind === 'departments') && departments.error) ||
    ((kind === 'roles' || kind === 'menus') && menus.error);
  const required = { required: true, message: t('required') };
  const departmentOptions = departments.data
    ?.filter((d) => d.enabled && (kind !== 'departments' || d.id !== row?.id))
    .map((d) => ({ value: d.id, label: d.name + ' (#' + d.id + ')' }));
  const parentOptions =
    kind === 'departments'
      ? departmentOptions
      : menus.data
          ?.filter((m) => m.enabled && m.menuType !== 'F' && m.id !== row?.id)
          .map((m) => ({ value: m.id, label: t(m.name) + ' (#' + m.id + ')' }));
  const initial = {
    enabled: true,
    dataScope: 5,
    factoryScope: 1,
    menuIds: [],
    deptIds: [],
    menuType: 'M',
    sortOrder: 0,
    ...row,
  };
  return (
    <Modal
      open
      title={t(row ? 'configuration.edit' : 'configuration.add')}
      onCancel={close}
      footer={null}
      destroyOnHidden
      maskClosable={false}
      closable={!mutation.isPending}
    >
      <ApiErrorAlert error={mutation.error ?? optionsError} />
      <Alert type="info" showIcon title={t('configuration.changeHelp')} />
      <Form<FormValues>
        form={form}
        layout="vertical"
        initialValues={initial}
        onFinish={(values) => mutation.mutate(values)}
      >
        {(kind === 'roles' || kind === 'dictionary-types') && (
          <Form.Item
            name="code"
            label={t('configuration.code')}
            rules={[
              required,
              { pattern: /^[A-Za-z][A-Za-z0-9_]{1,63}$/, message: t('configuration.codeHelp') },
            ]}
          >
            <Input disabled={!!row} maxLength={64} />
          </Form.Item>
        )}
        {kind !== 'dictionary-items' && (
          <Form.Item name="name" label={t('configuration.name')} rules={[required]}>
            <Input maxLength={100} />
          </Form.Item>
        )}
        {(kind === 'departments' || kind === 'menus') && (
          <Form.Item name="parentId" label={t('configuration.parent')}>
            <Select allowClear options={parentOptions} showSearch optionFilterProp="label" />
          </Form.Item>
        )}
        {kind === 'roles' && (
          <>
            <Form.Item name="dataScope" label={t('configuration.dataScope')} rules={[required]}>
              <Select
                options={[1, 2, 3, 4, 5].map((value) => ({
                  value,
                  label: t('users.scope' + value),
                }))}
              />
            </Form.Item>
            {scope === 2 && (
              <Form.Item name="deptIds" label={t('configuration.customDepartments')}>
                <Select
                  mode="multiple"
                  options={departmentOptions}
                  showSearch
                  optionFilterProp="label"
                />
              </Form.Item>
            )}
            <Form.Item
              name="factoryScope"
              label={t('configuration.factoryScope')}
              rules={[required]}
            >
              <Select
                options={[1, 2].map((value) => ({
                  value,
                  label: t('configuration.factory' + value),
                }))}
              />
            </Form.Item>
            <Form.Item name="menuIds" label={t('configuration.permissions')}>
              <Select
                mode="multiple"
                showSearch
                optionFilterProp="label"
                options={menus.data?.map((m) => ({
                  value: m.id,
                  disabled: !m.enabled,
                  label: t(m.name) + (m.permission ? ' · ' + m.permission : ''),
                }))}
              />
            </Form.Item>
          </>
        )}
        {kind === 'menus' && (
          <>
            <Form.Item name="menuType" label={t('configuration.menuType')} rules={[required]}>
              <Select
                disabled={!!row}
                options={['M', 'C', 'F'].map((value) => ({
                  value,
                  label: t('configuration.type' + value),
                }))}
              />
            </Form.Item>
            {menuType === 'C' && (
              <Form.Item name="component" label={t('configuration.component')} rules={[required]}>
                <Select
                  options={components.map((c) => ({ value: c.value, label: c.value }))}
                  onChange={(value) => {
                    const selected = components.find((c) => c.value === value);
                    form.setFieldsValue({ path: selected?.path, permission: selected?.permission });
                  }}
                />
              </Form.Item>
            )}
            {menuType === 'C' && (
              <Form.Item name="path" label={t('configuration.path')} rules={[required]}>
                <Input readOnly />
              </Form.Item>
            )}
            {menuType !== 'M' && (
              <Form.Item name="permission" label={t('configuration.permission')} rules={[required]}>
                <Select
                  disabled={!!row || menuType === 'C'}
                  showSearch
                  options={profile.permissions.map((value) => ({ value, label: value }))}
                />
              </Form.Item>
            )}
          </>
        )}
        {kind === 'dictionary-items' && (
          <>
            <Form.Item name="value" label={t('configuration.value')} rules={[required]}>
              <Input maxLength={100} disabled={!!row} />
            </Form.Item>
            {(['labelZh', 'labelEn', 'labelVi'] as const).map((field) => (
              <Form.Item
                key={field}
                name={field}
                label={t('configuration.' + field)}
                rules={[required]}
              >
                <Input maxLength={100} />
              </Form.Item>
            ))}
          </>
        )}
        {(kind === 'menus' || kind === 'dictionary-items') && (
          <Form.Item name="sortOrder" label={t('configuration.sort')} rules={[required]}>
            <InputNumber min={0} max={99999} precision={0} />
          </Form.Item>
        )}
        <Form.Item name="enabled" label={t('configuration.enabled')} valuePropName="checked">
          <Switch />
        </Form.Item>
        <Space>
          <Button
            type="primary"
            htmlType="submit"
            aria-label={t('save')}
            loading={mutation.isPending}
            disabled={optionsPending || !!optionsError}
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

export function ConfigurationPage({
  kind: initialKind,
  profile,
}: {
  kind: Kind;
  profile: UserProfile;
}) {
  const { t, i18n } = useTranslation();
  const cache = useQueryClient();
  const [dictionary, setDictionary] = useState<{ id: string; name: string; enabled: boolean }>();
  const kind = initialKind === 'dictionary-types' && dictionary ? 'dictionary-items' : initialKind;
  const [editor, setEditor] = useState<{ row?: ConfigurationRow }>();
  const [keyword, setKeyword] = useState('');
  const list = useQuery({
    queryKey: ['configuration', kind, dictionary?.id, profile.id, profile.authorizationVersion],
    queryFn: () => configurationApi.list(kind, dictionary?.id),
  });
  const canWrite =
    profile.permissions.includes('system:' + permissionGroup[kind] + ':update') &&
    profile.permissions.includes('system:security:administer');
  const canEdit = canWrite && (kind !== 'dictionary-items' || !!dictionary?.enabled);
  const title = t('configuration.' + kind);
  const displayName = (row: ConfigurationRow) =>
    'labelZh' in row
      ? i18n.language === 'en'
        ? row.labelEn
        : i18n.language === 'vi'
          ? row.labelVi
          : row.labelZh
      : kind === 'menus'
        ? t(row.name)
        : row.name;
  const rows = list.data?.filter((row) =>
    (displayName(row) + ('code' in row ? row.code : 'value' in row ? row.value : ''))
      .toLocaleLowerCase()
      .includes(keyword.toLocaleLowerCase()),
  );
  return (
    <Card>
      <Typography.Title level={2}>
        {title}
        {dictionary ? ' · ' + dictionary.name : ''}
      </Typography.Title>
      <Typography.Paragraph type="secondary">{t('configuration.intro')}</Typography.Paragraph>
      <ApiErrorAlert error={list.error} />
      <Space wrap style={{ marginBottom: 16 }}>
        {dictionary && (
          <Button
            onClick={() => {
              setDictionary(undefined);
              setKeyword('');
            }}
          >
            {t('configuration.backTypes')}
          </Button>
        )}
        <Input.Search
          aria-label={t('configuration.search')}
          placeholder={t('configuration.search')}
          allowClear
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
        />
        <Button
          onClick={() => {
            void list.refetch();
          }}
        >
          {t('retry')}
        </Button>
        {canEdit && (
          <Button type="primary" aria-label={t('configuration.add')} onClick={() => setEditor({})}>
            {t('configuration.add')}
          </Button>
        )}
      </Space>
      <Table<ConfigurationRow>
        rowKey="id"
        loading={list.isPending}
        dataSource={rows}
        scroll={{ x: 750 }}
        pagination={{ pageSize: 20, showSizeChanger: false }}
        columns={[
          { title: t('configuration.name'), key: 'name', render: (_, row) => displayName(row) },
          {
            title: t('configuration.code'),
            key: 'code',
            render: (_, row) =>
              'code' in row
                ? row.code
                : 'value' in row
                  ? row.value
                  : 'permission' in row
                    ? row.permission
                    : row.id,
          },
          {
            title: t('configuration.details'),
            key: 'details',
            render: (_, row) =>
              'dataScope' in row
                ? t('users.scope' + row.dataScope)
                : 'menuType' in row
                  ? t('configuration.type' + row.menuType)
                  : 'parentId' in row
                    ? ((
                        list.data?.find((parent) => parent.id === row.parentId) as
                          { name: string } | undefined
                      )?.name ?? '—')
                    : 'labelEn' in row
                      ? row.labelEn + ' / ' + row.labelVi
                      : '—',
          },
          {
            title: t('users.status'),
            key: 'status',
            render: (_, row) => (
              <Tag color={row.enabled ? 'green' : 'default'}>
                {t(row.enabled ? 'users.enabled' : 'users.disabled')}
              </Tag>
            ),
          },
          {
            title: t('users.actions'),
            key: 'actions',
            render: (_, row) => (
              <Space>
                {canEdit && (
                  <Button aria-label={t('configuration.edit')} onClick={() => setEditor({ row })}>
                    {t('configuration.edit')}
                  </Button>
                )}
                {kind === 'dictionary-types' && 'name' in row && (
                  <Button
                    onClick={() => {
                      setDictionary({ id: row.id, name: row.name, enabled: row.enabled });
                      setKeyword('');
                    }}
                  >
                    {t('configuration.items')}
                  </Button>
                )}
              </Space>
            ),
          },
        ]}
      />
      {editor && (
        <ConfigurationEditor
          key={kind + (editor.row?.id ?? 'new')}
          kind={kind}
          row={editor.row}
          profile={profile}
          typeId={dictionary?.id}
          close={() => setEditor(undefined)}
          done={async () => {
            await cache.invalidateQueries({ queryKey: ['configuration'] });
            await cache.invalidateQueries({ queryKey: ['config-options'] });
            await cache.invalidateQueries({ queryKey: ['user-options'] });
            await cache.invalidateQueries({ queryKey: ['menus'] });
          }}
        />
      )}
    </Card>
  );
}
