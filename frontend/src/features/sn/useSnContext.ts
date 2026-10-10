import { useQuery } from '@tanstack/react-query';
import type { UserProfile } from '../../api/contract';
import { snQueryApi } from '../../api/snQuery';

/** 业务身份由当前服务端授权推导，不从浏览器存储或系统管理权限猜测。 */
export function useSnContext(profile?: UserProfile) {
  return useQuery({
    queryKey: ['sn', 'context', profile?.id, profile?.authorizationVersion],
    queryFn: snQueryApi.context,
    enabled: !!profile?.permissions.includes('sn:dashboard:view') && !profile.mustChangePassword,
  });
}
