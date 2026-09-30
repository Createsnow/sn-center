package com.adtek.system.vo;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
@Schema(requiredProperties={"actions","modules"})
public record AuditOptionsVO(List<String> actions,List<String> modules) {}
