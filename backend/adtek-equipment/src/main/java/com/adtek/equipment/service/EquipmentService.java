package com.adtek.equipment.service;

import com.adtek.common.core.PageResult;
import com.adtek.equipment.dto.*;
import com.adtek.equipment.query.EquipmentQuery;
import com.adtek.equipment.vo.EquipmentVO;

/** 设备用例；每项权限独立装配部门/本人范围，写入与成功审计共同提交。 */
public interface EquipmentService {
    /** 有界分页；只能返回当前 list 权限可见记录。 */
    PageResult<EquipmentVO> list(EquipmentQuery query);

    /** 按 detail 权限读取；不可见对象同不存在一起返回 404。 */
    EquipmentVO detail(String id);

    /** 所有者与部门取当前身份，编码由数据库保证唯一。 */
    EquipmentVO create(EquipmentCreateDTO command);

    /** 原版本不匹配返回 409；权限撤销与写入通过安全协调锁串行。 */
    EquipmentVO update(String id, EquipmentUpdateDTO command);
}
