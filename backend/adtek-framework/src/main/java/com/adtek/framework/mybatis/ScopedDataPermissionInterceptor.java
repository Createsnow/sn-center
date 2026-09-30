package com.adtek.framework.mybatis;

import com.adtek.common.core.BusinessException;
import com.adtek.framework.constant.ErrorCodeConstants;
import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.session.*;

import java.sql.Connection;
import java.util.*;

/** 在分页前解析追加范围，并按最终 SQL 顺序重建绑定参数；任何未知语句、表或解析失败均拒绝。 INSERT 无 WHERE，仍要求用例上下文，由用例校验新增对象的目标部门及角色资格。 */
public final class ScopedDataPermissionInterceptor extends DataPermissionInterceptor {
    private final Map<String, DataPermissionRules.Rule> rules;
    private final ThreadLocal<Rewrite> current = new ThreadLocal<>();

    public ScopedDataPermissionInterceptor(List<DataPermissionRules> providers) {
        Map<String, DataPermissionRules.Rule> collected = new HashMap<>();
        providers.forEach(
                provider ->
                        provider.statements()
                                .forEach(
                                        (id, rule) -> {
                                            if (collected.putIfAbsent(id, rule) != null) {
                                                throw new IllegalStateException(
                                                        "Duplicate scope statement " + id);
                                            }
                                        }));
        rules = Map.copyOf(collected);
        setDataPermissionHandler(
                (com.baomidou.mybatisplus.extension.plugins.handler.MultiDataPermissionHandler)
                        this::condition);
    }

    @Override
    public void beforeQuery(
            Executor executor,
            MappedStatement statement,
            Object parameter,
            RowBounds bounds,
            ResultHandler handler,
            BoundSql sql) {
        rewrite(statement, sql);
    }

    @Override
    public void beforePrepare(StatementHandler handler, Connection connection, Integer timeout) {
        var wrapped = PluginUtils.mpStatementHandler(handler);
        if (wrapped.mappedStatement().getSqlCommandType() != SqlCommandType.SELECT) {
            rewrite(wrapped.mappedStatement(), wrapped.boundSql());
        }
    }

    private void rewrite(MappedStatement statement, BoundSql sql) {
        var rule = rules.get(statement.getId());
        if (rule == null) {
            throw denied();
        }
        if (rule.exemptionReason() != null) {
            return;
        }
        var state = ScopeContext.require();
        if (!state.resource().equals(rule.resource())
                || !rule.permissions().contains(state.permission())
                || !state.actor().permissions().contains(state.permission())) {
            throw denied();
        }
        if (statement.getSqlCommandType() == SqlCommandType.INSERT) {
            return;
        }
        Rewrite work = new Rewrite(rule, state);
        current.set(work);
        try {
            List<ParameterMapping> original = sql.getParameterMappings();
            int[] index = {0};
            String named =
                    SqlScopeBindings.transform(
                            sql.getSql(),
                            token -> {
                                if (!token.equals("?")) {
                                    throw denied();
                                }
                                return ":__adtek_o" + index[0]++;
                            });
            if (index[0] != original.size()) {
                throw denied();
            }
            String parsed = parserSingle(named, statement.getId());
            if (work.tables == 0) {
                throw denied();
            }
            List<ParameterMapping> mappings = new ArrayList<>();
            String bound =
                    SqlScopeBindings.transform(
                            parsed,
                            token -> {
                                if (token.startsWith("__adtek_o")) {
                                    mappings.add(
                                            original.get(Integer.parseInt(token.substring(9))));
                                } else if (work.values.containsKey(token)) {
                                    mappings.add(
                                            new ParameterMapping.Builder(
                                                            statement.getConfiguration(),
                                                            token,
                                                            Long.class)
                                                    .build());
                                    sql.setAdditionalParameter(token, work.values.get(token));
                                } else {
                                    throw denied();
                                }
                                return "?";
                            });
            var wrapped = PluginUtils.mpBoundSql(sql);
            wrapped.sql(bound);
            wrapped.parameterMappings(mappings);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw denied();
        } finally {
            current.remove();
        }
    }

    private Expression condition(Table table, Expression where, String statementId) {
        Rewrite work = current.get();
        var columns = work.rule.tables().get(table.getName());
        if (columns == null) {
            throw denied();
        }
        String qualifier = table.getAlias() == null ? table.getName() : table.getAlias().getName();
        if (!qualifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw denied();
        }
        work.tables++;
        var actor = work.state.actor();
        var grant = actor.grants().get(work.state.permission());
        var scope = actor.grants().get(work.state.permission()).departments();
        List<String> alternatives = new ArrayList<>();
        if (columns.factoryColumn() != null) {
            if (grant.allFactories()) {
                alternatives.add("1 = 1");
            } else if (!grant.factoryIds().isEmpty()) {
                var ids = grant.factoryIds().stream().sorted().map(work::bind).toList();
                alternatives.add(
                        qualifier
                                + "."
                                + columns.factoryColumn()
                                + " IN ("
                                + String.join(",", ids)
                                + ")");
            }
        } else if (scope.all()) {
            alternatives.add("1 = 1");
        } else {
            if (!scope.departmentIds().isEmpty()) {
                var ids = scope.departmentIds().stream().sorted().map(work::bind).toList();
                alternatives.add(
                        qualifier
                                + "."
                                + columns.departmentColumn()
                                + " IN ("
                                + String.join(",", ids)
                                + ")");
            }
            if (scope.self()) {
                alternatives.add(
                        qualifier
                                + "."
                                + columns.ownerColumn()
                                + " = "
                                + work.bind(actor.userId()));
            }
        }
        try {
            return CCJSqlParserUtil.parseCondExpression(
                    "("
                            + (alternatives.isEmpty() ? "1 = 0" : String.join(" OR ", alternatives))
                            + ")");
        } catch (Exception exception) {
            throw denied();
        }
    }

    private static BusinessException denied() {
        return new BusinessException(ErrorCodeConstants.SCOPE_RULE_MISSING);
    }

    private static final class Rewrite {
        final DataPermissionRules.Rule rule;
        final ScopeContext.State state;
        final Map<String, Long> values = new HashMap<>();
        int tables;

        Rewrite(DataPermissionRules.Rule rule, ScopeContext.State state) {
            this.rule = rule;
            this.state = state;
        }

        String bind(long value) {
            String name = "__adtek_s" + values.size();
            values.put(name, value);
            return ":" + name;
        }
    }
}
