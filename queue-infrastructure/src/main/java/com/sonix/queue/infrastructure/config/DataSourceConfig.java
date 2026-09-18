package com.sonix.queue.infrastructure.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class DataSourceConfig {


    /**
     * 이유: 풀 이름을 코드에서 박는다.
     * 문제: 안 주면 Hikari 가 {@code HikariPool-1/2} 를 <b>생성 순서대로</b> 붙이는데 그 순서가 보장되지 않는다 —
     *       지표만 봐서는 <b>어느 쪽 풀이 포화했는지 알 수 없다</b>.
     * 해결: yml(앱 3 × 프로파일 3 × 풀 2 = 18곳)이 아니라 여기 둔다 — 환경별로 달라질 값이 아니고,
     *       18곳에 흩어지면 <b>한 곳이 빠져도 아무도 모른다</b>.
     * ⚠️ {@code @ConfigurationProperties} 바인딩이 뒤에 돌지만 <b>yml 에 실재하는 키만</b> 덮는다 —
     *    {@code pool-name} 을 yml 에 쓰면 그쪽이 이긴다.
     */
   @Bean
    @ConfigurationProperties("spring.datasource.master")
    public DataSource masterDataSource() {
        HikariDataSource ds = DataSourceBuilder.create().type(HikariDataSource.class).build();
        ds.setPoolName("master");
        return ds;
    }

    @Bean
    @ConfigurationProperties("spring.datasource.replica")
    public DataSource replicaDataSource() {
        HikariDataSource ds = DataSourceBuilder.create().type(HikariDataSource.class).build();
        ds.setPoolName("replica");
        return ds;
    }

    @Bean
    public DataSource routingDataSource(@Qualifier("masterDataSource") DataSource master,
                                        @Qualifier("replicaDataSource") DataSource replica) {
        ReplicationRoutingDataSource routing = new ReplicationRoutingDataSource();

        Map<Object, Object> dataSources = new HashMap<>();
        dataSources.put("master", master);
        dataSources.put("replica", replica);

        routing.setTargetDataSources(dataSources);
        routing.setDefaultTargetDataSource(master);

        return routing;

    }

    /**
     * 중요!! LazyConnect를 안하면 커넥션을 얻은 시점에 readOnly 파악을 못함.
     * readOnly 여부 세팅 이후 Connection을 가져야 Master / Replica로 판단함
     * 없으면 무조건 Master쪽의 DB로 붙음
     * */
    @Primary
    @Bean
    public DataSource dataSource(@Qualifier("routingDataSource") DataSource routing) {
        return new LazyConnectionDataSourceProxy(routing);
    }


}

