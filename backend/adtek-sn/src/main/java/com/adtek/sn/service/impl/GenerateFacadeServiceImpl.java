package com.adtek.sn.service.impl;

import com.adtek.sn.dto.GenDeleteDTO;
import com.adtek.sn.dto.GenPreviewDTO;
import com.adtek.sn.dto.PiLinesDTO;
import com.adtek.sn.service.GenerateService;
import com.adtek.sn.service.OrderService;
import com.adtek.sn.support.Camel;
import com.adtek.sn.support.CurrentUser;
import com.adtek.sn.support.SnSettings;
import com.adtek.sn.vo.RevokeConfigVO;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 生成页接口：解析当前操作人后委派生成、删号、工作台用例，文档型结果转 camelCase。 */
@Service
public class GenerateFacadeServiceImpl implements GenerateService {
    private final GenerateServiceImpl generate;
    private final DeletionServiceImpl deletion;
    private final WorkbenchServiceImpl workbench;
    private final OrderService orders;
    private final SnSettings settings;

    public GenerateFacadeServiceImpl(
            GenerateServiceImpl generate,
            DeletionServiceImpl deletion,
            WorkbenchServiceImpl workbench,
            OrderService orders,
            SnSettings settings) {
        this.generate = generate;
        this.deletion = deletion;
        this.workbench = workbench;
        this.orders = orders;
        this.settings = settings;
    }

    private CurrentUser cu() {
        return CurrentUser.current(settings.initAdminEmpNo()).requireOperator();
    }

    @Override
    public Map<String, Object> pis(String q, String tab) {
        return workbench.pis(cu(), q, tab, true);
    }

    @Override
    public Map<String, Object> context(String pi, String billNo) {
        return workbench.context(cu(), pi, billNo);
    }

    @Override
    public Map<String, Object> preview(GenPreviewDTO b) {
        return Camel.map(
                generate.preview(cu(), b.piNo, b.scope, b.bills, b.lines, b.seqMode, b.seqStart, b.seqEnd));
    }

    @Override
    public Map<String, Object> gaps(String pi) {
        return Camel.map(generate.gaps(cu(), pi));
    }

    @Override
    public Map<String, Object> segments(String pi, String billNo) {
        return Camel.map(generate.billSegments(cu(), pi, billNo));
    }

    @Override
    public Map<String, Object> segmentSns(
            String pi,
            String billNo,
            String seqPi,
            Long start,
            Long end,
            Integer page,
            Integer size,
            Integer billSeq) {
        return Camel.map(generate.segmentSns(cu(), pi, billNo, seqPi, start, end, page, size, billSeq));
    }

    @Override
    public Map<String, Object> run(String token) {
        return Camel.map(generate.generate(cu(), token));
    }

    @Override
    public List<Map<String, Object>> jobs(String pi, Integer limit) {
        CurrentUser cu = cu();
        return Camel.list(generate.jobs(pi, limit == null ? 20 : limit, cu.readFactories(null)));
    }

    @Override
    public Map<String, Object> progress(String batch) {
        return Camel.map(generate.progress(cu(), batch));
    }

    @Override
    public Map<String, Object> cancel(String batch) {
        return Camel.map(generate.cancel(cu(), batch));
    }

    @Override
    public Map<String, Object> deletePreview(GenDeleteDTO b) {
        return Camel.map(deletion.preview(cu(), b.piNo, b.bills, b.lines, b.startSn, b.endSn));
    }

    @Override
    public Map<String, Object> delete(GenDeleteDTO b) {
        return Camel.map(deletion.delete(cu(), b.piNo, b.bills, b.lines, b.startSn, b.endSn, b.reason));
    }

    @Override
    public List<Map<String, Object>> deleted(String pi) {
        return Camel.list(deletion.deleted(cu(), pi, 200));
    }

    @Override
    public RevokeConfigVO revokeConfig(PiLinesDTO body) {
        return orders.revokeConfig(body.piNo, body.lines);
    }
}
