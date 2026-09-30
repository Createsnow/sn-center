package com.adtek.sn.service.support;
import com.adtek.common.core.BusinessException;
import com.adtek.sn.service.port.ErpProductionOrderPort.Row;
import java.math.BigInteger;
import java.math.BigDecimal;
import java.util.*;
import static com.adtek.sn.constant.ErrorCodeConstants.*;
/** 完整来源身份/头一致性校验；同物料不合并，按稳定分录内码排序后编号，历史额度不依赖该编号。 */
public final class OrderSnapshot {
 private OrderSnapshot(){}
 public static Map<String,List<Row>> validate(List<Row> rows,String expectedBill){
  Map<String,List<Row>> bills=new LinkedHashMap<>();Set<String> entries=new HashSet<>();Map<String,String> numbers=new HashMap<>();
  for(var row:rows){
   if(row.billId()==null||row.entryId()==null||!row.billId().matches("[1-9][0-9]{0,63}")||!row.entryId().matches("[1-9][0-9]{0,63}")||!Set.of("1","2").contains(row.status()))throw new BusinessException(ERP_INVALID);
   try{PlannedQuantity.require(row.qty());}catch(ArithmeticException e){throw new BusinessException(ERP_INVALID);}
   if(expectedBill!=null&&!row.billNo().equals(expectedBill))throw new BusinessException(ERP_INVALID);
   String old=numbers.putIfAbsent(row.billNo(),row.billId());if(old!=null&&!old.equals(row.billId()))throw new BusinessException(ERP_INVALID);
   if(!entries.add(row.billId()+"/"+row.entryId()))throw new BusinessException(ERP_INVALID);
   var group=bills.computeIfAbsent(row.billId(),ignored->new ArrayList<>());
   if(!group.isEmpty()){var first=group.getFirst();if(!List.of(first.billNo(),first.customer(),first.po(),first.pi(),first.orgId(),first.orgCode(),first.orgName()).equals(List.of(row.billNo(),row.customer(),row.po(),row.pi(),row.orgId(),row.orgCode(),row.orgName())))throw new BusinessException(ERP_INVALID);}
   group.add(row);
  }
  for(var group:bills.values()){
   group.sort(Comparator.comparing(row->new BigInteger(row.entryId())));
   try{PlannedQuantity.require(group.stream().map(Row::qty).reduce(BigDecimal.ZERO,BigDecimal::add));}catch(ArithmeticException e){throw new BusinessException(ERP_INVALID);}
  }
  return bills;
 }
}
