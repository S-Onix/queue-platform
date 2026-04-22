package com.sonix.queue.infrastructure.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaRepositories(basePackages = "com.sonix.queue.infrastructure.repository")
@EntityScan(basePackages = "com.sonix.queue.infrastructure.entity")
public class JpaConfig {
}
