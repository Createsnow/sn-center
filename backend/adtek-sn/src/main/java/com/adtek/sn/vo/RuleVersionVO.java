package com.adtek.sn.vo;
import java.time.Instant;
import io.swagger.v3.oas.annotations.media.Schema;
/** 编码版本的固定或尚未使用参数。
 * @param id 精确版本ID
 * @param version 精确版本序号
 * @param used 一旦业务引用即不可修改参数
 * @param contentDigest 编码摘要，未用版本原位修改也会变化
 * @param createdBy 创建主体ID精确字符串；旧历史未记录时为null，不伪造主体或查询现名覆盖历史
 */
@Schema(requiredProperties={"id","ruleId","version","prefix","suffix","base","length","alphabet","used","contentDigest","createdBy","createdAt"})
public record RuleVersionVO(String id,String ruleId,String version,String prefix,String suffix,int base,int length,String alphabet,
 boolean used,String contentDigest,@Schema(types={"string","null"}) String createdBy,Instant createdAt) {}
