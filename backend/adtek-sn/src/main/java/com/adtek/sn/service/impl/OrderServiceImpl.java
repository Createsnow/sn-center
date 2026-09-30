package com.adtek.sn.service.impl;
import com.adtek.common.annotation.DataScope;
import com.adtek.common.core.*;
import com.adtek.framework.security.CommandValidator;
import com.adtek.sn.convert.SnConvert;
import com.adtek.sn.domain.*;
import com.adtek.sn.dto.OrderSyncDTO;
import com.adtek.sn.mapper.*;
import com.adtek.sn.query.SnPageQuery;
import com.adtek.sn.service.*;
import com.adtek.sn.service.port.ErpProductionOrderPort;
import com.adtek.sn.service.support.SnPolicy;
import com.adtek.sn.service.support.PlannedQuantity;
import com.adtek.sn.vo.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.math.BigInteger;
import java.util.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.adtek.sn.constant.ErrorCodeConstants.*;

@Service
public class OrderServiceImpl implements OrderService {
 private static final Logger LOG=LoggerFactory.getLogger(OrderServiceImpl.class);
 private final SnPolicy policy;private final CommandValidator validator;private final OrderBillMapper bills;private final OrderEntryMapper entries;private final SourceQuotaMapper quotas;private final OrderSyncMapper attempts;private final ErpProductionOrderPort erp;private final OrderPublishService publisher;private final SnConvert convert;
 public OrderServiceImpl(SnPolicy policy,CommandValidator validator,OrderBillMapper bills,OrderEntryMapper entries,SourceQuotaMapper quotas,OrderSyncMapper attempts,ErpProductionOrderPort erp,OrderPublishService publisher,SnConvert convert){this.policy=policy;this.validator=validator;this.bills=bills;this.entries=entries;this.quotas=quotas;this.attempts=attempts;this.erp=erp;this.publisher=publisher;this.convert=convert;}
 @Override @Transactional(readOnly=true) @DataScope(resource="sn:order",permission="sn:order:list")
 public PageResult<OrderBillVO> list(SnPageQuery query){
  validator.requireValid(query);var where=Wrappers.<OrderBillEntity>lambdaQuery().eq(OrderBillEntity::getActive,true).ne(OrderBillEntity::getCustomer,"")
   .eq(SnPolicy.optional(query.getCustomer())!=null,OrderBillEntity::getCustomer,SnPolicy.text(query.getCustomer())).eq(SnPolicy.optional(query.getPi())!=null,OrderBillEntity::getPi,SnPolicy.text(query.getPi()))
   .orderByDesc(OrderBillEntity::getBillNo,OrderBillEntity::getId);
  if(SnPolicy.optional(query.getFactoryId())!=null)where.eq(OrderBillEntity::getFactoryId,SnPolicy.id(query.getFactoryId()));
  String like=SnPolicy.like(query.getQ());
  if(like!=null){
   // 明细先按相同工厂范围筛选候选；只筛选订单，不改变订单全部有效分录的汇总量。
   var matches=entries.selectList(Wrappers.<OrderEntryEntity>lambdaQuery().select(OrderEntryEntity::getBillId).eq(OrderEntryEntity::getActive,true).apply("material LIKE {0} ESCAPE '!'",like)).stream().map(OrderEntryEntity::getBillId).distinct().toList();
   if(matches.size()>10000)throw new BusinessException(RESOURCE_LIMIT);
   where.and(w->{w.apply("bill_no LIKE {0} ESCAPE '!'",like).or().apply("pi LIKE {0} ESCAPE '!'",like).or().apply("po LIKE {0} ESCAPE '!'",like);if(!matches.isEmpty())w.or().in(OrderBillEntity::getId,matches);});
  }
  var page=bills.selectPage(new Page<>(query.getPage(),query.getSize()),where);var ids=page.getRecords().stream().map(OrderBillEntity::getId).toList();
  if(ids.isEmpty())return new PageResult<>(List.of(),Long.toString(page.getTotal()),query.getPage(),query.getSize());
  var history=quotas.forBills(ids);var current=entries.selectList(Wrappers.<OrderEntryEntity>lambdaQuery().in(OrderEntryEntity::getBillId,ids).eq(OrderEntryEntity::getActive,true));
  var result=page.getRecords().stream().map(bill->{
   var ledger=history.stream().filter(q->q.billId()==bill.getId()).toList();
   BigInteger generated=ledger.stream().map(q->BigInteger.valueOf(q.generatedQty())).reduce(BigInteger.ZERO,BigInteger::add),allocated=ledger.stream().map(q->BigInteger.valueOf(q.allocatedQty())).reduce(BigInteger.ZERO,BigInteger::add);
   BigInteger remaining=current.stream().filter(e->e.getBillId().equals(bill.getId())).map(e->BigInteger.valueOf(remaining(e,ledger))).reduce(BigInteger.ZERO,BigInteger::add);
   return convert.bill(bill,generated.toString(),allocated.toString(),Boolean.TRUE.equals(bill.getQuantityReady())?remaining.toString():"0");
  }).toList();
  return new PageResult<>(result,Long.toString(page.getTotal()),query.getPage(),query.getSize());
 }
 @Override @Transactional(readOnly=true) @DataScope(resource="sn:order",permission="sn:order:list")
 public PageResult<OrderLineVO> lines(String id,SnPageQuery query){
  validator.requireValid(query);long billId=SnPolicy.id(id);var bill=bills.selectById(billId);if(bill==null||!bill.getActive()||bill.getCustomer().isEmpty())throw SnPolicy.missing();
  var page=entries.selectPage(new Page<>(query.getPage(),query.getSize()),Wrappers.<OrderEntryEntity>lambdaQuery().eq(OrderEntryEntity::getBillId,billId).eq(OrderEntryEntity::getActive,true).orderByAsc(OrderEntryEntity::getOrdinal,OrderEntryEntity::getId));var ledger=quotas.forBills(List.of(billId));
  var result=page.getRecords().stream().map(e->{var q=ledger.stream().filter(l->l.sourceEntryId().equals(e.getSourceEntryId())).findFirst().orElseThrow();return convert.line(e,Long.toString(q.generatedQty()),Long.toString(q.allocatedQty()),Long.toString(remaining(e,ledger)));}).toList();
  return new PageResult<>(result,Long.toString(page.getTotal()),query.getPage(),query.getSize());
 }
 @Override @Transactional(readOnly=true) @DataScope(resource="sn:order",permission="sn:order:list")
 public OrderMetaVO meta(){
  var actor=policy.read("sn:order:list",false);var stats=bills.stats();var unmapped=bills.unmapped();if(unmapped.size()>1000)throw new BusinessException(RESOURCE_LIMIT);
  // 合法空快照仍有最近成功时间；受限主体不能借此读取全局同步活动。
  var time=actor.grants().get("sn:order:list").allFactories()?attempts.lastSuccess():stats.syncedAt();
  return new OrderMetaVO(time,Long.toString(stats.bills()),Long.toString(stats.rowsCount()),Long.toString(stats.excluded()),unmapped.stream().map(o->o.orgCode()+" / "+o.orgName()).toList());
 }
 @Override @Transactional(readOnly=true) @DataScope(resource="sn:order",permission="sn:order:list")
 public PageResult<String> customers(SnPageQuery query){validator.requireValid(query);String like=SnPolicy.like(query.getQ());return new PageResult<>(bills.customers(like,(query.getPage()-1)*query.getSize(),query.getSize()),Long.toString(bills.customerCount(like)),query.getPage(),query.getSize());}
 @Override public OrderSyncVO synchronize(OrderSyncDTO command){
  validator.requireValid(command);var actor=policy.read("sn:order:sync",true);String no=SnPolicy.optional(command.billNo());var attempt=publisher.begin(no);
  try{
   var source=erp.source();var rows=erp.orders(no);List<ErpProductionOrderPort.Organization> orgs=null;boolean orgFailed=false;
   if(no==null&&actor.grants().containsKey("sn:factory:sync")&&actor.grants().get("sn:factory:sync").allFactories()){
    try{orgs=erp.organizations();}catch(BusinessException e){orgFailed=true;}
   }
   return publisher.publish(actor,attempt.id(),source,rows,orgs,orgFailed);
  }catch(RuntimeException error){
   int code=error instanceof BusinessException business?business.getError().code():SYNC_FAILED.code();
   try{publisher.failed(attempt.id(),code);}catch(RuntimeException failure){LOG.error("SN sync failure record unavailable traceId={} exceptionType={}",MDC.get("traceId"),failure.getClass().getSimpleName());}
   throw error;
  }
 }
 @Override @Transactional(readOnly=true) public PageResult<OrderSyncVO> syncs(SnPageQuery query){
  validator.requireValid(query);policy.read("sn:order:list",true);var page=attempts.selectPage(new Page<>(query.getPage(),query.getSize()),Wrappers.<OrderSyncEntity>lambdaQuery().orderByDesc(OrderSyncEntity::getStartedAt,OrderSyncEntity::getId));return new PageResult<>(page.getRecords().stream().map(convert::sync).toList(),Long.toString(page.getTotal()),query.getPage(),query.getSize());
 }
 private static long remaining(OrderEntryEntity entry,List<SourceQuotaMapper.Quota> quotas){long generated=quotas.stream().filter(q->q.billId()==entry.getBillId()&&q.sourceEntryId().equals(entry.getSourceEntryId())).mapToLong(SourceQuotaMapper.Quota::generatedQty).findFirst().orElseThrow();return PlannedQuantity.remaining(entry.getQty(),generated);}
}
