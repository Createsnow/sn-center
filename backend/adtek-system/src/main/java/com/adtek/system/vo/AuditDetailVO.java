package com.adtek.system.vo;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
@Schema(requiredProperties={"event","changes","reason","failureCode","redacted"})
public record AuditDetailVO(AuditLogVO event,List<AuditChangeVO> changes,
    @Schema(types={"string","null"}) String reason,@Schema(types={"string","null"}) String failureCode,boolean redacted) {}
