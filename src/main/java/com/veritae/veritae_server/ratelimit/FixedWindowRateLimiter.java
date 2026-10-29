package com.veritae.veritae_server.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 키(IP·회원)마다 "정해진 시간 창 안에 몇 번까지"를 센다. 서버가 한 대라 메모리에만 두고, 재시작하면 처음부터 다시 센다.
 * 창이 끝나면 횟수가 0으로 돌아간다(고정 창 방식) - 창 경계에서 잠깐 두 배까지 허용될 수 있지만 남용을 막는 목적에는 충분하다.
 */
public class FixedWindowRateLimiter {

    private static final int DEFAULT_CLEANUP_THRESHOLD = 10_000;

    private final Clock clock;
    private final int cleanupThreshold;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(Clock clock) {
        this(clock, DEFAULT_CLEANUP_THRESHOLD);
    }

    FixedWindowRateLimiter(Clock clock, int cleanupThreshold) {
        this.clock = clock;
        this.cleanupThreshold = cleanupThreshold;
    }

    /** 허용하면 0, 넘었으면 창이 끝날 때까지 남은 초(1 이상)를 돌려준다. */
    public long tryAcquire(String key, int maxRequests, Duration window) {
        Instant now = clock.instant();
        if (windows.size() >= cleanupThreshold) {
            windows.values().removeIf(w -> !now.isBefore(w.end()));
        }
        Window updated = windows.compute(key, (k, current) -> {
            if (current == null || !now.isBefore(current.end())) {
                return new Window(now.plus(window), 1);
            }
            return new Window(current.end(), current.count() + 1);
        });
        if (updated.count() <= maxRequests) {
            return 0;
        }
        return Math.max(1, Duration.between(now, updated.end()).toSeconds());
    }

    int trackedKeyCount() {
        return windows.size();
    }

    private record Window(Instant end, int count) {
    }
}
