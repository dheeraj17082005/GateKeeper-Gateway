package in.coderarmy.gatekeeper.gateway.service;

import in.coderarmy.gatekeeper.gateway.dto.GatewayRoute;
import in.coderarmy.gatekeeper.gateway.exception.GatewayPolicyException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class IdempotencyService {
    private final StringRedisTemplate redis;

    public IdempotencyService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void reserve(GatewayRoute route, String clientIp, String key) {
        if (key == null || !key.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new GatewayPolicyException(HttpStatus.BAD_REQUEST,
                    "A valid Idempotency-Key is required", "INVALID_IDEMPOTENCY_KEY");
        }

        String redisKey = "gateway:idempotency:" + route.getId() + ":" + clientIp + ":" + key;
        Boolean reserved;
        try {
            reserved = redis.opsForValue().setIfAbsent(redisKey, "reserved", Duration.ofHours(24));
        } catch (DataAccessException exception) {
            reserved = null;
        }

        if (reserved == null) {
            throw new GatewayPolicyException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Idempotency is temporarily unavailable", "POLICY_UNAVAILABLE");
        }
        if (!reserved) {
            throw new GatewayPolicyException(HttpStatus.CONFLICT,
                    "This Idempotency-Key has already been used", "DUPLICATE_REQUEST");
        }
    }
}
