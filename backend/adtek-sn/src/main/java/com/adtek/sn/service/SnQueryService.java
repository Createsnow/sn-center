package com.adtek.sn.service;

import com.adtek.common.core.*;
import com.adtek.framework.task.vo.TaskVO;
import com.adtek.sn.dto.SnExportDTO;
import com.adtek.sn.query.SnItemQuery;
import com.adtek.sn.vo.*;

/** 原SN查询/导出用例；只读不会领取、打印或修改当前归属。 */
public interface SnQueryService {
    /** 全部原筛选的稳定keyset页；游标绑定主体/授权版本/完整筛选，当前明细随业务合法变化。 */
    CursorResult<SnQueryItemVO> items(SnItemQuery query, String cursor, int size);

    /** 页面按原型顺序分页；编号排序使用不可变创建顺序，不依赖随机 UUID。 */
    SnQueryPageVO page(SnItemQuery query, int page, int size);

    /** 相同当前范围和筛选的精确数量；不以旧100000展示截断冒充总量。 */
    SnCountVO count(SnItemQuery query);

    /** 在写协调锁保护的一致视图固定全部行，文件任务只消费快照；超资源准入整笔拒绝。 */
    TaskVO export(SnExportDTO command);

    /** 原操作查询不创建新快照；格式参与操作动作与载荷契约。 */
    TaskVO operation(String format, String operation);

    /** 本人固定导出历史；每项须仍具备全部原成员范围，下载亦逐块检查。 */
    PageResult<SnExportVO> exports(int page, int size);
}
