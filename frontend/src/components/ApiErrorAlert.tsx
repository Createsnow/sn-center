import { Alert, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { ApiError } from '../api/contract';

export function ApiErrorAlert({ error }: { error: unknown }) {
  const { t, i18n } = useTranslation();
  if (!error) {
    return null;
  }
  const known = error instanceof ApiError;
  const key =
    known && error.status === 0
      ? 'errors.network'
      : known
        ? `errors.${error.code}`
        : 'errors.unknown';
  return (
    <Alert
      type="error"
      showIcon
      title={t(i18n.exists(key) ? key : 'errors.unknown', known ? error.params : {})}
      description={
        known && error.traceId ? (
          <Typography.Text type="secondary">
            {t('trace')}: {error.traceId}
          </Typography.Text>
        ) : undefined
      }
    />
  );
}
