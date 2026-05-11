package com.sonix.queue.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;


@Slf4j
public class ReplicationRoutingDataSource extends AbstractRoutingDataSource {

    private static final Logger LOG = LoggerFactory.getLogger(ReplicationRoutingDataSource.class);
    /**
     * 트렌젝션 요청시 R/W 중에 어느 DB로 가야할지 판단하는 코드
     * */
    @Override
    protected Object determineCurrentLookupKey() {
        String key = TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? "replica" : "master";
        LOG.info(">>> Routing to [{}]", key);

        return key;
    }
}
