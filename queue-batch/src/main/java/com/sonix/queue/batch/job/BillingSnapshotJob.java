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
import java.util.concurrent.atomic.AtomicLong;

/**
 * 월별 과금 스냅샷 (Sprint 9 · {@code doc/ROADMAP.md} §Sprint 9).
 *
 * <h2>매일 UTC 00:30 — 마감은 월 단위인데 실행은 왜 매일인가</h2>
 * 마감(한 달을 확정하는 순간)은 월 경계를 넘긴 <b>1일 첫 실행</b>에서 일어난다. 매일 도는 것은
 * 그 마감을 보호하기 위해서다.
 *
 * <p>🪤 <b>월말 23:59에 마감하면 그 달의 마지막 60초를 못 센다</b> — 그 토큰들은 다음 마감이
 * 한 달 뒤라 <b>청구서가 나간 뒤에야</b> 잡힌다. 경계를 넘긴 뒤에 마감해야 누락 창이 0이 된다.
 *
 * <p>🪤 <b>00:05이 아니라 00:30인 이유</b>: {@code ReconcileJob.SETTLE_SECONDS}가 300초다
 * ("컨슈머가 5분까지 밀리는 것은 정상"). 00:05이면 여유가 정확히 0이라, 말일 23:59:59 토큰이
 * 00:05:01에 적재되면 <b>두 표에서 똑같이 빠지고</b> {@code countBillingMismatch}가 0(정상)을
 * 돌려준다 — 자기 탐지기가 못 보는 사고 유형이 된다. 00:30은 그 6배 여유다.
 *
 * <p>🔴 <b>"월 1회만"으로 바꾸면 방어 장치 셋이 무너진다</b>(바꿨다가 되돌렸다) —
 * <ul>
 *   <li>{@link #purgeSettledPartition}의 대상이 매 실행 이동하므로 <b>DROP이 한 번 실패하면
 *       그 파티션을 다시 보는 실행이 없다.</b> 그런데 lock wait이 짧아 포기는 정상 경로다</li>
 *   <li>아래 전월 재집계가 월초 1회가 되어 늦게 붙는 admit이 두 달간 빠진다</li>
 *   <li>{@code mismatch} 게이지가 한 달 내내 낡은 값을 보고한다</li>
 * </ul>
 *
 * <h2>전월을 함께 다시 집계한다</h2>
 * 5/1 00:30에 4월분이 확정돼 있다는 보장이 없다 — 말일 토큰이 아직 Kafka를 타는 중일 수 있다.
 * <b>전월은 그 달 내내 덮어쓴다</b>(UPSERT가 멱등이라 공짜다). 반대로 <b>더 과거는 보지 않는다</b> —
 * 파티션이 DROP된 달을 집계하면 남은 행만 세어 이미 청구한 금액을 <b>깎아 버린다</b>.
 *
 * <h2>ShedLock을 쓰지 않는다</h2>
 * UPSERT가 멱등이라 batch가 N대여도 각자 같은 값을 쓴다(실측: 가상 스레드 8개 동시 UPSERT에서
 * 데드락 0, 값 정확). 다만 <b>비용은 다르다</b> — 파티션 스캔이 인스턴스 수만큼 곱해진다.
 * 그걸 감당하게 만드는 것이 어댑터의 {@code PARTITION (pYYYY_MM)} 절이다(§83).
 */
@Slf4j
@Component
public class BillingSnapshotJob {

    private final BillingRepository billingRepository;

    /** 대사 결과 미상(미실행 또는 실패). 0(불일치 없음)과 구분하기 위한 값이다. */
    private static final long NOT_MEASURED = -1L;

    /**
     * 집계 결과 counter. 관측 대상이 "지금 몇 개"가 아니라 <b>"돌았는가"</b>라 gauge가 아니다 —
     * 실패는 최대 한 달 뒤 청구서에서 드러나므로 로그만으로는 늦다.
     *
     * <p><b>생성자에서 미리 등록한다</b> — 한 번도 안 돌면 시계열 자체가 없어 "어제 안 돌았다"를
     * PromQL로 물어볼 수 없다. 0에서 시작해야 {@code increase()}가 말을 한다.
     */
    private final Counter success;
    private final Counter failure;

    /**
     * 두 집계표가 어긋난 테넌트 수. <b>0이어야 한다</b> — 어긋날 구조적 이유가 없어서
     * 0이 아닌 것 자체가 사고 신호다(포트 javadoc 참조).
     *
     * <p>counter가 아니라 gauge인 이유는 {@code ReconcileJob}과 같다 — 관측 대상이
     * "몇 번 일어났나"가 아니라 <b>"지금 몇 개가 어긋나 있나"</b>이고, 원인을 고치면 0으로 돌아와야 한다.
     *
     * <p>⚠️ N대가 각자 같은 값을 보고하므로 PromQL에선 {@code sum}이 아니라 {@code max}로 본다.
     *
     * <p>⚠️ <b>{@code -1}은 "값을 모른다"다</b> — 대사가 실패했거나 아직 한 번도 안 돌았다.
     * 0(정상)과 구분해야 한다. 초기값도 {@code -1}인 이유가 같다: {@code 0}으로 두면
     * <b>batch 기동 직후부터 첫 실행까지 지표가 가장 건강해 보인다.</b> 실패를 0으로 두면
     * 조회가 깨진 순간 같은 일이 벌어진다 — 둘은 같은 함정의 두 얼굴이다.
     */
    private final AtomicLong mismatch = new AtomicLong(NOT_MEASURED);

    public BillingSnapshotJob(BillingRepository billingRepository, MeterRegistry meterRegistry) {
        this.billingRepository = billingRepository;
        meterRegistry.gauge("queue.billing.mismatch", mismatch);
        this.success = Counter.builder("queue.billing.snapshot")
                .tag("result", "success").register(meterRegistry);
        this.failure = Counter.builder("queue.billing.snapshot")
                .tag("result", "failure").register(meterRegistry);
    }

    /**
     * 매일 UTC 00:30. 근거(왜 매일인지·왜 00:05이 아닌지)는 클래스 javadoc 참조.
     *
     * <p><b>{@code zone = "UTC"}는 못박는다</b> — 월 경계·파티션 표현식·{@code issued_at}이 전부
     * UTC라 여기만 KST로 돌면 월이 바뀌는 날 "전월"의 뜻이 9시간 어긋난다.
     */
    @Scheduled(cron = "${queue.batch.billing.cron:0 30 0 * * *}", zone = "UTC")
    public void snapshot() {
        YearMonth current = YearMonth.from(LocalDate.now(ZoneOffset.UTC));

        for (YearMonth month : List.of(current.minusMonths(1), current)) {
            try {
                billingRepository.upsertMonthlySnapshot(month);
                // 큐×일 통계도 같은 월·같은 주기로 간다. 축이 같아야
                // SUM(total_enqueued) == billing_snapshots.count 롤업 검증이 성립한다 (§86).
                // 순서가 이쪽인 이유: 돈 경로가 먼저다. 통계가 죽어도 청구는 이미 확정돼 있다
                billingRepository.upsertDailyStats(month);
                success.increment();
                log.info("과금·통계 집계 month={}", month);
            } catch (RuntimeException e) {
                // 전월 실패가 당월을 막지 않는다. 둘은 서로 독립이고, 실패분은 내일 주기가 가져간다.
                // 통계 집계가 죽어도 같은 counter를 올린다 — 카운터를 나누지 않는 건 조치가 같아서다.
                // 둘 다 "그 달 집계가 안 끝났다"이고, 둘 다 멱등이라 재실행 하나로 복구된다
                failure.increment();
                log.error("과금·통계 집계 실패 month={}", month, e);
            }
        }

        reconcile(current.minusMonths(1));
        purgeSettledPartition(current.minusMonths(2));
    }

    /**
     * M+2월에 {@code tokens} 파티션을 지운다 — <b>집계가 끝난 뒤에만</b>.
     *
     * <h2>왜 같은 배치인가</h2>
     * 분리하면 <b>순서를 사람이 지켜야 한다.</b> 한 번 어긋나면 그 달 원본이 집계 없이 사라지고,
     * 되돌릴 방법이 없다. 같은 메서드에 순서대로 두면 <b>문장 순서가 곧 게이트</b>다 —
     * 앞이 던지면 뒤에 도달하지 못한다. 별도 게이트도, 별도 플래그도 필요 없다.
     *
     * <h2>🔴 여기서 집계를 한 번 더 하는 이유 (매일 하는데도)</h2>
     * <b>DROP 대상 달은 일일 집계 대상에 없다.</b> 6/5에 지우는 건 4월인데 그날 잡이 보는 건
     * 5월·6월이라, 4월의 마지막 집계(5/31) 뒤에 도착한 4월 토큰은 아무도 세지 않은 채
     * 파티션과 함께 사라진다 — <b>곧 미청구다.</b>
     *
     * <h2>통과해야 하는 관문 셋</h2>
     * <ol>
     *   <li><b>파티션이 있는가</b> — 없으면 이미 지운 것이다. 정상 종료(로그도 안 남긴다)</li>
     *   <li><b>집계가 예외 없이 끝났는가</b> — 던지면 DROP에 도달하지 못한다</li>
     *   <li><b>대사가 0인가, 그리고 원본이 비지 않았는데 집계가 비지는 않았는가</b> —
     *       두 집계가 <b>나란히 실패해 둘 다 0행</b>이면 대사는 0을 돌려준다. 그 상태로 지우면
     *       원본이 통째로 사라진다. 원본 건수를 따로 봐야 그 조합이 걸린다</li>
     * </ol>
     *
     * <h2>ShedLock을 쓰지 않는다 — 이유가 위와 다르다</h2>
     * DDL은 멱등하지 않다. 대신 <b>MySQL 메타데이터 락이 직렬화한다</b> — 한 대만 성공하고
     * 나머지는 {@code ERROR 1507}을 받는다. 결과가 같아(파티션은 사라졌다) 삼키고 로그만 남긴다.
     * 지는 쪽도 관문 ③은 이미 통과했으므로 판정은 양쪽 다 옳고 삭제만 한 번 일어난다.
     */
    private void purgeSettledPartition(YearMonth target) {
        try {
            long rows = billingRepository.countPartitionRows(target);
            if (rows < 0) {
                return;   // 파티션 없음 = 이미 지웠다. 정상이다
            }

            // 관문 ②: 지우기 직전 최종 확정. 던지면 아래로 못 간다
            billingRepository.upsertMonthlySnapshot(target);
            billingRepository.upsertDailyStats(target);

            // 관문 ③
            long mismatched = billingRepository.countBillingMismatch(target);
            if (mismatched != 0) {
                log.error("파티션 DROP 보류 month={} 불일치 테넌트={}건 — 집계가 원본과 다르다. "
                        + "원본이 살아 있는 지금이 아니면 판정할 수 없다", target, mismatched);
                return;
            }
            if (rows > 0 && billingRepository.countDailyStatRows(target) == 0) {
                log.error("파티션 DROP 보류 month={} — 원본 {}건인데 집계가 0행이다. "
                        + "대사가 0인 것은 두 집계가 나란히 비었기 때문이다", target, rows);
                return;
            }

            billingRepository.dropPartition(target);
            log.warn("파티션 DROP month={} rows={} — 원본은 영구 삭제됐다. "
                    + "이후 이 달의 근거는 queue_daily_stats·billing_snapshots뿐이다", target, rows);
        } catch (RuntimeException e) {
            // 경쟁에서 진 인스턴스의 ERROR 1507도 여기로 온다 — 결과가 같으므로 피해가 없다.
            // 🔑 그 외 실패는 **다음 날 주기가 그대로 다시 시도한다**. 이 문장이 참이려면
            //    잡이 매일 돌아야 한다 — lock_wait_timeout이 짧아 포기가 정상 경로이기 때문이다.
            //    월 1회로 바꾸면 대상이 이동해 그 파티션을 다시 보는 실행이 없어진다(클래스 javadoc)
            log.error("파티션 정리 실패 month={} — DROP은 일어나지 않았다", target, e);
        }
    }

    /**
     * 두 집계표를 대조한다. <b>정착된 달에만 한다.</b>
     *
     * <p>🔴 <b>당월은 대조하면 안 된다.</b> 두 UPSERT가 별개 트랜잭션이라 각자 자기 시점의
     * {@code tokens}를 세는데, 당월 파티션에는 컨슈머가 지금도 적재 중이다. 두 커밋 사이
     * (실측 180ms)에 들어온 토큰은 daily에만 잡혀 <b>상시 어긋난 채로 남고</b>, 게이지가 매일
     * 울려 진짜 사고를 못 알아보게 된다. 당월의 결함은 다음 달에 잡히며 파티션 DROP(M+2)
     * 전이라 늦지 않다.
     *
     * <p>🪤 월이 바뀐 직후 며칠은 전월분 늦은 적재가 남아 있어 <b>일시적으로 0이 아닐 수 있다</b>.
     * 경보를 걸 때 그 창을 감안하라.
     */
    private void reconcile(YearMonth settled) {
        try {
            long n = billingRepository.countBillingMismatch(settled);
            mismatch.set(n);
            if (n > 0) {
                log.error("과금 대사 불일치 month={} tenants={} — daily 합계와 청구액이 다르다. "
                        + "tokens 파티션이 살아 있는 동안만 판정 가능하다", settled, n);
            }
        } catch (RuntimeException e) {
            // 🔴 0으로 두면 "대사가 죽었다"가 "전부 정상"으로 보인다. 이 게이지가 유일한 경보면이다
            mismatch.set(NOT_MEASURED);
            log.error("과금 대사 실패 month={} — 게이지를 -1로 둔다(정상 0과 구분)", settled, e);
        }
    }
}
