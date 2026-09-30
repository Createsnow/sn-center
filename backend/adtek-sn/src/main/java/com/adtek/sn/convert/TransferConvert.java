package com.adtek.sn.convert;

import com.adtek.sn.domain.*;
import com.adtek.sn.vo.*;

import org.mapstruct.Mapper;

@Mapper
public interface TransferConvert {
    TransferVO transfer(TransferEntity row, String direction);

    TransferCandidateVO candidate(TransferCandidateEntity row);

    TransferTargetVO target(TransferTargetEntity row);
}
