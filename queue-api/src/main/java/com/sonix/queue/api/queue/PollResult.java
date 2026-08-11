package com.sonix.queue.api.queue;

public record PollResult(boolean ready, String admitToken,
                         long frontSeq, long total, int nextPollAfterSec) {

}