package com.adtek.system.service.impl;

import com.adtek.framework.security.IdentityNamePort;
import com.adtek.system.mapper.IdentityMapper;

import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class IdentityNameServiceImpl implements IdentityNamePort {
    private final IdentityMapper identities;

    public IdentityNameServiceImpl(IdentityMapper identities) {
        this.identities = identities;
    }

    @Override
    public Optional<String> username(long userId) {
        var user = identities.findById(userId);
        return user == null ? Optional.empty() : Optional.of(user.getUsername());
    }
}
