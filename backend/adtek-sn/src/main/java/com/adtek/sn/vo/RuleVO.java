package com.adtek.sn.vo;
import io.swagger.v3.oas.annotations.media.Schema;
/** 当前规则列表投影；历史版本由单独受权分页接口读取，禁止默默只取前N版。
 * @param revision 并发编辑版本，不能当编码版本
 * @param current 当前编码版本
 */
@Schema(requiredProperties={"id","code","name","bindScope","bindValue","currentVersion","revision","current"})
public record RuleVO(String id,String code,String name,String bindScope,String bindValue,String currentVersion,String revision,RuleVersionVO current) {}
