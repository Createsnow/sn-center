import type { ReactNode } from 'react';

export function PageHead({ title, desc, children }: { title: string; desc?: string; children?: ReactNode }) {
  return (
    <div className="page__head">
      <div>
        <h2 className="page__title">{title}</h2>
        {desc ? <p className="page__desc">{desc}</p> : null}
      </div>
      <div className="page__actions">{children}</div>
    </div>
  );
}
