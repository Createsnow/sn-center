package com.adtek.sn.service;

import com.adtek.sn.dto.GenDeleteDTO;
import com.adtek.sn.dto.GenPreviewDTO;
import com.adtek.sn.dto.PiLinesDTO;
import com.adtek.sn.vo.RevokeConfigVO;

import java.util.List;
import java.util.Map;

/** SN 生成与删号（F-09、F-10）：系统管理员、普通操作员（只含授权组织的单据）。返回的生成计划、任务等为文档型数据（camelCase 键）。 */
public interface GenerateService {
    Map<String, Object> pis(String q, String tab);

    Map<String, Object> context(String pi, String billNo);

    Map<String, Object> preview(GenPreviewDTO body);

    Map<String, Object> gaps(String pi);

    Map<String, Object> segments(String pi, String billNo);

    Map<String, Object> segmentSns(
            String pi,
            String billNo,
            String seqPi,
            Long start,
            Long end,
            Integer page,
            Integer size,
            Integer billSeq);

    Map<String, Object> run(String token);

    List<Map<String, Object>> jobs(String pi, Integer limit);

    Map<String, Object> progress(String batch);

    Map<String, Object> cancel(String batch);

    Map<String, Object> deletePreview(GenDeleteDTO body);

    Map<String, Object> delete(GenDeleteDTO body);

    List<Map<String, Object>> deleted(String pi);

    RevokeConfigVO revokeConfig(PiLinesDTO body);
}
