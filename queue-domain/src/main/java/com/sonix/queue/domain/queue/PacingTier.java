package com.sonix.queue.domain.queue;

import java.util.ArrayList;
import java.util.List;

/**
 * 폴링 간격 사다리의 한 구간 (§79).
 *
 * <p>{@code /status} 응답에 실려 <b>30만 명 전원에게 동일하게</b> 내려간다. rank 계산도, 구간
 * 선택도 클라이언트가 한다 — 서버가 하면 응답이 사람마다 달라져 §79의 목적(개인화 삭제)이 사라진다.
 *
 * <p><b>이 표를 응답에 싣는 이유는 운영 레버다.</b> SDK에 사다리를 하드코딩하면 오픈 당일
 * "전원 폴링 간격 2배" 긴급 조치를 하려 해도 테넌트들이 각자 재배포해야 하고, 옛 버전은 계속
 * 최저 간격으로 때린다. 서버에 남는 수단이 429뿐인데 그건 부하 제어가 아니라 대기 실패다.
 *
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
     * Redis 오버라이드 문자열을 파싱한다. 형식은 {@code "50:2,1000:5,5000:10,10000:15,*:20"} —
     * {@code 상한:간격} 쌍의 CSV이고 마지막 상한은 {@code *}(= 그 이상 전부)다.
     *
     * <p><b>왜 JSON이 아니라 CSV인가:</b> 이 값은 사고 중에 사람이 {@code redis-cli}로 직접 치는
     * 운영 레버다. 짧을수록 오타가 준다. 중첩 배열 JSON을 손으로 파싱하는 코드도 같이 사라진다.
     *
     * <p><b>형식이 깨지면 조용히 {@link #DEFAULT}로 되돌아간다.</b> 로그를 남기지 않는 이유는
     * 이 경로가 폴링 핫패스(최대 15만/s)라서다 — 잘못된 키 하나로 초당 15만 줄이 쌓인다.
     * 오퍼레이터는 {@code /status} 응답이 그대로인 것으로 실패를 안다.
     *
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
