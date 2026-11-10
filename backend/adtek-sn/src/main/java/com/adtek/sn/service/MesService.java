package com.adtek.sn.service;

import com.adtek.sn.vo.MesReplyVO;

/**
 * MES 对外接口（原系统 /api/mes/v1，现经入口改写到 /api/v1/sn/mes）：② 取下一批、③ 提交回执、④ 已交付查询、⑤ 批次明细。
 *
 * <p>认证在用例内完成：MES 接口账户每次请求带 Authorization: Bearer &lt;工号&gt;:&lt;密钥&gt;；④⑤ 另可由页面账户用登录令牌按授权范围调用。
 * 应答体与原系统相同（成功与出错同一信封），出错不返回 200。
 */
public interface MesService {
    /** 接口：NEXT ② / RECEIPT ③ / DELIVERED ④ / ITEMS ⑤。 */
    enum Op {
        NEXT,
        RECEIPT,
        DELIVERED,
        ITEMS
    }

    MesReplyVO handle(
            Op op, String authorization, String contentEncoding, byte[] body, String client);
}
