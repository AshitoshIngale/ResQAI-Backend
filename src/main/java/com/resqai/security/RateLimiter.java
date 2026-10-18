package com.resqai.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token-bucket rate limiting per key (ip or identifier).
 * Suitable for a single instance; swap for a distributed bucket (Redis) when scaling out.
 */
@Component
public class RateLimiter {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public enum Policy {
        // endpoint -> capacity / refill
        REGISTER(5, Duration.ofHours(1)),
        VERIFY_EMAIL(10, Duration.ofHours(1)),
        PHONE_OTP(5, Duration.ofMinutes(15)),
        LOGIN(10, Duration.ofMinutes(5)),
        PASSWORD_RESET(5, Duration.ofHours(1)),
        EMERGENCY_LOOKUP(30, Duration.ofMinutes(15)),
        BYSTANDER_REPORT(10, Duration.ofMinutes(15));

        private final long capacity;
        private final Duration refill;

        Policy(long capacity, Duration refill) {
            this.capacity = capacity;
            this.refill = refill;
        }

        Bandwidth bandwidth() {
            return Bandwidth.builder().capacity(capacity).refillGreedy(capacity, refill).build();
        }
    }

    public boolean tryConsume(Policy policy, String key) {
        Bucket bucket = buckets.computeIfAbsent(policy.name() + ":" + key,
            k -> Bucket.builder().addLimit(policy.bandwidth()).build());
        return bucket.tryConsume(1);
    }
}
