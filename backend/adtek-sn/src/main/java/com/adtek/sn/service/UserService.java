package com.adtek.sn.service;

import com.adtek.sn.dto.UserCreateDTO;
import com.adtek.sn.dto.UserUpdateDTO;
import com.adtek.sn.query.UserQuery;
import com.adtek.sn.vo.PageVO;
import com.adtek.sn.vo.SecretVO;
import com.adtek.sn.vo.UserVO;

/** 账户管理（F-01）：只有系统管理员账户；账户只停用不删除。 */
public interface UserService {
    PageVO<UserVO> list(UserQuery q);

    UserVO create(UserCreateDTO body);

    UserVO update(long id, UserUpdateDTO body);

    UserVO disable(long id);

    UserVO enable(long id);

    UserVO resetPassword(long id, String password);

    UserVO resetSecret(long id);

    SecretVO viewSecret(long id);
}
