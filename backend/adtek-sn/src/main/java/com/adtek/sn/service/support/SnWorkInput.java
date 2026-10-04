package com.adtek.sn.service.support;

import com.adtek.sn.domain.*;

import java.util.List;

/** 服务器生成的不可变命令值；由sn_work/preview的payload保存，不是客户端可填的Entity。 */
public record SnWorkInput(
        String pi,
        Long billId,
        Long factoryId,
        String customer,
        String material,
        Long requestedStart,
        List<String> sns,
        String fileName,
        Long ruleId,
        Long ruleNumber,
        String ruleDigest,
        Plan plan,
        List<Member> members) {
    /** 分配固定成员只保存分区键与不可变ID，避免重复存储整枚来源/SN文本。 */
    public record Member(java.time.LocalDate genMonth, String id) {}

    public record Plan(
            OrderBillEntity bill,
            List<Segment> segments,
            long baseLast,
            boolean startLocked,
            Long requestedStart,
            long quantity,
            long start,
            long end,
            long ruleId,
            long ruleNumber,
            long ruleVersionId,
            String ruleDigest,
            SequenceSpec spec,
            Long incomingMax,
            String incomingSn) {}

    public record Segment(
            String entryId,
            long ordinal,
            String material,
            long quantity,
            long start,
            long end,
            long entryVersion,
            long consumed) {}
}
