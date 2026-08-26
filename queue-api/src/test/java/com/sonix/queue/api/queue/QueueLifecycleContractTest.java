package com.sonix.queue.api.queue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonix.queue.domain.queue.Queue;
import com.sonix.queue.domain.queue.QueueRepository;
import com.sonix.queue.domain.queue.QueueStatus;
import com.sonix.queue.domain.tenant.TenantRepository;
import com.sonix.queue.infrastructure.queue.QueueKeys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.autoconfigure.filter.TypeExcludeFilters;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 큐 <b>상태 전이 계약</b>(QE006 / 409)과 <b>생성 입력 검증</b>({@code @Min})의 통합 테스트.
 *
 * <p><b>왜 슬라이스(@WebMvcTest)가 아니라 전 컨텍스트인가</b>: 검증 대상이 컨트롤러 하나에
 * 있지 않다. 400은 {@code @Valid}(핸들러 어댑터), 409는 {@code QueueService.guardTransition},
 * "409로 위장되지 않는다"는 {@code GlobalExceptionHandler}에 <b>안 걸린다는 사실</b>이고,
 * 마지막 재현은 실제 Redis Lua다. 목으로 끊으면 그중 무엇도 남지 않는다.
 *
 * <p><b>왜 SQL 직접 시드가 아니라 REST 전 경로인가</b>: signup → login → JWT → 큐 API를 그대로
 * 탄다. 예전에 SQL로 시드한 통합 테스트가 인증 필터 결함(엔진 경로가 전부 401)을 숨긴 전례가
 * 있다. 여기서도 Security 체인·Rate Limit 필터를 전부 통과한 상태로 계약을 본다.
 *
 * <p><b>공유 인프라 규약</b>: 같은 MySQL·Redis를 다른 에이전트가 동시에 쓴다.
 * 이 클래스가 만드는 것은 전부 {@code it_state_} 네임스페이스이고 {@code @AfterAll}에서
 * <b>자기 tenant_id에 속한 행만</b> 지운다. 전체 DELETE/TRUNCATE/FLUSHALL은 쓰지 않는다.
 *
 * <p><b>Rate Limit 격리</b>: signup/login은 IP Fixed Window(5/분, 10/분)다. MockMvc 기본
 * remoteAddr(127.0.0.1)을 쓰면 같은 IP를 쓰는 다른 에이전트의 인증 테스트와 한도를 나눠 써
 * 우리 쪽이 무작위로 429가 된다. 그래서 {@link #FROM_TEST_IP}로 전용 출발지를 박는다
 * (필터는 XFF가 아니라 {@code getRemoteAddr()}만 본다 — {@code RateLimitFilter} 참조).
 */
@SpringBootTest(properties = {
        // 🪤 오버라이드가 없으면 local 프로파일의 replica(3307)를 본다. CI는 MySQL이 한 대뿐이라
        //    ConnectException으로 11건이 깨진다 — 로컬엔 3307이 있어서 통과하고 CI에서만 드러났다.
        //    다른 @Tag("mysql") 테스트들이 이미 같은 오버라이드를 갖고 있다.
        // 🔴 대가: 이 설정에서는 master/replica 라우팅이 갈리지 않는다. readOnly가 replica로 새는
        //    결함(§86의 countBillingMismatch 같은 것)은 **어떤 테스트로도 못 잡는다.** 눈으로 봐야 한다
        "spring.datasource.replica.jdbc-url=jdbc:mysql://127.0.0.1:3306/queue_platform?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
})
@AutoConfigureMockMvc
@TypeExcludeFilters(QueueLifecycleContractTest.ExcludeStrayTestConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("mysql")
@Tag("redis")
class QueueLifecycleContractTest {

    /**
     * {@code EnqueueE2ETestConfig}를 컴포넌트 스캔에서 뺀다.
     *
     * <p>⚠️ <b>테스트 소스셋의 {@code @Configuration}은 전 컨텍스트 부팅 시 함께 스캔된다.</b>
     * {@code QueueApiApplication}이 {@code scanBasePackages = "com.sonix.queue"}라
     * 같은 패키지에 있는 그 클래스가 잡히고, 거기 든 인메모리 스텁
     * ({@code queueRepository}·{@code tenantRepository}·{@code enqueueEventPublisher} 등)이
     * 진짜 JPA/Kafka 빈과 <b>같은 타입 2개</b>가 되어 기동이 {@code NoUniqueBeanDefinitionException}으로
     * 죽는다(실측). 그 클래스에 {@code @TestConfiguration}을 붙여 고치는 방법은 쓸 수 없다 —
     * 그러면 {@code @SpringBootTest(classes = EnqueueE2ETestConfig.class)}로 쓰는 기존 두 테스트가
     * {@code containsNonTestComponent == false}가 되어 <b>앱 전체를 함께 부팅</b>하게 된다.
     *
     * <p>그래서 <b>이쪽에서만</b> 뺀다. Boot이 이 목적으로 제공하는 {@code @TypeExcludeFilters}는
     * 스캔 이전에 필터를 등록하므로, 나중에 도는 {@code BeanFactoryPostProcessor}와 달리
     * 애초에 빈 정의가 생기지 않는다.
     */
    static class ExcludeStrayTestConfig extends TypeExcludeFilter {
        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
            return EnqueueE2ETestConfig.class.getName().equals(reader.getClassMetadata().getClassName());
        }

        // Boot이 컨텍스트 캐시 키에 이 필터를 넣으므로 equals/hashCode가 없으면 기동을 거부한다.
        // 상태가 없으니 "같은 클래스면 같다"로 충분하다.
        @Override public boolean equals(Object o) { return o != null && getClass() == o.getClass(); }
        @Override public int hashCode() { return getClass().hashCode(); }
    }

    /** 이 테스트가 소유하는 네임스페이스. 정리도 이 범위 안에서만 한다. */
    private static final String NS = "it_state_";

    /** signup의 {@code @Size(min = 12)}를 만족해야 한다 — 짧으면 계약 검증 전에 400으로 죽는다. */
    private static final String PASSWORD = NS + "password_1234";

    /** 공유 IP의 Fixed Window를 남과 나눠 쓰지 않기 위한 전용 출발지. */
    private static final RequestPostProcessor FROM_TEST_IP = request -> {
        request.setRemoteAddr("10.234.77.1");
        return request;
    };

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private QueueRepository queueRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired @Qualifier("stringRedisTemplate") private StringRedisTemplate cluster1;
    @Autowired @Qualifier("cluster2StringRedisTemplate") private StringRedisTemplate cluster2;
    @Autowired private DataSource dataSource;

    private String accessToken;
    private Long tenantId;
    private final List<String> createdQueueIds = new ArrayList<>();

    // ── 준비 / 정리 ──────────────────────────────────────────────────────────

    @BeforeAll
    void signupAndLogin() throws Exception {
        String email = NS + System.nanoTime() + "@example.com";

        mockMvc.perform(post("/api/v1/tenants/signup").with(FROM_TEST_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","name":"%s"}
                                """.formatted(email, PASSWORD, NS + "tenant")))
                .andExpect(status().isOk());

        MvcResult login = mockMvc.perform(post("/api/v1/tenants/login").with(FROM_TEST_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();

        accessToken = json(login).path("data").path("accessToken").asText();
        assertThat(accessToken).isNotBlank();

        tenantId = tenantRepository.findByEmail(email).orElseThrow().getId();
    }

    @AfterAll
    void cleanUpOwnDataOnly() {
        for (String queueId : createdQueueIds) {
            // 소유 클러스터를 모르므로 양쪽에 지운다. 남의 키 이름은 여기서 나올 수 없다.
            for (StringRedisTemplate redis : List.of(cluster1, cluster2)) {
                redis.delete(List.of(
                        QueueKeys.waiting(queueId), QueueKeys.seq(queueId),
                        QueueKeys.tokens(queueId), QueueKeys.lastActive(queueId)));
            }
        }
        if (tenantId == null) {
            return;
        }
        // 리포지토리에 삭제 메서드가 없어 SQL로 지운다. WHERE는 전부 이 테스트가 만든
        // tenant_id 하나로 한정된다 — 남의 행에 닿을 수 있는 문장이 없다.
        deleteByTenantId("DELETE FROM tokens WHERE tenant_id = ?");
        deleteByTenantId("DELETE FROM queues WHERE tenant_id = ?");
        deleteByTenantId("DELETE FROM api_keys WHERE tenant_id = ?");
        deleteByTenantId("DELETE FROM refresh_tokens WHERE tenant_id = ?");
        deleteByTenantId("DELETE FROM tenants WHERE id = ?");
    }

    private void deleteByTenantId(String sql) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("정리 실패: " + sql, e);
        }
    }

    // ── ① 상태 전이 계약 (QE006 / 409) ────────────────────────────────────────

    @Nested
    @DisplayName("① 상태 전이")
    class StateTransition {

        @Test
        @DisplayName("ACTIVE 큐를 바로 삭제하면 500이 아니라 409 QE006이다")
        void deleteActive_is409() throws Exception {
            String queueId = createQueue(uniqueName("del-active"), 100);

            mockMvc.perform(delete("/api/v1/queues/" + queueId).with(auth()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorResponse.code").value("QE006"));
        }

        @Test
        @DisplayName("정상 순서 ACTIVE → pause → delete 는 그대로 성공한다")
        void pauseThenDelete_succeeds() throws Exception {
            // 🔴 이 테스트가 없으면 guardTransition이 "전이를 전부 409로 막는" 회귀를 통과시킨다.
            //    거부만 검증하면 거부만 잘하는 코드가 통과한다.
            String queueId = createQueue(uniqueName("happy-path"), 100);

            mockMvc.perform(post("/api/v1/queues/" + queueId + "/pause").with(auth()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("PAUSED"));

            mockMvc.perform(delete("/api/v1/queues/" + queueId).with(auth()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("DELETED"));
        }

        @Test
        @DisplayName("이미 PAUSED인 큐를 다시 pause하면 409 QE006이다")
        void pauseTwice_is409() throws Exception {
            String queueId = createQueue(uniqueName("pause-twice"), 100);

            mockMvc.perform(post("/api/v1/queues/" + queueId + "/pause").with(auth()))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/api/v1/queues/" + queueId + "/pause").with(auth()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorResponse.code").value("QE006"));
        }

        @Test
        @DisplayName("ACTIVE 큐의 resume은 409 QE006이고, PAUSED 큐의 resume은 성공한다")
        void resume_onlyFromPaused() throws Exception {
            String queueId = createQueue(uniqueName("resume"), 100);

            mockMvc.perform(post("/api/v1/queues/" + queueId + "/resume").with(auth()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorResponse.code").value("QE006"));

            mockMvc.perform(post("/api/v1/queues/" + queueId + "/pause").with(auth()))
                    .andExpect(status().isOk());

            // 반대 방향: 허용된 전이는 막히지 않는다
            mockMvc.perform(post("/api/v1/queues/" + queueId + "/resume").with(auth()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        }

        @Test
        @DisplayName("DELETED 큐의 수정은 409 QE006이고, ACTIVE 큐의 수정은 성공한다")
        void update_rejectedOnlyWhenDeleted() throws Exception {
            String queueId = createQueue(uniqueName("update"), 100);
            String newName = uniqueName("renamed");

            // 반대 방향 먼저: ACTIVE에서는 그대로 200
            mockMvc.perform(patch("/api/v1/queues/" + queueId).with(auth())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"%s"}
                                    """.formatted(newName)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value(newName));

            mockMvc.perform(post("/api/v1/queues/" + queueId + "/pause").with(auth()))
                    .andExpect(status().isOk());
            mockMvc.perform(delete("/api/v1/queues/" + queueId).with(auth()))
                    .andExpect(status().isOk());

            mockMvc.perform(patch("/api/v1/queues/" + queueId).with(auth())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"%s"}
                                    """.formatted(uniqueName("after-delete"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorResponse.code").value("QE006"));
        }

        @Test
        @DisplayName("QE006은 큐 전이 전용이다 — 다른 도메인의 IllegalStateException은 409로 위장되지 않는다")
        void otherIllegalStateException_isNotDisguisedAs409() throws Exception {
            // 🔴 이 테스트가 없으면 "IllegalStateException을 전역 핸들러에서 통째로 409에 매핑"하는
            //    구현이 위 테스트를 전부 통과한다. 그 순간 TimeZoneGuard·JwtKeyStore·RedisQueueEngine의
            //    프로그래머/인프라 오류가 "클라이언트 잘못(409)"으로 둔갑해 5xx 알람이 죽는다.
            //
            //    그 셋은 HTTP로 도달할 수 없다(기동 시점 · Lua 응답 파손). 로컬에서 재현하려면
            //    DB 타임존을 틀거나 Lua 반환을 깨야 하는데 둘 다 공유 인프라를 건드린다.
            //    그래서 대신 쓰는 것이 ApiKey.revoke()다 — 같은 IllegalStateException이고,
            //    guardTransition을 거치지 않으며, REST 두 번 호출로 재현된다.
            MvcResult issued = mockMvc.perform(post("/api/v1/tenants/me/api-keys").with(auth()))
                    .andExpect(status().isOk())
                    .andReturn();
            String apiKeyId = json(issued).path("data").path("apiKeyId").asText();

            mockMvc.perform(delete("/api/v1/tenants/me/api-keys/" + apiKeyId).with(auth()))
                    .andExpect(status().isOk());

            // 두 번째 revoke → ApiKey.revoke()의 IllegalStateException.
            // 처리기가 없어 DispatcherServlet 밖으로 그대로 빠져나온다(= 실 서버에서 500).
            // 이 예외가 어딘가에서 409로 바뀌면 아래 단언이 깨진다.
            assertThatThrownBy(() ->
                    mockMvc.perform(delete("/api/v1/tenants/me/api-keys/" + apiKeyId).with(auth())))
                    .hasRootCauseInstanceOf(IllegalStateException.class);
        }
    }

    // ── ② 생성 입력 검증 (@Min) ──────────────────────────────────────────────

    @Nested
    @DisplayName("② 생성 입력 검증")
    class CreateValidation {

        @Test
        @DisplayName("maxCapacity를 생략하면 400 — int 기본값 0이 통과하면 그 큐는 첫 사람부터 영구 429다")
        void maxCapacityOmitted_is400() throws Exception {
            mockMvc.perform(post("/api/v1/queues").with(auth())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"%s"}
                                    """.formatted(uniqueName("no-capacity"))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("maxCapacity 0 · 음수는 400")
        void maxCapacityZeroOrNegative_is400() throws Exception {
            for (int bad : new int[]{0, -1}) {
                mockMvc.perform(post("/api/v1/queues").with(auth())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"name":"%s","maxCapacity":%d}
                                        """.formatted(uniqueName("cap" + bad), bad)))
                        .andExpect(status().isBadRequest());
            }
        }

        @Test
        @DisplayName("waitingTtl 0 · inactiveTtl 0은 400 — 0이면 회수 cutoff가 now라 방금 폴링한 사람까지 집힌다")
        void ttlZero_is400() throws Exception {
            mockMvc.perform(post("/api/v1/queues").with(auth())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"%s","maxCapacity":100,"waitingTtl":0}
                                    """.formatted(uniqueName("wttl0"))))
                    .andExpect(status().isBadRequest());

            mockMvc.perform(post("/api/v1/queues").with(auth())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"%s","maxCapacity":100,"inactiveTtl":0}
                                    """.formatted(uniqueName("ittl0"))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("TTL 둘을 생략하면 통과하고 기본값 7200/300이 적용된다 — @Min이 null을 통과시키는 계약")
        void ttlOmitted_appliesDefaults() throws Exception {
            // 🔴 이 테스트가 없으면 @Min에 @NotNull을 함께 거는 "강화"가 통과한다.
            //    그 순간 TTL을 안 보내던 기존 Tenant의 큐 생성이 전부 400이 된다.
            MvcResult result = mockMvc.perform(post("/api/v1/queues").with(auth())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"%s","maxCapacity":1}
                                    """.formatted(uniqueName("ttl-default"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.waitingTtl").value(7200))
                    .andExpect(jsonPath("$.data.inactiveTtl").value(300))
                    // maxCapacity 1은 하한 경계 — @Min(1)이 실수로 @Min(2)가 되면 여기서 깨진다
                    .andExpect(jsonPath("$.data.maxCapacity").value(1))
                    .andReturn();

            createdQueueIds.add(json(result).path("data").path("queueId").asText());
        }
    }

    // ── ③ 그 검증이 막는 결함의 실물 ─────────────────────────────────────────

    @Nested
    @DisplayName("③ maxCapacity=0 큐의 실제 결과")
    class ZeroCapacityConsequence {

        @Test
        @DisplayName("maxCapacity=0 큐는 실제 Redis에서 첫 한 명부터 429 QUEUE_FULL이다")
        void zeroCapacityQueue_rejectsFirstUser() throws Exception {
            // ⚠️ 이제 API로는 이런 큐를 만들 수 없다(@Min(1) → 400). 그래서 리포지토리로 직접
            //    심는다. 여기서 재현하는 것이 ②의 400들이 실제로 막는 사고다 —
            //    enqueue_bulk.lua의 `if currentSize >= maxCapacity`가 0 >= 0으로 참이라,
            //    텅 빈 큐인데 아무도 못 들어간다. 관측상 "큐가 비었는데 전원 429"라
            //    원인을 역추적하기가 매우 어렵다.
            String queueId = NS + "zerocap_" + System.nanoTime();
            Queue zeroCapacity = Queue.reconstruct(
                    null, queueId, tenantId, uniqueName("zero-cap"),
                    0, 7200, 300, QueueStatus.ACTIVE, LocalDateTime.now(), null);
            queueRepository.save(zeroCapacity);
            createdQueueIds.add(queueId);

            // enqueue는 JWT가 아니라 X-API-Key 경로다 (ApiKeyAuthenticationFilter).
            MvcResult issued = mockMvc.perform(post("/api/v1/tenants/me/api-keys").with(auth()))
                    .andExpect(status().isOk())
                    .andReturn();
            String rawKey = json(issued).path("data").path("rawKey").asText();

            mockMvc.perform(post("/api/v1/queues/" + queueId + "/tokens")
                            .with(FROM_TEST_IP)
                            .header("X-API-Key", rawKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"identifier":"%sfirst_user"}
                                    """.formatted(NS)))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.errorResponse.code").value("Q005"));

            // 큐는 실제로 비어 있다 — 정원이 찬 게 아니라 정원이 0이라 못 들어간 것이다.
            assertThat(waitingSize(queueId)).isZero();
        }

        private long waitingSize(String queueId) {
            for (StringRedisTemplate redis : List.of(cluster1, cluster2)) {
                Long size = redis.opsForZSet().zCard(QueueKeys.waiting(queueId));
                if (size != null && size > 0) return size;
            }
            return 0L;
        }
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private RequestPostProcessor auth() {
        return request -> {
            request.addHeader("Authorization", "Bearer " + accessToken);
            return FROM_TEST_IP.postProcessRequest(request);
        };
    }

    private String uniqueName(String suffix) {
        return NS + suffix + "_" + System.nanoTime();
    }

    private String createQueue(String name, int maxCapacity) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/queues").with(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","maxCapacity":%d}
                                """.formatted(name, maxCapacity)))
                .andExpect(status().isOk())
                .andReturn();

        String queueId = json(result).path("data").path("queueId").asText();
        createdQueueIds.add(queueId);
        return queueId;
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
