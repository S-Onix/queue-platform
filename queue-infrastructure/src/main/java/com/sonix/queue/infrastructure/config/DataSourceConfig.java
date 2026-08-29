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
     * 풀 이름을 여기서 박는다. 안 주면 Hikari가 "HikariPool-1"/"HikariPool-2"를 생성 순서대로
     * 붙이는데, 그 순서는 보장되지 않는다. 그러면 hikaricp_connections{pool="HikariPool-1"} 이
     * master인지 replica인지 지표만 봐서는 알 수 없다 — 풀 포화를 어느 쪽에서 봤는지가 사라진다.
     * yml(앱 3개 × 프로파일 3개 × 풀 2개 = 18곳)이 아니라 여기 두는 이유는, 이름이 환경별로
     * 달라질 값이 아니고 18곳에 흩어지면 한 곳이 빠져도 아무도 모르기 때문이다.
     * ⚠️ @ConfigurationProperties 바인딩은 이 메서드가 끝난 뒤에 돈다. 바인더는 yml에 실재하는
     *    키만 덮으므로 pool-name 을 yml에 안 쓰는 한 이 값이 유지된다 — 쓰면 yml이 이긴다.
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

