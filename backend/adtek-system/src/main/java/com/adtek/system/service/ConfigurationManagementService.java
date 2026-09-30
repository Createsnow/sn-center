package com.adtek.system.service;
import com.adtek.system.dto.*;
import com.adtek.system.vo.*;
import java.util.List;
/** 全局配置用例，不向业务局部管理员开放；配置没有租户或伪造的所属部门。 */
public interface ConfigurationManagementService {
    /** 要求 role:list 及全部部门范围的 security:administer；返回最多 1000 项，超过上限明确拒绝，不截断。 */
    List<RoleVO> roles();
    /** 要求 role:update 和全局安全管理；先锁协调行和当前账号，再校验版本、引用和授权上限；成功审计与配置同事务，任何失败整体回滚。id 为空新建；停用保留历史。 */
    ConfigurationChangeVO saveRole(String id, RoleSaveDTO command);
    /** 新建配置；权限、协调锁及失败回滚同修改，审计业务类型为 INSERT。 */
    ConfigurationChangeVO createRole(RoleSaveDTO command);
    /** 要求 dept:list 及全部部门范围的 security:administer；返回最多 1000 项，超过上限明确拒绝，不截断。 */
    List<DepartmentVO> departments();
    /** 要求 dept:update 和全局安全管理；先锁协调行和当前账号，再校验版本、引用和授权上限；成功审计与配置同事务，任何失败整体回滚。id 为空新建；停用保留历史。 */
    ConfigurationChangeVO saveDepartment(String id, DepartmentSaveDTO command);
    /** 新建配置；权限、协调锁及失败回滚同修改，审计业务类型为 INSERT。 */
    ConfigurationChangeVO createDepartment(DepartmentSaveDTO command);
    /** 要求 menu:list 及全部部门范围的 security:administer；返回最多 1000 项，超过上限明确拒绝，不截断。 */
    List<ManagedMenuVO> menus();
    /** 要求 menu:update 和全局安全管理；先锁协调行和当前账号，再校验版本、引用和授权上限；成功审计与配置同事务，任何失败整体回滚。id 为空新建；停用保留历史。 */
    ConfigurationChangeVO saveManagedMenu(String id, ManagedMenuSaveDTO command);
    /** 新建配置；权限、协调锁及失败回滚同修改，审计业务类型为 INSERT。 */
    ConfigurationChangeVO createManagedMenu(ManagedMenuSaveDTO command);
    /** 要求 dict:list 及全部部门范围的 security:administer；返回最多 1000 项，超过上限明确拒绝，不截断。 */
    List<DictionaryTypeVO> dictionaryTypes();
    /** 要求 dict:update 和全局安全管理；先锁协调行和当前账号，再校验版本、引用和授权上限；成功审计与配置同事务，任何失败整体回滚。id 为空新建；停用保留历史。 */
    ConfigurationChangeVO saveDictionaryType(String id, DictionaryTypeSaveDTO command);
    /** 新建配置；权限、协调锁及失败回滚同修改，审计业务类型为 INSERT。 */
    ConfigurationChangeVO createDictionaryType(DictionaryTypeSaveDTO command);
    /** 要求 dict:list 及全部部门范围的 security:administer；返回最多 1000 项，超过上限明确拒绝，不截断。 */
    List<DictionaryItemVO> dictionaryItems(String typeId);
    /** 要求 dict:update 和全局安全管理；先锁协调行和当前账号，再校验版本、引用和授权上限；成功审计与配置同事务，任何失败整体回滚。id 为空新建；停用保留历史。 */
    ConfigurationChangeVO saveDictionaryItem(String id, DictionaryItemSaveDTO command);
    /** 新建配置；权限、协调锁及失败回滚同修改，审计业务类型为 INSERT。 */
    ConfigurationChangeVO createDictionaryItem(DictionaryItemSaveDTO command);
}
