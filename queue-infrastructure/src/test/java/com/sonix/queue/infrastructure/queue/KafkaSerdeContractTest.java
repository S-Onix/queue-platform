package com.sonix.queue.infrastructure.queue;

import com.sonix.queue.domain.queue.EnqueueEvent;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 <b>프로듀서가 쓴 바이트를 컨슈머가 그대로 읽는가</b> — 유일하게 실 브로커가 필요한 검증.
 *
 * <p><b>왜 브로커가 필요한가:</b> 직렬화 계약이 <b>서로 다른 모듈의 서로 다른 yml</b>에 절반씩
 * 적혀 있는데, {@code queue-consumer}는 최말단이라 아무도 참조하지 않으므로(§73 D20) 한
 * 컨텍스트에서 양쪽 설정을 같이 띄울 수 없다. 그래서 브로커를 사이에 둔다.
 *
 * <p>🔑 <b>설정값은 두 yml에서 실제로 읽는다.</b> 복붙하면 계약 검증이 아니라 상수 잠금이 되어,
 * 정작 yml이 바뀌었을 때 이 테스트가 조용히 통과한다. 다만 범위는 <b>{@code properties} 절 전량 +
 * kebab 키 4개</b>(key/value 직렬화기 · acks · auto-offset-reset)다 — 직렬화에 영향을 주는 kebab
 * 키가 새로 생기면 손으로 옮겨야 하고, 안 옮기면 이 테스트는 조용히 통과한다.
 *
 * <h2>이 테스트가 잡는 것 / 못 잡는 것 (2026-09-02 반사실 실측)</h2>
 * <b>주의: 세 설정이 다 같은 무게가 아니다.</b> 하중을 받는 것은 하나뿐이다.
 * <table border="1">
 *   <caption>yml을 하나씩 깨뜨려 실제로 재본 결과</caption>
 *   <tr><th>깨뜨린 것</th><th>결과</th><th>왜</th></tr>
 *   <tr><td>컨슈머 {@code spring.json.value.default.type}</td><td>🔴 <b>FAILED</b></td>
 *       <td>타입 헤더가 없으므로 이 값이 <b>유일한</b> 타입 정보다</td></tr>
 *   <tr><td>컨슈머 {@code spring.json.use.type.headers: false} 삭제</td><td>PASSED</td>
 *       <td>헤더가 애초에 없어 기본 타입으로 되돌아간다</td></tr>
 *   <tr><td>프로듀서 {@code spring.json.add.type.headers: true}</td><td>PASSED</td>
 *       <td>컨슈머가 헤더를 안 쓴다</td></tr>
 *   <tr><td>컨슈머 {@code spring.json.trusted.packages}</td><td>PASSED</td>
 *       <td>신뢰 검사는 <b>타입 헤더를 읽을 때만</b> 돈다 — 지금은 도달 불가</td></tr>
 * </table>
 *
 * <p>🔑 <b>{@code add}=true + {@code use}=true로 켜도 {@code trusted.packages}를 깨뜨릴 수 없다</b>
 * (2026-09-02 조합 실측). 🔴 <b>헤더를 안 읽어서가 아니다</b> — 그 조합에서는 실제로 헤더를 읽는다.
 * 깨지지 않는 이유는 {@code JsonDeserializer.initialize()}가
 * {@code addTargetPackageToTrusted()}를 불러 <b>기본 타입의 패키지를 신뢰 목록에 자동 추가</b>하기
 * 때문이다(바이트코드 확인). 그래서 헤더의 FQCN이 {@code com.sonix.queue.domain.queue.*}인 한
 * 언제나 신뢰된다. {@code value.default.type}까지 지워야 비로소 신뢰 검사가 하중을 받는다.
 *
 * <p>🪤 <b>그래서 열려 있는 실제 위험은 "패키지 이동"이다.</b> 누가 {@code add.type.headers}를 켠
 * 상태에서 이벤트 클래스를 다른 패키지로 옮기면, 헤더의 FQCN이 자동 신뢰 목록 밖이라 역직렬화가
 * 실패한다. 지금 조합({@code add}=false)에서는 런타임 영향이 0이다.
 *
 * <p>🪤 <b>그래서 "양쪽 yml이 어긋나면 전 구간이 죽는다"는 과장이다.</b> 실제로 죽는 경로는
 * {@code value.default.type} 하나뿐이고, 나머지 셋은 지금 조합에서 <b>여분</b>이다
 * (일관되게 맞춰 둔 것이지 각각이 하중을 받는 게 아니다). 이 문단을 지우지 마라 —
 * 지우면 다음 사람이 네 설정이 다 지켜지는 줄 알고 이 테스트를 믿는다.
 *
 * <p>🪤 <b>타입 헤더 단정은 넣지 않았다.</b> {@code JsonDeserializer.removeTypeHeaders}가 기본
 * {@code true}라 소비 시점엔 헤더가 이미 지워져 있다 — 단정을 걸어도 <b>영원히 통과하는
 * 죽은 신호</b>가 된다(실측). 그리고 프로듀서가 헤더를 싣기 시작해도 깨지는 것이 없다.
 *
 * <p>기존 {@code EnqueueEventSerdeTest}(8건)와 겹치지 않는다. 저쪽은 {@code ObjectMapper}를 직접
 * 왕복시켜 <b>record의 하위 호환</b>을 본다 — Spring Kafka의 직렬화기 설정을 한 줄도 타지 않는다.
 */
@Tag("kafka")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KafkaSerdeContractTest {

    /**
     * 운영 토픽을 건드리지 않는다. <b>실행마다 이름이 다르다</b> — 고정 이름이면 공유 브로커에서
     * 두 판이 겹칠 때 남의 {@code @AfterAll}이 내 본문 도중 토픽을 지워
     * {@code KafkaException: Send failed}로 깨진다(실측 재현). CI는 브로커가 잡별 일회용이라
     * 무해하지만, 로컬은 브로커 하나를 여럿이 쓴다.
     */
    private static final String TOPIC = "token-lifecycle-serde-contract-" + UUID.randomUUID();

    private static Map<String, Object> apiKafka;
    private static Map<String, Object> consumerKafka;
    private static String bootstrap;

    @BeforeAll
    void loadYamlAndCreateTopic() throws Exception {
        apiKafka = kafkaSection("queue-api");
        consumerKafka = kafkaSection("queue-consumer");
        bootstrap = resolvePlaceholder((String) apiKafka.get("bootstrap-servers"));

        // 컨슈머의 bootstrap이 다르면 이 테스트가 성립하지 않는다 — 계약의 전제부터 확인한다.
        assertThat(resolvePlaceholder((String) consumerKafka.get("bootstrap-servers")))
                .as("두 앱이 같은 클러스터를 보고 있어야 한다")
                .isEqualTo(bootstrap);

        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap))) {
            int brokers = admin.describeCluster().nodes().get(10, TimeUnit.SECONDS).size();
            // RF는 브로커 수에 맞춘다(로컬 3 / CI 1). min.insync=1로 둬야 단일 브로커에서도
            // acks=all이 성립한다 — 이 테스트가 재려는 것은 복제 설정이 아니라 직렬화다.
            NewTopic topic = new NewTopic(TOPIC, 1, (short) Math.min(3, brokers))
                    .configs(Map.of("min.insync.replicas", "1"));
            // 삼키지 않는다. 이름에 UUID가 붙어 존재 충돌이 없으므로 여기서 나는 실패는
            // RF 오류·타임아웃·인가 실패뿐이고, 삼키면 auto.create.topics가 브로커 기본 파티션
            // 수로 토픽을 만들어 assign(0)이 아무것도 못 읽는다 — 그때 나는 실패 메시지는
            // "계약이 깨졌다"라 원인을 정반대로 가리킨다.
            admin.createTopics(List.of(topic)).all().get(20, TimeUnit.SECONDS);
        }
    }

    @AfterAll
    void dropTopic() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap))) {
            admin.deleteTopics(List.of(TOPIC)).all().get(20, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // 삭제 실패는 테스트 결과를 바꾸지 않는다(이름이 판마다 다르므로 다음 판과 무관).
            // 대신 공유 브로커에 토픽이 하나 쌓인다 — 잔재를 없애려던 취지와는 반대라,
            // 자주 보이면 그때 손봐야 한다.
        }
    }

    @Test
    @DisplayName("🔴 프로듀서가 쓴 바이트를 컨슈머가 EnqueueEvent로 읽는다 (기본 타입 계약)")
    void producerBytesAreReadableByConsumer() throws Exception {
        EnqueueEvent sent = new EnqueueEvent(
                "ADMITTED", "tok_" + UUID.randomUUID(), "q_serde", 7L, "user-serde", 4242L,
                Instant.parse("2026-09-02T01:02:03.456Z"), "adm_abc",
                Instant.parse("2026-09-02T01:02:04.789Z"), null);

        ProducerFactory<String, Object> pf = new DefaultKafkaProducerFactory<>(producerProps());
        try (Consumer<String, Object> consumer =
                     new DefaultKafkaConsumerFactory<String, Object>(consumerProps()).createConsumer()) {
            // 🔑 subscribe가 아니라 assign이다. subscribe는 <b>컨슈머 그룹에 가입</b>하고,
            // 가입한 그룹은 오프셋을 커밋하지 않아도 브로커에 Empty 상태로 남는다(실측: 실행마다 +1).
            // 오토커밋을 꺼도 안 없어진다 — 커밋과 그룹 생성은 다른 일이다.
            // 이 테스트는 파티션이 1개이고 재배분이 필요 없으므로 수동 배정으로 충분하고,
            // 덤으로 리밸런스 대기가 사라져 더 빠르고 결정적이다.
            consumer.assign(List.of(new TopicPartition(TOPIC, 0)));
            // seekToBeginning을 넣지 않는다. group.id가 없어 커밋된 오프셋이 존재할 수 없으므로
            // 위치는 yml의 auto-offset-reset(earliest)이 정한다 — 명시 seek을 걸면 그 값이
            // latest로 바뀌어도 테스트가 통과해, 복사해 온 설정 하나가 검증에서 빠진다.

            KafkaTemplate<String, Object> template = new KafkaTemplate<>(pf);
            template.send(TOPIC, sent.tokenId(), sent).get(15, TimeUnit.SECONDS);
            template.destroy();

            ConsumerRecord<String, Object> got = pollOne(consumer, sent.tokenId());

            assertThat(got).as("컨슈머가 메시지를 못 받았다 — 계약이 깨졌거나 브로커가 없다").isNotNull();
            // ErrorHandlingDeserializer가 실패를 삼키면 값이 null로 온다. 그 상태를 통과시키면 안 된다.
            assertThat(got.value())
                    // 표에서 실측했듯 실제로 깨지는 원인은 컨슈머의 value.default.type 하나다.
                    // "타입 헤더 설정"이라 적으면 실패한 사람을 엉뚱한 줄로 보낸다.
                    .as("역직렬화 실패(값 null) — 컨슈머의 spring.json.value.default.type을 먼저 봐라")
                    .isInstanceOf(EnqueueEvent.class);
            assertThat((EnqueueEvent) got.value()).isEqualTo(sent);
            assertThat(got.key()).isEqualTo(sent.tokenId());
        }
    }

    private ConsumerRecord<String, Object> pollOne(Consumer<String, Object> consumer, String key) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, Object> records = consumer.poll(Duration.ofSeconds(2));
            for (ConsumerRecord<String, Object> r : records) {
                if (key.equals(r.key())) {
                    return r;
                }
            }
        }
        return null;
    }

    // ── 두 yml에서 실제 설정을 읽는다 ────────────────────────────────────

    private Map<String, Object> producerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        Map<String, Object> producer = section(apiKafka, "producer");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, producer.get("key-serializer"));
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, producer.get("value-serializer"));
        props.put(ProducerConfig.ACKS_CONFIG, String.valueOf(producer.get("acks")));
        section(producer, "properties").forEach((k, v) -> props.put(k, String.valueOf(v)));
        return props;
    }

    private Map<String, Object> consumerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        Map<String, Object> consumer = section(consumerKafka, "consumer");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, consumer.get("key-deserializer"));
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, consumer.get("value-deserializer"));
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, consumer.get("auto-offset-reset"));
        // group.id를 주지 않는다. assign()으로 수동 배정하므로 그룹이 필요 없고,
        // 그래야 운영 그룹(db-writer)은 물론 임시 그룹조차 브로커에 남기지 않는다.
        // 오토커밋은 group.id 없이는 성립하지 않으므로 명시적으로 끈다.
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        section(consumer, "properties").forEach((k, v) -> props.put(k, String.valueOf(v)));
        return props;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> parent, String key) {
        Object v = parent.get(key);
        return v instanceof Map ? new LinkedHashMap<>((Map<String, Object>) v) : Map.of();
    }

    /** {@code ../<module>/src/main/resources/application.yml} 의 {@code spring.kafka} 절. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> kafkaSection(String module) throws Exception {
        Path yml = Path.of("..", module, "src", "main", "resources", "application.yml");
        assertThat(Files.exists(yml))
                .as("설정 원본을 못 찾았다: %s (테스트 작업 디렉터리가 모듈 루트가 아닌가)", yml.toAbsolutePath())
                .isTrue();
        try (InputStream in = Files.newInputStream(yml)) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> kafka = section(section(root, "spring"), "kafka");
            assertThat(kafka).as("%s에 spring.kafka가 없다", yml).isNotEmpty();
            return kafka;
        }
    }

    /** {@code ${KAFKA_BOOTSTRAP:a,b,c}} → 환경변수 우선, 없으면 기본값. */
    private static String resolvePlaceholder(String raw) {
        if (raw == null || !raw.startsWith("${")) {
            return raw;
        }
        String body = raw.substring(2, raw.length() - 1);
        int colon = body.indexOf(':');
        String name = colon < 0 ? body : body.substring(0, colon);
        String fallback = colon < 0 ? null : body.substring(colon + 1);
        String env = System.getenv(name);
        return env != null ? env : fallback;
    }
}
