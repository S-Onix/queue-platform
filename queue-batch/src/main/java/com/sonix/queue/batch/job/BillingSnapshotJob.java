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
 * 이유: 월별 과금 스냅샷. 마감은 월 경계를 넘긴 <b>1일 첫 실행</b>에서 일어난다(§84).
 * 문제: 월말 23:59 에 마감하면 그 달 마지막 60초를 놓치고, 다음 마감이 한 달 뒤라
 *       <b>청구서가 나간 뒤에야</b> 잡힌다. 00:05 면 대사 정착 300초와 여유가 정확히 0이다.
 * 해결: 매일 UTC 00:30. 전월도 함께 다시 집계한다(UPSERT 가 멱등이라 공짜다).
 * 🔴 "월 1회만"으로 바꾸지 마라 — DROP 재시도·늦은 admit·mismatch 게이지 셋이 함께 무너진다.
 *
 * @author sonix
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
     * 이유: 두 집계표가 어긋난 테넌트 수. <b>0이어야 한다</b> — 어긋날 구조적 이유가 없다.
     * 문제: 실패를 0으로 두면 <b>조회가 깨진 순간이 가장 건강해 보인다</b>(초기값도 같은 함정).
     * 해결: {@code -1} 은 "값을 모른다"다 — 대사 실패 또는 아직 한 번도 안 돎. 0과 구분한다.
     * 🔑 counter 가 아니라 gauge 다 — "몇 번"이 아니라 <b>"지금 몇 개가 어긋나 있나"</b>다.
     * ⚠️ N대가 각자 보고하므로 PromQL 에선 {@code sum} 이 아니라 {@code max} 로 본다.
     *
     * @author sonix
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
     * 이유: M+2 월 {@code tokens} 파티션을 지운다 — <b>집계가 끝난 뒤에만</b>.
     * 문제: 분리하면 순서를 사람이 지켜야 하고, 한 번 어긋나면 원본이 집계 없이 사라진다.
     * 해결: 같은 메서드에 순서대로 둔다 — <b>문장 순서가 곧 게이트</b>다(앞이 던지면 못 간다).
     *       관문 셋: ①파티션 존재 ②집계 무예외 ③대사 0 <b>그리고 원본이 비지 않았을 것</b>.
     * 🪤 ③의 뒷조건이 없으면 두 집계가 나란히 실패해 둘 다 0행일 때 원본이 통째로 사라진다.
     *
     * @author sonix
     * @param target 지울 달. 이 달은 일일 집계 대상 밖이라 여기서 한 번 더 집계한다(미청구 방지)
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
     * 이유: 두 집계표를 대조한다. <b>정착된 달에만</b> 한다.
     * 문제: 🔴 당월을 대조하면 상시 어긋난 채로 남아 게이지가 매일 울린다.
     * 원인: 두 UPSERT 가 별개 트랜잭션이라 각자 자기 시점의 {@code tokens} 를 세는데,
     *       당월 파티션에는 컨슈머가 지금도 적재 중이다 — 두 커밋 사이(실측 180ms)가 샌다.
     * 🪤 월이 바뀐 직후 며칠은 전월 늦은 적재로 <b>일시적으로 0이 아닐 수 있다</b>(경보 창 감안).
     *
     * @author sonix
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
