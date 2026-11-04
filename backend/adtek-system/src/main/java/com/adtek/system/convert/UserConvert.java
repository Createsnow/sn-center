package com.adtek.system.convert;

import com.adtek.system.domain.SysUserEntity;
import com.adtek.system.dto.BootstrapAdminDTO;
import com.adtek.system.dto.UserCreateDTO;
import com.adtek.system.dto.UserUpdateDTO;
import com.adtek.system.vo.ManagedUserVO;
import com.adtek.system.vo.UserProfileVO;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;
import java.util.Set;

/** Entity 到对外资料的唯一字段映射；显式权限来源由用例传入，不映射密码/安全版本。 */
@Mapper
public interface UserConvert {
    @Mapping(target = "version", source = "user.securityVersion")
    @Mapping(target = "roleIds", source = "roleIds")
    @Mapping(target = "deptName", source = "deptName")
    @Mapping(target = "resourceIds", source = "resourceIds")
    ManagedUserVO toManaged(
            SysUserEntity user, List<String> roleIds, String deptName, List<String> resourceIds);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "username", source = "username")
    @Mapping(target = "displayName", source = "displayName")
    @Mapping(target = "deptId", source = "deptId")
    SysUserEntity fromCreate(UserCreateDTO command);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "displayName", source = "displayName")
    @Mapping(target = "deptId", source = "deptId")
    SysUserEntity fromUpdate(UserUpdateDTO command);

    @Mapping(target = "permissions", source = "permissions")
    @Mapping(target = "authorizationVersion", source = "user.securityVersion")
    UserProfileVO toProfile(SysUserEntity user, Set<String> permissions);

    @Mapping(target = "principalType", ignore = true)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "passwordHash", ignore = true)
    @Mapping(target = "deptId", ignore = true)
    @Mapping(target = "enabled", ignore = true)
    @Mapping(target = "mustChangePassword", ignore = true)
    @Mapping(target = "securityVersion", ignore = true)
    @Mapping(target = "language", ignore = true)
    @Mapping(target = "createBy", ignore = true)
    @Mapping(target = "createTime", ignore = true)
    @Mapping(target = "updateBy", ignore = true)
    @Mapping(target = "updateTime", ignore = true)
    SysUserEntity fromBootstrap(BootstrapAdminDTO command);
}
