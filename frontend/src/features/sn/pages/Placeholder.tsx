import { CarOutlined, CodeSandboxOutlined } from '@ant-design/icons';
import { Button, Tag } from 'antd';
import { useTranslation } from 'react-i18next';
import { PageHead } from '../components/PageHead';
import { useAccount } from '../lib/auth';
import { menuItem } from '../lib/menu';
import { usePage } from '../lib/page';

/** 建设中的模块（箱号管理、托号管理）占位页：说明规划功能，不调任何接口。 */
export default function Placeholder() {
  const { t } = useTranslation('sn');
  const { path, open } = usePage();
  const account = useAccount();
  const rel = path.replace(/^\/sn/, '');
  const key = rel.slice(1).replace(/\/(\w)/g, (_, c: string) => c.toUpperCase());
  const title = t(menuItem(path)?.title ?? '');
  return (
    <div className="page">
      <PageHead title={title} desc={t('soon.desc')} />
      <div className="surface" style={{ display: 'flex', gap: 20, alignItems: 'flex-start', padding: 28 }}>
        <span style={{ fontSize: 40, color: 'var(--sn-muted)' }}>
          {rel.startsWith('/pallet') ? <CarOutlined /> : <CodeSandboxOutlined />}
        </span>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 16 }}>
            <b>{title}</b>
            <Tag>{t('nav.soon')}</Tag>
          </div>
          <p style={{ margin: 0, lineHeight: 1.7 }}>{t(`soon.plan.${key}`)}</p>
          <p className="muted" style={{ margin: 0 }}>
            {t('soon.note')}
          </p>
          {account.canAct ? (
            <div>
              <Button type="link" style={{ padding: 0 }} onClick={() => open('/sn/generate')}>
                {t('soon.toSn')}
              </Button>
            </div>
          ) : null}
        </div>
      </div>
    </div>
  );
}
