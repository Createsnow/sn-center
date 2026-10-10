import { useQuery } from '@tanstack/react-query';
import type { UserProfile } from '../../api/contract';
import { loadCatalog } from '../../api/catalog';
import { snApi } from '../../api/sn';

/** 同一主体共享完整工厂目录；进入工厂管理或成功写入时刷新，痕迹名称随目录显示。 */
export function useSnFactories(profile: UserProfile, refreshOnMount = false) {
  return useQuery({
    queryKey: ['sn-factory-catalog', profile.id, profile.authorizationVersion],
    queryFn: ({ signal }) => loadCatalog((page) => snApi.factories({ page, size: 50 }), signal),
    staleTime: (query) => (query.state.data?.length ? Infinity : 0),
    gcTime: Infinity,
    refetchOnMount: refreshOnMount ? 'always' : true,
    refetchOnWindowFocus: false,
    retry: false,
  });
}
