import { Tabs } from 'antd';
import { useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { DeliveryBatchDrawer } from '../components/DeliveryBatchDrawer';
import { PageHead } from '../components/PageHead';
import { useAccount } from '../lib/auth';
import { usePage } from '../lib/page';
import { AlertsTab } from './query/AlertsTab';
import { DeliveryTab, type DeliveryTabHandle } from './query/DeliveryTab';
import { SnTab } from './query/SnTab';

const TABS = ['sn', 'delivery', 'alerts'];

/** SN 查询（F-18）：SN 明细、交付批次、交付告警三个页签；交付批次详情抽屉各页签共用。 */
export default function Query() {
  const { t } = useTranslation('sn');
  const account = useAccount();
  const { query } = usePage();
  const q = (k: string) => query.get(k) ?? '';
  const [init] = useState(() => {
    const tab = account.canAct && TABS.includes(q('tab')) ? q('tab') : 'sn';
    return {
      tab,
      factory: account.multiScope ? q('factory') : '',
      pi: q('pi'),
      batch: q('batchNo'),
      billNo: q('billNo'),
      transferNo: q('transferNo'),
    };
  });
  const [tab, setTab] = useState(init.tab);
  const [opened, setOpened] = useState<string[]>([init.tab]);
  const [deliveryNo, setDeliveryNo] = useState(init.tab === 'delivery' ? init.batch : '');
  const deliveryTab = useRef<DeliveryTabHandle>(null);
  const items = [
    { key: 'sn', label: t('query.tabSn'), children: <SnTab init={{ factoryCode: init.factory, pi: init.pi, billNo: init.billNo }} /> },
    ...(account.canAct
      ? [
          {
            key: 'delivery',
            label: t('query.tabDelivery'),
            children: (
              <DeliveryTab
                ref={deliveryTab}
                init={{ factoryCode: init.factory, pi: init.pi, batchNo: init.tab === 'delivery' ? init.batch : '', transferNo: init.transferNo }}
                onOpen={setDeliveryNo}
              />
            ),
          },
          { key: 'alerts', label: t('query.tabAlerts'), children: <AlertsTab onOpen={setDeliveryNo} /> },
        ]
      : []),
  ].map((x) => ({ ...x, children: opened.includes(x.key) ? x.children : null }));
  return (
    <div className="page page--fill">
      <PageHead title={t('query.title')} desc={t('query.desc')} />
      <div className="surface fill">
        <Tabs
          className="sn-tabs-fill"
          activeKey={tab}
          onChange={(k) => {
            setTab(k);
            setOpened((o) => (o.includes(k) ? o : [...o, k]));
          }}
          items={items}
        />
      </div>
      <DeliveryBatchDrawer batchNo={deliveryNo} onChange={setDeliveryNo} onChanged={() => deliveryTab.current?.reload()} />
    </div>
  );
}
