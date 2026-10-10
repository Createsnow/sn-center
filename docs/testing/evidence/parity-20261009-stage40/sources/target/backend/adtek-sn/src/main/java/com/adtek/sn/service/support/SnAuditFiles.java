package com.adtek.sn.service.support;

import java.util.*;

/** 自由文本CSV防公式解释；PI/SN/物料等业务编码保持原值，精确文本表格使用XLSX。 */
public final class SnAuditFiles {
    private SnAuditFiles() {}

    public static final List<String> HEADER =
            List.of(
                    "时间", "工号", "姓名", "角色", "来源", "动作", "结果", "工厂", "PI", "客户", "物料", "来源订单",
                    "起始SN", "结束SN", "数量", "操作前状态", "操作后状态", "批次号", "请求号", "转厂单号", "原因", "失败原因",
                    "请求ID", "详情", "工厂名称", "转入工厂", "转入PI", "痕迹ID");
    private static final Set<Integer> FREE_TEXT = Set.of(1, 2, 3, 4, 5, 6, 20, 21, 23, 24);

    public static List<String> csv(List<String> cells) {
        if (cells.size() != HEADER.size())
            throw new IllegalStateException("Audit snapshot column count changed");
        var safe = new ArrayList<>(cells);
        for (int index : FREE_TEXT) {
            String value = safe.get(index);
            if (value == null) continue;
            String visible = value.stripLeading();
            if ((!visible.isEmpty() && "=+-@".indexOf(visible.charAt(0)) >= 0)
                    || value.startsWith("\t")
                    || value.startsWith("\r")
                    || value.startsWith("\n")) safe.set(index, "'" + value);
        }
        return safe;
    }

    public static final List<String> PROTOTYPE_HEADER = List.copyOf(HEADER.subList(0, 24));

    /** 格式2固定映射；历史文件恢复不查询当前身份或可变业务数据。 */
    public static List<String> prototypeCells(List<String> fixed, Set<String> roles) {
        if (fixed.size() != 24) throw new IllegalStateException("Audit v2 column count changed");
        var cells = new ArrayList<>(fixed);
        if (roles.contains("SN_HQ")) cells.set(3, "admin");
        else if (roles.contains("FACTORY_OPERATOR")) cells.set(3, "factory_operator");
        else if (roles.contains("SN_QUERY_ALL") || roles.contains("SN_QUERY_FACTORY"))
            cells.set(3, "query");
        if ("MES".equals(cells.get(4))) cells.set(4, "API");
        cells.set(5, prototypeAction(cells.get(5)));
        if ("SUCCESS".equals(cells.get(6))) cells.set(6, "OK");
        else if ("FAILED".equals(cells.get(6))) cells.set(6, "FAIL");
        cells.set(23, compactJson(cells.get(23)));
        return cells;
    }

    public static String prototypeAction(String value) {
        String action = value.endsWith(":failed") ? value.substring(0, value.length() - 7) : value;
        return switch (action) {
            case "sn:generation:execute" -> "SN_GENERATE";
            case "sn:allocation:execute" -> "SN_ALLOCATE";
            case "sn:acquire:take" -> "SN_ACQUIRE";
            case "sn:print:execute" -> "SN_PRINT";
            case "sn:acquire:callback" -> "SN_CALLBACK";
            case "sn:transfer:apply" -> "TRANSFER_APPLY";
            case "sn:transfer:withdraw" -> "TRANSFER_WITHDRAW";
            case "sn:transfer:approve" -> "TRANSFER_APPROVE";
            case "sn:transfer:reject" -> "TRANSFER_REJECT";
            case "sn:transfer:direct" -> "TRANSFER_DIRECT";
            case "sn:rule:create" -> "RULE_CREATE";
            case "sn:rule:update" -> "RULE_UPDATE";
            case "sn:pi-init:execute" -> "PI_INIT";
            case "sn:order:sync" -> "ORDER_SYNC";
            case "sn:order:schedule" -> "ORDER_SYNC_SCHEDULE";
            case "sn:factory:sync" -> "FACTORY_SYNC";
            case "sn:factory:create" -> "FACTORY_CREATE";
            default -> value;
        };
    }

    /** 格式5单独标识原型纯文本详情；旧JSON格式转换不变，文本空格不得当JSON空白移除。 */
    public static List<String> prototypeCellsV5(List<String> fixed, Set<String> roles) {
        if (fixed.size() != 25 || !Set.of("JSON", "TEXT").contains(fixed.get(24)))
            throw new IllegalStateException("Audit v5 detail encoding changed");
        var cells = prototypeCells(fixed.subList(0, 24), roles);
        if ("TEXT".equals(fixed.get(24))) cells.set(23, fixed.get(23));
        return cells;
    }

    /** 格式6加入真实认证动作与匿名角色；旧格式5恢复必须保留原投影。 */
    public static List<String> prototypeCellsV6(List<String> fixed, Set<String> roles) {
        var cells = new ArrayList<>(prototypeCellsV5(fixed, roles));
        if ("auth:PASSWORD_CHANGE".equals(cells.get(5))) cells.set(5, "PASSWORD_CHANGE");
        if ("[]".equals(cells.get(3))) cells.set(3, "");
        return cells;
    }

    /** 输入已由MySQL JSON列验证；仅移除字符串外空白，不解析数字，避免大整数或小数损失。 */
    public static String compactJson(String value) {
        if (value == null) return null;
        var compact = new StringBuilder();
        boolean quoted = false, escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quoted) {
                compact.append(c);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') {
                quoted = true;
                compact.append(c);
            } else if (c != ' ' && c != '\n' && c != '\r' && c != '\t') compact.append(c);
        }
        return compact.toString();
    }

    /** 原型所有文本列防公式，数量仍是数值；只在必要时引用并保留CRLF。 */
    public static void prototypeCsvRow(java.io.OutputStream output, List<String> cells)
            throws java.io.IOException {
        var line = new StringBuilder();
        for (int col = 0; col < cells.size(); col++) {
            if (col > 0) line.append(',');
            String value = cells.get(col) == null ? "" : cells.get(col);
            boolean number = col == 14 && value.matches("-?[0-9]+");
            if (!number && !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0)
                value = "'" + value;
            if (value.indexOf(',') >= 0
                    || value.indexOf('"') >= 0
                    || value.indexOf('\n') >= 0
                    || value.indexOf('\r') >= 0)
                line.append('"').append(value.replace("\"", "\"\"")).append('"');
            else line.append(value);
        }
        output.write(
                line.append("\r\n").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
