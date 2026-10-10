import { Button, Input, Modal } from 'antd';
import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { ManagedUser } from '../../../api/users';
import { PrototypeIcon } from '../../../components/prototype/PrototypeIcon';

/** 原型消息框：确认后关闭再发请求；密码校验在确认及非空输入时执行。 */
export function SnAccountActionDialog({
  kind,
  user,
  close,
  submit,
}: {
  kind: 'disable' | 'reset';
  user: ManagedUser;
  close: () => void;
  submit: (password?: string) => void;
}) {
  const { t } = useTranslation();
  const id = useId();
  const [password, setPassword] = useState('');
  const [invalid, setInvalid] = useState(false);
  const valid = (value: string) => value.length >= 8 && /[A-Za-z]/.test(value) && /\d/.test(value);
  const confirm = () => {
    if (kind === 'reset' && !valid(password)) {
      setInvalid(true);
      return;
    }
    close();
    submit(password);
  };
  return (
    <Modal
      open
      centered
      width={420}
      rootClassName="sn-account-action"
      destroyOnHidden
      title={t(kind === 'reset' ? 'prototype:users.resetPwd' : 'prototype:users.disable')}
      closeIcon={<PrototypeIcon name="Close" />}
      onCancel={close}
      footer={
        <>
          <Button onClick={close}>{t('snAcquire.cancelButton')}</Button>
          <Button type="primary" onClick={confirm}>
            {t('snAcquire.confirmButton')}
          </Button>
        </>
      }
    >
      <div className="sn-account-action-message">
        {kind === 'disable' && <PrototypeIcon name="WarningFilled" />}
        {kind === 'reset' ? (
          <label htmlFor={id}>{t('prototype:users.resetPrompt', { emp: user.username })}</label>
        ) : (
          <p>
            {t('prototype:users.disableConfirm', { emp: user.username, name: user.displayName })}
          </p>
        )}
      </div>
      {kind === 'reset' && (
        <div className="sn-account-action-input">
          <Input
            id={id}
            type="password"
            autoFocus
            aria-invalid={invalid}
            status={invalid ? 'error' : undefined}
            value={password}
            onChange={(event) => {
              setPassword(event.target.value);
              if (event.target.value) setInvalid(!valid(event.target.value));
            }}
            onPressEnter={confirm}
          />
          <div className="sn-account-action-error">
            {invalid ? t('prototype:password.policy') : ''}
          </div>
        </div>
      )}
    </Modal>
  );
}
