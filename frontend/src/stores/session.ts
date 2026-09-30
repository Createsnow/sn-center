import { create } from 'zustand';

type SessionStatus = 'restoring' | 'signedOut' | 'signedIn' | 'unavailable';

/** 只保存会话控制状态；没有 persist 中间件，Bearer 不写入任何浏览器持久存储。 */
export const useSession = create<{
  status: SessionStatus;
  accessToken: string | null;
  expiresAt: number;
  setAccess: (token: string, expiresAt: string) => void;
  clear: () => void;
  unavailable: () => void;
}>((set) => ({
  status: 'restoring',
  accessToken: null,
  expiresAt: 0,
  setAccess: (accessToken, expiresAt) =>
    set({ accessToken, expiresAt: Date.parse(expiresAt), status: 'signedIn' }),
  clear: () => set({ accessToken: null, expiresAt: 0, status: 'signedOut' }),
  unavailable: () => set({ accessToken: null, expiresAt: 0, status: 'unavailable' }),
}));
