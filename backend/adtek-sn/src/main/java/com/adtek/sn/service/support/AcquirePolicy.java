package com.adtek.sn.service.support;
import com.adtek.common.core.*;
import com.adtek.framework.security.*;
import com.adtek.sn.mapper.AcquireMapper;
import com.adtek.sn.query.AcquireQuery;
import java.util.*;
import org.springframework.stereotype.Component;
/** 每个操作单独取工厂范围；同PI跨厂展示不能转换成写权限、批次或文件读取权限。 */
@Component public class AcquirePolicy {
 public static final String VIEW="sn:acquire:view",TAKE="sn:acquire:take",PRINT="sn:print:execute",DOWNLOAD="sn:print:download",CALLBACK="sn:acquire:callback",ITEMS="sn:items:read";
 private final CurrentActor current;private final AuthorizationPort authorization;private final AuthorizationMutationPort mutations;private final AcquireMapper rows;
 public AcquirePolicy(CurrentActor current,AuthorizationPort authorization,AuthorizationMutationPort mutations,AcquireMapper rows){this.current=current;this.authorization=authorization;this.mutations=mutations;this.rows=rows;}
 public Actor read(String permission){var requested=current.requirePermission(permission);var fresh=authorization.loadCurrent(requested.userId()).orElseThrow(AcquirePolicy::forbidden);if(fresh.securityVersion()!=requested.securityVersion())throw forbidden();return require(fresh,permission,null);}
 public Actor lock(Actor actor,String permission,long factory){return require(mutations.lockCurrent(actor),permission,factory);}
 public Actor require(Actor actor,String permission,Long factory){var grant=actor.grants().get(permission);if(actor.mustChangePassword()||grant==null||(factory!=null&&!grant.permitsFactory(factory)))throw forbidden();if((PRINT.equals(permission)||DOWNLOAD.equals(permission))&&!"USER".equals(actor.principalType()))throw forbidden();return actor;}
 public AcquireMapper.Scope scope(Actor actor,String permission,AcquireQuery q,boolean allowPiAll){var grant=require(actor,permission,null).grants().get(permission);Long factory=q.factoryId()==null||q.factoryId().isBlank()?null:SnPolicy.id(q.factoryId());String pi=SnPolicy.optional(q.pi());
  if(factory!=null&&!grant.permitsFactory(factory))throw forbidden();if(q.piAll()&&!allowPiAll)throw SnPolicy.invalid();
  if(q.piAll()){if(pi==null||!"USER".equals(actor.principalType()))throw forbidden();if(!grant.allFactories()&&rows.owns(new AcquireMapper.Scope(false,grant.factoryIds().stream().sorted().toList(),null,pi,false))==0)throw forbidden();}
  return new AcquireMapper.Scope(grant.allFactories(),grant.factoryIds().stream().sorted().toList(),factory,pi,q.piAll());}
 public void material(String mode,String value){if(!Set.of("ALL","EMPTY","VALUE").contains(mode)||("VALUE".equals(mode)&&(value==null||value.isEmpty())))throw SnPolicy.invalid();}
 public static String pi(String value){String pi=SnPolicy.text(value);if(pi.isEmpty()||pi.length()>128||pi.codePoints().anyMatch(Character::isISOControl))throw SnPolicy.invalid();return pi;}
 public static BusinessException forbidden(){return new BusinessException(com.adtek.framework.constant.ErrorCodeConstants.FORBIDDEN);}
}
