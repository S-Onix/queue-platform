package com.sonix.queue.infrastructure.routing.com.sonix.queue.infrastructure;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@ComponentScan(basePackages = "com.sonix.queue.infrastructure")
@EnableJpaRepositories(basePackages = "com.sonix.queue.infrastructure")
@EntityScan(basePackages = "com.sonix.queue.infrastructure")
public class TestConfig {

}
