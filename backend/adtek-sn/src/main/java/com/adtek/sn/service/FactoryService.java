package com.adtek.sn.service;

import com.adtek.sn.dto.MesConfigDTO;
import com.adtek.sn.vo.FactorySyncVO;
import com.adtek.sn.vo.FactoryToggleVO;
import com.adtek.sn.vo.FactoryVO;
import com.adtek.sn.vo.MesTestVO;

import java.util.List;

/** 生产组织（金蝶同步，只读）与 MES 对接配置（只有系统管理员）。 */
public interface FactoryService {
    /** 生产组织列表（任何页面账户）；系统管理员另看 MES 配置与绑定的启用账户数。 */
    List<FactoryVO> list();

    /** 从金蝶组织机构同步（系统管理员）；金蝶调用失败直接抛出，本地列表不动。 */
    FactorySyncVO sync();

    /** 保存 MES 对接配置；已开启对接时改了连接配置须先连接测试通过。 */
    FactoryVO saveConfig(String code, MesConfigDTO body);

    /** 连接测试：发一个不含号的 PING 批次，只提示结果；通过时记下配置摘要。 */
    MesTestVO test(String code, MesConfigDTO body);

    /** 切换前的提示。 */
    FactoryToggleVO togglePreview(String code);

    /** 开启 / 关闭「对接 MES」。 */
    FactoryVO setMesEnabled(String code, boolean enabled);
}
