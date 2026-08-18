package com.sonix.queue.infrastructure.queue;

import io.lettuce.core.cluster.SlotHash;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link QueueKeys}가 만드는 <b>모든</b> 키가 같은 queueId에 대해 같은 슬롯에 떨어지는지 단언한다.
 *
 * <p><b>왜 리플렉션 전수인가:</b> 키를 하나씩 나열해 검사하면 <b>앞으로 추가되는 키</b>가
 * 검사에서 빠진다. 해시태그 없는 키를 기존 Lua의 KEYS에 끼워 넣는 순간 그 스크립트는
 * {@code CROSSSLOT}으로 죽는데, 이 결함은 Cluster에서만 드러난다. 팩토리 메서드를 전수
 * 열거하면 새 키도 자동으로 이 단언에 걸린다.
 *
 * <p><b>왜 Redis 없이 하는가:</b> 슬롯은 {@code CRC16(해시태그) % 16384}로 결정되는 순수 함수다.
 * 실 클러스터에 붙어 확인하면 "태그가 없어도 우연히 같은 노드"인 경우가 1/4 확률로 통과한다
 * (마스터 4대). 슬롯 자체를 비교하면 그 운 요소가 원리적으로 개입하지 못한다.
 * {@link SlotHash}는 Lettuce가 실제 라우팅에 쓰는 바로 그 구현이다.
 */
class QueueKeysSlotTest {

    /**
     * 4개 마스터(0-4095 / 4096-8191 / 8192-12287 / 12288-16383)를 전부 밟는 12개 queueId.
     *
     * <p>큐 하나짜리로 검사하면 "그 큐가 우연히 배치된 한 노드"만 보게 된다. 슬롯 구간을
     * 전부 덮어야 노드 배치와 무관하게 성립한다는 것이 드러난다.
     */
    static final List<String> QUEUE_IDS = List.of(
            "q_dev_slot_0", "q_dev_slot_1", "q_dev_slot_2", "q_dev_slot_3",
            "q_dev_slot_4", "q_dev_slot_5", "q_dev_slot_6", "q_dev_slot_7",
            "q_dev_slot_8", "q_dev_slot_9", "q_dev_slot_12", "q_dev_slot_13");

    /**
     * {@code String...} -> {@code String} 형태의 public static 키 팩토리 전부.
     *
     * <p><b>인자 개수를 1개로 묶지 않는 이유:</b> §80이 추가할 admit 키 4종 중 셋은
     * {@code admitByToken(queueId, tokenId)}처럼 <b>2인자</b>다(두 번째 인자는 tokenId·
     * admitToken·requestId 같은 런타임 값). {@code parameterCount == 1}로 거르면 그 셋이
     * 조용히 검사에서 빠져, "전수 열거라 새 키도 자동으로 걸린다"는 이 테스트의 약속이
     * 깨진다. 첫 인자가 queueId(= 해시태그)인 규약만 지켜지면 인자가 몇 개든 슬롯은 같다.
     */
    private static List<Method> keyFactories() {
        return Arrays.stream(QueueKeys.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> Modifier.isStatic(m.getModifiers()))
                .filter(m -> m.getReturnType() == String.class)
                .filter(m -> m.getParameterCount() >= 1)
                .filter(m -> Arrays.stream(m.getParameterTypes()).allMatch(t -> t == String.class))
                .sorted((a, b) -> a.getName().compareTo(b.getName()))
                .toList();
    }

    /** 첫 인자에 queueId, 나머지 인자에는 런타임 값을 흉내 낸 더미를 채운다. */
    private static String invoke(Method factory, String queueId) throws Exception {
        Object[] args = new Object[factory.getParameterCount()];
        args[0] = queueId;
        for (int i = 1; i < args.length; i++) {
            args[i] = "runtime-value-" + i;
        }
        return (String) factory.invoke(null, args);
    }

    @Test
    @DisplayName("QueueKeys의 모든 키 팩토리는 같은 queueId에 대해 같은 슬롯을 만든다")
    void allKeysOfSameQueueShareSlot() throws Exception {
        List<Method> factories = keyFactories();

        // 열거 자체가 고장나면(예: 시그니처 변경) 단언이 0건을 통과해 무력화된다.
        assertThat(factories)
                .as("QueueKeys의 키 팩토리를 하나도 못 찾았다 — 리플렉션 필터가 낡았다")
                .hasSizeGreaterThanOrEqualTo(4);

        for (String queueId : QUEUE_IDS) {
            Map<String, Integer> slotByKey = new LinkedHashMap<>();
            for (Method factory : factories) {
                String key = invoke(factory, queueId);
                slotByKey.put(key, SlotHash.getSlot(key));
            }

            assertThat(Set.copyOf(slotByKey.values()))
                    .as("queueId=%s 의 키들이 서로 다른 슬롯에 떨어진다 (해시태그 누락 → CROSSSLOT): %s",
                            queueId, slotByKey)
                    .hasSize(1);
        }
    }

    /**
     * §80(Sprint 7 Admit)이 추가할 키 4종. <b>아직 {@link QueueKeys}에 없어</b> 문자열로 만든다.
     *
     * <p>구현 전에 못 박는 이유: admit.lua는 이 키들을 {@code KEYS[]} 선언 없이 스크립트 안에서
     * 조립한다(tokenId·admitToken·requestId가 런타임 값이라 선언이 불가능). 선언이 없으면 Redis의
     * CROSSSLOT 사전 검사도 걸리지 않고, <b>슬롯이 어긋나도 같은 노드면 조용히 성공</b>한다
     * (마스터 4대 기준 25%). 즉 실행 검증만으로는 결함이 안 드러나므로 슬롯 동일성을 따로 단언한다.
     *
     * <p>{@code QueueKeys}에 팩토리가 들어오면 이 메서드는 지운다 — 그때는 위 전수 열거가 덮는다.
     */
    private static List<String> admitKeysOf(String queueId) {
        String tag = "queue:{" + queueId + "}:";
        return List.of(
                tag + "admitted",
                tag + "admit-by-token:tok_01J8ZXQ7",
                tag + "admit-by-admit:adm_9f3c1b",
                tag + "admit-idem:req-tenant-supplied");
    }

    @Test
    @DisplayName("§80이 추가할 admit 키 4종도 기존 큐 키와 같은 슬롯이다")
    void admitKeysShareSlotWithExistingQueueKeys() {
        for (String queueId : QUEUE_IDS) {
            int expected = SlotHash.getSlot(QueueKeys.waiting(queueId));

            Map<String, Integer> slotByKey = new LinkedHashMap<>();
            for (String key : admitKeysOf(queueId)) {
                slotByKey.put(key, SlotHash.getSlot(key));
            }

            assertThat(slotByKey.values())
                    .as("queueId=%s - admit 키가 waiting(slot %d)과 다른 슬롯에 떨어진다: %s",
                            queueId, expected, slotByKey)
                    .containsOnly(expected);
        }
    }

    @Test
    @DisplayName("검사에 쓰는 12개 queueId는 4개 마스터의 슬롯 구간을 전부 덮는다")
    void queueIdsCoverAllMasterRanges() {
        Set<Integer> ranges = new TreeSet<>();
        for (String queueId : QUEUE_IDS) {
            ranges.add(SlotHash.getSlot(QueueKeys.waiting(queueId)) / 4096);
        }

        assertThat(ranges)
                .as("샘플이 한쪽 마스터에만 몰려 있으면 '우연히 통과'를 배제하지 못한다")
                .containsExactly(0, 1, 2, 3);
    }
}
