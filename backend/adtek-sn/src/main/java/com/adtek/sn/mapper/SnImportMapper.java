package com.adtek.sn.mapper;

import com.adtek.sn.domain.SnImportChunkEntity;

import org.apache.ibatis.annotations.*;

/** 只由初始化用例在任务登记/执行事务访问；固定输入不能从客户端修改。 */
@Mapper
public interface SnImportMapper {
    @Insert(
            "INSERT INTO sn_import_chunk(task_id,ordinal,quantity,sns)"
                    + " VALUES(#{taskId},#{ordinal},#{quantity},#{sns})")
    int insert(SnImportChunkEntity chunk);

    @Select(
            "SELECT task_id,ordinal,quantity,CAST(sns AS CHAR) sns FROM sn_import_chunk"
                    + " WHERE task_id=#{task} AND ordinal>=#{ordinal} ORDER BY ordinal LIMIT 1")
    SnImportChunkEntity next(@Param("task") String task, @Param("ordinal") int ordinal);
}
