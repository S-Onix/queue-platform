package com.sonix.queue.infrastructure.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonix.queue.domain.queue.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisListCommands.Direction;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;


@Slf4j
@Component
public class RedisListOutboxConsumer implements EnqueueOutbox {
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisListOutboxConsumer(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<OutboxEntry> claim(int max) {
        List<OutboxEntry> claimed = new ArrayList<>(max);
        for (int i = 0; i < max; i++) {
            String raw = redisTemplate.opsForList()
                    .move(OutboxKeys.pending(), Direction.LEFT, OutboxKeys.processing(), Direction.RIGHT);
            if (raw == null) break;                 // pending 비었음
            OutboxEntry e = parse(raw);
            if (e != null) claimed.add(e);          // parse 실패분은 processing에 남음(후속 dead-letter)
        }
        return claimed;
    }

    @Override
    public List<OutboxEntry> inflight() {
        List<String> raws = redisTemplate.opsForList().range(OutboxKeys.processing(),0, -1);
        if (raws == null || raws.isEmpty()) return List.of();
        List<OutboxEntry> entries = new ArrayList<>(raws.size());
        for (String raw : raws) {
            OutboxEntry e = parse(raw);
            if (e != null) entries.add(e);
        }
        return entries;
    }

    @Override
    public void ack(List<OutboxEntry> entries) {

    }
    private OutboxEntry parse(String raw) {
        try {
            return new OutboxEntry(raw, objectMapper.readValue(raw, EnqueueEvent.class));
        } catch (Exception ex) {
            log.error("outbox payload 역직렬화 실패(skip): {}", raw, ex);  // 후속 dead-letter
            return null;
        }
    }
}
