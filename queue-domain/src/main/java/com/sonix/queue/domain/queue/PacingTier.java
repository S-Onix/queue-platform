package com.sonix.queue.domain.queue;

import java.util.ArrayList;
import java.util.List;

/**
 * 이유: 폴링 간격 사다리의 한 구간(§79). {@code /status} 로 <b>30만 명 전원에게 동일하게</b> 내려간다.
 * 문제: SDK 에 사다리를 하드코딩하면 오픈 당일 "전원 간격 2배" 긴급 조치를 못 한다 —
 *       테넌트가 각자 재배포해야 하고 옛 버전은 계속 최저 간격으로 때린다.
 * 해결: 표를 응답에 실어 <b>운영 레버</b>로 쓴다. rank 계산도 구간 선택도 클라이언트가 한다
 *       (서버가 하면 응답이 사람마다 달라져 §79 의 목적인 개인화 삭제가 사라진다).
 *
 * @author sonix
 * @param maxRank     이 구간의 rank 상한(포함). <b>{@code null}이면 "그 이상 전부"</b>(마지막 구간)
 * @param intervalSec 이 구간의 폴링 간격(초)
 */
public record PacingTier(Long maxRank, int intervalSec) {

    /**
     * 기본 사다리. §79 이전 {@code QueueEngineService.basePollAfterSec()}의 값을 그대로 옮긴 것이다.
     * Redis 오버라이드 키가 없을 때 쓰인다 — 평상시 큐 대부분이 이 경로다.
     */
    public static final List<PacingTier> DEFAULT = List.of(
            new PacingTier(50L, 2),
            new PacingTier(1_000L, 5),
            new PacingTier(5_000L, 10),
            new PacingTier(10_000L, 15),
            new PacingTier(null, 20));

    /**
     * 이유: Redis 오버라이드 문자열을 파싱한다. 형식은 {@code "50:2,1000:5,*:20"} — 상한:간격 CSV.
     * 원인: JSON 이 아닌 것은 <b>사고 중에 사람이 redis-cli 로 치는 값</b>이라서다. 짧을수록 오타가 준다.
     * 해결: 형식이 깨지면 조용히 {@link #DEFAULT} 로 되돌아간다 — 이 경로가 폴링 핫패스(최대 15만/s)다.
     * 🪤 그래서 실패가 <b>조용하다</b> — 오퍼레이터는 {@code /status} 가 그대로인 것으로만 알아챈다.
     *
     * @author sonix
     * @param raw Redis {@code queue:&#123;queueId&#125;:pacing} 값. {@code null}이면 기본값
     */
    public static List<PacingTier> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }

        List<PacingTier> tiers = new ArrayList<>();
        for (String part : raw.split(",")) {
            int colon = part.indexOf(':');
            if (colon < 0) {
                return DEFAULT;
            }
            String upper = part.substring(0, colon).trim();
            try {
                int intervalSec = Integer.parseInt(part.substring(colon + 1).trim());
                if (intervalSec <= 0) {
                    return DEFAULT;
                }
                tiers.add(new PacingTier("*".equals(upper) ? null : Long.parseLong(upper), intervalSec));
            } catch (NumberFormatException e) {
                return DEFAULT;
            }
        }

        // 마지막 구간은 반드시 catch-all이어야 한다. 아니면 상한을 넘는 rank에 간격이 정의되지
        // 않아 SDK가 무엇을 할지 계약에 없는 상태가 된다 — 그럴 바엔 기본 사다리가 낫다.
        if (tiers.isEmpty() || tiers.get(tiers.size() - 1).maxRank() != null) {
            return DEFAULT;
        }
        return List.copyOf(tiers);
    }
}
