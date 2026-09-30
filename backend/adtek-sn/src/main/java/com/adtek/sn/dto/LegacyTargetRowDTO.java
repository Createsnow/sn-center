package com.adtek.sn.dto;
import java.util.Map;
/** 仅由已核验来源编译的主数据行；table/columns由Mapper封闭白名单限制，不接受SQL。 */
public record LegacyTargetRowDTO(String table,String sourceTable,String sourceKey,String evidenceDigest,Map<String,Object> values) {
    @Override public String toString(){return "LegacyTargetRowDTO[redacted]";}
}
