package com.adtek.sn.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 内部起号扫描；调用方有全厂生成/初始化或全厂转厂读取权限，写用例持快照/PI锁。 */
@Mapper
public interface IncomingSnMapper {
    @Select(
            "<script>SELECT sn FROM sn_key WHERE pi=#{pi}"
                    + " AND (seq_pi IS NULL OR seq_pi&lt;&gt;#{pi})"
                    + " AND CHAR_LENGTH(sn)=#{length}"
                    + "<if test='after!=null'> AND sn&gt;#{after}</if>"
                    + " ORDER BY sn LIMIT 1000</script>")
    List<String> page(
            @Param("pi") String pi, @Param("length") int length, @Param("after") String after);
}
