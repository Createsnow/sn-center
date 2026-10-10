package com.adtek.sn.convert;

import com.adtek.sn.domain.*;
import com.adtek.sn.vo.*;

import org.mapstruct.Mapper;

@Mapper
public interface TransferConvert {
    TransferVO transfer(TransferEntity row, String direction);

    TransferCandidateVO candidate(
            TransferCandidateEntity row,
            java.util.List<String> materials,
            long duplicateCount,
            java.util.List<String> duplicateSamples);

    TransferTargetVO target(TransferTargetEntity row);

    TransferSnapshotVO snapshot(TransferSnapshotEntity row);

    @org.mapstruct.Mapping(target = "currentStatus", source = "status")
    TransferDetailVO detail(TransferDetailEntity row, String status);
}
