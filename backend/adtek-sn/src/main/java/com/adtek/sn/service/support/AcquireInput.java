package com.adtek.sn.service.support;
import java.util.List;
/** 服务器规范化的固定命令输入；回调清单已从固定批次解析，和traceId无关。 */
public record AcquireInput(long factoryId,String pi,String batchId,List<String> sns) {}
