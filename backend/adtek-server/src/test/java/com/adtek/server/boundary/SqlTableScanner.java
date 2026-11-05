package com.adtek.server.boundary;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从迁移脚本与 Mapper XML 中提取被引用的表名，用于表归属门禁。
 *
 * <p>采用词法扫描而非完整 SQL 解析：MyBatis 动态 SQL 片段与 MySQL 方言 DDL 无法稳定地交给通用解析器，而表名只会出现在 FROM、JOIN、INTO、
 * UPDATE、TABLE、REFERENCES、INDEX … ON、VIEW、RENAME … TO 之后。注释与字符串字面量先被移除；函数参数中的 FROM（如 {@code
 * EXTRACT(YEAR FROM x)}）、{@code ON UPDATE}、{@code ON DUPLICATE KEY UPDATE}、{@code FOR UPDATE} 与
 * WITH 子句的临时名不计为表。 无法识别的写法宁可多报（门禁失败后改写为常规写法），不可漏报。
 */
final class SqlTableScanner {
    private static final Pattern TOKEN =
            Pattern.compile("`([^`]+)`|([A-Za-z_][A-Za-z0-9_$]*)|(\\d+(?:\\.\\d+)?)|(\\S)");
    private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern XML_TAG = Pattern.compile("<[^>]*>");
    private static final Pattern PARAMETER = Pattern.compile("#\\{[^}]*}");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("(--[ \\t]|#)[^\\n]*");
    private static final Pattern STRING =
            Pattern.compile("'(?:[^'\\\\]|\\\\.|'')*'|\"(?:[^\"\\\\]|\\\\.)*\"");

    /** 不能作为表名或别名的关键字；未加反引号的这些词一律视为关键字。 */
    private static final Set<String> KEYWORDS =
            Set.of(
                    "add",
                    "after",
                    "algorithm",
                    "all",
                    "alter",
                    "and",
                    "any",
                    "as",
                    "auto_increment",
                    "by",
                    "case",
                    "change",
                    "character",
                    "charset",
                    "check",
                    "collate",
                    "column",
                    "comment",
                    "constraint",
                    "convert",
                    "create",
                    "cross",
                    "default",
                    "delayed",
                    "delete",
                    "drop",
                    "dual",
                    "dumpfile",
                    "else",
                    "end",
                    "engine",
                    "exists",
                    "first",
                    "for",
                    "force",
                    "foreign",
                    "from",
                    "fulltext",
                    "group",
                    "having",
                    "high_priority",
                    "if",
                    "ignore",
                    "in",
                    "index",
                    "inner",
                    "insert",
                    "into",
                    "is",
                    "join",
                    "key",
                    "left",
                    "like",
                    "limit",
                    "lock",
                    "low_priority",
                    "modify",
                    "natural",
                    "not",
                    "nowait",
                    "null",
                    "of",
                    "offset",
                    "on",
                    "or",
                    "order",
                    "outer",
                    "outfile",
                    "partition",
                    "primary",
                    "quick",
                    "read",
                    "recursive",
                    "rename",
                    "replace",
                    "right",
                    "row_format",
                    "select",
                    "set",
                    "skip",
                    "some",
                    "spatial",
                    "straight_join",
                    "table",
                    "tables",
                    "temporary",
                    "then",
                    "to",
                    "union",
                    "unique",
                    "update",
                    "use",
                    "using",
                    "value",
                    "values",
                    "view",
                    "when",
                    "where",
                    "window",
                    "with",
                    "write");

    private SqlTableScanner() {}

    /** 迁移脚本等普通 SQL 中引用的表名（反引号已去除，保持原大小写）。 */
    static Set<String> tables(String sql) {
        return scan(tokenize(stripCommentsAndStrings(sql)));
    }

    /** Mapper XML 中引用的表名：先去掉 XML 注释与标签、把 #{...} 参数替换为占位符。 */
    static Set<String> tablesInMapperXml(String xml) {
        String sql = XML_COMMENT.matcher(xml).replaceAll(" ");
        sql = sql.replace("<![CDATA[", " ").replace("]]>", " ");
        sql = XML_TAG.matcher(sql).replaceAll(" ");
        sql =
                sql.replace("&lt;", " < ")
                        .replace("&gt;", " > ")
                        .replace("&amp;", " & ")
                        .replace("&quot;", " ")
                        .replace("&apos;", " ");
        sql = PARAMETER.matcher(sql).replaceAll(" ? ");
        return tables(sql);
    }

    private static String stripCommentsAndStrings(String sql) {
        String result = PARAMETER.matcher(sql).replaceAll(" ? ");
        result = BLOCK_COMMENT.matcher(result).replaceAll(" ");
        result = STRING.matcher(result).replaceAll(" ? ");
        return LINE_COMMENT.matcher(result).replaceAll(" ");
    }

    private record Token(String text, boolean identifier, boolean quoted) {
        String word() {
            return identifier && !quoted ? text.toLowerCase(Locale.ROOT) : text;
        }

        boolean keyword() {
            return identifier && !quoted && KEYWORDS.contains(word());
        }

        boolean name() {
            return identifier && !keyword();
        }

        boolean is(String value) {
            return identifier ? !quoted && word().equals(value) : text.equals(value);
        }
    }

    private static List<Token> tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(sql);
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                tokens.add(new Token(matcher.group(1), true, true));
            } else if (matcher.group(2) != null) {
                tokens.add(new Token(matcher.group(2), true, false));
            } else if (matcher.group(3) != null) {
                tokens.add(new Token(matcher.group(3), false, false));
            } else {
                tokens.add(new Token(matcher.group(4), false, false));
            }
        }
        return tokens;
    }

    private static Set<String> scan(List<Token> tokens) {
        Set<String> tables = new LinkedHashSet<>();
        Set<String> temporaryNames = new LinkedHashSet<>();
        // 每层括号是否为函数调用参数（其中的 FROM 不是表来源）。
        Deque<Boolean> frames = new ArrayDeque<>();
        boolean statementHasWith = false;
        boolean renameTableStatement = false;
        boolean statementStart = true;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            Token previous = i > 0 ? tokens.get(i - 1) : null;
            if (token.is(";")) {
                frames.clear();
                statementHasWith = false;
                renameTableStatement = false;
                statementStart = true;
                continue;
            }
            if (statementStart) {
                renameTableStatement =
                        token.is("rename")
                                && i + 1 < tokens.size()
                                && tokens.get(i + 1).is("table");
                statementStart = false;
            }
            if (token.is("(")) {
                frames.push(previous != null && previous.name());
                continue;
            }
            if (token.is(")")) {
                frames.poll();
                continue;
            }
            if (token.is("with")) {
                statementHasWith = true;
            }
            if (statementHasWith
                    && token.name()
                    && i + 2 < tokens.size()
                    && tokens.get(i + 1).is("as")
                    && tokens.get(i + 2).is("(")) {
                temporaryNames.add(token.text());
            }
            if (!token.identifier() || token.quoted()) {
                continue;
            }
            switch (token.word()) {
                case "from" -> {
                    if (!Boolean.TRUE.equals(frames.peek())) {
                        readList(tokens, i + 1, tables);
                    }
                }
                case "join", "straight_join", "into", "references", "view" ->
                        readName(tokens, i + 1, tables);
                case "update" -> {
                    if (previous == null
                            || !(previous.is("on") || previous.is("key") || previous.is("for"))) {
                        readList(tokens, i + 1, tables);
                    }
                }
                case "table", "tables" -> {
                    int next = readList(tokens, i + 1, tables);
                    if (next + 1 < tokens.size() && tokens.get(next).is("like")) {
                        readName(tokens, next + 1, tables);
                    }
                }
                case "truncate" -> readName(tokens, i + 1, tables);
                case "index", "key" -> {
                    if (i + 2 < tokens.size()
                            && tokens.get(i + 1).name()
                            && tokens.get(i + 2).is("on")) {
                        readName(tokens, i + 3, tables);
                    }
                }
                case "to", "as" -> {
                    if (renameTableStatement && token.is("to")
                            || previous != null && previous.is("rename")) {
                        readName(tokens, i + 1, tables);
                    }
                }
                default -> {
                    // 其他关键字与标识符不引入表名。
                }
            }
        }
        tables.removeAll(temporaryNames);
        return tables;
    }

    /** 读取逗号分隔的表列表（每项可带别名）；遇到子查询括号即停止。返回列表之后的位置。 */
    private static int readList(List<Token> tokens, int start, Set<String> into) {
        int position = start;
        while (true) {
            int afterName = readName(tokens, position, into);
            if (afterName == position) {
                return position;
            }
            position = afterName;
            if (position < tokens.size() && tokens.get(position).is("as")) {
                position += 2;
            } else if (position < tokens.size() && tokens.get(position).name()) {
                position++;
            }
            if (position < tokens.size() && tokens.get(position).is(",")) {
                position++;
            } else {
                return position;
            }
        }
    }

    /** 读取一个可带库名限定的表名（跳过 IF [NOT] EXISTS）；不是表名时原位返回。 */
    private static int readName(List<Token> tokens, int start, Set<String> into) {
        int position = start;
        if (position < tokens.size() && tokens.get(position).is("if")) {
            position++;
            if (position < tokens.size() && tokens.get(position).is("not")) {
                position++;
            }
            if (position < tokens.size() && tokens.get(position).is("exists")) {
                position++;
            }
        }
        if (position >= tokens.size() || !tokens.get(position).name()) {
            return start;
        }
        StringBuilder name = new StringBuilder(tokens.get(position).text());
        position++;
        while (position + 1 < tokens.size()
                && tokens.get(position).is(".")
                && tokens.get(position + 1).identifier()) {
            name.append('.').append(tokens.get(position + 1).text());
            position += 2;
        }
        into.add(name.toString());
        return position;
    }
}
