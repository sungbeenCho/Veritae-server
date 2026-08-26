package com.veritae.veritae_server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 영상 분석 비동기 처리 전용 스레드풀. 코어/최대 크기를 작게 잡은 이유는 개인 프로젝트
 * 규모라 동시 영상 분석 요청이 많지 않을 것으로 예상되고, 데스크탑(3060Ti) GPU가 하나뿐이라
 * 어차피 순차 처리가 자연스럽기 때문 - corePoolSize=2/maxPoolSize=2라 동시 처리 가능한 2건을
 * 넘어가는 3번째 요청부터 큐(용량 50)에 쌓이고, 큐까지 다 찬 뒤 53번째 요청부터는 거부된다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "videoAnalysisExecutor")
    public Executor videoAnalysisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("video-analysis-");
        executor.initialize();
        return executor;
    }
}
