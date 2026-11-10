package com.adtek.sn.support;

import java.sql.SQLException;
import java.util.function.Supplier;

/** MySQL 错误识别（按错误号）与死锁整段重做（原系统 db.retry_deadlock）。 */
public final class DbErrors {
    private DbErrors() {}

    public static final int ER_DUP_ENTRY = 1062;
    public static final int ER_LOCK_WAIT_TIMEOUT = 1205;
    public static final int ER_LOCK_DEADLOCK = 1213;
    public static final int ER_LOCK_NOWAIT = 3572;
    public static final int DEADLOCK_ATTEMPTS = 3;

    /** 异常链上第一个 SQLException 的错误号；没有为 0。 */
    public static int errorCode(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException s && s.getErrorCode() != 0) {
                return s.getErrorCode();
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return 0;
    }

    public static boolean isDuplicate(Throwable e) {
        return errorCode(e) == ER_DUP_ENTRY;
    }

    public static boolean isDeadlock(Throwable e) {
        return errorCode(e) == ER_LOCK_DEADLOCK;
    }

    public static boolean isNowait(Throwable e) {
        return errorCode(e) == ER_LOCK_NOWAIT;
    }

    /** 异常链上的消息里是否含某个约束名（区分唯一键）。 */
    public static boolean mentions(Throwable e, String text) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(text)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** 数据库锁冲突：死锁、锁等待超时、连接池取不到连接（推送任务据此转为等待、稍后接着推）。 */
    public static boolean isLockConflict(Throwable e) {
        int code = errorCode(e);
        if (code == ER_LOCK_DEADLOCK || code == ER_LOCK_WAIT_TIMEOUT) {
            return true;
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.springframework.jdbc.CannotGetJdbcConnectionException
                    || t instanceof java.sql.SQLTransientConnectionException) {
                return true;
            }
        }
        return false;
    }

    /** 执行 fn（其中自己开、自己提交事务），遇死锁稍等后整段重做，最多 attempts 次；只在没有外层事务时重做。 */
    public static <T> T retryDeadlock(Supplier<T> fn, int attempts, boolean independent) {
        for (int i = 0; ; i++) {
            try {
                return fn.get();
            } catch (RuntimeException e) {
                if (!isDeadlock(e) || i + 1 >= attempts || (!independent && Tx.inTx())) {
                    throw e;
                }
                try {
                    Thread.sleep((long) (50 * (i + 1) + Math.random() * 200));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
