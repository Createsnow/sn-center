package com.adtek.framework.mybatis;

import static org.assertj.core.api.Assertions.*;

import com.adtek.common.core.*;

import org.apache.ibatis.mapping.*;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.Test;

import java.util.*;

class ScopedDataPermissionInterceptorTest {
    private static final String ID = "test.Mapper.query";
    private final Configuration config = new Configuration();
    private final ScopedDataPermissionInterceptor interceptor =
            new ScopedDataPermissionInterceptor(
                    List.of(
                            () ->
                                    Map.of(
                                            ID,
                                            DataPermissionRules.Rule.scoped(
                                                    "test:user",
                                                    "sys_user",
                                                    "dept_id",
                                                    "id",
                                                    "read"))));

    private Actor actor(boolean all, Set<Long> departments, boolean self) {
        return new Actor(
                9007199254741001L,
                1,
                false,
                Map.of(
                        "read",
                        new AuthorizationScope(
                                new DataScope(all, departments, self), false, Set.of())));
    }

    private BoundSql rewrite(String sql, Object... params) {
        List<ParameterMapping> mappings = new ArrayList<>();
        Map<String, Object> values = new HashMap<>();
        for (int i = 0; i < params.length; i++) {
            String key = "p" + i;
            mappings.add(new ParameterMapping.Builder(config, key, Object.class).build());
            values.put(key, params[i]);
        }
        var bound = new BoundSql(config, sql, mappings, values);
        var statement =
                new MappedStatement.Builder(config, ID, object -> bound, SqlCommandType.SELECT)
                        .build();
        interceptor.beforeQuery(null, statement, values, RowBounds.DEFAULT, null, bound);
        return bound;
    }

    private List<Object> values(BoundSql sql) {
        return sql.getParameterMappings().stream()
                .map(
                        mapping ->
                                sql.hasAdditionalParameter(mapping.getProperty())
                                        ? sql.getAdditionalParameter(mapping.getProperty())
                                        : ((Map<?, ?>) sql.getParameterObject())
                                                .get(mapping.getProperty()))
                .toList();
    }

    @Test
    void joinBindsInsertedScopesInFinalOrderWithoutLosingOriginalPredicates() throws Exception {
        try (var ignored =
                ScopeContext.open("test:user", "read", actor(false, Set.of(31L), true))) {
            var sql =
                    rewrite(
                            "SELECT a.id FROM sys_user a JOIN sys_user b ON b.id=a.id AND"
                                    + " b.username=? WHERE a.enabled=?",
                            "needle",
                            true);
            assertThat(sql.getSql())
                    .contains("a.dept_id", "b.dept_id")
                    .doesNotContain("9007199254741001", ":__adtek_");
            assertThat(values(sql))
                    .containsExactly(
                            "needle", 31L, 9007199254741001L, true, 31L, 9007199254741001L);
        }
    }

    @Test
    void subqueryAndAggregateRetainBoundSearchValues() throws Exception {
        try (var ignored =
                ScopeContext.open("test:user", "read", actor(false, Set.of(31L), false))) {
            var sql =
                    rewrite(
                            "SELECT COUNT(*) FROM sys_user a WHERE a.id IN (SELECT b.id FROM"
                                    + " sys_user b WHERE b.username=?) AND a.enabled=?",
                            "x",
                            true);
            assertThat(sql.getSql()).contains("a.dept_id", "b.dept_id");
            assertThat(values(sql)).containsExactly("x", 31L, true, 31L);
        }
    }

    @Test
    void emptyScopeIsFalseWhileExplicitAllStillRequiresRuleAndContext() throws Exception {
        try (var ignored = ScopeContext.open("test:user", "read", actor(false, Set.of(), false))) {
            assertThat(rewrite("SELECT * FROM sys_user").getSql()).contains("1 = 0");
        }
        try (var ignored = ScopeContext.open("test:user", "read", actor(true, Set.of(), false))) {
            assertThat(rewrite("SELECT * FROM sys_user").getSql()).contains("1 = 1");
        }
        assertThatThrownBy(() -> rewrite("SELECT * FROM sys_user"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void unknownTableMalformedSqlAndUnregisteredStatementFailClosed() throws Exception {
        try (var ignored = ScopeContext.open("test:user", "read", actor(true, Set.of(), false))) {
            assertThatThrownBy(() -> rewrite("SELECT * FROM secret_table"))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> rewrite("this is not SQL"))
                    .isInstanceOf(BusinessException.class);
            var bound = new BoundSql(config, "SELECT * FROM sys_user", List.of(), null);
            var unknown =
                    new MappedStatement.Builder(
                                    config,
                                    "test.Mapper.undeclared",
                                    object -> bound,
                                    SqlCommandType.SELECT)
                            .build();
            assertThatThrownBy(
                            () ->
                                    interceptor.beforeQuery(
                                            null, unknown, null, RowBounds.DEFAULT, null, bound))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void quotedQuestionMarksAreNotBoundAndReservedParameterInjectionIsRejected() throws Exception {
        try (var ignored =
                ScopeContext.open("test:user", "read", actor(false, Set.of(31L), false))) {
            var sql =
                    rewrite(
                            "SELECT '?' AS label FROM sys_user WHERE username=? /* ? */",
                            "?':__adtek_s0");
            assertThat(values(sql)).containsExactly("?':__adtek_s0", 31L);
            assertThatThrownBy(() -> rewrite("SELECT * FROM sys_user WHERE id=:__adtek_s0"))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void readContextCannotExecuteAWriteOnlyStatement() throws Exception {
        var writes =
                new ScopedDataPermissionInterceptor(
                        List.of(
                                () ->
                                        Map.of(
                                                ID,
                                                DataPermissionRules.Rule.scoped(
                                                        "test:user",
                                                        "sys_user",
                                                        "dept_id",
                                                        "id",
                                                        "write"))));
        var sql = new BoundSql(config, "SELECT * FROM sys_user", List.of(), null);
        var statement =
                new MappedStatement.Builder(config, ID, object -> sql, SqlCommandType.SELECT)
                        .build();
        try (var ignored = ScopeContext.open("test:user", "read", actor(true, Set.of(), false))) {
            assertThatThrownBy(
                            () ->
                                    writes.beforeQuery(
                                            null, statement, null, RowBounds.DEFAULT, null, sql))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
