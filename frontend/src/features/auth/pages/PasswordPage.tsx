import { useMutation } from '@tanstack/react-query';
import { Alert, Button, Card, Form, Input, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router-dom';
import { authApi } from '../../../api/client';
import type { PasswordChange } from '../../../api/contract';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';

type PasswordForm = PasswordChange & { confirmation: string };

export function PasswordPage({ mandatory = false }: { mandatory?: boolean }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const change = useMutation({
    mutationFn: authApi.password,
    onSuccess: () => {
      void navigate('/login', { replace: true, state: { passwordChanged: true } });
    },
  });
  return (
    <section className="content-narrow">
      <Typography.Title level={2}>
        {t(mandatory ? 'forcedTitle' : 'changePassword')}
      </Typography.Title>
      {mandatory ? (
        <Alert type="info" showIcon title={t('forcedIntro')} />
      ) : (
        <Link to="/profile">{t('back')}</Link>
      )}
      <Card className="password-card">
        <ApiErrorAlert error={change.error} />
        <Form<PasswordForm>
          layout="vertical"
          requiredMark={false}
          disabled={change.isPending}
          onFinish={({ oldPassword, newPassword }) => change.mutate({ oldPassword, newPassword })}
        >
          <Form.Item
            name="oldPassword"
            label={t('oldPassword')}
            rules={[{ required: true, message: t('required') }]}
          >
            <Input.Password autoComplete="current-password" maxLength={256} />
          </Form.Item>
          <Form.Item
            name="newPassword"
            label={t('newPassword')}
            extra={t('passwordPolicy')}
            rules={[
              { required: true, message: t('required') },
              { pattern: /^(?=.*[A-Za-z])(?=.*\d)[^\p{Cc}]{8,64}$/u, message: t('passwordPolicy') },
            ]}
          >
            <Input.Password autoComplete="new-password" maxLength={128} />
          </Form.Item>
          <Form.Item
            name="confirmation"
            label={t('confirmPassword')}
            dependencies={['newPassword']}
            rules={[
              { required: true, message: t('required') },
              ({ getFieldValue }) => ({
                validator: (_, value: string) =>
                  value === getFieldValue('newPassword')
                    ? Promise.resolve()
                    : Promise.reject(new Error(t('passwordMismatch'))),
              }),
            ]}
          >
            <Input.Password autoComplete="new-password" maxLength={128} />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={change.isPending}>
            {t('changePassword')}
          </Button>
        </Form>
      </Card>
    </section>
  );
}
