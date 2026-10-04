package com.adtek.sn.service;

import com.adtek.common.core.PageResult;
import com.adtek.framework.task.vo.TaskVO;
import com.adtek.sn.dto.*;
import com.adtek.sn.query.SnPageQuery;
import com.adtek.sn.vo.*;

/** 初始化/准备号/整单分配用例，当前操作权限须有全部工厂范围，不能借其他权限扩大范围。 */
public interface SnWorkService {
    /** 查询PI状态不建控制行、不锁起号；view权限，历史清单原文不在响应中。 */
    PiInitializationVO initialization(String pi);

    /** 当前view权限下按PI分页查询全部初始化尝试，失败记录不表示已初始化。 */
    PageResult<SnWorkVO> imports(String pi, SnPageQuery query);

    /** 规范化并校验清单，短事务固定输入与PI活跃占用；整批历史号/护栏/水位/used/审计由持久任务原子提交。 */
    TaskVO initialize(PiInitializationDTO command);

    /** 生成页面上下文；历史消耗独立于当前快照，异常阻止生成。 */
    GenerationContextVO context(String billId);

    /** 当前generation:view全部工厂权限下的首次生成规则候选，按通用优先分页，不要求规则维护权限。 */
    PageResult<RuleVO> ruleOptions(SnPageQuery query);

    /** 当前view全部工厂权限，按稳定来源单据分页完整查看生成/分配历史，快照PI改名不丢旧记录。 */
    PageResult<SnWorkVO> history(String billId, String kind, SnPageQuery query);

    /** 预演只保存条件，不推进水位/额度；当前preview权限，最多有效期内且未绑定的原主体可提交。 */
    GenerationPreviewVO preview(GenerationPreviewDTO command);

    /** 同意图先查原任务，首次原子消耗预演并占PI；执行复核当前权限和全部条件，禁止跳号/部分提交。 */
    TaskVO generate(GenerationDTO command);

    /** 固定该来源单据全部当前待分配成员及生成时工厂；expectedQuantity不同则409，不支持部分或改投。 */
    TaskVO allocate(AllocationDTO command);

    /** 按类型与本人operationId恢复原操作；当前执行权限仍须有效，跨主体统一不存在。 */
    TaskVO operation(String kind, String operationId);
}
