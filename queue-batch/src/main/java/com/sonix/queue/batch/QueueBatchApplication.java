package com.sonix.queue.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.sonix.queue")
public class QueueBatchApplication {
    public static void main(String[] args) {
        SpringApplication.run(QueueBatchApplication.class, args);
    }
}
