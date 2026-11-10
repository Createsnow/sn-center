package com.adtek.sn.service;

import com.adtek.sn.vo.LoginVO;
import com.adtek.sn.vo.UserVO;

/** 登录、改密与当前账户。 */
public interface AuthService {
    /** 登录结果与签发的令牌（页面登录写入 Cookie）。 */
    record LoginResult(LoginVO body, String token, long ttlSeconds, boolean page) {}

    /** 登录（匿名入口）：成功、失败（含停用）都留痕；不做失败次数锁定。page = 页面登录：之前在页面上的会话全部失效（后登录的覆盖先登录的）。 */
    LoginResult login(String empNo, String password, String client, boolean page);

    UserVO me();

    /** 改自己的密码：所有会话失效（含当前页面），须用新密码重新登录。 */
    void changePassword(String oldPassword, String newPassword);

    UserVO setLang(String lang);

    /** 退出登录：吊销当前会话。 */
    void logout();
}
