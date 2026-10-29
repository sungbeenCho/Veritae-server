package com.veritae.veritae_server.ratelimit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;

@Configuration
// @WebMvcTest 슬라이스에는 @ConfigurationPropertiesScan이 적용되지 않으므로 여기서 직접 등록한다(모든 컨트롤러 테스트에 이 인터셉터가 함께 뜬다).
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor interceptor;

    public RateLimitConfig(RateLimitProperties properties) {
        this.interceptor = new RateLimitInterceptor(new FixedWindowRateLimiter(Clock.systemUTC()), properties);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/v1/**");
    }
}
