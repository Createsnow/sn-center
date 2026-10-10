package com.adtek.system.service;

import com.adtek.common.core.PageResult;
import com.adtek.system.dto.*;
import com.adtek.system.query.UserQuery;
import com.adtek.system.vo.*;

import java.util.List;

/** 用户管理用例；业务 ID 使用十进制文本，缺权/越范围拒绝，停用保留历史身份。 */
public interface UserManagementService {
    /** 按 list 权限范围查询；空范围为空页，COUNT 与结果执行同一 SQL 规则。 */
    PageResult<ManagedUserVO> list(UserQuery query);

    /** 内部业务端口读取单个可见人员；与列表相同数据范围，不能用认证Mapper绕开。 */
    ManagedUserVO get(String id);

    /** 指定 add/update 操作的部门选项；角色绑定需额外 security:administer，提交时仍复核。 */
    UserOptionsVO options(String operation);

    /** 当前授权菜单；客户端仅把已实现 component 映射到本地组件，不能动态加载远程代码。 */
    List<MenuVO> menus();

    /** 事务内校验唯一工号、目标部门/角色范围，保存强制改密账号及成功审计；任何失败整体回滚。 */
    ManagedUserVO create(UserCreateDTO command);

    /** 版本冲突 409；角色/部门/资料修改同事务授权及审计，权限收缩撤销旧会话。 */
    void update(String id, UserUpdateDTO command);

    /** SN业务资料编辑保留登录，每次请求即时读取新权限；授权并发版本仍递增，不接受客户端会话策略。 */
    void updatePreservingSession(String id, UserUpdateDTO command);

    /** 本人不能停用；至少保留一个启用的安全管理员，协调锁防止并发各自通过。 */
    void status(String id, UserStatusDTO command);

    /** 不允许本人重置；不允许管理高于自身授权的账号，成功后目标全部旧会话失效并强制改密。 */
    void resetPassword(String id, UserPasswordResetDTO command);
}
