import type { TransferImpact } from '../../api/snTransfer';

/** 起点后移即提示；连续转入可能没有跳过的号，unused=0 不能隐藏后移。 */
export function transferJump(value?: {
  available: boolean;
  before: TransferImpact['before'] | null;
  after: TransferImpact['after'] | null;
  unused: string | null;
}) {
  if (
    !value?.available ||
    !value.before ||
    !value.after ||
    BigInt(value.after.startSeq) <= BigInt(value.before.startSeq)
  )
    return undefined;
  return {
    before: value.before.startSn ?? value.before.startSeq,
    after: value.after.startSn ?? value.after.startSeq,
    unused: value.unused ?? '0',
  };
}
