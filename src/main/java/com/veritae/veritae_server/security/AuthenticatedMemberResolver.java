package com.veritae.veritae_server.security;

import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * JwtAuthenticationFilter 가 principal 로 심어 둔 memberId(UUID)를 꺼낸다.
 * SecurityConfig 가 /api/v1/members/** 를 인증 필요로 막아두므로 정상 흐름에서는 항상 존재한다.
 */
@Component
public class AuthenticatedMemberResolver {

    public UUID currentMemberId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UUID memberId)) {
            throw new InsufficientAuthenticationException("인증 정보가 없습니다.");
        }
        return memberId;
    }
}
