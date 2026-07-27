package com.sonix.queue.infrastructure.queue;

public class OutboxKeys {
    private OutboxKeys(){}
    public static String pending() { return "{enqueue-outbox}:pending";}
    public static String processing() { return "{enqueue-outbox}:processing";}
}
