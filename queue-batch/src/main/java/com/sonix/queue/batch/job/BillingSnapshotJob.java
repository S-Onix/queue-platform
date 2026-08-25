package com.sonix.queue.batch.job;

import com.sonix.queue.domain.billing.BillingRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 월별 과금 스냅샷 (Sprint 9 · {@code doc/ROADMAP.md} §Sprint 9).
 *
 * <h2>왜 "월 1회"가 아니라 매일인가</h2>
 * 로드맵의 원안은 "월 1회, M+2월 초"였다. 그건 <b>파티션 DROP</b>의 일정이지 집계의 일정이 아니다.
 * 집계만 떼어 매일 돌리면 셋이 좋아진다 —
 * <ul>
 *   <li>테넌트가 <b>당월 사용량을 오늘 볼 수 있다</b>. 월말에만 채우면 청구서가 나오기 전까지
 *       아무도 자기 요금을 모른다</li>
 *   <li>한 번 실패해도 다음 날이 가져간다. 월 1회는 그 한 번이 곧 <b>한 달치 미청구</b>다</li>
 *   <li>16만 행에 115ms다(실측). 매일 돌릴 이유를 반박할 만한 비용이 아니다</li>
 * </ul>
 *
 * <h2>전월을 함께 다시 집계한다</h2>
 * 5/1 00:30에 4월분이 확정돼 있다는 보장이 없다. 4/30 23:59:59에 enqueue된 토큰은 Kafka를
 * 타는 중이라 아직 DB에 없을 수 있고({@code ReconcileJob}의 정착 시간과 같은 이유), 유령 토큰
 * 복구가 붙으면 뒤늦게 행이 생긴다. 그래서 <b>전월은 그 달 내내 다시 덮어쓴다</b> —
 * UPSERT가 멱등이라 공짜다.
 *
 * <p>반대로 <b>더 과거는 보지 않는다.</b> 파티션이 DROP된 달을 집계하면 남은 행만 세어
 * 이미 청구한 금액을 <b>깎아 버린다</b>. 원장은 지난 뒤엔 건드리지 않는 게 맞다.
 *
 * <h2>ShedLock을 쓰지 않는다</h2>
 * <b>정확성</b>은 {@code ReconcileJob}과 같은 이유로 성립한다. UPSERT가 멱등이라 batch가 N대여도
 * 각자 같은 값을 쓸 뿐이고, 동시에 돌면 마지막 쓰기가 이기는데 둘 다 같은 SELECT의 결과라
 * 어느 쪽이 이겨도 정답이다(실측: 가상 스레드 8개 동시 UPSERT에서 데드락 0, 값 정확).
 *
 * <p>다만 <b>비용은 같지 않다</b>. {@code ReconcileJob}의 중복 실행은 큐당 {@code ZCOUNT} +
 * {@code COUNT} 읽기지만, 이쪽은 파티션 스캔이 인스턴스 수만큼 곱해진다. 그걸 감당 가능하게
 * 만드는 것이 어댑터의 {@code PARTITION (pYYYY_MM)} 절이다(§83).
 */
@Slf4j
@Component
public class BillingSnapshotJob {

    private final BillingRepository billingRepository;

    /**
     * 집계 결과 counter. <b>생성자에서 미리 등록한다</b> — 한 번도 안 돌면 시계열 자체가 없어
     * "어제 안 돌았다"를 PromQL로 물어볼 수 없기 때문이다. 0에서 시작해야 {@code increase()}가 말을 한다.
     *
     * <p>같은 모듈의 다른 잡들은 gauge를 내지만 이건 counter다 — 관측 대상이 "지금 몇 개"가 아니라
     * "돌았는가"라서다. 실패는 최대 한 달 뒤 청구서에서 드러나므로 로그만으로는 늦다.
     */
    private final Counter success;
    private final Counter failure;

    public BillingSnapshotJob(BillingRepository billingRepository, MeterRegistry meterRegistry) {
        this.billingRepository = billingRepository;
        this.success = Counter.builder("queue.billing.snapshot")
                .tag("result", "success").register(meterRegistry);
        this.failure = Counter.builder("queue.billing.snapshot")
                .tag("result", "failure").register(meterRegistry);
    }

    /**
     * 매일 UTC 00:30.
     *
     * <p>자정 정각을 피한 건 파티션 운영 쿼리·일 통계와 겹치지 않게 하기 위함이고,
     * <b>zone을 UTC로 못박은 건</b> 집계 경계가 UTC이기 때문이다. 서버 TZ(KST)로 돌면
     * 월이 바뀌는 날 "전월"의 뜻이 9시간 어긋난다.
     */
    @Scheduled(cron = "${queue.batch.billing.cron:0 30 0 * * *}", zone = "UTC")
    public void snapshot() {
        YearMonth current = YearMonth.from(LocalDate.now(ZoneOffset.UTC));

        for (YearMonth month : List.of(current.minusMonths(1), current)) {
            try {
                billingRepository.upsertMonthlySnapshot(month);
                success.increment();
                log.info("과금 집계 month={}", month);
            } catch (RuntimeException e) {
                // 전월 실패가 당월을 막지 않는다. 둘은 서로 독립이고, 실패분은 내일 주기가 가져간다
                failure.increment();
                log.error("과금 집계 실패 month={}", month, e);
            }
        }
    }
}
