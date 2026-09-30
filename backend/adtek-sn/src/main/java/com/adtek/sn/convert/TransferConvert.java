package com.adtek.sn.convert;
import org.mapstruct.Mapper;
import com.adtek.sn.domain.*;
import com.adtek.sn.vo.*;
@Mapper public interface TransferConvert {
 TransferVO transfer(TransferEntity row,String direction);
 TransferCandidateVO candidate(TransferCandidateEntity row);
 TransferTargetVO target(TransferTargetEntity row);
}
