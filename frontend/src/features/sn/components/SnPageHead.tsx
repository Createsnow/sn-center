import type { ReactNode } from 'react';
import { Typography } from 'antd';

/** 延续旧SN页标题/说明/右侧操作结构；具体流程由各页实现，不合并成通用CRUD。 */
export function SnPageHead({
  title,
  description,
  children,
}: {
  title: string;
  description: string;
  children?: ReactNode;
}) {
  return (
    <div className="sn-page-head">
      <div>
        <Typography.Title level={2}>{title}</Typography.Title>
        <p>{description}</p>
      </div>
      <div className="sn-page-actions">{children}</div>
    </div>
  );
}
