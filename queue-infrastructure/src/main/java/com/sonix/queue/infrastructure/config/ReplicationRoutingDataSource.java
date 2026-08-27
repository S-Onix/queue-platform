package com.sonix.queue.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;


@Slf4j
public class ReplicationRoutingDataSource extends AbstractRoutingDataSource {

    /**
     * 트랜잭션 요청 시 R/W 중 어느 DB로 갈지 판단한다.
     *
     * <p><b>🔴 로그는 반드시 debug다.</b> 이 메서드는 <b>커넥션을 얻을 때마다</b> 호출된다 —
     * 요청당이 아니라 트랜잭션당이다. 2026-08-27 통합 실측에서 컨슈머 한 대가 <b>13분에
     * 61,774줄</b>을 찍었고 {@code api.log}가 <b>59MB</b>가 됐다. 프로덕션 트래픽에서는
     * 이 한 줄이 디스크와 I/O를 먹는 관측 대상이 아니라 부하 요인이다.
     *
     * <p>라우팅을 눈으로 봐야 할 때만 올려라:
     * {@code --logging.level.com.sonix.queue.infrastructure.config=DEBUG}
     */
    @Override
    protected Object determineCurrentLookupKey() {
        String key = TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? "replica" : "master";
        log.debug(">>> Routing to [{}]", key);

        return key;
    }
}
