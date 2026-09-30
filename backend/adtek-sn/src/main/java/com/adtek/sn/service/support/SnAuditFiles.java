package com.adtek.sn.service.support;
import java.util.*;
/** 自由文本CSV防公式解释；PI/SN/物料等业务编码保持原值，精确文本表格使用XLSX。 */
public final class SnAuditFiles {
 private SnAuditFiles(){}
 public static final List<String> HEADER=List.of("时间","工号","姓名","角色","来源","动作","结果","工厂","PI","客户","物料","来源订单","起始SN","结束SN","数量","操作前状态","操作后状态","批次号","请求号","转厂单号","原因","失败原因","请求ID","详情","工厂名称","转入工厂","转入PI","痕迹ID");
 private static final Set<Integer> FREE_TEXT=Set.of(1,2,3,4,5,6,20,21,23,24);
 public static List<String> csv(List<String> cells){if(cells.size()!=HEADER.size())throw new IllegalStateException("Audit snapshot column count changed");var safe=new ArrayList<>(cells);for(int index:FREE_TEXT){String value=safe.get(index);if(value==null)continue;String visible=value.stripLeading();if((!visible.isEmpty()&&"=+-@".indexOf(visible.charAt(0))>=0)||value.startsWith("\t")||value.startsWith("\r")||value.startsWith("\n"))safe.set(index,"'"+value);}return safe;}
}
