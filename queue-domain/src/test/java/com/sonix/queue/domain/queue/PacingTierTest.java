package com.sonix.queue.domain.queue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pacing 오버라이드 파싱 (§79). Spring·Redis 없음.
 *
 * <p>이 값은 <b>사고 중에 사람이 {@code redis-cli}로 직접 치는 운영 레버</b>다. 오타가 나면
 * 폴링 계약이 깨지므로, 깨진 입력이 조용히 기본 사다리로 돌아가는지가 여기서 지켜야 할 성질이다.
 */
class PacingTierTest {

    @Test
    @DisplayName("키가 없으면(null) 코드 상수 사다리 — 평상시 큐 대부분이 이 경로다")
    void nullFallsBackToDefault() {
        assertThat(PacingTier.parse(null)).isSameAs(PacingTier.DEFAULT);
        assertThat(PacingTier.parse("  ")).isSameAs(PacingTier.DEFAULT);
    }

    @Test
    @DisplayName("기본 사다리는 §79 이전 서버 상수(2/5/10/15/20초)와 같은 값이다")
    void defaultLadderMatchesLegacyConstants() {
        assertThat(PacingTier.DEFAULT).containsExactly(
                new PacingTier(50L, 2),
                new PacingTier(1_000L, 5),
                new PacingTier(5_000L, 10),
                new PacingTier(10_000L, 15),
                new PacingTier(null, 20));
    }

    @Test
    @DisplayName("정상 오버라이드: 마지막 * 는 catch-all(null 상한)로 파싱된다")
    void parsesOverride() {
        List<PacingTier> tiers = PacingTier.parse("50:4, 1000:10, *:40");

        assertThat(tiers).containsExactly(
                new PacingTier(50L, 4),
                new PacingTier(1_000L, 10),
                new PacingTier(null, 40));
    }

    @Test
    @DisplayName("전원 간격 2배 — 이 레버 하나로 재배포 없이 부하를 절반으로 줄인다")
    void doublingLever() {
        List<PacingTier> tiers = PacingTier.parse("50:4,1000:10,5000:20,10000:30,*:40");

        assertThat(tiers).extracting(PacingTier::intervalSec)
                .containsExactly(4, 10, 20, 30, 40);
    }

    @DisplayName("형식이 깨지면 조용히 기본 사다리로 — 사고 중에 폴링 계약까지 잃지 않는다")
    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {
            "50-2,*:20",        // 구분자가 ':'가 아님
            "50:2,*:abc",       // 간격이 숫자가 아님
            "x:2,*:20",         // 상한이 숫자도 '*'도 아님
            "50:0,*:20",        // 간격 0 → 무한 폴링
            "50:-5,*:20",       // 음수 간격
            "50:2,1000:5",      // 마지막이 catch-all이 아니다 → 상한 초과 rank의 간격이 미정의
            "*",                // 값 자체가 없음
    })
    void malformedFallsBackToDefault(String raw) {
        assertThat(PacingTier.parse(raw)).isSameAs(PacingTier.DEFAULT);
    }
}
