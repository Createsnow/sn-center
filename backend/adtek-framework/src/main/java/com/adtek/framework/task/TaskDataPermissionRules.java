package com.adtek.framework.task;

import com.adtek.framework.mybatis.DataPermissionRules;

import org.springframework.stereotype.Component;

import java.util.*;

/** 基础设施内部语句逐项登记；没有包级或继承方法豁免，主体/对象授权在TaskAccess和业务处理器执行。 */
@Component
public class TaskDataPermissionRules implements DataPermissionRules {
    public Map<String, Rule> statements() {
        Map<String, Rule> rules = new HashMap<>();
        for (String method :
                List.of(
                        "lockSubmission",
                        "now",
                        "operationTyped",
                        "operationForTask",
                        "find",
                        "lock",
                        "candidate",
                        "activeCount",
                        "userActiveCount",
                        "insertOperationTyped",
                        "insertTask",
                        "claim",
                        "succeed",
                        "operationSucceeded",
                        "fail",
                        "operationEnded",
                        "cancel",
                        "recover",
                        "progress",
                        "progressWrite")) {
            rules.put(
                    "com.adtek.framework.task.mapper.DurableTaskMapper." + method,
                    Rule.internal("持久操作设施：外部按可信主体和业务处理器授权，执行持任务锁及代次条件，工作器仅领取登记任务"));
        }
        for (String method : List.of("find", "insert", "ready", "state", "metadata")) {
            rules.put(
                    "com.adtek.framework.task.mapper.DurableFileMapper." + method,
                    Rule.internal("受控文件元数据：由任务持锁写入，读取由当前主体及完整快照范围授权"));
        }
        return Map.copyOf(rules);
    }
}
