package com.veritae.veritae_server.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class FixedWindowRateLimiterTest {

    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-29T09:00:00Z"));

    @Test
    void tryAcquire_withinLimit_shouldAllow() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(clock);

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("login:1.2.3.4", 3, MINUTE)).isZero();
        }
    }

    @Test
    void tryAcquire_overLimit_shouldReturnSecondsUntilWindowEnds() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(clock);
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("login:1.2.3.4", 3, MINUTE);
        }
        clock.advance(Duration.ofSeconds(20));

        long retryAfter = limiter.tryAcquire("login:1.2.3.4", 3, MINUTE);

        assertThat(retryAfter).isEqualTo(40);
    }

    @Test
    void tryAcquire_afterWindowEnds_shouldAllowAgain() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(clock);
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("login:1.2.3.4", 3, MINUTE);
        }
        clock.advance(MINUTE);

        assertThat(limiter.tryAcquire("login:1.2.3.4", 3, MINUTE)).isZero();
    }

    @Test
    void tryAcquire_differentKeys_shouldBeCountedSeparately() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(clock);
        limiter.tryAcquire("login:1.2.3.4", 1, MINUTE);

        assertThat(limiter.tryAcquire("login:5.6.7.8", 1, MINUTE)).isZero();
        assertThat(limiter.tryAcquire("signup:1.2.3.4", 1, MINUTE)).isZero();
    }

    @Test
    void tryAcquire_whenManyKeysTracked_shouldDropExpiredOnes() {
        // 요청한 IP마다 기록이 남으므로, 기록이 많아지면 시간이 지난 것부터 비워 메모리가 계속 늘지 않게 한다.
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(clock, 3);
        limiter.tryAcquire("a", 5, MINUTE);
        limiter.tryAcquire("b", 5, MINUTE);
        limiter.tryAcquire("c", 5, MINUTE);
        clock.advance(MINUTE);

        limiter.tryAcquire("d", 5, MINUTE);

        assertThat(limiter.trackedKeyCount()).isEqualTo(1);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
