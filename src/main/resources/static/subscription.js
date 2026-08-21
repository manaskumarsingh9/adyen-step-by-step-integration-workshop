// Tokenization Module - Drives the /api/subscription-payment and /api/subscriptions-cancel endpoints.
const statusElement = document.getElementById("status");
const summaryElement = document.querySelector(".order-summary");
const toastElement = document.getElementById("webhook-toast");

const TOAST_VISIBLE_MS = 3000;

const EVENT_LABELS = {
    AUTHORISATION: { success: "Payment confirmed by webhook", failure: "Payment declined" },
    RECURRING_CONTRACT: { success: "Token confirmed by webhook", failure: "Token creation failed" }
};

function buildToastMessage(event) {
    const labels = EVENT_LABELS[event.eventCode] || { success: event.eventCode, failure: event.eventCode + " failed" };
    let message = event.success ? labels.success : labels.failure;
    if (!event.success && event.reason) {
        message += ": " + event.reason;
    }
    return message;
}

let toastHideTimeoutId = null;

// Shows a card in the corner of the page reporting what the webhook said happened, then fades it
// out again after a couple of seconds.
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

// /api/subscription-payment already returns the final resultCode synchronously, but Adyen also
// sends a separate AUTHORISATION webhook to /webhooks shortly after - poll for it and pop a toast
// once it lands, the same way the preauthorisation page reacts to its modification webhooks.
function pollForWebhookToast() {
    let lastSeenSequence = parseInt(summaryElement.dataset.eventSequence, 10) || 0;

    let elapsedMs = 0;
    const intervalMs = 2000;
    const timeoutMs = 20000;

    const intervalId = setInterval(async () => {
        elapsedMs += intervalMs;
        try {
            const response = await fetch("/api/subscription/status");
            const data = await response.json();
            const event = data.lastEvent;

            if (event && event.sequence > lastSeenSequence) {
                lastSeenSequence = event.sequence;
                showToast(buildToastMessage(event), !event.success);
                clearInterval(intervalId);
                return;
            }
        } catch (error) {
            console.error(error);
        }

        if (elapsedMs >= timeoutMs) {
            clearInterval(intervalId);
        }
    }, intervalMs);
}

async function callEndpoint(endpoint, successMessage) {
    statusElement.innerHTML = "Working...";
    try {
        const response = await fetch(endpoint, {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
            }
        });

        if (!response.ok) {
            const message = await response.text();
            statusElement.innerHTML = "Failed: " + (message || response.status);
            return false;
        }

        // /api/subscriptions-cancel returns an empty body, /api/subscription-payment returns the PaymentResponse.
        const body = await response.text();
        const resultCode = body ? JSON.parse(body).resultCode : null;
        statusElement.innerHTML = resultCode ? successMessage + " (" + resultCode + ")" : successMessage;
        return true;
    } catch (error) {
        console.error(error);
        statusElement.innerHTML = "Error occurred. Look at console for details.";
        return false;
    }
}

document.getElementById("charge-button").addEventListener("click", async () => {
    const ok = await callEndpoint("/api/subscription-payment", "Charged the subscription");
    if (ok) {
        // The token stays the same either way, so no reload needed here - just wait for the webhook toast.
        pollForWebhookToast();
    }
});

document.getElementById("cancel-button").addEventListener("click", async () => {
    const ok = await callEndpoint("/api/subscriptions-cancel", "Cancelled the subscription");
    if (ok) {
        // Deleting a stored token is a synchronous API call with no webhook involved, so show the
        // confirmation immediately and reload once it's had a moment to be read.
        showToast("Subscription cancelled", false);
        setTimeout(() => location.reload(), TOAST_VISIBLE_MS);
    }
});
