import { SafetyCertificateOutlined } from '@ant-design/icons';
import { useMutation } from '@tanstack/react-query';
import { Card, Descriptions, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { authApi } from '../../../api/client';
import type { UserProfile } from '../../../api/contract';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';
import { LanguageSelect } from '../../../components/LanguageSelect';

export function ProfilePage({ profile }: { profile: UserProfile }) {
  const { t } = useTranslation();
  const language = useMutation({ mutationFn: authApi.language });
  return (
    <section className="profile-content">
      <div className="page-heading">
        <Typography.Title level={2}>{t('profile')}</Typography.Title>
        <p>{t('profileIntro')}</p>
      </div>
      <div className="profile-grid">
        <Card title={t('account')}>
          <Descriptions
            column={1}
            items={[
              { key: 'name', label: t('displayName'), children: profile.displayName },
              { key: 'username', label: t('username'), children: profile.username },
            ]}
          />
          <div className="preference">
            <Typography.Title level={5}>{t('preferredLanguage')}</Typography.Title>
            <LanguageSelect
              value={profile.language}
              onChange={(value) => language.mutate(value)}
              disabled={language.isPending}
            />
            <p className="muted">{t('languageHelp')}</p>
            <ApiErrorAlert error={language.error} />
          </div>
        </Card>
        <Card className="security-card">
          <SafetyCertificateOutlined className="security-icon" />
          <Typography.Title level={4}>{t('security')}</Typography.Title>
          <p>{t('securityHelp')}</p>
          <Link to="/change-password">{t('changePassword')} →</Link>
        </Card>
      </div>
    </section>
  );
}
