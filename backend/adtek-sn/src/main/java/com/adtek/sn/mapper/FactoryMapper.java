package com.adtek.sn.mapper;

import com.adtek.sn.domain.FactoryEntity;
import com.adtek.sn.vo.FactoryToggleVO;
import com.adtek.sn.vo.KeyCountVO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 生产组织与 MES 对接配置（sn_factory），及开关对接时涉及的号池、推送任务、批次。 */
@Mapper
public interface FactoryMapper {
    List<FactoryEntity> selectAll();

    FactoryEntity get(@Param("code") String code);

    FactoryEntity lock(@Param("code") String code);

    List<String> disabledCodes();

    List<String> mesCodes();

    boolean isMes(@Param("code") String code);

    Integer tzOffset(@Param("code") String code);

    List<KeyCountVO> boundUsers();

    int insert(
            @Param("code") String code,
            @Param("name") String name,
            @Param("now") LocalDateTime now);

    int rename(
            @Param("code") String code,
            @Param("name") String name,
            @Param("now") LocalDateTime now);

    int setEnabled(
            @Param("code") String code,
            @Param("enabled") boolean enabled,
            @Param("now") LocalDateTime now);

    int updateTest(
            @Param("code") String code,
            @Param("tested") String tested,
            @Param("now") LocalDateTime now,
            @Param("message") String message,
            @Param("status") String status);

    int updateConfig(@Param("entity") FactoryEntity entity);

    long activeTasks(@Param("code") String code);

    List<FactoryToggleVO.BatchBrief> openBatches(@Param("code") String code);

    long openBatchTotal(@Param("code") String code);

    long openBatchQty(@Param("code") String code);

    long reassignQty(@Param("code") String code);

    long allocatedCount(@Param("code") String code);

    long unbatchedCount(@Param("code") String code);

    int allocatedToPush(@Param("code") String code, @Param("now") LocalDateTime now);

    int unbatchedToAllocated(@Param("code") String code, @Param("now") LocalDateTime now);

    int deleteFinishedTasks(@Param("code") String code);

    int setMesEnabled(
            @Param("code") String code,
            @Param("enabled") boolean enabled,
            @Param("now") LocalDateTime now);
}
