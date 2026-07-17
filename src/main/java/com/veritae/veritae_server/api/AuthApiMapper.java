package com.veritae.veritae_server.api;

import com.veritae.veritae_server.auth.IssuedTokens;
import com.veritae.veritae_server.auth.RefreshedAccessToken;
import com.veritae.veritae_server.openapi.model.AccessTokenResponse;
import com.veritae.veritae_server.openapi.model.TokenResponse;

public final class AuthApiMapper {

    private AuthApiMapper() {
    }

    public static TokenResponse toTokenResponse(IssuedTokens tokens) {
        return new TokenResponse(
                tokens.accessToken(),
                tokens.refreshToken(),
                TokenResponse.TokenTypeEnum.BEARER,
                tokens.expiresInSeconds());
    }

    public static AccessTokenResponse toAccessTokenResponse(RefreshedAccessToken refreshed) {
        return new AccessTokenResponse(
                refreshed.accessToken(),
                AccessTokenResponse.TokenTypeEnum.BEARER,
                refreshed.expiresInSeconds());
    }
}
