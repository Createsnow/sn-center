package com.adtek.server;

import com.adtek.common.core.*;
import com.adtek.framework.security.ResourceMembershipPort;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;

import java.util.Set;

/** 装配层提供关闭的默认实现；项目引入业务资格时用自己的 Bean 替代并添加行为测试。 */
@Configuration
public class ResourceMembershipConfiguration {
    @Bean
    @ConditionalOnMissingBean(ResourceMembershipPort.class)
    ResourceMembershipPort resourceMembership() {
        return new ResourceMembershipPort() {
            public Set<Long> resourceIds(long userId) {
                return Set.of();
            }

            public void replace(Actor administrator, long userId, Set<Long> ids) {
                if (!ids.isEmpty())
                    throw new BusinessException(
                            com.adtek.common.constant.ErrorCodeConstants.INVALID_ARGUMENT);
            }
        };
    }
}
