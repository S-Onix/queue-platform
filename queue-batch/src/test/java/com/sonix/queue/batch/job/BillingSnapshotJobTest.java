package com.sonix.queue.batch.job;

import com.sonix.queue.domain.billing.BillingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.inOrder;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.InOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BillingSnapshotJobTest {

    @Mock private BillingRepository billingRepository;

    private MeterRegistry registry;
    private BillingSnapshotJob job;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        job = new BillingSnapshotJob(billingRepository, registry);
        // 기본값 = "그 달 파티션이 이미 없다"(정상). 파티션 정리를 보는 테스트만 뒤집는다.
        // 0L로 두면 모든 테스트가 DROP 경로까지 흘러 집계 호출 수가 어긋난다
        lenient().when(billingRepository.countPartitionRows(any())).thenReturn(-1L);
    }

    @Test
    @DisplayName("전월과 당월을 집계한다 — 그 이상 과거는 건드리지 않는다(파티션 DROP된 달을 깎는다)")
    void aggregatesPreviousAndCurrentMonth() {
        YearMonth current = YearMonth.from(LocalDate.now(ZoneOffset.UTC));

        job.snapshot();

        ArgumentCaptor<YearMonth> months = ArgumentCaptor.forClass(YearMonth.class);
        verify(billingRepository, times(2)).upsertMonthlySnapshot(months.capture());
        assertThat(months.getAllValues()).containsExactly(current.minusMonths(1), current);
    }

    @Test
    @DisplayName("전월이 실패해도 당월은 집계한다 — 둘은 독립이다")
    void currentMonthSurvivesPreviousMonthFailure() {
        YearMonth current = YearMonth.from(LocalDate.now(ZoneOffset.UTC));
        doThrow(new RuntimeException("boom"))
                .when(billingRepository).upsertMonthlySnapshot(current.minusMonths(1));

        job.snapshot();

        verify(billingRepository).upsertMonthlySnapshot(current);
        assertThat(counter("failure")).isEqualTo(1.0);
        assertThat(counter("success")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("counter는 한 번도 안 돌아도 0으로 존재한다 — 없으면 \"어제 안 돌았다\"를 못 묻는다")
    void countersAreRegisteredUpfront() {
        assertThat(counter("success")).isZero();
        assertThat(counter("failure")).isZero();
    }

    @Test
    @DisplayName("일별 통계도 같은 월·같은 주기로 집계한다 — 축이 같아야 billing과 롤업 대조가 된다")
    void aggregatesDailyStatsForSameMonths() {
        YearMonth current = YearMonth.from(LocalDate.now(ZoneOffset.UTC));

        job.snapshot();

        ArgumentCaptor<YearMonth> months = ArgumentCaptor.forClass(YearMonth.class);
        verify(billingRepository, times(2)).upsertDailyStats(months.capture());
        assertThat(months.getAllValues()).containsExactly(current.minusMonths(1), current);
    }

    @Test
    @DisplayName("불일치 테넌트 수를 gauge로 낸다 — 두 표가 어긋나는 건 구조적으로 불가능하므로 0이 아니면 사고다")
    void publishesMismatchGauge() {
        when(billingRepository.countBillingMismatch(any())).thenReturn(2L);

        job.snapshot();

        assertThat(gauge()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("집계가 실패해도 대사는 돈다 — 낡은 값이 남은 그때가 바로 두 표가 어긋나 있는 순간이다")
    void mismatchCheckRunsEvenWhenAggregationFails() {
        YearMonth current = YearMonth.from(LocalDate.now(ZoneOffset.UTC));
        doThrow(new RuntimeException("boom"))
                .when(billingRepository).upsertMonthlySnapshot(current.minusMonths(1));
        when(billingRepository.countBillingMismatch(any())).thenReturn(1L);

        job.snapshot();

        verify(billingRepository).countBillingMismatch(current.minusMonths(1));
        assertThat(gauge()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("대사가 죽어도 집계 성패 판정은 오염되지 않는다 — 별개의 실패다")
    void mismatchFailureDoesNotFlipSuccessCounter() {
        when(billingRepository.countBillingMismatch(any()))
                .thenThrow(new RuntimeException("boom"));

        job.snapshot();

        assertThat(counter("success")).isEqualTo(2.0);
        assertThat(counter("failure")).isZero();
    }

    // ────────────────────────── 파티션 정리 (§86) ──────────────────────────

    @Test
    @DisplayName("파티션이 없으면 아무것도 안 한다 — 이미 지운 상태는 정상이다")
    void skipsWhenPartitionAlreadyGone() {
        job.snapshot();

        verify(billingRepository, never()).dropPartition(any());
        // 일일 집계 2회뿐 — DROP 대상 달의 최종 집계는 일어나지 않았다
        verify(billingRepository, times(2)).upsertDailyStats(any());
    }

    @Test
    @DisplayName("DROP 직전에 그 달을 한 번 더 집계한다 — 정기 집계가 안 보는 달이라 여기가 유일한 기회다")
    void aggregatesTargetMonthRightBeforeDrop() {
        YearMonth current = YearMonth.from(LocalDate.now(ZoneOffset.UTC));
        YearMonth target = current.minusMonths(2);
        when(billingRepository.countPartitionRows(target)).thenReturn(100L);
        when(billingRepository.countDailyStatRows(target)).thenReturn(7L);

        job.snapshot();

        // 🔑 순서가 곧 게이트다. 집계 → 대사 → DROP
        InOrder order = inOrder(billingRepository);
        order.verify(billingRepository).upsertMonthlySnapshot(target);
        order.verify(billingRepository).upsertDailyStats(target);
        order.verify(billingRepository).countBillingMismatch(target);
        order.verify(billingRepository).dropPartition(target);
    }

    @Test
    @DisplayName("집계가 던지면 DROP에 도달하지 못한다 — 게이트가 문장 순서다")
    void aggregationFailureBlocksDrop() {
        YearMonth target = YearMonth.from(LocalDate.now(ZoneOffset.UTC)).minusMonths(2);
        when(billingRepository.countPartitionRows(target)).thenReturn(100L);
        doThrow(new RuntimeException("boom")).when(billingRepository).upsertDailyStats(target);

        job.snapshot();

        verify(billingRepository, never()).dropPartition(any());
    }

    @Test
    @DisplayName("대사가 어긋나면 DROP을 보류한다 — 원본이 살아 있는 지금이 아니면 판정할 수 없다")
    void mismatchBlocksDrop() {
        YearMonth target = YearMonth.from(LocalDate.now(ZoneOffset.UTC)).minusMonths(2);
        when(billingRepository.countPartitionRows(target)).thenReturn(100L);
        when(billingRepository.countBillingMismatch(target)).thenReturn(3L);

        job.snapshot();

        verify(billingRepository, never()).dropPartition(any());
    }

    @Test
    @DisplayName("원본이 있는데 집계가 0행이면 DROP을 보류한다 — 둘 다 비면 대사는 0을 돌려준다")
    void emptyAggregateBlocksDropEvenWhenMismatchIsZero() {
        // 🔑 대사만 믿으면 여기서 원본이 통째로 사라진다. 두 집계가 나란히 실패한 경우
        //    SUM(daily)=0, billing=0 → 불일치 0. "정상"으로 보인다
        YearMonth target = YearMonth.from(LocalDate.now(ZoneOffset.UTC)).minusMonths(2);
        when(billingRepository.countPartitionRows(target)).thenReturn(160_843L);
        when(billingRepository.countBillingMismatch(target)).thenReturn(0L);
        when(billingRepository.countDailyStatRows(target)).thenReturn(0L);

        job.snapshot();

        verify(billingRepository, never()).dropPartition(any());
    }

    @Test
    @DisplayName("빈 파티션은 집계가 0행이어도 지운다 — 원본이 0건이면 잃을 것이 없다")
    void dropsEmptyPartition() {
        YearMonth target = YearMonth.from(LocalDate.now(ZoneOffset.UTC)).minusMonths(2);
        when(billingRepository.countPartitionRows(target)).thenReturn(0L);
        // countDailyStatRows를 스텁하지 않는다 — rows > 0 이 거짓이라 단축 평가로 호출조차 안 된다.
        // 스텁하면 Mockito가 UnnecessaryStubbing으로 죽는다. 그게 곧 단축 평가의 증거다

        job.snapshot();

        verify(billingRepository).dropPartition(target);
        verify(billingRepository, never()).countDailyStatRows(any());
    }

    @Test
    @DisplayName("DROP이 죽어도 집계 성패 판정은 오염되지 않는다 — 경쟁에서 진 인스턴스의 1507이 여기로 온다")
    void dropFailureDoesNotFlipSuccessCounter() {
        YearMonth target = YearMonth.from(LocalDate.now(ZoneOffset.UTC)).minusMonths(2);
        when(billingRepository.countPartitionRows(target)).thenReturn(10L);
        when(billingRepository.countDailyStatRows(target)).thenReturn(1L);
        doThrow(new RuntimeException("ERROR 1507")).when(billingRepository).dropPartition(target);

        job.snapshot();

        assertThat(counter("success")).isEqualTo(2.0);
        assertThat(counter("failure")).isZero();
    }

    // ────────────────────────── 대사 (§86) ──────────────────────────

    @Test
    @DisplayName("대사는 정착된 달만 본다 — 당월은 두 집계 사이에 컨슈머가 적재해 상시 어긋난다")
    void reconcilesOnlySettledMonth() {
        YearMonth current = YearMonth.from(LocalDate.now(ZoneOffset.UTC));

        job.snapshot();

        // 🔑 인자를 any()로 두면 "당월도 본다"는 결함이 통과한다
        verify(billingRepository).countBillingMismatch(current.minusMonths(1));
        verify(billingRepository, never()).countBillingMismatch(current);
    }

    @Test
    @DisplayName("대사가 죽으면 게이지가 -1이다 — 0으로 두면 조회가 깨진 순간 가장 건강해 보인다")
    void mismatchFailureShowsAsMinusOne() {
        when(billingRepository.countBillingMismatch(any())).thenThrow(new RuntimeException("boom"));

        job.snapshot();

        assertThat(gauge()).isEqualTo(-1.0);
    }

    @Test
    @DisplayName("집계 대상 월을 그대로 넘긴다 — 인자를 무시해도 통과하는 검증을 막는다")
    void passesExactMonthsToAggregation() {
        YearMonth current = YearMonth.from(LocalDate.now(ZoneOffset.UTC));

        job.snapshot();

        ArgumentCaptor<YearMonth> months = ArgumentCaptor.forClass(YearMonth.class);
        verify(billingRepository, times(2)).upsertDailyStats(months.capture());
        assertThat(months.getAllValues()).containsExactly(current.minusMonths(1), current);
    }

    @Test
    @DisplayName("일별 집계가 죽어도 당월 집계와 대사는 계속된다 — try 밖으로 새면 통째로 스킵된다")
    void dailyStatsFailureIsContained() {
        YearMonth current = YearMonth.from(LocalDate.now(ZoneOffset.UTC));
        doThrow(new RuntimeException("boom"))
                .when(billingRepository).upsertDailyStats(current.minusMonths(1));
        when(billingRepository.countBillingMismatch(any())).thenReturn(0L);

        job.snapshot();

        verify(billingRepository).upsertDailyStats(current);
        verify(billingRepository).countBillingMismatch(current.minusMonths(1));
        assertThat(counter("failure")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("한 번도 안 돌았으면 게이지가 -1이다 — 0으로 두면 기동 직후가 가장 건강해 보인다")
    void gaugeStartsUnmeasured() {
        assertThat(registry.get("queue.billing.mismatch").gauge().value()).isEqualTo(-1.0);
    }

    private double gauge() {
        return registry.get("queue.billing.mismatch").gauge().value();
    }

    private double counter(String result) {
        return registry.get("queue.billing.snapshot").tag("result", result).counter().count();
    }
}
