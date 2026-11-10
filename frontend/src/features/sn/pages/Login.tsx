import { BarcodeOutlined, LockOutlined, UserOutlined } from '@ant-design/icons';
import { Alert, Button, Form, Input, Select } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../lib/auth';
import { showError } from '../lib/notify';

const LOCALES = [
  { value: 'zh-CN', label: '简体中文' },
  { value: 'en', label: 'English' },
  { value: 'vi', label: 'Tiếng Việt' },
];

export function Login() {
  const { t, i18n } = useTranslation('sn');
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const login = useAuth((s) => s.login);
  const [empNo, setEmpNo] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const reason = params.get('reason');
  const notice = reason && reason.startsWith('error.') ? i18n.t(reason) : '';

  async function onLogin() {
    if (!empNo || !password) return;
    setLoading(true);
    try {
      await login(empNo.trim(), password);
      const redirect = params.get('redirect');
      navigate(redirect && redirect.startsWith('/sn') && !redirect.startsWith('/sn/login') ? redirect : '/sn');
    } catch (e) {
      showError(e);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="sn-login">
      <div className="sn-hero">
        <BarcodeOutlined style={{ fontSize: 40 }} />
        <h1>{t('app.title')}</h1>
        <p>{t('login.lead')}</p>
        <ul>
          <li>{t('login.point1')}</li>
          <li>{t('login.point2')}</li>
          <li>{t('login.point3')}</li>
        </ul>
      </div>
      <div className="sn-panel">
        <div className="sn-panel-top">
          <h2>{t('login.title')}</h2>
          <Select
            size="small"
            style={{ width: 118 }}
            value={i18n.language}
            options={LOCALES}
            onChange={(v: string) => void i18n.changeLanguage(v)}
          />
        </div>
        {notice ? <Alert type="warning" showIcon title={notice} style={{ marginBottom: 16 }} /> : null}
        <Form layout="vertical" onFinish={() => void onLogin()}>
          <Form.Item label={t('login.empNo')}>
            <Input
              size="large"
              prefix={<UserOutlined />}
              autoComplete="username"
              value={empNo}
              onChange={(e) => setEmpNo(e.target.value)}
            />
          </Form.Item>
          <Form.Item label={t('login.password')}>
            <Input.Password
              size="large"
              prefix={<LockOutlined />}
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </Form.Item>
          <Button type="primary" size="large" block loading={loading} htmlType="submit">
            {t('login.submit')}
          </Button>
        </Form>
        <p className="sn-hint">{t('login.hint')}</p>
      </div>
    </div>
  );
}
