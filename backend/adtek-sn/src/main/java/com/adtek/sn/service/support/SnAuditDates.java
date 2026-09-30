package com.adtek.sn.service.support;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Component;
/** 上海业务自然日的取时边界；相对日期只在首次固定快照时解析，不参与原意图的重试摘要。 */
@Component public class SnAuditDates {
    public LocalDate today(){return LocalDate.now(ZoneId.of("Asia/Shanghai"));}
}
