package com.adtek.sn.support;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * 事务（与原系统 db.tx() / db.new_tx() 语义一致）：{@link #run} 已在事务中则加入，否则新开；{@link #isolated} 总是新开独立事务。 隔离级别
 * READ_COMMITTED。
 */
@Component
public class Tx {
    private final TransactionTemplate required;
    private final TransactionTemplate requiresNew;

    public Tx(PlatformTransactionManager manager) {
        required = new TransactionTemplate(manager);
        required.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        requiresNew = new TransactionTemplate(manager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNew.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public <T> T run(Supplier<T> body) {
        return required.execute(status -> body.get());
    }

    public void exec(Runnable body) {
        required.executeWithoutResult(status -> body.run());
    }

    public <T> T isolated(Supplier<T> body) {
        return requiresNew.execute(status -> body.get());
    }

    public void execIsolated(Runnable body) {
        requiresNew.executeWithoutResult(status -> body.run());
    }

    public static boolean inTx() {
        return TransactionSynchronizationManager.isActualTransactionActive();
    }

    /** 当前事务结束（提交或回滚）后执行；不在事务里时马上执行。 */
    public static void afterTx(Runnable fn) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            fn.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        try {
                            fn.run();
                        } catch (RuntimeException ignored) {
                            // 与原系统一致：事务后的收尾失败不影响结果
                        }
                    }
                });
    }

    /** 当前事务提交后执行；不在事务里时马上执行。 */
    public static void afterCommit(Runnable fn) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            fn.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        fn.run();
                    }
                });
    }

    /** 当前事务提交前（仍在事务中）执行；不在事务里返回 false（由调用方马上执行）。 */
    public static boolean beforeCommit(Runnable fn) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        fn.run();
                    }
                });
        return true;
    }
}
