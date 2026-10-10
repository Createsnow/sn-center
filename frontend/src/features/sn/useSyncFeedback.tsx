import { CloseOutlined } from '@ant-design/icons';
import { App } from 'antd';
import { useTranslation } from 'react-i18next';
import { syncErrorText } from './syncErrorText';

/** 同步提示按原型持续计时，切换页面不提前销毁；失败保留列表/选单。 */
export function useSyncFeedback() {
  const { t } = useTranslation();
  const { message: feedback } = App.useApp();
  const error = (value: unknown) => {
    const key = crypto.randomUUID();
    void feedback.error({
      key,
      duration: 5,
      className: 'sn-prototype-error-message',
      content: (
        <span className="sn-feedback-content">
          <span>{syncErrorText(value, t)}</span>
          <button
            type="button"
            aria-label={t('common.close')}
            onClick={() => feedback.destroy(key)}
          >
            <CloseOutlined />
          </button>
        </span>
      ),
    });
  };
  return { feedback, holder: null, error };
}
