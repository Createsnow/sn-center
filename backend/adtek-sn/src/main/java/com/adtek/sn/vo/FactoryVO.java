package com.adtek.sn.vo;
import java.time.Instant;
import io.swagger.v3.oas.annotations.media.Schema;
/** 工厂目录，不把名称当组织身份；时间UTC。
 * @param id 精确本地ID
 * @param version 精确编辑版本
 */
@Schema(requiredProperties={"id","code","name","source","enabled","version","createdAt","updatedAt"})
public record FactoryVO(String id,String code,String name,String source,boolean enabled,String version,Instant createdAt,Instant updatedAt) {}
