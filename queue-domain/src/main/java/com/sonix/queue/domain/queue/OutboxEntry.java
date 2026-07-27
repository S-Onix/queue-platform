package com.sonix.queue.domain.queue;

public record OutboxEntry (String raw, EnqueueEvent event){
}
