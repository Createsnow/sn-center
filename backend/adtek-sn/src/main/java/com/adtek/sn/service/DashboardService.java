package com.adtek.sn.service;

import java.util.Map;

/** 首页看板（F-20）：全部页面账户，按授权范围汇总。 */
public interface DashboardService {
    Map<String, Object> load();
}
