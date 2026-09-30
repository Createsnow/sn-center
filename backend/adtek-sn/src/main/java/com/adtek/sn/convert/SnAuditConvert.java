package com.adtek.sn.convert;
import org.mapstruct.Mapper;
import com.adtek.sn.domain.SnAuditEntity;
import com.adtek.sn.vo.SnAuditVO;
@Mapper public interface SnAuditConvert { SnAuditVO view(SnAuditEntity row); }
