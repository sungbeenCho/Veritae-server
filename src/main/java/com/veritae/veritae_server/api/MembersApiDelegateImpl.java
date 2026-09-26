package com.veritae.veritae_server.api;

import com.veritae.veritae_server.domain.member.Member;
import com.veritae.veritae_server.domain.member.MemberService;
import com.veritae.veritae_server.openapi.api.MembersApiDelegate;
import com.veritae.veritae_server.openapi.model.MemberResponse;
import com.veritae.veritae_server.openapi.model.WithdrawalRequest;
import com.veritae.veritae_server.security.AuthenticatedMemberResolver;
import com.veritae.veritae_server.withdrawal.WithdrawalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MembersApiDelegateImpl implements MembersApiDelegate {

    private final MemberService memberService;
    private final AuthenticatedMemberResolver authenticatedMemberResolver;
    private final WithdrawalService withdrawalService;

    @Override
    public ResponseEntity<MemberResponse> getMyProfile() {
        Member member = memberService.getById(authenticatedMemberResolver.currentMemberId());
        return ResponseEntity.ok(MemberApiMapper.toResponse(member));
    }

    @Override
    public ResponseEntity<Void> withdraw(WithdrawalRequest withdrawalRequest) {
        withdrawalService.withdraw(authenticatedMemberResolver.currentMemberId(), withdrawalRequest.getPassword());
        return ResponseEntity.noContent().build();
    }
}
