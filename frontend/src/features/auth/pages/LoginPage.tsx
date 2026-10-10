import { useMutation } from '@tanstack/react-query';
import { Alert, Button, Form, Input, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { useLocation, useNavigate } from 'react-router-dom';
import { authApi } from '../../../api/client';
import { isLanguage, isRecord, type LoginCommand } from '../../../api/contract';
import { usePrototypeFeedback } from '../../sn/usePrototypeFeedback';
import { authenticationErrorText } from '../authenticationErrorText';
import { LanguageSelect } from '../../../components/LanguageSelect';
import { PrototypeIcon } from '../../../components/prototype/PrototypeIcon';

export function LoginPage() {
  const { t, i18n } = useTranslation();
  const [form] = Form.useForm<LoginCommand>();
  const password = Form.useWatch('password', form);
  const navigate = useNavigate();
  const feedback = usePrototypeFeedback();
  const location = useLocation();
  const login = useMutation({
    mutationFn: authApi.login,
    onError: (error) => feedback.error(authenticationErrorText(error, t)),
    onSuccess: () => {
      const redirect = new URLSearchParams(location.search).get('redirect');
      void navigate(redirect?.startsWith('/') && !redirect.startsWith('//') ? redirect : '/', {
        replace: true,
      });
    },
  });
  const changed = isRecord(location.state) && location.state.passwordChanged === true;
  return (
    <main className="prototype-login">
      <section className="prototype-login-hero">
        <PrototypeIcon name="Stamp" />
        <h1>{t('prototype:app.title')}</h1>
        <p>{t('prototype:login.lead')}</p>
        <ul>
          {[1, 2, 3].map((point) => (
            <li key={point}>{t('prototype:login.point' + point)}</li>
          ))}
        </ul>
      </section>
      <section className="prototype-login-panel">
        <div className="prototype-login-top">
          <Typography.Title level={2}>{t('prototype:login.title')}</Typography.Title>
          <LanguageSelect
            compact
            suffixIcon={<PrototypeIcon name="ArrowDown" />}
            value={isLanguage(i18n.language) ? i18n.language : 'zh-CN'}
            onChange={(value) => {
              void i18n.changeLanguage(value);
            }}
          />
        </div>
        <div>
          {changed && <Alert type="success" title={t('passwordChanged')} showIcon />}
          {feedback.holder}
          <Form<LoginCommand>
            form={form}
            layout="vertical"
            onFinish={(values) => {
              if (values.username && values.password) {
                login.mutate({ ...values, username: values.username.trim() });
              }
            }}
            requiredMark={false}
            disabled={login.isPending}
          >
            <Form.Item name="username" label={t('prototype:login.empNo')}>
              <Input
                prefix={<PrototypeIcon name="User" />}
                autoComplete="username"
                maxLength={32}
                size="large"
              />
            </Form.Item>
            <Form.Item name="password" label={t('prototype:login.password')}>
              <Input.Password
                prefix={<PrototypeIcon name="Lock" />}
                visibilityToggle={Boolean(password)}
                iconRender={(visible) => <PrototypeIcon name={visible ? 'View' : 'Hide'} />}
                autoComplete="current-password"
                maxLength={256}
                size="large"
              />
            </Form.Item>
            <Button
              aria-label={t('prototype:login.submit')}
              type="primary"
              htmlType="submit"
              block
              size="large"
              loading={login.isPending}
            >
              {t('prototype:login.submit')}
            </Button>
          </Form>
          <p className="prototype-login-note">{t('prototype:login.hint')}</p>
        </div>
      </section>
    </main>
  );
}
