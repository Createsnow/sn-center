package com.adtek.sn.service.impl;

import com.adtek.framework.task.service.DurableTaskService;
import com.adtek.sn.convert.*;
import com.adtek.sn.mapper.*;
import com.adtek.sn.service.DashboardService;
import com.adtek.sn.service.support.*;
import com.adtek.sn.vo.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;

@Service
public class DashboardServiceImpl implements DashboardService {
    private final SnQueryMapper rows;
    private final TransferMapper transfers;
    private final SnQueryPolicy policy;
    private final SnQueryConvert convert;
    private final SnConvert sn;
    private final FactoryMapper factories;
    private final DurableTaskService tasks;
    private final ObjectMapper json;

    public DashboardServiceImpl(
            SnQueryMapper rows,
            TransferMapper transfers,
            SnQueryPolicy policy,
            SnQueryConvert convert,
            SnConvert sn,
            FactoryMapper factories,
            DurableTaskService tasks,
            ObjectMapper json) {
        this.rows = rows;
        this.transfers = transfers;
        this.policy = policy;
        this.convert = convert;
        this.sn = sn;
        this.factories = factories;
        this.tasks = tasks;
        this.json = json;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DashboardVO overview() {
        var actor = policy.read(SnQueryPolicy.DASHBOARD);
        var scope = policy.basic(actor, SnQueryPolicy.DASHBOARD);
        var zone = ZoneId.of("Asia/Shanghai");
        var date = LocalDate.now(zone);
        String pending = null;
        List<SnWorkVO> recent = List.of();
        List<DashboardJobVO> jobs = new ArrayList<>();
        if (actor.permissions().contains(TransferPolicy.VIEW)) {
            var both = policy.intersection(scope, policy.basic(actor, TransferPolicy.VIEW));
            pending =
                    Long.toString(
                            transfers.count(
                                    new TransferMapper.Scope(both.all(), both.factories(), null),
                                    null,
                                    "PENDING"));
        }
        var generation = actor.grants().get("sn:generation:execute");
        if (generation != null && generation.allFactories())
            recent =
                    rows.recent(scope).stream()
                            .map(
                                    row -> {
                                        var factory =
                                                row.factoryId() == null
                                                        ? null
                                                        : factories.selectById(row.factoryId());
                                        var code = factory == null ? "" : factory.getCode();
                                        var input =
                                                json.readValue(row.payload(), SnWorkInput.class);
                                        var progress = tasks.businessProgress(row.taskId());
                                        // 订单取生成提交时的快照；重试进度只取当前执行代次。
                                        long processed =
                                                "SUCCESS".equals(row.state())
                                                        ? row.quantity()
                                                        : "FAILED".equals(row.state())
                                                                ? 0
                                                                : Math.min(
                                                                        row.quantity(),
                                                                        Long.parseLong(
                                                                                progress
                                                                                        .processed()));
                                        jobs.add(
                                                new DashboardJobVO(
                                                        Long.toString(
                                                                rows.generationNumber(
                                                                        row.taskId())),
                                                        row.taskId(),
                                                        input.plan().bill().getBillNo(),
                                                        row.pi(),
                                                        code,
                                                        Long.toString(row.quantity()),
                                                        Long.toString(processed),
                                                        row.state(),
                                                        row.createdAt()));
                                        return sn.work(
                                                row,
                                                code,
                                                Long.toString(rows.generationNumber(row.taskId())),
                                                Long.toString(processed),
                                                GenerationErrors.read(
                                                        json, row.errorCode(), row.errorParams()),
                                                row.firstSn() == null
                                                        ? SequenceCodec.format(
                                                                input.plan().start(),
                                                                input.plan().spec())
                                                        : row.firstSn(),
                                                row.lastSn() == null
                                                        ? SequenceCodec.format(
                                                                input.plan().end(),
                                                                input.plan().spec())
                                                        : row.lastSn());
                                    })
                            .toList();
        return new DashboardVO(
                rows.states(scope).stream().map(convert::count).toList(),
                pending,
                scope.all() ? Long.toString(rows.generated()) : null,
                scope.all() ? Long.toString(rows.imported()) : null,
                rows
                        .today(
                                scope,
                                date.atStartOfDay(zone).toInstant(),
                                date.plusDays(1).atStartOfDay(zone).toInstant())
                        .stream()
                        .map(convert::count)
                        .toList(),
                recent,
                jobs,
                date.toString(),
                zone.getId(),
                scope.all());
    }
}
