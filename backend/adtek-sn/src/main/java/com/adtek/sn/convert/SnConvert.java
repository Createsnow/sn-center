package com.adtek.sn.convert;

import com.adtek.sn.domain.*;
import com.adtek.sn.dto.*;
import com.adtek.sn.vo.*;

import org.mapstruct.*;

/** ID/数量/版本只在边界转为精确字符串；身份、状态、版本和时间由服务设置，不由请求覆盖。 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface SnConvert {
    // BigDecimal只在JSON边界变为普通十进制文本；不走浮点，也不把整数展示为固定4位小数。
    default String decimal(java.math.BigDecimal value) {
        return com.adtek.sn.service.support.PlannedQuantity.text(value);
    }

    PiCounterVO counter(PiCounterEntity entity);

    SnWorkVO work(SnWorkEntity entity, String factoryCode);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "source", ignore = true)
    @Mapping(target = "enabled", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    FactoryEntity factory(FactoryCreateDTO command);

    FactoryVO factory(FactoryEntity entity);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "currentVersion", ignore = true)
    @Mapping(target = "revision", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    RuleEntity rule(RuleSaveDTO command);

    @Mapping(target = "length", source = "seqLength")
    RuleVersionVO version(RuleVersionEntity entity);

    @Mapping(target = "id", source = "entity.id")
    @Mapping(target = "current", source = "current")
    RuleVO rule(RuleEntity entity, RuleVersionVO current);

    OrderSyncVO sync(OrderSyncEntity entity);

    @Mapping(target = "generatedQty", source = "generated")
    @Mapping(target = "allocatedQty", source = "allocated")
    @Mapping(target = "quota", source = "remaining")
    OrderBillVO bill(OrderBillEntity entity, String generated, String allocated, String remaining);

    @Mapping(target = "generatedQty", source = "generated")
    @Mapping(target = "allocatedQty", source = "allocated")
    @Mapping(target = "quota", source = "remaining")
    OrderLineVO line(OrderEntryEntity entity, String generated, String allocated, String remaining);
}
