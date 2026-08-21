package com.adyen.workshop;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fan-out hub for webhook-driven toast notifications, so a toast shows up on whichever page is
 * currently open when the webhook lands - not only the specific page that triggered the action
 * (the previous design polled a status endpoint from subscription.js/preauthorisation.js
 * individually, so a webhook was only ever noticed by the one page that happened to be running
 * that poll loop).
 *
 * Keeps a small replay buffer so a page opened - or navigated to - a few seconds after a webhook
 * lands still catches it via its id in the reconnect query param/header, instead of only serving
 * genuinely-live events.
 */
@Component
public class NotificationBroadcaster {

    public record Event(long id, String source, String eventCode, boolean success, String reason) {
    }

    private static final int REPLAY_BUFFER_SIZE = 50;
    private static final long EMITTER_TIMEOUT_MS = 30L * 60 * 1000;

    private final AtomicLong sequence = new AtomicLong(0);
    private final Deque<Event> recentEvents = new ArrayDeque<>();
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public synchronized SseEmitter subscribe(long lastSeenId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));

        for (Event event : recentEvents) {
            if (event.id() > lastSeenId) {
                sendTo(emitter, event);
            }
        }
        return emitter;
    }

    public synchronized void broadcast(String source, String eventCode, boolean success, String reason) {
        Event event = new Event(sequence.incrementAndGet(), source, eventCode, success, reason);
        recentEvents.addLast(event);
        while (recentEvents.size() > REPLAY_BUFFER_SIZE) {
            recentEvents.removeFirst();
        }
        for (SseEmitter emitter : emitters) {
            sendTo(emitter, event);
        }
    }

    private void sendTo(SseEmitter emitter, Event event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(event.id()))
                    .name("webhook-event")
                    .data(event));
        } catch (IOException | IllegalStateException e) {
            emitters.remove(emitter);
        }
    }
}
