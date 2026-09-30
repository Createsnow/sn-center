package com.adtek.system.convert;

import com.adtek.system.domain.OperLogEntity;
import com.adtek.system.vo.AuditLogVO;

import org.mapstruct.Mapper;

/** 只映射列表白名单；changes 由独立脱敏投影处理。 */
@Mapper
public interface AuditConvert {
    AuditLogVO toView(OperLogEntity entity);
}
