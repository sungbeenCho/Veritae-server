package com.veritae.veritae_server.api;

import com.veritae.veritae_server.domain.member.Member;
import com.veritae.veritae_server.openapi.model.MemberResponse;

public final class MemberApiMapper {

    private MemberApiMapper() {
    }

    public static MemberResponse toResponse(Member member) {
        return new MemberResponse(member.getId(), member.getEmail(), member.getNickname());
    }
}
