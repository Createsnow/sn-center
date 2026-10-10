import { useMutation } from '@tanstack/react-query';
import { Alert, Button, Form, Input, Typography, message } from 'antd';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { authApi } from '../../../api/client';
import type { PasswordChange } from '../../../api/contract';
import { usePrototypeFeedback } from '../../sn/usePrototypeFeedback';
import { authenticationErrorText } from '../authenticationErrorText';
import { PrototypeIcon } from '../../../components/prototype/PrototypeIcon';

type PasswordForm = PasswordChange & { confirmation: string };

export function PasswordPage({ mandatory = false }: { mandatory?: boolean }) {
  const { t } = useTranslation();
  const [form] = Form.useForm<PasswordForm>();
  const oldPassword = Form.useWatch('oldPassword', form);
  const newPassword = Form.useWatch('newPassword', form);
  const confirmation = Form.useWatch('confirmation', form);
  const navigate = useNavigate();
  const feedback = usePrototypeFeedback();
  const change = useMutation({
    mutationFn: authApi.password,
    onError: (error) => feedback.error(authenticationErrorText(error, t)),
    onSuccess: () => {
      void message.success({
        content: t('prototype:password.done'),
        className: 'sn-prototype-success-message prototype-password-success',
        icon: <PrototypeIcon name="SuccessFilled" />,
        duration: 3,
      });
      void navigate('/', { replace: true });
    },
  });
  return (
    <section className="prototype-password">
      <div className="prototype-password-panel">
        <Typography.Title level={2}>{t('prototype:password.title')}</Typography.Title>
        {mandatory && (
          <Alert
            type="warning"
            showIcon
            icon={
              <span>
                <PrototypeIcon name="WarningFilled" />
              </span>
            }
            title={t('prototype:password.forced')}
          />
        )}
        {feedback.holder}
        <Form<PasswordForm>
          form={form}
          initialValues={{ oldPassword: '', newPassword: '', confirmation: '' }}
          layout="vertical"
          validateTrigger="onBlur"
          disabled={change.isPending}
          onFinish={({ oldPassword, newPassword }) => change.mutate({ oldPassword, newPassword })}
        >
          <Form.Item
            name="oldPassword"
            label={t('prototype:password.old')}
            rules={[{ required: true, message: t('prototype:common.required') }]}
          >
            <Input.Password
              autoComplete="current-password"
              maxLength={256}
              visibilityToggle={Boolean(oldPassword)}
              iconRender={(visible) => <PrototypeIcon name={visible ? 'View' : 'Hide'} />}
            />
          </Form.Item>
          <Form.Item
            name="newPassword"
            label={t('prototype:password.new')}
            extra={t('prototype:password.policy')}
            validateFirst
            rules={[
              { required: true, message: t('prototype:common.required') },
              {
                pattern: /^(?=.*[A-Za-z])(?=.*\d)[^\p{Cc}]{8,64}$/u,
                message: t('prototype:password.policy'),
              },
            ]}
          >
            <Input.Password
              autoComplete="new-password"
              maxLength={128}
              visibilityToggle={Boolean(newPassword)}
              iconRender={(visible) => <PrototypeIcon name={visible ? 'View' : 'Hide'} />}
            />
          </Form.Item>
          <Form.Item
            name="confirmation"
            label={t('prototype:password.confirm')}
            rules={[
              ({ getFieldValue }) => ({
                validator: (_, value: string) =>
                  value === getFieldValue('newPassword')
                    ? Promise.resolve()
                    : Promise.reject(new Error(t('prototype:password.mismatch'))),
              }),
            ]}
          >
            <Input.Password
              autoComplete="new-password"
              maxLength={128}
              visibilityToggle={Boolean(confirmation)}
              iconRender={(visible) => <PrototypeIcon name={visible ? 'View' : 'Hide'} />}
            />
          </Form.Item>
          <div className="prototype-password-actions">
            <Button
              onClick={() => {
                if (mandatory) void authApi.logout();
                else void navigate(-1);
              }}
            >
              {t(mandatory ? 'prototype:app.logout' : 'prototype:common.cancel')}
            </Button>
            <Button type="primary" htmlType="submit" loading={change.isPending}>
              {t('prototype:common.save')}
            </Button>
          </div>
        </Form>
      </div>
    </section>
  );
}
