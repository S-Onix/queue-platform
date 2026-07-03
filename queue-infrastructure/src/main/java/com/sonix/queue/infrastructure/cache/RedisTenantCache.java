package com.sonix.queue.infrastructure.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonix.queue.domain.tenant.Tenant;
import com.sonix.queue.domain.tenant.TenantCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

/**
 * Tenant Cache 구현체
 * >> TTL 60
 * 직렬화: JSON String (Jackson + TenantMixin)
 * 데이터 타입: Redis String
 *
 *
 * 장애 처리 방침 (가용성 우선):
 * Redis 장애 시 예외 전파 X, 로그만 남김
 * 호출자는 캐시 미스로 인식 → DB fallback 진행
 * 손상된 캐시는 자동 삭제
 * */

@Slf4j
@Repository
public class RedisTenantCache implements TenantCache {

    private static final Duration TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisTenantCache(StringRedisTemplate redisTemplate, ObjectMapper cacheObjectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = cacheObjectMapper;
    }

    @Override
    public Optional<Tenant> get(String tenantId) {
        String key = RedisKeyFactory.tenant(tenantId);
        try{
            String json = redisTemplate.opsForValue().get(key);
            if(json == null) {
                return Optional.empty();
            }
            Tenant tenant = objectMapper.readValue(json, Tenant.class);
            return Optional.of(tenant);
        }catch(JsonProcessingException e) {
            log.warn("Tenant 캐시 역직렬화 실패, 손상 데이터 삭제 후 DB fallback: tenantId={}", tenantId, e);
            safeDelete(key);
            return Optional.empty();
        }catch (Exception e) {
            log.warn("Tenant 캐시 조회 실패, DB fallback: tenantId={}", tenantId, e);
            return Optional.empty();
        }
    }

    @Override
    public void put(Tenant tenant) {
        String key = RedisKeyFactory.tenant(tenant.getTenantId());
        try{
            String json = objectMapper.writeValueAsString(tenant);
            redisTemplate.opsForValue().set(key, json, TTL);
            log.debug("Tenant 캐시 저장: tenantId={}, ttl={}s", tenant.getTenantId(), TTL.getSeconds());
        } catch (JsonProcessingException e) {
            log.warn("Tenant 캐시 직렬화 실패, 저장 스킵: tenantId={}", tenant.getTenantId(), e);
        } catch (Exception e) {
            log.warn("Tenant 캐시 저장 실패: tenantId={}", tenant.getTenantId(), e);
        }
    }

    @Override
    public void invalidate(String tenantId) {
        String key = RedisKeyFactory.tenant(tenantId);
        try{
            Boolean deleted = redisTemplate.delete(key);
            log.debug("Tenant 캐시 무효화: tenantId={}, deleted={}", tenantId, deleted);
        } catch (Exception e) {
            log.warn("Tenant 캐시 무효화 실패: tenantId={}", tenantId, e);
        }
    }

    private void safeDelete(String key) {
        try{
            redisTemplate.delete(key);
        }catch(Exception e) {
            log.warn("손상 캐시 삭제 실패: key={}", key, e);
        }
    }
}
