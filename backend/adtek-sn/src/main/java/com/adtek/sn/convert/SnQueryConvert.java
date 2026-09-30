package com.adtek.sn.convert;

import com.adtek.sn.domain.*;
import com.adtek.sn.vo.*;

import org.mapstruct.Mapper;

@Mapper
public interface SnQueryConvert {
    SnQueryItemVO item(SnQueryItemEntity row, String seqText);

    SnExportVO export(SnExportEntity row, String fileState);

    SnCountVO count(SnCountEntity row);
}
