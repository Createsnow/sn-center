package com.adtek.equipment.service.impl;

import com.adtek.framework.mybatis.DataPermissionRules;

import org.springframework.stereotype.Component;

import java.util.*;

/** list/detail/create/update 范围各自独立；不存在默认放行的 Mapper。 */
@Component
public class EquipmentDataPermissionRules implements DataPermissionRules {
    public Map<String, Rule> statements() {
        Map<String, Rule> rules = new HashMap<>();
        for (String method :
                List.of("selectList", "selectCount", "selectById", "insert", "change")) {
            String[] permissions =
                    switch (method) {
                        case "insert" -> new String[] {"equipment:asset:create"};
                        case "change" -> new String[] {"equipment:asset:update"};
                        case "selectById" ->
                                new String[] {"equipment:asset:detail", "equipment:asset:update"};
                        default -> new String[] {"equipment:asset:list"};
                    };
            rules.put(
                    "com.adtek.equipment.mapper.EquipmentMapper." + method,
                    Rule.scoped(
                            "equipment:asset",
                            "equipment_asset",
                            "dept_id",
                            "create_by",
                            permissions));
        }
        return Map.copyOf(rules);
    }
}
