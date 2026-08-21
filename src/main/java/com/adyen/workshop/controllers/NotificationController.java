package com.adyen.workshop.controllers;

import com.adyen.workshop.NotificationBroadcaster;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Serves the global webhook notification stream that notifications.js opens on every page, so a
 * toast shows up regardless of which page happens to be open when the webhook arrives.
 */
@RestController
public class NotificationController {

    private final NotificationBroadcaster notificationBroadcaster;

    public NotificationController(NotificationBroadcaster notificationBroadcaster) {
        this.notificationBroadcaster = notificationBroadcaster;
    }

    // EventSource can't set custom headers on the initial request, so the client also passes its
    // last-seen id as a query param (persisted in localStorage across page navigations). The
    // Last-Event-ID header is still honored when present, since the browser sends it automatically
    // on its own built-in reconnect after a dropped connection.
    @GetMapping("/api/notifications/stream")
    public SseEmitter stream(@RequestParam(defaultValue = "0") long lastEventId,
                              @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventIdHeader) {
        long effectiveLastEventId = lastEventIdHeader != null ? lastEventIdHeader : lastEventId;
        return notificationBroadcaster.subscribe(effectiveLastEventId);
    }
}
