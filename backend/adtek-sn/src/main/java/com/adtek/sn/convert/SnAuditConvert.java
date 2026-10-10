package com.adtek.sn.convert;

import com.adtek.sn.domain.SnAuditEntity;
import com.adtek.sn.vo.SnAuditVO;

import org.mapstruct.Mapper;

@Mapper
public interface SnAuditConvert {
    @org.mapstruct.Mapping(
            target = "detail",
            expression =
                    "java(row.getDetailText() != null ? row.getDetailText() : detail(row.getDetail()))")
    SnAuditVO view(SnAuditEntity row);

    @org.mapstruct.Named("auditDetail")
    default String detail(String value) {
        return com.adtek.sn.service.support.SnAuditFiles.compactJson(value);
    }
}
