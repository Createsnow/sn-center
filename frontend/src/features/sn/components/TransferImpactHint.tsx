import { useQuery } from '@tanstack/react-query';
import { Alert, Spin } from 'antd';
import { useTranslation } from 'react-i18next';
import type { UserProfile } from '../../../api/contract';
import { transferApi, type TransferHint } from '../../../api/snTransfer';
import { ApiErrorAlert } from '../../../components/ApiErrorAlert';

/** 只读测算定期刷新；最终写入仍由执行时事务和唯一护栏决定。受限账号不读取全PI水位。 */
export function TransferImpactHint({
  profile,
  input,
  transferId,
}: {
  profile: UserProfile;
  input?: TransferHint;
  transferId?: string;
}) {
  const { t } = useTranslation();
  const query = useQuery({
    queryKey: [
      'sn',
      'transfer-impact',
      profile.id,
      profile.authorizationVersion,
      input,
      transferId,
    ],
    queryFn: () => (input ? transferApi.hint(input) : transferApi.pendingHint(transferId!)),
    enabled: !!input || !!transferId,
    retry: false,
    refetchInterval: 15_000,
  });
  const value = query.data;
  return (
    <div className="sn-operation-actions" data-testid="transfer-impact">
      <ApiErrorAlert error={query.error} />
      {query.isPending && <Spin size="small" />}
      {value && (
        <Alert
          showIcon
          type={value.unused && BigInt(value.unused) > 0n ? 'warning' : 'info'}
          title={
            !value.available
              ? t('snTransfer.impactRestricted')
              : value.before && value.after
                ? t('snTransfer.impact', {
                    rule: value.ruleCode,
                    before: value.before.startSn ?? value.before.startSeq,
                    after: value.after.startSn ?? value.after.startSeq,
                    unused: value.unused,
                  })
                : t('snTransfer.impactNone')
          }
          description={value.available ? t('snTransfer.impactNote') : undefined}
        />
      )}
    </div>
  );
}
