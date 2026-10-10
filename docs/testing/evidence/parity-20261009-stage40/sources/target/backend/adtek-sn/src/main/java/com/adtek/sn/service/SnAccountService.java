package com.adtek.sn.service;

import com.adtek.sn.dto.SnAccountCreateDTO;
import com.adtek.sn.dto.SnAccountUpdateDTO;
import com.adtek.sn.vo.SnAccountSavedVO;

/** SN三业务角色/单厂账号用例；通用扩展账号仍由平台管理。 */
public interface SnAccountService {
    SnAccountSavedVO create(SnAccountCreateDTO command);

    void update(String id, SnAccountUpdateDTO command);
}
