import { Alert, Button, Form, Input } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../lib/auth';
import { showError, showSuccess } from '../lib/notify';

const POLICY = /^[A-Za-z0-9!@#$&*-]{4,64}$/;

export function ChangePassword() {
  const { t } = useTranslation('sn');
  const navigate = useNavigate();
  const changePassword = useAuth((s) => s.changePassword);
  const [form] = Form.useForm<{ old: string; next: string; confirm: string }>();
  const [loading, setLoading] = useState(false);

  async function submit(v: { old: string; next: string }) {
    setLoading(true);
    try {
      await changePassword(v.old, v.next);
      showSuccess(t('password.done'));
      navigate('/sn/login?reason=error.auth.passwordChanged');
    } catch (e) {
      showError(e);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="sn-center">
      <div className="sn-panel">
        <h2 style={{ margin: '0 0 12px', fontSize: 20 }}>{t('password.title')}</h2>
        <Alert type="info" showIcon title={t('password.relogin')} />
        <Form form={form} layout="vertical" style={{ marginTop: 16 }} onFinish={(v) => void submit(v)}>
          <Form.Item label={t('password.old')} name="old" rules={[{ required: true, message: t('common.required') }]}>
            <Input.Password autoComplete="current-password" />
          </Form.Item>
          <Form.Item
            label={t('password.new')}
            name="next"
            extra={<span className="muted">{t('password.policy')}</span>}
            rules={[
              { required: true, message: t('common.required') },
              {
                validator: (_r, v: string) =>
                  v && POLICY.test(v) && /[A-Za-z]/.test(v) && /\d/.test(v)
                    ? Promise.resolve()
                    : Promise.reject(new Error(t('password.policy'))),
              },
            ]}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          <Form.Item
            label={t('password.confirm')}
            name="confirm"
            dependencies={['next']}
            rules={[
              ({ getFieldValue }) => ({
                validator: (_r, v: string) =>
                  v === getFieldValue('next') ? Promise.resolve() : Promise.reject(new Error(t('password.mismatch'))),
              }),
            ]}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
            <Button onClick={() => navigate(-1)}>{t('common.cancel')}</Button>
            <Button type="primary" loading={loading} htmlType="submit">
              {t('common.save')}
            </Button>
          </div>
        </Form>
      </div>
    </div>
  );
}
