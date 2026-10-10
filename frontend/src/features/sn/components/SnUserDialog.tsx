import { Button, Form, Input, Modal, Radio } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { UserProfile } from '../../../api/contract';
import type { ManagedUser } from '../../../api/users';
import { snAccountsApi } from '../../../api/snAccounts';
import { PrototypeIcon } from '../../../components/prototype/PrototypeIcon';
import { accountErrorText } from '../accountErrorText';
import { canonicalAccountRole, snAccountRoles, type SnAccountRole } from '../accountRoles';
import { FactoryPicker } from './FactoryPicker';

type Fields = {
  username: string;
  displayName: string;
  role: SnAccountRole;
  factoryId?: string;
  password: string;
};

/** 原型账户表单在保存时校验；密码只保留在当前弹窗内，关闭即销毁。 */
export function SnUserDialog({
  user,
  profile,
  close,
  done,
  failed,
}: {
  user?: ManagedUser;
  profile: UserProfile;
  close: () => void;
  done: () => Promise<void>;
  failed: (message: string) => void;
}) {
  const { t } = useTranslation();
  const [form] = Form.useForm<Fields>();
  const [saving, setSaving] = useState(false);
  const password = Form.useWatch('password', form);
  const role =
    Form.useWatch('role', form) ?? (user ? canonicalAccountRole(user) : 'factory_operator');
  const save = async () => {
    setSaving(true);
    const values = form.getFieldsValue(true);
    const input = {
      displayName: values.displayName,
      role: values.role,
      factoryId: values.role === 'admin' ? undefined : values.factoryId || undefined,
    };
    try {
      if (user) await snAccountsApi.update(user.id, { ...input, version: user.version });
      else
        await snAccountsApi.create({
          ...input,
          username: values.username.trim(),
          password: values.password,
        });
      close();
      await done();
    } catch (error) {
      failed(accountErrorText(error, t));
    } finally {
      setSaving(false);
    }
  };
  return (
    <Modal
      open
      width={520}
      style={{ top: '15vh' }}
      destroyOnHidden
      rootClassName="sn-user-modal sn-rule-modal"
      title={
        user ? t('prototype:users.editTitle', { emp: user.username }) : t('prototype:users.create')
      }
      closeIcon={<PrototypeIcon name="Close" />}
      onCancel={close}
      footer={
        <>
          <Button onClick={close}>{t('prototype:common.cancel')}</Button>
          <Button type="primary" loading={saving} onClick={() => void save()}>
            {t('prototype:common.save')}
          </Button>
        </>
      }
    >
      <Form
        form={form}
        component="div"
        colon={false}
        labelCol={{ flex: '100px' }}
        wrapperCol={{ flex: '1' }}
        initialValues={{
          username: user?.username ?? '',
          displayName: user?.displayName ?? '',
          role: user ? canonicalAccountRole(user) : 'factory_operator',
          factoryId: user?.factoryIds[0],
          password: '',
        }}
      >
        <Form.Item name="username" label={t('prototype:users.empNo')} required>
          <Input disabled={!!user} autoComplete="off" />
        </Form.Item>
        <Form.Item name="displayName" label={t('prototype:users.name')} required>
          <Input />
        </Form.Item>
        <Form.Item label={t('prototype:users.role')} required>
          <Form.Item name="role" noStyle>
            <Radio.Group buttonStyle="solid">
              {snAccountRoles.map((value) => (
                <Radio.Button key={value} value={value}>
                  {t(`prototype:roles.${value}`)}
                </Radio.Button>
              ))}
            </Radio.Group>
          </Form.Item>
          <div className="sn-account-hint">{t(`prototype:users.roleHint.${role}`)}</div>
        </Form.Item>
        {role !== 'admin' && (
          <Form.Item
            name="factoryId"
            label={t('prototype:common.factory')}
            required={role === 'factory_operator'}
          >
            <FactoryPicker profile={profile} />
          </Form.Item>
        )}
        {!user && (
          <Form.Item label={t('prototype:users.initPwd')} required>
            <Form.Item name="password" noStyle>
              <Input.Password
                visibilityToggle={Boolean(password)}
                autoComplete="new-password"
                iconRender={(visible) => <PrototypeIcon name={visible ? 'View' : 'Hide'} />}
              />
            </Form.Item>
            <div className="sn-account-hint">
              {`${t('prototype:password.policy')} ${t('prototype:users.firstLogin')}`}
            </div>
          </Form.Item>
        )}
      </Form>
    </Modal>
  );
}
