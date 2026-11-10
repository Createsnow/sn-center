import { Alert, Button, Form, Input, Modal } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { snApi } from '../../../api/sn';
import { run, showSuccess } from '../lib/notify';

export type ManualKind = 'confirm' | 'void';

/** 交付批次（含转出批次）人工确认 / 作废：原因、依据必填。 */
export function BatchManualDialog(props: {
  batchNo: string;
  kind: ManualKind | null;
  onClose: () => void;
  onDone: (kind: ManualKind, batchNo: string) => void;
}) {
  const { t } = useTranslation('sn');
  const [reason, setReason] = useState('');
  const [basis, setBasis] = useState('');
  const [busy, setBusy] = useState(false);
  const kind = props.kind;
  async function submit() {
    if (!kind) return;
    setBusy(true);
    const f = kind === 'void' ? snApi.voidBatch : snApi.confirmBatch;
    const r = await run(() => f(props.batchNo, reason.trim(), basis.trim()));
    setBusy(false);
    if (r) {
      showSuccess(t('common.done'));
      setReason('');
      setBasis('');
      props.onClose();
      props.onDone(kind, props.batchNo);
    }
  }
  return (
    <Modal
      open={!!kind}
      width={520}
      title={kind === 'void' ? t('batch.voidTitle', { no: props.batchNo }) : t('batch.confirmTitle', { no: props.batchNo })}
      onCancel={props.onClose}
      destroyOnHidden
      footer={[
        <Button key="c" onClick={props.onClose}>{t('common.cancel')}</Button>,
        <Button
          key="o"
          type="primary"
          danger={kind === 'void'}
          loading={busy}
          disabled={!reason.trim() || !basis.trim()}
          onClick={() => void submit()}
        >
          {t('common.confirm')}
        </Button>,
      ]}
    >
      <Alert type="warning" showIcon title={kind === 'void' ? t('batch.voidTip') : t('batch.confirmTip')} style={{ marginBottom: 12 }} />
      <Form layout="vertical">
        <Form.Item label={t('common.reason')} required>
          <Input.TextArea rows={2} maxLength={500} showCount placeholder={t('common.reasonPlaceholder')} value={reason} onChange={(e) => setReason(e.target.value)} />
        </Form.Item>
        <Form.Item label={t('batch.basis')} required>
          <Input.TextArea rows={2} maxLength={500} showCount placeholder={t('batch.basisPlaceholder')} value={basis} onChange={(e) => setBasis(e.target.value)} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
