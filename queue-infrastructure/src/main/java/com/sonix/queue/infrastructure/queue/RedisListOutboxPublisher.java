package com.sonix.queue.infrastructure.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonix.queue.domain.queue.EnqueueEvent;
import com.sonix.queue.domain.queue.EnqueueEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RedisListOutboxPublisher implements EnqueueEventPublisher {
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisListOutboxPublisher(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(EnqueueEvent event) {
        try{
            String payload = objectMapper.writeValueAsString(event);
            redisTemplate.opsForList().rightPush(OutboxKeys.pending(), payload);
        }catch(Exception e) {
            log.error("enqueue outbox RPUSH 실패 tokenId={} queueId={}: {}", event.tokenId(), event.queueId(), e.getMessage(), e);
        }
    }


}
