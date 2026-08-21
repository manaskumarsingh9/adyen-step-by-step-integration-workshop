package com.adyen.workshop;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory record of the most recent tokenization-related webhook event (subscription create or
 * charge), so the frontend can poll for and display it as a toast notification - independent of
 * whether that event ended up changing TokenStore. A real application would push this to the
 * browser instead of polling.
 */
@Component
public class TokenEventStore {
    public record Event(long sequence, String eventCode, boolean success, String reason) {
    }

    private final AtomicLong sequence = new AtomicLong(0);
    private final AtomicReference<Event> latest = new AtomicReference<>();

    public void record(String eventCode, boolean success, String reason) {
        long seq = sequence.incrementAndGet();
        latest.set(new Event(seq, eventCode, success, reason));
    }

    public Event get() {
        return latest.get();
    }

    public long currentSequence() {
        return sequence.get();
    }
}
