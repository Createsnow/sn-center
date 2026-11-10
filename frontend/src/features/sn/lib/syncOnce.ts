/**
 * 记录本页面（本次打开 / 刷新）里、每个登录令牌下已自动同步过的功能。
 * 只存在内存：刷新或新开页面会清空；重新登录换了令牌也会再同步一次。
 */
const done = new Set<string>();

/** 本页面、本次登录下第一次调用返回 true（并记下），之后都返回 false。 */
export function firstSyncThisLogin(feature: string, token: string): boolean {
  const key = `${feature}\n${token}`;
  if (!token || done.has(key)) return false;
  done.add(key);
  return true;
}
