package com.adtek.sn.vo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 规则库的一条规则：规则、当前版本、全部版本、是否生成过号、用过的 PI、PI 绑定。 */
public class RuleViewVO {
    public Rule rule;
    public RuleVersionVO current;
    public List<RuleVersionVO> versions;
    public boolean used;
    public List<String> pis;
    public Map<String, List<String>> bindings;

    /** 规则。 */
    public static class Rule {
        public long id;
        public String ruleCode;
        public String ruleName;
        public String categoryScope;
        public String categoryValue;
        public String factoryCode;
        public int currentVersion;
        public String createdBy;
        public LocalDateTime createdAt;
        public String updatedBy;
        public LocalDateTime updatedAt;
    }
}
