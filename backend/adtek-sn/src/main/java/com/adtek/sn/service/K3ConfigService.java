package com.adtek.sn.service;

import com.adtek.sn.dto.K3ConfigDTO;
import com.adtek.sn.vo.K3ConfigVO;

/** 金蝶连接参数（生产订单页「金蝶连接」）：只有系统管理员；应用密钥不回显。 */
public interface K3ConfigService {
    /** 当前参数（不含应用密钥）、来源、最近连接测试与最后修改。 */
    K3ConfigVO view();

    /** 连接测试：用页面上的参数（密钥留空 = 当前的）登录金蝶并读组织机构，只提示结果、不保存（独立事务留痕）。 */
    K3ConfigVO.TestResult test(K3ConfigDTO body);

    /** 保存：先用新参数连接测试，通过才保存；失败 K3_TEST_FAILED。 */
    K3ConfigVO save(K3ConfigDTO body);

    /** 恢复默认：先用 .env 的 K3_* 连接测试，通过才删掉页面保存的参数；失败 K3_RESET_FAILED。 */
    K3ConfigVO reset();
}
