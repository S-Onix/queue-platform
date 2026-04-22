package com.sonix.queue.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.sonix.queue")

public class QueueApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(QueueApiApplication.class, args);
    }
}
