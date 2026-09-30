package com.adtek.framework.mybatis;

import com.adtek.common.core.Actor;
import com.adtek.common.core.BusinessException;
import com.adtek.framework.constant.ErrorCodeConstants;

/** 同步用例内的类型化授权上下文；finally 清理，不跨线程隐式传播。 */
public final class ScopeContext {
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();
    private ScopeContext() {}
    public record State(String resource, String permission, Actor actor) {}
    public static State require() {
        State state = CURRENT.get();
        if (state == null) { throw new BusinessException(ErrorCodeConstants.SCOPE_RULE_MISSING); }
        return state;
    }
    public static AutoCloseable open(String resource, String permission, Actor actor) {
        State previous = CURRENT.get();
        CURRENT.set(new State(resource, permission, actor));
        return () -> { if (previous == null) { CURRENT.remove(); } else { CURRENT.set(previous); } };
    }
    /** 管理事务取得协调锁后，用数据库重新解析的主体替换请求阶段快照。 */
    public static void refresh(Actor actor) {
        State state = require();
        if (actor.userId() != state.actor().userId() || !actor.permissions().contains(state.permission())) {
            throw new BusinessException(ErrorCodeConstants.FORBIDDEN);
        }
        CURRENT.set(new State(state.resource(), state.permission(), actor));
    }
}
