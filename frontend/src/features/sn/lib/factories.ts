import { useQuery } from '@tanstack/react-query';
import { snApi, type Row } from '../../../api/sn';
import { str } from './row';

/** 生产组织（全局缓存一份）。 */
export function useFactories() {
  const query = useQuery({ queryKey: ['sn', 'factories'], queryFn: () => snApi.factories(), staleTime: 60_000 });
  const factories: Row[] = query.data ?? [];
  const nameOf = (code: unknown) => {
    const c = str(code);
    if (!c) return '';
    return str(factories.find((f) => str(f.factoryCode) === c)?.factoryName) || c;
  };
  return { factories, nameOf, reload: query.refetch };
}
