package com.adtek.sn.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/** 在业务事务内保留单号；回滚释放保留，历史删除后也不复用已提交的号码。 */
@Mapper
public interface BusinessNumberMapper {
    @Insert("INSERT IGNORE INTO sn_business_number(number) VALUES(#{number})")
    int reserve(String number);
}
