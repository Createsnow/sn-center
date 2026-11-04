package com.adtek.equipment.convert;

import com.adtek.equipment.domain.EquipmentEntity;
import com.adtek.equipment.dto.EquipmentCreateDTO;
import com.adtek.equipment.vo.EquipmentVO;

import org.mapstruct.*;

/** 只允许显式业务字段进入新实体；ID/部门/所有者/版本由服务设置。 */
@Mapper
public interface EquipmentConvert {
    EquipmentVO toVO(EquipmentEntity entity);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "code", source = "code")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "enabled", source = "enabled")
    EquipmentEntity fromCreate(EquipmentCreateDTO command);
}
