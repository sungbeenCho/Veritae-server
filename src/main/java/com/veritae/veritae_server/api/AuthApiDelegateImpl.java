package com.veritae.veritae_server.api;

import com.veritae.veritae_server.auth.AuthService;
import com.veritae.veritae_server.domain.member.Member;
import com.veritae.veritae_server.domain.member.MemberService;
import com.veritae.veritae_server.openapi.api.AuthApiDelegate;
import com.veritae.veritae_server.openapi.model.AccessTokenResponse;
import com.veritae.veritae_server.openapi.model.LoginRequest;
import com.veritae.veritae_server.openapi.model.MemberResponse;
import com.veritae.veritae_server.openapi.model.RefreshRequest;
import com.veritae.veritae_server.openapi.model.SignupRequest;
import com.veritae.veritae_server.openapi.model.TokenResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthApiDelegateImpl implements AuthApiDelegate {

    private final MemberService memberService;
    private final AuthService authService;

    @Override
    public ResponseEntity<MemberResponse> signup(SignupRequest signupRequest) {
        Member member = memberService.signup(
                signupRequest.getEmail(), signupRequest.getPassword(), signupRequest.getNickname());
        return ResponseEntity.status(HttpStatus.CREATED).body(MemberApiMapper.toResponse(member));
    }

    @Override
    public ResponseEntity<TokenResponse> login(LoginRequest loginRequest) {
        var tokens = authService.login(loginRequest.getEmail(), loginRequest.getPassword());
        return ResponseEntity.ok(AuthApiMapper.toTokenResponse(tokens));
    }

    @Override
    public ResponseEntity<AccessTokenResponse> refreshToken(RefreshRequest refreshRequest) {
        var refreshed = authService.refresh(refreshRequest.getRefreshToken());
        return ResponseEntity.ok(AuthApiMapper.toAccessTokenResponse(refreshed));
    }
}
