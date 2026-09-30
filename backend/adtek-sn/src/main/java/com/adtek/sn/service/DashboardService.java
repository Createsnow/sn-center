package com.adtek.sn.service;
import com.adtek.sn.vo.DashboardVO;
/** 首页只按当前每项权限范围汇总；今日数来自已提交业务事实，时间边界为Asia/Shanghai自然日。 */
public interface DashboardService {
    /** 未授予对应全厂/转厂/生成读取权限的敏感统计返回null或空列表，不返回全局后台任务。 */
    DashboardVO overview();
}
