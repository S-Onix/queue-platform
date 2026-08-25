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

    private double counter(String result) {
        return registry.get("queue.billing.snapshot").tag("result", result).counter().count();
    }
}
