package com.sonix.queue.api.dev;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

// VT 확인용 컨드롤러
@RestController
public class ThreadCheckController {
    /**
     * VT 동작 확인
     * ThreadName       isVirtual javaVersion
     * ----------       --------- -----------
     * tomcat-handler-0      True 21.0.10+8-LTS-217
     * */

    @GetMapping("/thread-check")
    public Map<String, Object> threadCheck() {
        Thread current = Thread.currentThread();

        return Map.of(
                "ThreadName", current.getName(),
                "isVirtual", current.isVirtual(),
                "javaVersion", Runtime.version().toString()
        );
    }

}
