import { CloseOutlined, HomeOutlined, MoreOutlined } from '@ant-design/icons';
import { Button, Dropdown, type MenuProps } from 'antd';
import { useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { HOME, type Tab } from '../lib/tabList';
import { menuItem } from '../lib/menu';

type Action = 'refresh' | 'close' | 'closeOthers' | 'closeRight' | 'closeAll';

/** 顶栏页签：与浏览器标签页一样叠加，用户手动关闭前一直保留；首页固定在最左，可拖动排序，右键菜单。 */
export function TabBar(props: {
  tabs: Tab[];
  active: string;
  onGo: (tab: Tab) => void;
  onAction: (action: Action, path: string) => void;
  onMove: (from: number, to: number) => void;
}) {
  const { t } = useTranslation('sn');
  const [dragPath, setDragPath] = useState('');
  const strip = useRef<HTMLDivElement>(null);

  const title = (path: string) => {
    const m = menuItem(path);
    return m ? t(m.title) : path;
  };
  const fullTitle = (path: string) => {
    const m = menuItem(path);
    if (!m) return path;
    const g = (m as { group?: string }).group;
    return g && g !== 'nav.groupWork' ? `${t(g)} / ${title(path)}` : title(path);
  };
  const actions = (path: string): MenuProps['items'] => {
    const i = props.tabs.findIndex((x) => x.path === path);
    const n = props.tabs.length;
    return [
      { key: 'refresh', label: t('tabs.refresh') },
      { type: 'divider' },
      { key: 'close', label: t('tabs.close'), disabled: path === HOME },
      {
        key: 'closeOthers',
        label: t('tabs.closeOthers'),
        disabled: props.tabs.every((x) => x.path === HOME || x.path === path),
      },
      { key: 'closeRight', label: t('tabs.closeRight'), disabled: i < 0 || i >= n - 1 },
      { key: 'closeAll', label: t('tabs.closeAll'), disabled: n <= 1 },
    ];
  };

  return (
    <div className="sn-tabbar">
      <div
        ref={strip}
        className="sn-strip"
        role="tablist"
        onWheel={(e) => {
          if (strip.current && Math.abs(e.deltaY) > Math.abs(e.deltaX)) strip.current.scrollLeft += e.deltaY;
        }}
      >
        {props.tabs.map((tab, i) => (
          <Dropdown
            key={tab.path}
            trigger={['contextMenu']}
            menu={{ items: actions(tab.path), onClick: ({ key }) => props.onAction(key as Action, tab.path) }}
          >
            <div
              className={`sn-tab${tab.path === props.active ? ' on' : ''}${dragPath === tab.path ? ' dragging' : ''}`}
              role="tab"
              tabIndex={0}
              aria-selected={tab.path === props.active}
              title={fullTitle(tab.path)}
              draggable={i > 0}
              onClick={() => props.onGo(tab)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  props.onGo(tab);
                }
              }}
              onAuxClick={(e) => {
                if (e.button === 1 && i > 0) props.onAction('close', tab.path);
              }}
              onDragStart={(e) => {
                if (i === 0) {
                  e.preventDefault();
                  return;
                }
                setDragPath(tab.path);
                e.dataTransfer.effectAllowed = 'move';
              }}
              onDragOver={(e) => {
                if (!dragPath) return;
                e.preventDefault();
                if (tab.path === dragPath || tab.path === HOME) return;
                const from = props.tabs.findIndex((x) => x.path === dragPath);
                props.onMove(from, i);
              }}
              onDragEnd={() => setDragPath('')}
            >
              {i === 0 ? <HomeOutlined /> : null}
              <span className="sn-tt">{title(tab.path)}</span>
              {i > 0 ? (
                <button
                  className="sn-x"
                  type="button"
                  aria-label={t('tabs.close')}
                  onClick={(e) => {
                    e.stopPropagation();
                    props.onAction('close', tab.path);
                  }}
                >
                  <CloseOutlined style={{ fontSize: 10 }} />
                </button>
              ) : null}
            </div>
          </Dropdown>
        ))}
      </div>
      <Dropdown
        trigger={['click']}
        placement="bottomRight"
        menu={{ items: actions(props.active), onClick: ({ key }) => props.onAction(key as Action, props.active) }}
      >
        <Button type="text" icon={<MoreOutlined />} title={t('tabs.more')} aria-label={t('tabs.more')} />
      </Dropdown>
    </div>
  );
}
