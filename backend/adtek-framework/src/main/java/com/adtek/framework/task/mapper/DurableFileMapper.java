package com.adtek.framework.task.mapper;

import com.adtek.framework.task.domain.FileEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 存储键只接受框架生成值；写入与任务控制行同事务，下载读取仍需当前业务授权。 */
@Mapper
public interface DurableFileMapper {
    FileEntity find(@Param("id") String id);

    int insert(@Param("id") String id, @Param("key") String key, @Param("rows") long rows);

    int metadata(@Param("id") String id, @Param("name") String name, @Param("type") String type);

    int ready(@Param("id") String id, @Param("digest") String digest, @Param("bytes") long bytes);

    int state(@Param("id") String id, @Param("state") String state);
}
