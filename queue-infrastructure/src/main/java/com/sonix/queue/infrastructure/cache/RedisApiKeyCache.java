package com.sonix.queue.infrastructure.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonix.queue.domain.apikey.ApiKey;
import com.sonix.queue.domain.apikey.ApiKeyCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Repository
public class RedisApiKeyCache implements ApiKeyCache {
    private static final Duration POSITIVE_TTL = Duration.ofSeconds(60);  // Exist Hit
    private static final Duration NEGATIVE_TTL = Duration.ofSeconds(30);  // Null Hit (Null Marker TTL)
    private static final String NULL_MARKER = "__NULL_MARKER__";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisApiKeyCache(StringRedisTemplate redisTemplate,
                             ObjectMapper cacheObjectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = cacheObjectMapper;
    }



    @Override
    public Optional<ApiKey> get(String keyHash) {
        String key = RedisKeyFactory.apiKey(keyHash);

        try{
            String value = redisTemplate.opsForValue().get(key);
            if(value == null) {
                return Optional.empty();
            }

            if(NULL_MARKER.equals(value)) {
                log.debug("ApiKey Negative 캐시 HIT: keyHash={}", keyHash);
                return Optional.empty();
            }
            ApiKey apiKey = objectMapper.readValue(value, ApiKey.class);
            return Optional.of(apiKey);
        } catch(JsonProcessingException e) {
            log.warn("ApiKey 캐시 역직렬화 실패, 손상 데이터 삭제 후 DB fallback: keyHash={}", keyHash, e);
            safeDelete(key);
            return Optional.empty();
        } catch(Exception e) {
            log.warn("ApiKey 캐시 조회 실패, DB fallback: keyHash={}", keyHash, e);
            return Optional.empty();
        }
    }

    @Override
    public void put(ApiKey apiKey) {
        String key = RedisKeyFactory.apiKey(apiKey.getKeyHash());
        try{
            String json = objectMapper.writeValueAsString(apiKey);
            redisTemplate.opsForValue().set(key, json, POSITIVE_TTL);
            log.debug("ApiKey 캐시 저장: keyHash={}, ttl={}s", apiKey.getKeyHash(), POSITIVE_TTL.getSeconds());
        } catch (JsonProcessingException e) {
            log.warn("ApiKey 캐시 직렬화 실패, 저장 스킵: keyHash={}", apiKey.getKeyHash(), e);
        } catch (Exception e) {
            log.warn("ApiKey 캐시 저장 실패: keyHash={}", apiKey.getKeyHash(), e);
        }
    }

    @Override
    public void putNegative(String keyHash) {
        String key = RedisKeyFactory.apiKey(keyHash);
        try {
            redisTemplate.opsForValue().set(key, NULL_MARKER, NEGATIVE_TTL);
            log.debug("ApiKey Negative 캐시 저장: keyHash={}, ttl={}s", keyHash, NEGATIVE_TTL.getSeconds());
        } catch (Exception e) {
            log.warn("ApiKey Negative 캐시 저장 실패: keyHash={}", keyHash, e);
        }
    }

    @Override
    public void invalidate(String keyHash) {
        String key = RedisKeyFactory.apiKey(keyHash);
        try {
            Boolean deleted = redisTemplate.delete(key);
            log.debug("ApiKey 캐시 무효화: keyHash={}, deleted={}", keyHash, deleted);
        } catch (Exception e) {
            log.warn("ApiKey 캐시 무효화 실패: keyHash={}", keyHash, e);
        }
    }

    private void safeDelete(String key) {
        try{
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("손상 캐시 삭제 실패: key={}", key, e);
        }
    }
}
