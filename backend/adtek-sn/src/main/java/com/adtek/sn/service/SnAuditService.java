package com.adtek.sn.service;

import com.adtek.common.core.*;
import com.adtek.framework.task.vo.TaskVO;
import com.adtek.sn.dto.SnAuditExportDTO;
import com.adtek.sn.query.SnAuditQuery;
import com.adtek.sn.vo.*;

/** 原SN操作痕迹页面用例；人员权限按历史原厂/目标厂复核，SERVICE不可登录此页面。 */
public interface SnAuditService {
    /** 稳定时间+ID游标，默认上海最近30天；在线/归档/合并同范围，无跨厂PI例外。 */
    CursorResult<SnAuditVO> items(SnAuditQuery query, String cursor, int size);

    /** 精确计数与列表同过滤；全部工厂范围才可读无工厂的全局配置事实。 */
    SnCountVO count(SnAuditQuery query);

    /** 以时间与不可变ID读取历史完整白名单详情，越权等同不存在。 */
    SnAuditVO get(String id, java.time.Instant createdAt);

    /** 返回当前永久保留与导出准入；不暗示归档记录可被删除。 */
    SnAuditMetaVO meta();

    /** 同操作号复用固定快照，单条SQL固定一致视图；超准入整事务回滚，文件失败只恢复原快照。 */
    TaskVO export(SnAuditExportDTO command);

    /** 查询原主体格式/操作号的结果，当前范围收缩立即拒绝。 */
    TaskVO operation(String format, String operationId);

    /** 读取本人导出记录，并对每份原范围重新授权；其他人的文件不可见。 */
    PageResult<SnExportVO> exports(int page, int size);
}
