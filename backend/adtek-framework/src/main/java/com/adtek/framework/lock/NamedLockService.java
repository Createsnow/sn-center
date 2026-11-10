package com.adtek.framework.lock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

import javax.sql.DataSource;

/**
 * MySQL 命名锁：同一组键按排序依次加锁（避免死锁），任一把拿不到则全部释放；每组锁占一条独立连接（不在业务事务的连接上）， 持锁进程或连接异常断开时由 MySQL 自动释放，多实例下互斥。
 *
 * <p>锁名为 {@code {namespace}_{kind}_{md5(key)}}（MySQL 锁名最长 64 字符），与原系统相同，便于新旧版本切换期间互斥。
 */
@Service
public class NamedLockService {
    private final DataSource dataSource;
    private final String namespace;

    public NamedLockService(
            DataSource dataSource, @Value("${adtek.lock.namespace:sn}") String namespace) {
        this.dataSource = dataSource;
        this.namespace = namespace;
    }

    /** 已持有的一组锁；{@link #release()} 可重复调用。 */
    public static final class Held implements AutoCloseable {
        private final Connection connection;
        private final List<String> names;
        private boolean released;

        Held(Connection connection, List<String> names) {
            this.connection = connection;
            this.names = names;
        }

        public synchronized void release() {
            if (released) {
                return;
            }
            released = true;
            try (connection) {
                for (int i = names.size() - 1; i >= 0; i--) {
                    try (PreparedStatement ps =
                            connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                        ps.setString(1, names.get(i));
                        ps.execute();
                    }
                }
            } catch (SQLException ignored) {
                // 连接关闭后 MySQL 自动释放
            }
        }

        @Override
        public void close() {
            release();
        }
    }

    String name(String kind, String key) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("MD5").digest(key.getBytes(StandardCharsets.UTF_8));
            return namespace + "_" + kind + "_" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 加锁：最多等 timeoutSeconds 秒（0 = 不等）；拿不到返回空。 */
    public Optional<Held> tryAcquire(String kind, Collection<String> keys, int timeoutSeconds) {
        List<String> names = new ArrayList<>();
        for (String key : new TreeSet<>(keys)) {
            names.add(name(kind, key));
        }
        Connection connection;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open lock connection", e);
        }
        List<String> got = new ArrayList<>();
        try {
            for (String n : names) {
                try (PreparedStatement ps = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
                    ps.setString(1, n);
                    ps.setInt(2, timeoutSeconds);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || rs.getInt(1) != 1) {
                            new Held(connection, got).release();
                            return Optional.empty();
                        }
                    }
                }
                got.add(n);
            }
        } catch (SQLException | RuntimeException e) {
            new Held(connection, got).release();
            throw new IllegalStateException("named lock failed", e);
        }
        return Optional.of(new Held(connection, got));
    }

    /** 锁到当前事务结束（提交或回滚后释放）；不在事务里时只判断能否拿到，随即释放。拿不到返回 false。 */
    public boolean holdUntilTransactionEnd(String kind, String key, int timeoutSeconds) {
        Optional<Held> held = tryAcquire(kind, List.of(key), timeoutSeconds);
        if (held.isEmpty()) {
            return false;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            held.get().release();
            return true;
        }
        Held h = held.get();
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        h.release();
                    }
                });
        return true;
    }

    /** 该锁是否有人持有（任一实例）。 */
    public boolean isUsed(String kind, String key) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT IS_USED_LOCK(?)")) {
            ps.setString(1, name(kind, key));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getObject(1) != null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("named lock check failed", e);
        }
    }
}
