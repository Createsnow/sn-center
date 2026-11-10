package com.adtek.sn.service;

import com.adtek.sn.dto.LineSelectDTO;
import com.adtek.sn.dto.PiBindDTO;
import com.adtek.sn.dto.PiLinesDTO;
import com.adtek.sn.dto.RuleCreateDTO;
import com.adtek.sn.dto.RulePreviewDTO;
import com.adtek.sn.dto.RuleUpdateDTO;
import com.adtek.sn.vo.RuleActionVO;
import com.adtek.sn.vo.RuleOptionVO;
import com.adtek.sn.vo.RulePreviewVO;
import com.adtek.sn.vo.RuleViewVO;

import java.util.List;

/** 规则库与规则选取（F-08）：系统管理员、普通操作员（全局规则只能选用，授权组织的规则可增删改）。 */
public interface RuleService {
    List<RuleViewVO> list(String q, String category, String categoryValue);

    RuleViewVO create(RuleCreateDTO body);

    RuleActionVO update(long id, RuleUpdateDTO body);

    RuleActionVO delete(long id);

    RulePreviewVO preview(RulePreviewDTO body);

    List<RuleOptionVO> options(String pi, String customer);

    RuleActionVO selectForLines(LineSelectDTO body);

    RuleActionVO clearLines(PiLinesDTO body);

    RuleActionVO bindPi(PiBindDTO body);

    RuleActionVO unbindPi(String pi);
}
