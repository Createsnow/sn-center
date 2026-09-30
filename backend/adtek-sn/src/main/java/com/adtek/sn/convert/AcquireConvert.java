package com.adtek.sn.convert;

import com.adtek.sn.domain.*;
import com.adtek.sn.vo.*;

import org.mapstruct.*;

@Mapper
public interface AcquireConvert {
    @Mapping(target = "factoryId", source = "row.factoryId")
    @Mapping(target = "factoryCode", source = "factory.code")
    @Mapping(target = "factoryName", source = "factory.name")
    com.adtek.sn.service.support.AcquireSnapshot snapshot(
            SnCurrentEntity row, com.adtek.sn.domain.FactoryEntity factory, String seqText);

    AcquireSummaryVO summary(AcquireSummaryEntity row);

    AcquireSegmentVO segment(AcquireSegmentEntity row);

    AcquireBatchVO batch(AcquireBatchEntity row);

    AcquireWorkVO work(AcquireWorkEntity row);

    AcquireResultVO result(AcquireResultEntity row);

    PrintVO print(PrintEntity row, String xlsxState, String csvState);

    SnReadItemVO item(SnCurrentEntity row, String seqText);
}
