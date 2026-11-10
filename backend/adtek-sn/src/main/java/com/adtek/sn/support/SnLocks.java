package com.adtek.sn.support;

import com.adtek.framework.lock.NamedLockService;
import com.adtek.sn.constant.SnErrorCode;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * 跨实例互斥（原系统 app/services/locks.py）：
 *
 * <ul>
 *   <li>PI 操作锁：同一 PI 的生成（含取消）、撤销、分配、转单、转组织同一时刻只执行一个，后到的立即 PI_BUSY（不排队）；
 *   <li>组织建批锁：MES 取批时查未确认批次与建批在同一把锁内；
 *   <li>订单同步锁：按单据同步、全量重建最多排队 30 秒，比对同步和进页同步直接跳过；
 *   <li>前缀锁：判断前缀能否用到事务结束，同一前缀的判断、占用串行。
 * </ul>
 */
@Component
public class SnLocks {
    public static final int PREFIX_WAIT_SECONDS = 30;
    public static final int SYNC_WAIT_SECONDS = 30;
    private final NamedLockService locks;

    public SnLocks(NamedLockService locks) {
        this.locks = locks;
    }

    public Optional<NamedLockService.Held> tryAcquire(String kind, List<String> keys, int timeout) {
        return locks.tryAcquire(kind, keys, timeout);
    }

    /** PI 操作锁；拿不到抛 PI_BUSY。调用方负责 release。 */
    public NamedLockService.Held piLock(String... pis) {
        List<String> keys = new ArrayList<>();
        for (String p : pis) {
            if (p != null && !p.isEmpty()) {
                keys.add(p);
            }
        }
        return locks.tryAcquire("pi", keys, 0)
                .orElseThrow(
                        () ->
                                Biz.err(
                                        SnErrorCode.PI_BUSY,
                                        "pi",
                                        String.join("、", new TreeSet<>(keys))));
    }

    public <T> T withPiLock(Supplier<T> body, String... pis) {
        NamedLockService.Held h = piLock(pis);
        try {
            return body.get();
        } finally {
            h.release();
        }
    }

    public <T> T withOrgLock(String org, int timeout, Supplier<T> body) {
        NamedLockService.Held h =
                locks.tryAcquire("org", List.of(org), timeout)
                        .orElseThrow(() -> Biz.err(SnErrorCode.CONFLICT_RETRY));
        try {
            return body.get();
        } finally {
            h.release();
        }
    }

    /** 前缀锁：锁到当前事务结束；不在事务里时不加。等不到报稍后重试。 */
    public void holdPrefix(String prefix) {
        if (!Tx.inTx()) {
            return;
        }
        if (!locks.holdUntilTransactionEnd("prefix", prefix, PREFIX_WAIT_SECONDS)) {
            throw Biz.err(SnErrorCode.CONFLICT_RETRY);
        }
    }

    public boolean syncRunning() {
        return locks.isUsed("sync", "orders");
    }

    public Optional<NamedLockService.Held> syncLock(boolean wait) {
        return locks.tryAcquire("sync", List.of("orders"), wait ? SYNC_WAIT_SECONDS : 0);
    }
}
