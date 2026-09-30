package com.adtek.sn.service.support;
import com.adtek.common.core.*;
import com.adtek.framework.security.*;
import org.springframework.stereotype.Component;
/** SN用例工厂策略；部门范围不授予SN工厂资格，全局目录写要求该操作的全部工厂范围。 */
@Component
public class SnPolicy {
 private final CurrentActor actors; private final AuthorizationMutationPort mutations;
 public SnPolicy(CurrentActor actors,AuthorizationMutationPort mutations){this.actors=actors;this.mutations=mutations;}
 public Actor read(String permission,boolean global){return require(actors.requirePermission(permission),permission,global);}
 public Actor lock(String permission){return require(mutations.lockCurrent(read(permission,true)),permission,true);}
 public Actor require(Actor actor,String permission,boolean global){
  var grant=actor.grants().get(permission);
  if(actor.mustChangePassword()||grant==null||(global&&!grant.allFactories())){throw new BusinessException(com.adtek.framework.constant.ErrorCodeConstants.FORBIDDEN);}
  return actor;
 }
 public static long id(String value){
  try{if(value==null||!value.matches("[1-9][0-9]{0,18}"))throw invalid();return Long.parseLong(value);}
  catch(NumberFormatException e){throw invalid();}
 }
 public static String text(String value){return value==null?"":value.strip();}
 public static String optional(String value){String normalized=text(value);return normalized.isEmpty()?null:normalized;}
 public static String like(String value){String normalized=optional(value);return normalized==null?null:"%"+normalized.replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
 public static BusinessException invalid(){return new BusinessException(com.adtek.common.constant.ErrorCodeConstants.INVALID_ARGUMENT);}
 public static BusinessException missing(){return new BusinessException(com.adtek.common.constant.ErrorCodeConstants.NOT_FOUND);}
}
