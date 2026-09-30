package com.adtek.framework.task.mapper;

import com.adtek.framework.task.domain.FileEntity;

import org.apache.ibatis.annotations.*;

/** 存储键只接受框架生成值；写入与任务控制行同事务，下载读取仍需当前业务授权。 */
@Mapper
public interface DurableFileMapper {
    @Select(
            "SELECT"
                    + " id,task_id,state,storage_key,sha256,byte_size,row_count,created_at,"
                    + "filename,media_type"
                    + " FROM sys_file WHERE task_id=#{id}")
    FileEntity find(String id);

    @Insert(
            "INSERT INTO sys_file(id,task_id,state,storage_key,row_count)"
                    + " VALUES(#{id},#{id},'PREPARING',#{key},#{rows})")
    int insert(@Param("id") String id, @Param("key") String key, @Param("rows") long rows);

    @Update("UPDATE sys_file SET filename=#{name},media_type=#{type} WHERE task_id=#{id}")
    int metadata(@Param("id") String id, @Param("name") String name, @Param("type") String type);

    @Update(
            "UPDATE sys_file SET state='READY',sha256=#{digest},byte_size=#{bytes} WHERE"
                    + " task_id=#{id}")
    int ready(@Param("id") String id, @Param("digest") String digest, @Param("bytes") long bytes);

    @Update("UPDATE sys_file SET state=#{state} WHERE task_id=#{id}")
    int state(@Param("id") String id, @Param("state") String state);
}
