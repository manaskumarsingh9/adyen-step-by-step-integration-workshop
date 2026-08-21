// Global webhook toast - loaded on every page via layout.html, so a toast shows up regardless of
// which page happens to be open when the webhook lands (rather than only the specific page that
// triggered the action and happened to be polling for it).
const toastElement = document.getElementById("webhook-toast");

const TOAST_VISIBLE_MS = 3000;
window.TOAST_VISIBLE_MS = TOAST_VISIBLE_MS;

const TOKEN_EVENT_LABELS = {
    AUTHORISATION: { success: "Payment confirmed by webhook", failure: "Payment declined" },
    RECURRING_CONTRACT: { success: "Token confirmed by webhook", failure: "Token creation failed" }
};

const PREAUTH_EVENT_LABELS = {
    AUTHORISATION_ADJUSTMENT: { success: "Amount updated", failure: "Amount update failed" },
    CAPTURE: { success: "Payment captured", failure: "Capture failed" },
    CAPTURE_FAILED: { success: "Payment captured", failure: "Capture failed" },
    CANCELLATION: { success: "Preauthorisation cancelled", failure: "Cancel failed" },
    TECHNICAL_CANCEL: { success: "Preauthorisation cancelled", failure: "Cancel failed" },
    REFUND: { success: "Payment refunded", failure: "Refund failed" },
    REFUND_FAILED: { success: "Payment refunded", failure: "Refund failed" },
    REFUNDED_REVERSED: { success: "Refund reversed - amount returned to merchant", failure: "Refund reversal failed" }
};

const LABELS_BY_SOURCE = { token: TOKEN_EVENT_LABELS, preauth: PREAUTH_EVENT_LABELS };

function buildToastMessage(event) {
    const labels = (LABELS_BY_SOURCE[event.source] || {})[event.eventCode] || { success: event.eventCode, failure: event.eventCode + " failed" };
    let message = event.success ? labels.success : labels.failure;
    if (!event.success && event.reason) {
        message += ": " + event.reason;
    }
    return message;
}

let toastHideTimeoutId = null;

// Shows a card in the corner of the page reporting what happened, then fades it out again after a
// couple of seconds. Exposed on window so page-specific scripts can also use it for synchronous,
// non-webhook confirmations (e.g. "Subscription cancelled").
function showToast(message, isFailure) {
    toastElement.textContent = message;
    toastElement.classList.toggle("webhook-toast-failure", !!isFailure);
    toastElement.classList.add("webhook-toast-visible");

    if (toastHideTimeoutId) {
        clearTimeout(toastHideTimeoutId);
    }
    toastHideTimeoutId = setTimeout(() => {
        toastElement.classList.remove("webhook-toast-visible");
    }, TOAST_VISIBLE_MS);
}
window.showAppToast = showToast;

// Persisted across page navigations (same-origin, this browser only) so returning to - or landing
// fresh on - any page still catches a webhook that arrived while no page was open to receive it
// live, as long as it's still within the server's short replay buffer.
const LAST_SEEN_KEY = "adyen-workshop-last-notification-id";

function getLastSeenId() {
    return parseInt(localStorage.getItem(LAST_SEEN_KEY), 10) || 0;
}

function setLastSeenId(id) {
    localStorage.setItem(LAST_SEEN_KEY, String(id));
}

function connect() {
    const eventSource = new EventSource("/api/notifications/stream?lastEventId=" + getLastSeenId());

    eventSource.addEventListener("webhook-event", (e) => {
        const event = JSON.parse(e.data);
        setLastSeenId(event.id);
        showToast(buildToastMessage(event), !event.success);

        // Lets page-specific scripts react (e.g. preauthorisation.js reloading itself) without
        // this shared script needing to know about every page's own behaviour.
        window.dispatchEvent(new CustomEvent("webhook-notification", { detail: event }));
    });

    // The browser retries automatically (with the native Last-Event-ID header) after a dropped
    // connection - nothing to do here.
    eventSource.onerror = (error) => console.error("Notification stream error", error);

    // Without this, navigating away leaves the connection open until the server notices the
    // socket is dead (only on its next write attempt, logged loudly by Tomcat as a broken pipe).
    // Closing it here tells the server immediately, so ordinary page navigation never accumulates
    // stale connections for later broadcasts to trip over.
    window.addEventListener("pagehide", () => eventSource.close());
}

connect();
