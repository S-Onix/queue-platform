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
 * 이유: Tenant 캐시 구현체(TTL 60초 · JSON String + {@code TenantMixin}).
 * 문제: Redis 가 죽으면 인증·Rate Limit 이 통째로 막힐 수 있다.
 * 해결: <b>가용성 우선</b> — 예외를 전파하지 않고 로그만 남긴다. 호출자는 <b>캐시 미스</b> 로 보고
 *       DB 폴백으로 간다. 손상된 값은 자동 삭제한다.
 * 🪤 그래서 Redis 장애가 <b>조용하다</b> — 지표·로그가 유일한 단서다.
 *
 * @author sonix
 */

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
    public Optional<Tenant> get(Long id) {
        String key = RedisKeyFactory.tenant(id);
        try{
            String json = redisTemplate.opsForValue().get(key);
            if(json == null) {
                return Optional.empty();
            }
            Tenant tenant = objectMapper.readValue(json, Tenant.class);
            return Optional.of(tenant);
        }catch(JsonProcessingException e) {
            log.warn("Tenant 캐시 역직렬화 실패, 손상 데이터 삭제 후 DB fallback: id={}", id, e);
            safeDelete(key);
            return Optional.empty();
        }catch (Exception e) {
            log.warn("Tenant 캐시 조회 실패, DB fallback: id={}", id, e);
            return Optional.empty();
        }
    }

    @Override
    public void put(Tenant tenant) {
        String key = RedisKeyFactory.tenant(tenant.getId());
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
    public void invalidate(Long id) {
        String key = RedisKeyFactory.tenant(id);
        try{
            Boolean deleted = redisTemplate.delete(key);
            log.debug("Tenant 캐시 무효화: id={}, deleted={}", id, deleted);
        } catch (Exception e) {
            log.warn("Tenant 캐시 무효화 실패: id={}", id, e);
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
