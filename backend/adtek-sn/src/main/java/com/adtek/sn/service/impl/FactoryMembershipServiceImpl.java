package com.adtek.sn.service.impl;
import com.adtek.common.core.BusinessException;
import com.adtek.common.core.Actor;
import com.adtek.framework.security.*;
import com.adtek.sn.mapper.*;
import com.adtek.sn.service.support.SnPolicy;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
/** 授权关系属于SN，身份模块只见类型化端口。 */
@Service
public class FactoryMembershipServiceImpl implements FactoryMembershipPort {
 private final FactoryMembershipMapper mapper; private final FactoryMapper factories;
 public FactoryMembershipServiceImpl(FactoryMembershipMapper mapper,FactoryMapper factories){this.mapper=mapper;this.factories=factories;}
 @Override public Set<Long> factoryIds(long userId){return Set.copyOf(mapper.factoryIds(userId));}
 @Override @Transactional(propagation=Propagation.MANDATORY)
 public void replace(Actor administrator,long userId,Set<Long> requested){
  if(requested==null||requested.size()>100)throw SnPolicy.invalid();
  var previous=factoryIds(userId);if(previous.equals(requested))return;
  var grant=administrator.grants().get("sn:factory:assign");
  if(administrator.mustChangePassword()||!administrator.permissions().contains("system:security:administer")||grant==null
    ||(!grant.allFactories()&&(!grant.factoryIds().containsAll(requested)||!grant.factoryIds().containsAll(previous))))
   throw new BusinessException(com.adtek.framework.constant.ErrorCodeConstants.FORBIDDEN);
  for(long id:requested){var factory=factories.selectById(id);if(factory==null||!factory.getEnabled())throw SnPolicy.invalid();}
  mapper.clear(userId);requested.stream().sorted().forEach(id->mapper.add(userId,id));
 }
}
