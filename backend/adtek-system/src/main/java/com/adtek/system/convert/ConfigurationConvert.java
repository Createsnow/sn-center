package com.adtek.system.convert;
import com.adtek.system.domain.*;
import com.adtek.system.dto.*;
import com.adtek.system.vo.*;
import java.util.List;
import org.mapstruct.*;
/** 仅作配置字段映射；版本、关系校验、授权和审计由服务完成。 */
@Mapper
public interface ConfigurationConvert {
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "code", source = "code")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "dataScope", source = "dataScope")
    @Mapping(target = "factoryScope", source = "factoryScope")
    @Mapping(target = "enabled", source = "enabled")
    RoleEntity fromRole(RoleSaveDTO command);
    @Mapping(target = "menuIds", source = "menuIds")
    @Mapping(target = "deptIds", source = "deptIds")
    RoleVO toRole(RoleEntity entity, List<String> menuIds, List<String> deptIds);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "name", source = "name")
    @Mapping(target = "parentId", source = "parentId")
    @Mapping(target = "enabled", source = "enabled")
    DepartmentEntity fromDepartment(DepartmentSaveDTO command);
    DepartmentVO toDepartment(DepartmentEntity entity);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "name", source = "name")
    @Mapping(target = "parentId", source = "parentId")
    @Mapping(target = "menuType", source = "menuType")
    @Mapping(target = "permission", source = "permission")
    @Mapping(target = "path", source = "path")
    @Mapping(target = "component", source = "component")
    @Mapping(target = "sortOrder", source = "sortOrder")
    @Mapping(target = "enabled", source = "enabled")
    ManagedMenuEntity fromManagedMenu(ManagedMenuSaveDTO command);
    ManagedMenuVO toManagedMenu(ManagedMenuEntity entity);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "code", source = "code")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "enabled", source = "enabled")
    DictionaryTypeEntity fromDictionaryType(DictionaryTypeSaveDTO command);
    DictionaryTypeVO toDictionaryType(DictionaryTypeEntity entity);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "typeId", source = "typeId")
    @Mapping(target = "value", source = "value")
    @Mapping(target = "labelZh", source = "labelZh")
    @Mapping(target = "labelEn", source = "labelEn")
    @Mapping(target = "labelVi", source = "labelVi")
    @Mapping(target = "sortOrder", source = "sortOrder")
    @Mapping(target = "enabled", source = "enabled")
    DictionaryItemEntity fromDictionaryItem(DictionaryItemSaveDTO command);
    DictionaryItemVO toDictionaryItem(DictionaryItemEntity entity);
}
