package com.veritae.veritae_server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * springdoc 이 컨트롤러 애노테이션을 스캔해 런타임에 재생성하는 {@code /v3/api-docs} 는, 이 프로젝트가
 * 쓰는 Spring Boot 4(Jackson 3) + swagger-core(Jackson 2 기반 ModelResolver) 조합에서 요청 바디
 * 스키마가 {@code {"type":"string"}} 으로 뭉개지는 등 실제로 깨져서 나온다(실기동 확인, 2026-07-17).
 *
 * <p>이 프로젝트는 애초에 openapi-first(ADR-0001) 라 손으로 작성한 {@code src/main/resources/openapi.yaml}
 * 이 유일한 진실 공급원이다. springdoc 의 런타임 재생성 결과를 신뢰하는 대신, Swagger UI 가 바로 이
 * 정적 파일을 읽도록 고정해 두 스펙이 따로 노는 상황 자체를 없앤다.
 */
@Configuration
public class OpenApiStaticSpecConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/openapi.yaml")
                .addResourceLocations("classpath:/");
    }
}
