package com.adtek.sn.convert;

import com.adtek.sn.domain.SnAuditEntity;
import com.adtek.sn.vo.SnAuditVO;

import org.mapstruct.Mapper;

@Mapper
public interface SnAuditConvert {
    SnAuditVO view(SnAuditEntity row);
}
