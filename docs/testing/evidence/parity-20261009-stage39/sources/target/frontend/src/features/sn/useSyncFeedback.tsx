import { CloseOutlined } from '@ant-design/icons';
import { message } from 'antd';
import { useTranslation } from 'react-i18next';
import { syncErrorText } from './syncErrorText';

/** 同步失败保留当前列表/选单，仅显示原型的五秒可关闭提示。 */
export function useSyncFeedback() {
  const { t } = useTranslation();
  const [feedback, holder] = message.useMessage({ top: 16 });
  const error = (value: unknown) => {
    const key = 'sn-sync-error';
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
  return { feedback, holder, error };
}
