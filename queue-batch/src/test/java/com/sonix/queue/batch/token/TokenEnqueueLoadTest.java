package com.sonix.queue.batch.token;

import com.sonix.queue.common.util.IdGenerator;
import com.sonix.queue.domain.queue.EnqueueEvent;
import com.sonix.queue.domain.queue.EnqueueResult;
import com.sonix.queue.domain.queue.PendingEnqueue;
import com.sonix.queue.infrastructure.queue.KafkaEnqueueEventPublisher;
import com.sonix.queue.infrastructure.queue.QueueKeys;
import com.sonix.queue.infrastructure.queue.RedisQueueEngine;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Token Enqueue 부하 테스트 (실제 Redis + Kafka + MySQL) — 전체 파이프라인 스케일 검증.
 *
 * <p><b>시나리오</b>: 10 tenant · 50 queue 시드 후, {@code COUNT}명(기본 20만)이 <b>랜덤 큐</b>에
 * enqueue → Redis Lua(ZADD NX + INCR seq) → Kafka 발행 → Consumer(배치) → MySQL {@code tokens} 적재.
 *
 * <p><b>검증</b>: 최종 row 수 == 발행 성공 수, token_id 전역 유일(중복 0), <b>큐별 seq 유일</b>.
 * <b>리포트</b>: enqueue TPS · publish TPS · 적재 완료 시간.
 *
 * <p><b>실행</b> (기본 {@code ./gradlew test}에서 제외):
 * <pre>
 *   LOAD=true COUNT=200000 ./gradlew :queue-batch:test --tests "*TokenEnqueueLoadTest"
 *   LOAD=true COUNT=2000000 ./gradlew :queue-batch:test --tests "*TokenEnqueueLoadTest"
 * </pre>
 * Redis Sentinel(26379~) + Kafka(9092~) + MySQL(3306) 기동 필요.
 *
 * <p><b>격리/정리</b>: 유니크 group-id + latest + 할당 후 발행으로 스테일 메시지 차단.
 * @AfterAll에서 Redis 키(50큐×3) + DB(tokens/queues/tenants)를 모두 정리한다.
 */
@SpringBootTest(classes = TokenEnqueueLoadTestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.master.jdbc-url=jdbc:mysql://127.0.0.1:3306/queue_platform?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&rewriteBatchedStatements=true",
                "spring.datasource.master.username=queueapp",
                "spring.datasource.master.password=queueapp1234",
                "spring.datasource.master.maximum-pool-size=20",
                "spring.datasource.replica.jdbc-url=jdbc:mysql://127.0.0.1:3306/queue_platform?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul",
                "spring.datasource.replica.username=queueapp",
                "spring.datasource.replica.password=queueapp1234",
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect",

                "spring.data.redis.sentinel.master=mymaster",
                "spring.data.redis.sentinel.nodes=127.0.0.1:26379,127.0.0.1:26380,127.0.0.1:26381",

                "spring.kafka.bootstrap-servers=localhost:9092,localhost:9094,localhost:9096",
                "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
                "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer",
                "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
                "spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.JsonDeserializer",
                "spring.kafka.consumer.auto-offset-reset=latest",
                "spring.kafka.consumer.enable-auto-commit=false",
                "spring.kafka.consumer.properties.spring.json.trusted.packages=com.sonix.queue.*",
                "spring.kafka.listener.type=batch",
                "spring.kafka.listener.ack-mode=manual",

                // 부하 테스트 로그 억제 — local 프로파일의 SQL/bind DEBUG/TRACE가
                // 20만+ row에서 GB급 stdout 캡처를 만들어 Gradle 리포트 단계 OOM을 유발했음.
                "logging.level.org.hibernate.SQL=WARN",
                "logging.level.org.hibernate.sql=WARN",
                "logging.level.org.hibernate.orm.jdbc.bind=WARN",
                "logging.level.com.sonix.queue=INFO"
        })
@EnabledIfEnvironmentVariable(named = "LOAD", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TokenEnqueueLoadTest {

    private static final int COUNT = Integer.parseInt(System.getenv().getOrDefault("COUNT", "200000"));
    private static final int TENANTS = 10;
    private static final int QUEUES = 50;                 // tenant당 5개
    private static final int CHUNK = 500;                 // Bulk Lua 청크 = CHUNK_SIZE
    private static final long CAPACITY = COUNT;           // per-queue 부하보다 훨씬 크게 → FULL 없음
    private static final String Q_PREFIX = "q_load_";
    private static final String T_PREFIX = "t_load_";

    @Autowired private RedisQueueEngine engine;
    @Autowired private KafkaEnqueueEventPublisher publisher;
    @Autowired private KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private KafkaListenerEndpointRegistry registry;

    private final List<String> queueIds = new ArrayList<>();
    private final long[] tenantPks = new long[TENANTS];
    private long redisMemBaseline; // 부하 전 Redis used_memory (delta 계산용)

    @DynamicPropertySource
    static void uniqueConsumerGroup(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.consumer.group-id", () -> "token-load-" + System.currentTimeMillis());
    }

    @BeforeAll
    void seedFixtures() {
        cleanupAll(); // 이전 잔여 제거
        for (int t = 0; t < TENANTS; t++) {
            String tk = T_PREFIX + t;
            jdbc.update("INSERT IGNORE INTO tenants (tenant_id, email, password_hash, name) VALUES (?, ?, ?, ?)",
                    tk, tk + "@load.local", "x", "load");
            tenantPks[t] = jdbc.queryForObject("SELECT id FROM tenants WHERE tenant_id = ?", Long.class, tk);
        }
        for (int q = 0; q < QUEUES; q++) {
            String qid = Q_PREFIX + q;
            queueIds.add(qid);
            jdbc.update("INSERT IGNORE INTO queues (queue_id, tenant_id, name, max_capacity) VALUES (?, ?, ?, ?)",
                    qid, tenantPks[q / (QUEUES / TENANTS)], "load-" + q, (int) CAPACITY);
        }
        redisMemBaseline = redisUsedMemory(); // 부하 전 기준선 (정리 직후 = 깨끗한 상태)
    }

    @AfterAll
    void cleanupFixtures() {
        cleanupAll();
    }

    @Test
    @DisplayName("N명 랜덤 큐 enqueue → Redis → Kafka → Consumer → MySQL 적재까지 전 구간 스케일 검증")
    void fullPipeline_underLoad() throws Exception {
        awaitListenerAssignment(Duration.ofSeconds(30));

        // 1) COUNT명을 50큐에 랜덤 분배 (고정 시드로 재현 가능)
        int[] perQueue = new int[QUEUES];
        Random rnd = new Random(42);
        for (int i = 0; i < COUNT; i++) perQueue[rnd.nextInt(QUEUES)]++;

        final Instant issuedAt = Instant.now(); // 현재 파티션(월)에 적재
        long enqNanos = 0, pubNanos = 0, okCount = 0;

        // 2) 큐별 청크 단위로 enqueue + 발행 (스트리밍 — 메모리 유계)
        for (int q = 0; q < QUEUES; q++) {
            String queueId = queueIds.get(q);
            long tenantId = tenantPks[q / (QUEUES / TENANTS)];
            int total = perQueue[q];

            for (int base = 0; base < total; base += CHUNK) {
                int size = Math.min(CHUNK, total - base);
                List<PendingEnqueue> batch = new ArrayList<>(size);
                for (int j = 0; j < size; j++) {
                    String identifier = "u_" + q + "_" + (base + j); // 큐 내 유일 → 전부 OK(EXISTS 없음)
                    batch.add(new PendingEnqueue(queueId, identifier, IdGenerator.generate("tok_")));
                }

                long t0 = System.nanoTime();
                List<EnqueueResult> results = engine.parseBulkResult(engine.executeBulkLua(queueId, batch, CAPACITY));
                long t1 = System.nanoTime();
                enqNanos += (t1 - t0);

                for (EnqueueResult r : results) {
                    if (r.isOk()) {
                        publisher.publish(EnqueueEvent.of(tenantId, queueId, r, issuedAt));
                        okCount++;
                    }
                }
                pubNanos += (System.nanoTime() - t1);
            }
        }
        kafkaTemplate.flush(); // 발행 확정

        // 3) 적재 완료까지 폴링
        long expected = okCount;
        long t3 = System.nanoTime();
        long persistTimeoutSec = Math.max(180, COUNT / 1000);
        boolean allPersisted = awaitDbCount(expected, Duration.ofSeconds(persistTimeoutSec));
        long persistMs = (System.nanoTime() - t3) / 1_000_000;

        long dbTotal = dbCount();

        // 3-1) 메모리 측정 (정리 전 — 데이터가 살아있는 상태)
        Properties memAfter = redisMemInfo();
        long redisUsed = Long.parseLong(memAfter.getProperty("used_memory"));
        long redisRss = Long.parseLong(memAfter.getProperty("used_memory_rss"));
        String frag = memAfter.getProperty("mem_fragmentation_ratio");
        long redisDelta = redisUsed - redisMemBaseline;

        jdbc.execute("ANALYZE TABLE tokens");
        Map<String, Object> tbl = jdbc.queryForMap(
                "SELECT DATA_LENGTH + INDEX_LENGTH AS total_bytes, TABLE_ROWS AS row_cnt " +
                        "FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tokens'");
        long dbBytes = ((Number) tbl.get("total_bytes")).longValue();
        long dbRows = ((Number) tbl.get("row_cnt")).longValue();
        double dbPerRow = dbRows > 0 ? (double) dbBytes / dbRows : 0;

        // 4) 리포트
        double enqSec = enqNanos / 1e9, pubSec = pubNanos / 1e9;
        double MB = 1024.0 * 1024.0;
        System.out.printf("%n===== LOAD RESULT (COUNT=%,d) =====%n", COUNT);
        System.out.printf("enqueue(Redis Lua): %,d ok / %.2fs → %,.0f TPS%n", okCount, enqSec, okCount / enqSec);
        System.out.printf("publish(Kafka)    : %.2fs → %,.0f TPS%n", pubSec, okCount / pubSec);
        System.out.printf("persist(→MySQL)   : %,d rows / %,dms (폴링 완료=%b)%n", dbTotal, persistMs, allPersisted);
        System.out.printf("[Redis] used_memory Δ %.1f MB (기준 %.1f→%.1f MB, ~%.0f B/token) | rss %.1f MB, frag %s%n",
                redisDelta / MB, redisMemBaseline / MB, redisUsed / MB, (double) redisDelta / COUNT, redisRss / MB, frag);
        System.out.printf("[MySQL] tokens 총 %.1f MB (~%.0f B/row) → %,d건 추정 %.1f MB%n",
                dbBytes / MB, dbPerRow, COUNT, dbPerRow * COUNT / MB);
        System.out.println("==================================");

        // 5) 검증
        assertThat(okCount).as("유일 identifier + 충분한 capacity → 전부 OK").isEqualTo(COUNT);
        assertThat(allPersisted).as("발행분이 tokens에 전부 적재됨").isTrue();
        assertThat(dbTotal).as("DB row 수 == 발행 성공 수").isEqualTo(expected);

        Long distinctTokenId = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT token_id) FROM tokens WHERE queue_id IN (" + inPlaceholders() + ")",
                Long.class, queueIds.toArray());
        assertThat(distinctTokenId).as("token_id 전역 유일 — 중복 0").isEqualTo(expected);

        List<Map<String, Object>> seqDup = jdbc.queryForList(
                "SELECT queue_id FROM tokens WHERE queue_id IN (" + inPlaceholders() + ") " +
                        "GROUP BY queue_id HAVING COUNT(*) <> COUNT(DISTINCT seq)", queueIds.toArray());
        assertThat(seqDup).as("큐별 seq 유일 — 중복 seq 있는 큐 없음").isEmpty();
    }

    // ---------------------------------------------------------------------

    private void awaitListenerAssignment(Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            Collection<MessageListenerContainer> containers = registry.getListenerContainers();
            boolean allAssigned = !containers.isEmpty() && containers.stream().allMatch(c -> {
                Collection<TopicPartition> parts = c.getAssignedPartitions();
                return parts != null && !parts.isEmpty();
            });
            if (allAssigned) return;
            Thread.sleep(500);
        }
        throw new IllegalStateException("리스너 파티션 할당 대기 초과 — Kafka 확인");
    }

    private boolean awaitDbCount(long target, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (dbCount() >= target) return true;
            Thread.sleep(1000);
        }
        return false;
    }

    private long dbCount() {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tokens WHERE queue_id IN (" + inPlaceholders() + ")",
                Long.class, queueIds.toArray());
        return n == null ? 0 : n;
    }

    private String inPlaceholders() {
        return String.join(",", java.util.Collections.nCopies(queueIds.size(), "?"));
    }

    private Properties redisMemInfo() {
        return redis.execute((RedisCallback<Properties>) c -> c.serverCommands().info("memory"));
    }

    private long redisUsedMemory() {
        return Long.parseLong(redisMemInfo().getProperty("used_memory"));
    }

    /** Redis 키(50큐 × waiting/seq/tokens) + DB(tokens/queues/tenants) 전부 정리. */
    private void cleanupAll() {
        for (int q = 0; q < QUEUES; q++) {
            String qid = Q_PREFIX + q;
            redis.delete(QueueKeys.waiting(qid));
            redis.delete(QueueKeys.seq(qid));
            redis.delete(QueueKeys.tokens(qid));
        }
        jdbc.update("DELETE FROM tokens  WHERE queue_id  LIKE 'q\\_load\\_%'");
        jdbc.update("DELETE FROM queues  WHERE queue_id  LIKE 'q\\_load\\_%'");
        jdbc.update("DELETE FROM tenants WHERE tenant_id LIKE 't\\_load\\_%'");
    }
}
