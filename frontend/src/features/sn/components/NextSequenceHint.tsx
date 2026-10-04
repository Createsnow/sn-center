import { Alert } from 'antd';
import { useTranslation } from 'react-i18next';
import type { NextSequence } from '../../../api/snWork';

export function NextSequenceHint({ value }: { value?: NextSequence | null }) {
  const { t } = useTranslation();
  if (!value) return null;
  return (
    <Alert
      className="sn-operation-actions"
      data-testid="sn-next-start"
      type={value.startSn === null ? 'warning' : 'info'}
      title={
        value.startSn === null
          ? t('snPages.generate.exhaustedStart')
          : t('snPages.generate.nextStart', { sn: value.startSn, seq: value.startSeq })
      }
      description={
        value.adjusted
          ? t('snPages.generate.incomingStart', {
              own: value.ownLastSeq,
              sn: value.bySn,
              seq: value.bySeq,
            })
          : undefined
      }
    />
  );
}
