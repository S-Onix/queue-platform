package com.sonix.queue.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;


@Slf4j
public class ReplicationRoutingDataSource extends AbstractRoutingDataSource {

    /**
     * 이유: 트랜잭션 요청 시 R/W 중 어디로 갈지 판단한다. 기준은 {@code isCurrentTransactionReadOnly()} 다.
     * 문제: 🔴 <b>로그는 반드시 debug 다</b> — 이 메서드는 <b>커넥션을 얻을 때마다</b> 불린다 (요청당이 아니라 트랜잭션당).
     * 원인: 실측에서 컨슈머 한 대가 <b>13분에 61,774줄</b>을 찍어 로그가 59MB 가 됐다 —
     *       프로덕션에서는 이 한 줄이 관측이 아니라 <b>부하 요인</b>이다.
     * 해결: 눈으로 봐야 할 때만 올린다({@code logging.level...config=DEBUG}) — §4-3 의 유일한 확인 수단이다.
     *
     * @author sonix
     */
    @Override
    protected Object determineCurrentLookupKey() {
        String key = TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? "replica" : "master";
        log.debug(">>> Routing to [{}]", key);

        return key;
    }
}
