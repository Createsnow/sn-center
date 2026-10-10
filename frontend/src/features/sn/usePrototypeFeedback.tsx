import { CloseOutlined } from '@ant-design/icons';
import { message } from 'antd';
import { useCallback } from 'react';
import { useTranslation } from 'react-i18next';

/** 页面持有提示容器，使弹窗关闭后仍可看见保存结果。 */
export function usePrototypeFeedback(listClassName?: string) {
  const { t } = useTranslation();
  const [feedback, holder] = message.useMessage({ top: 16, classNames: { list: listClassName } });
  const error = useCallback(
    (text: string, duration = 5, showClose = true) => {
      // 每次失败独立计时和关闭，避免更新同一提示时沿用上一条的剩余时长。
      const key = crypto.randomUUID();
      void feedback.error({
        key,
        duration,
        className: 'sn-prototype-error-message',
        content: (
          <span className="sn-feedback-content">
            <span>{text}</span>
            {showClose && (
              <button
                type="button"
                aria-label={t('common.close')}
                onClick={() => feedback.destroy(key)}
              >
                <CloseOutlined />
              </button>
            )}
          </span>
        ),
      });
    },
    [feedback, t],
  );
  const success = useCallback(
    (text: string) => {
      void feedback.success({
        duration: 3,
        className: 'sn-prototype-success-message',
        content: text,
      });
    },
    [feedback],
  );
  const warning = useCallback(
    (text: string) => {
      void feedback.warning({
        duration: 3,
        className: 'sn-prototype-warning-message',
        content: text,
      });
    },
    [feedback],
  );
  return { holder, error, success, warning };
}
