package com.adtek.sn.service.impl;
import com.adtek.sn.convert.*;
import com.adtek.sn.mapper.*;
import com.adtek.sn.service.DashboardService;
import com.adtek.sn.service.support.*;
import com.adtek.sn.vo.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service public class DashboardServiceImpl implements DashboardService {
    private final SnQueryMapper rows;private final TransferMapper transfers;private final SnQueryPolicy policy;private final SnQueryConvert convert;private final SnConvert sn;private final FactoryMapper factories;
    public DashboardServiceImpl(SnQueryMapper rows,TransferMapper transfers,SnQueryPolicy policy,SnQueryConvert convert,SnConvert sn,FactoryMapper factories){this.rows=rows;this.transfers=transfers;this.policy=policy;this.convert=convert;this.sn=sn;this.factories=factories;}
    @Override @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public DashboardVO overview() {
        var actor=policy.read(SnQueryPolicy.DASHBOARD);var scope=policy.basic(actor,SnQueryPolicy.DASHBOARD);var zone=ZoneId.of("Asia/Shanghai");var date=LocalDate.now(zone);
        String pending=null;List<SnWorkVO> recent=List.of();
        if(actor.permissions().contains(TransferPolicy.VIEW)) {
            var both=policy.intersection(scope,policy.basic(actor,TransferPolicy.VIEW));
            pending=Long.toString(transfers.count(new TransferMapper.Scope(both.all(),both.factories(),null),null,"PENDING"));
        }
        var jobs=actor.grants().get("sn:generation:view");
        if(jobs!=null&&jobs.allFactories())recent=rows.recent(scope).stream().map(row->{var factory=row.factoryId()==null?null:factories.selectById(row.factoryId());return sn.work(row,factory==null?"":factory.getCode());}).toList();
        return new DashboardVO(rows.states(scope).stream().map(convert::count).toList(),pending,scope.all()?Long.toString(rows.generated()):null,scope.all()?Long.toString(rows.imported()):null,
            rows.today(scope,date.atStartOfDay(zone).toInstant(),date.plusDays(1).atStartOfDay(zone).toInstant()).stream().map(convert::count).toList(),recent,date.toString(),zone.getId(),scope.all());
    }
}
