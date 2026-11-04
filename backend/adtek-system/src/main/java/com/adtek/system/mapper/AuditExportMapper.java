package com.adtek.system.mapper;

import com.adtek.system.domain.AuditExportRowEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AuditExportMapper {
    /** 只写新登记任务的完整固定成员；与操作/任务/审计同一提交事务。 */
    int insert(@Param("rows") List<AuditExportRowEntity> rows);

    /** 固定序号扫描，调用方先验证任务所有者和完整范围；值全部绑定，limit受服务器上限约束。 */
    List<AuditExportRowEntity> page(
            @Param("id") String id, @Param("after") int after, @Param("limit") int limit);

    long count(@Param("id") String id);

    /** 当前system:audit:list范围由拦截器绑定；与总数比较验证整个产物，不只验证创建者。 */
    long visibleCount(@Param("id") String id);
}
