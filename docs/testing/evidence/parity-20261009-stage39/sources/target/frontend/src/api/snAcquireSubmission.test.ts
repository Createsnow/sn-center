import { afterEach, describe, expect, it, vi } from 'vitest';
import { submit } from './client';
import { ApiError } from './contract';
import { acquireApi } from './snAcquire';

vi.mock('./client', () => ({ query: vi.fn(), submit: vi.fn(), downloadFile: vi.fn() }));
const command = {
  operationId: '10000000-0000-4000-8000-000000000001',
  factoryId: '9007199254740993',
  pi: 'PRINT-QUEUE',
};
afterEach(() => {
  vi.useRealTimers();
  vi.resetAllMocks();
});

describe('打印提交的临时任务容量', () => {
  it('等待旧文件释放容量，始终复用同一操作号和输入，不跳过当前 PI', async () => {
    vi.useFakeTimers();
    const accepted = { taskId: '20000000-0000-4000-8000-000000000001' };
    vi.mocked(submit)
      .mockRejectedValueOnce(new ApiError(1003000008, 409))
      .mockRejectedValueOnce(new ApiError(1003000008, 409))
      .mockResolvedValueOnce(accepted);
    const pending = acquireApi.print(command);
    await vi.advanceTimersByTimeAsync(999);
    expect(submit).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1001);
    await expect(pending).resolves.toBe(accepted);
    expect(submit).toHaveBeenCalledTimes(3);
    for (const call of vi.mocked(submit).mock.calls) {
      expect(call[0]).toBe('/sn/prints');
      expect(call[1]).toBe(command);
    }
  });

  it('其他业务错误、权限拒绝和未知网络结果立即返回，不自动重试', async () => {
    vi.useFakeTimers();
    for (const error of [
      new ApiError(1004000022, 409),
      new ApiError(1003000004, 403),
      new Error('Network outcome unknown'),
    ]) {
      vi.mocked(submit).mockRejectedValueOnce(error);
      await expect(acquireApi.print(command)).rejects.toBe(error);
    }
    expect(submit).toHaveBeenCalledTimes(3);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('容量持续不足最多等待一分钟，返回原拒绝且不遗留重试计时器', async () => {
    vi.useFakeTimers();
    const rejected = new ApiError(1003000008, 409);
    vi.mocked(submit).mockRejectedValue(rejected);
    const outcome = expect(acquireApi.print(command)).rejects.toBe(rejected);
    await vi.advanceTimersByTimeAsync(60_000);
    await outcome;
    expect(submit).toHaveBeenCalledTimes(61);
    expect(vi.getTimerCount()).toBe(0);
  });
});
