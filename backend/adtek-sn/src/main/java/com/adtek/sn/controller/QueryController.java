package com.adtek.sn.controller;

import com.adtek.common.core.Result;
import com.adtek.sn.query.SnItemQuery;
import com.adtek.sn.service.QueryService;
import com.adtek.sn.support.PageReq;
import com.adtek.sn.support.Xlsx;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.Map;

/** SN 查询（只读，不能在这里转走号）。物料为空也是一个分组：传 materialCode= 时按空物料查。 */
@RestController
@RequestMapping("/api/v1/sn/sn")
public class QueryController {
    private final QueryService service;

    public QueryController(QueryService service) {
        this.service = service;
    }

    private static SnItemQuery query(
            String factoryCode,
            String customerCode,
            String pi,
            String billNo,
            String materialCode,
            String length,
            String status,
            String sn,
            Boolean piAll,
            String seqFrom,
            String seqTo) {
        SnItemQuery q = new SnItemQuery();
        q.factoryCode = factoryCode;
        q.customerCode = customerCode;
        q.pi = pi;
        q.billNo = billNo;
        q.materialCode = materialCode;
        q.length = length;
        q.status = status;
        q.sn = sn;
        q.piAll = Boolean.TRUE.equals(piAll);
        q.seqFrom = seqFrom;
        q.seqTo = seqTo;
        return q;
    }

    @GetMapping
    public Result<Map<String, Object>> list(
            @RequestParam(required = false) String factoryCode,
            @RequestParam(required = false) String customerCode,
            @RequestParam(required = false) String pi,
            @RequestParam(required = false) String billNo,
            @RequestParam(required = false) String materialCode,
            @RequestParam(required = false) String length,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sn,
            @RequestParam(required = false) Boolean piAll,
            @RequestParam(required = false) String seqFrom,
            @RequestParam(required = false) String seqTo,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return Result.ok(
                service.list(
                        query(
                                factoryCode,
                                customerCode,
                                pi,
                                billNo,
                                materialCode,
                                length,
                                status,
                                sn,
                                piAll,
                                seqFrom,
                                seqTo),
                        PageReq.of(page, pageSize)));
    }

    @GetMapping("/export-check")
    public Result<Map<String, Object>> exportCheck(
            @RequestParam(required = false) String factoryCode,
            @RequestParam(required = false) String customerCode,
            @RequestParam(required = false) String pi,
            @RequestParam(required = false) String billNo,
            @RequestParam(required = false) String materialCode,
            @RequestParam(required = false) String length,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sn,
            @RequestParam(required = false) Boolean piAll,
            @RequestParam(required = false) String seqFrom,
            @RequestParam(required = false) String seqTo) {
        return Result.ok(
                service.exportCheck(
                        query(
                                factoryCode,
                                customerCode,
                                pi,
                                billNo,
                                materialCode,
                                length,
                                status,
                                sn,
                                piAll,
                                seqFrom,
                                seqTo)));
    }

    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam(required = false) String factoryCode,
            @RequestParam(required = false) String customerCode,
            @RequestParam(required = false) String pi,
            @RequestParam(required = false) String billNo,
            @RequestParam(required = false) String materialCode,
            @RequestParam(required = false) String length,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sn,
            @RequestParam(required = false) Boolean piAll,
            @RequestParam(required = false) String seqFrom,
            @RequestParam(required = false) String seqTo) {
        return Xlsx.response(
                service.export(
                        query(
                                factoryCode,
                                customerCode,
                                pi,
                                billNo,
                                materialCode,
                                length,
                                status,
                                sn,
                                piAll,
                                seqFrom,
                                seqTo)));
    }
}
