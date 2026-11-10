package com.adtek.sn.mapper;

import com.adtek.sn.domain.BillStatEntity;
import com.adtek.sn.vo.PiBillVO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/** 单据汇总与待重算标记（sn_bill_stat、sn_bill_stat_dirty）。 */
@Mapper
public interface BillStatMapper {
    List<PiBillVO> pairsOfItems(@Param("ids") Collection<Long> ids);

    List<PiBillVO> pairsOfBatch(@Param("batchNo") String batchNo);

    List<PiBillVO> pairsOfBatchItems(@Param("batchId") long batchId);

    List<String> pisOfBill(@Param("billNo") String billNo);

    List<PiBillVO> pairsOfFactory(
            @Param("factoryCode") String factoryCode, @Param("status") String status);

    int markDirty(@Param("rows") List<PiBillVO> rows, @Param("now") LocalDateTime now);

    boolean anyDirty();

    List<PiBillVO> dirty(@Param("limit") int limit);

    List<BillStatEntity> computeBill(@Param("pi") String pi, @Param("bill") String bill);

    List<BillStatEntity> computeAll();

    int deleteBill(@Param("pi") String pi, @Param("bill") String bill);

    int deleteAll();

    int insertStats(@Param("rows") List<BillStatEntity> rows, @Param("now") LocalDateTime now);

    int deleteDirty(@Param("pi") String pi, @Param("bill") String bill, @Param("ver") long ver);

    boolean anyStat();

    boolean anyItem();

    List<PiBillVO> recentPairs(@Param("limit") int limit);

    List<PiBillVO> allStatPairs();

    List<PiBillVO> allDirtyPairs();

    List<PiBillVO> allItemPairs();

    boolean dirtyFor(@Param("pi") String pi, @Param("bill") String bill);

    List<BillStatEntity> storedBill(@Param("pi") String pi, @Param("bill") String bill);
}
