import { Input, Modal } from 'antd';
import i18n from '../../../i18n';

/** 确认框：确定返回 true，取消返回 false。 */
export function confirm(content: string, title?: string, danger = true): Promise<boolean> {
  return new Promise((resolve) => {
    Modal.confirm({
      title,
      content,
      okButtonProps: danger ? { danger: true } : undefined,
      okText: i18n.t('common.confirm', { ns: 'sn' }),
      cancelText: i18n.t('common.cancel', { ns: 'sn' }),
      onOk: () => resolve(true),
      onCancel: () => resolve(false),
    });
  });
}

/** 输入框：确定返回输入值（通过 validate 校验，返回 true 或错误文案），取消返回 null。 */
export function prompt(
  content: string,
  title: string,
  options?: { password?: boolean; validate?: (v: string) => true | string; initial?: string; textarea?: boolean },
): Promise<string | null> {
  return new Promise((resolve) => {
    let value = options?.initial ?? '';
    let error = '';
    const render = () => (
      <div>
        <p>{content}</p>
        {options?.password ? (
          <Input.Password autoFocus defaultValue={value} onChange={(e) => (value = e.target.value)} />
        ) : options?.textarea ? (
          <Input.TextArea autoFocus rows={3} defaultValue={value} onChange={(e) => (value = e.target.value)} />
        ) : (
          <Input autoFocus defaultValue={value} onChange={(e) => (value = e.target.value)} />
        )}
        {error ? <div style={{ color: '#b42318', marginTop: 6, fontSize: 12 }}>{error}</div> : null}
      </div>
    );
    const modal = Modal.confirm({
      title,
      icon: null,
      content: render(),
      okText: i18n.t('common.confirm', { ns: 'sn' }),
      cancelText: i18n.t('common.cancel', { ns: 'sn' }),
      onOk: () => {
        const r = options?.validate ? options.validate(value) : true;
        if (r !== true) {
          error = r;
          modal.update({ content: render() });
          return Promise.reject(new Error(r));
        }
        resolve(value);
        return undefined;
      },
      onCancel: () => resolve(null),
    });
  });
}

/** 复制文本：内网 HTTP 部署时 navigator.clipboard 不可用，退回 execCommand。 */
export async function copyText(text: string, container: HTMLElement = document.body): Promise<boolean> {
  if (window.isSecureContext && navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      /* 退回 execCommand */
    }
  }
  const ta = document.createElement('textarea');
  ta.value = text;
  ta.setAttribute('readonly', '');
  ta.style.cssText = 'position:fixed;top:0;left:0;width:1px;height:1px;opacity:0;';
  container.appendChild(ta);
  try {
    ta.focus();
    ta.select();
    return document.execCommand('copy');
  } catch {
    return false;
  } finally {
    container.removeChild(ta);
  }
}
