package com.sonix.queue.infrastructure.routing;

import com.sonix.queue.infrastructure.config.ReplicationRoutingDataSource;
import com.sonix.queue.infrastructure.entity.TestEntity;
import com.sonix.queue.infrastructure.repositoy.TestRepository;
import com.sonix.queue.infrastructure.routing.com.sonix.queue.infrastructure.TestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes = TestConfig.class)
@TestPropertySource(locations = "classpath:application.yml")
public class RoutingDataSourceTest {

    @Autowired
    private TestRepository testRepository;

    @Test
    @Transactional
    @DisplayName("Write -> Master Routing")
    void masterRoutingTest(){
        for(int i = 0; i < 10; i++){
            testRepository.save(new TestEntity());
        }
    }

    @Test
    @Transactional(readOnly = true)
    @DisplayName("Read -> Replica Routing")
    void replicaRoutingTest(){
        testRepository.findAll();
    }
}
