package com.sonix.queue.api;

import com.sonix.queue.api.security.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(scanBasePackages = "com.sonix.queue")
@EnableConfigurationProperties(JwtProperties.class)
public class QueueApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(QueueApiApplication.class, args);
    }
}