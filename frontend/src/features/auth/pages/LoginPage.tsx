import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { useMutation } from '@tanstack/react-query';
import { Alert, Button, Form, Input, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { useLocation, useNavigate } from 'react-router-dom';
import { authApi } from '../../../api/client';
import { isLanguage, isRecord, type LoginCommand } from '../../../api/contract';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';
import { LanguageSelect } from '../../../components/LanguageSelect';

export function LoginPage() {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();
  const login = useMutation({
    mutationFn: authApi.login,
    onSuccess: () => {
      void navigate('/', { replace: true });
    },
  });
  const changed = isRecord(location.state) && location.state.passwordChanged === true;
  return (
    <main className="login-layout">
      <section className="login-story">
        <div className="brand light">
          <span className="brand-mark">A</span>
          <span>
            ADTEK <small>SN CENTER</small>
          </span>
        </div>
        <div className="story-copy">
          <p className="eyebrow">SERIAL NUMBER MANAGEMENT</p>
          <h1>{t('appName')}</h1>
          <p>{t('tagline')}</p>
        </div>
        <div className="serial-art" aria-hidden="true">
          <div className="serial-grid" />
          <span>SN</span>
          <div className="serial-lines" />
        </div>
      </section>
      <section className="login-panel">
        <div className="login-language">
          <LanguageSelect
            value={isLanguage(i18n.language) ? i18n.language : 'zh-CN'}
            onChange={(value) => {
              void i18n.changeLanguage(value);
            }}
          />
        </div>
        <div className="login-form">
          <Typography.Title level={2}>{t('loginTitle')}</Typography.Title>
          <Typography.Paragraph type="secondary">{t('loginIntro')}</Typography.Paragraph>
          {changed && <Alert type="success" title={t('passwordChanged')} showIcon />}
          <ApiErrorAlert error={login.error} />
          <Form<LoginCommand>
            layout="vertical"
            onFinish={(values) => login.mutate(values)}
            requiredMark={false}
            disabled={login.isPending}
          >
            <Form.Item
              name="username"
              label={t('username')}
              rules={[{ required: true, message: t('required') }]}
            >
              <Input
                prefix={<UserOutlined />}
                autoComplete="username"
                maxLength={32}
                size="large"
                autoFocus
              />
            </Form.Item>
            <Form.Item
              name="password"
              label={t('password')}
              rules={[{ required: true, message: t('required') }]}
            >
              <Input.Password
                prefix={<LockOutlined />}
                autoComplete="current-password"
                maxLength={256}
                size="large"
              />
            </Form.Item>
            <Button
              aria-label={t('login')}
              type="primary"
              htmlType="submit"
              block
              size="large"
              loading={login.isPending}
            >
              {t('login')}
            </Button>
          </Form>
          <p className="login-note">{t('loginFootnote')}</p>
        </div>
        <footer>ADTEK · SN CENTER</footer>
      </section>
    </main>
  );
}
