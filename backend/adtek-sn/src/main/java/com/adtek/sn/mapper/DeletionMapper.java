package com.adtek.sn.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 删号：范围内的号、存档（sn_item_deleted）、移出号池与计数退回。tg 为删号范围参数。 */
@Mapper
public interface DeletionMapper {
    Map<String, Object> genItem(@Param("sn") String sn, @Param("pi") String pi);

    List<Map<String, Object>> byStatus(@Param("tg") Map<String, Object> tg);

    List<Map<String, Object>> lines(@Param("tg") Map<String, Object> tg);

    List<String> batches(@Param("tg") Map<String, Object> tg);

    List<String> factories(@Param("tg") Map<String, Object> tg);

    List<String> pendingBills(@Param("tg") Map<String, Object> tg);

    List<String> seqPis(@Param("tg") Map<String, Object> tg);

    long transferredIn(@Param("tg") Map<String, Object> tg);

    boolean anyRunning(@Param("pis") Collection<String> pis);

    List<Long> lockIds(@Param("tg") Map<String, Object> tg);

    List<Map<String, Object>> perLine(@Param("tg") Map<String, Object> tg);

    List<Map<String, Object>> perPi(@Param("tg") Map<String, Object> tg);

    int archive(
            @Param("ids") List<Long> ids,
            @Param("delNo") String delNo,
            @Param("by") String by,
            @Param("now") LocalDateTime now,
            @Param("reason") String reason);

    int deleteItems(@Param("ids") List<Long> ids);

    int giveBack(
            @Param("billNo") String billNo,
            @Param("seq") int seq,
            @Param("n") long n,
            @Param("a") long a,
            @Param("now") LocalDateTime now);

    int reduceGenerated(
            @Param("pi") String pi, @Param("n") long n, @Param("now") LocalDateTime now);

    List<Map<String, Object>> deleted(@Param("pi") String pi, @Param("limit") int limit);
}
