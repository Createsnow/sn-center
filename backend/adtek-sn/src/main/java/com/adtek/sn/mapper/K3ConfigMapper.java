package com.adtek.sn.mapper;

import com.adtek.sn.domain.K3ConfigEntity;
import com.adtek.sn.vo.AuditBriefVO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 金蝶连接参数（sn_k3_config）与相关痕迹。 */
@Mapper
public interface K3ConfigMapper {
    K3ConfigEntity selectStored();

    K3ConfigEntity lockStored();

    int insert(@Param("entity") K3ConfigEntity entity);

    int update(@Param("entity") K3ConfigEntity entity);

    int delete();

    AuditBriefVO lastAction(@Param("action") String action);

    List<AuditBriefVO> okHistory(@Param("action") String action, @Param("limit") int limit);
}
