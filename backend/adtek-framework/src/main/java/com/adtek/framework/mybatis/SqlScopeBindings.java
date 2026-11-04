package com.adtek.framework.mybatis;

import java.util.function.Function;

/** 仅重排 JDBC 占位符：解析器在 JOIN/子查询中插入条件后，原参数必须仍绑定原值。 引号、反引号及注释中的内容原样保留；保留命名空间不能出现在原 SQL 的参数位置。 */
final class SqlScopeBindings {
    private SqlScopeBindings() {}

    static String transform(String sql, Function<String, String> placeholder) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < sql.length(); ) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                char quote = c;
                out.append(c);
                i++;
                boolean closed = false;
                while (i < sql.length()) {
                    char next = sql.charAt(i++);
                    out.append(next);
                    if (next == '\\' && i < sql.length()) {
                        out.append(sql.charAt(i++));
                    } else if (next == quote) {
                        if (i < sql.length() && sql.charAt(i) == quote) {
                            out.append(sql.charAt(i++));
                        } else {
                            closed = true;
                            break;
                        }
                    }
                }
                if (!closed) {
                    throw new IllegalArgumentException("Unclosed SQL quote");
                }
            } else if (c == '#' || (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-')) {
                int end = sql.indexOf('\n', i);
                if (end < 0) {
                    end = sql.length();
                }
                out.append(sql, i, end);
                i = end;
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0 || (i + 2 < sql.length() && sql.charAt(i + 2) == '!')) {
                    throw new IllegalArgumentException("Unsupported SQL comment");
                }
                out.append(sql, i, end + 2);
                i = end + 2;
            } else if (c == '?') {
                out.append(placeholder.apply("?"));
                i++;
            } else if (c == ':' && sql.startsWith(":__adtek_", i)) {
                int end = i + 1;
                while (end < sql.length()
                        && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '_')) {
                    end++;
                }
                out.append(placeholder.apply(sql.substring(i + 1, end)));
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
