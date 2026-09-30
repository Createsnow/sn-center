package com.adtek.system.convert;
import com.adtek.system.domain.AuditExportRowEntity;
import com.adtek.system.vo.AuditLogVO;
import org.mapstruct.*;
/** 字符串ID准确映回内部Long；固定序号、任务标识及安全CSV由用例显式提供。 */
@Mapper(componentModel="spring")
public interface AuditExportConvert {
    @Mapping(target="taskId",source="taskId") @Mapping(target="ordinal",source="ordinal")
    @Mapping(target="actorDeptId",source="event.actorDeptId") @Mapping(target="userId",source="event.userId")
    @Mapping(target="csvLine",source="csvLine")
    AuditExportRowEntity row(AuditLogVO event,String taskId,int ordinal,String csvLine);
}
