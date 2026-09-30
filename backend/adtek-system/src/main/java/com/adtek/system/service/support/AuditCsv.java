package com.adtek.system.service.support;

import com.adtek.system.vo.AuditLogVO;

import java.util.Arrays;
import java.util.stream.Collectors;

/** UTF8程序交换CSV；精确ID保持原字符，不声称电子表格自动识别为文本。自由文本防公式解释。 */
public final class AuditCsv {
    public static final String HEADER =
            "id,created_at,user_id,actor_username,actor_dept_id,module,action,success,object_id,trace_id\r\n";

    private AuditCsv() {}

    public static String row(AuditLogVO event) {
        return Arrays.stream(
                                new String[] {
                                    event.id(),
                                    event.createTime().toString(),
                                    event.userId(),
                                    safe(event.actorUsername()),
                                    event.actorDeptId(),
                                    safe(event.moduleName()),
                                    safe(event.action()),
                                    Boolean.toString(event.success()),
                                    safe(event.objectId()),
                                    event.traceId()
                                })
                        .map(
                                value ->
                                        "\""
                                                + (value == null ? "" : value.replace("\"", "\"\""))
                                                + "\"")
                        .collect(Collectors.joining(","))
                + "\r\n";
    }

    private static String safe(String value) {
        if (value == null) {
            return null;
        }
        String visible = value.stripLeading();
        return (!visible.isEmpty() && "=+-@".indexOf(visible.charAt(0)) >= 0)
                        || value.startsWith("\t")
                        || value.startsWith("\r")
                        || value.startsWith("\n")
                ? "'" + value
                : value;
    }
}
