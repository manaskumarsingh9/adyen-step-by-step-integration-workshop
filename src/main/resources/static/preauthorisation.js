// Preauthorisation Module - Drives /api/modify-amount, /api/capture, /api/cancel, /api/refund.
const statusElement = document.getElementById("status");
const summaryElement = document.querySelector(".order-summary");
const toastElement = document.getElementById("webhook-toast");

const TOAST_VISIBLE_MS = 3000;

const EVENT_LABELS = {
    AUTHORISATION_ADJUSTMENT: { success: "Amount updated", failure: "Amount update failed" },
    CAPTURE: { success: "Payment captured", failure: "Capture failed" },
    CAPTURE_FAILED: { success: "Payment captured", failure: "Capture failed" },
    CANCELLATION: { success: "Preauthorisation cancelled", failure: "Cancel failed" },
    TECHNICAL_CANCEL: { success: "Preauthorisation cancelled", failure: "Cancel failed" },
    REFUND: { success: "Payment refunded", failure: "Refund failed" },
    REFUND_FAILED: { success: "Payment refunded", failure: "Refund failed" },
    REFUNDED_REVERSED: { success: "Refund reversed - amount returned to merchant", failure: "Refund reversal failed" }
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

// The modification APIs are asynchronous: the POST above only confirms Adyen *received* the
// request, not the outcome - that arrives later via a webhook to /webhooks, which updates
// PreauthStore and PreauthEventStore server-side. Poll the lightweight status endpoint until a
// new event shows up: pop a toast with what it said, then reload once the store actually changed
// so the shopper doesn't have to refresh manually.
function pollUntilChanged(successMessage) {
    const baselinePsp = summaryElement.dataset.psp;
    const baselineStatus = summaryElement.dataset.status;
    const baselineAmount = summaryElement.dataset.amount;
    let lastSeenSequence = parseInt(summaryElement.dataset.eventSequence, 10) || 0;

    let elapsedMs = 0;
    const intervalMs = 3000;
    const timeoutMs = 90000;

    const intervalId = setInterval(async () => {
        elapsedMs += intervalMs;
        try {
            const response = await fetch("/api/preauthorisation/status");
            const data = await response.json();
            const current = data.preauth;
            const event = data.lastEvent;

            let stateChanged = false;
            if (event && event.sequence > lastSeenSequence) {
                lastSeenSequence = event.sequence;
                showToast(buildToastMessage(event), !event.success);

                stateChanged = current === null
                    ? baselinePsp !== ""
                    : (current.pspReference !== baselinePsp || current.status !== baselineStatus || String(current.amountValue) !== baselineAmount);
            }

            if (stateChanged) {
                clearInterval(intervalId);
                // Give the toast a moment to be read before the reload wipes it away.
                setTimeout(() => location.reload(), TOAST_VISIBLE_MS);
                return;
            }
        } catch (error) {
            console.error(error);
        }

        if (elapsedMs >= timeoutMs) {
            clearInterval(intervalId);
            statusElement.innerHTML = successMessage + " - still waiting on the confirmation webhook, refresh the page to check.";
        }
    }, intervalMs);
}

async function callEndpoint(endpoint, successMessage, body) {
    statusElement.innerHTML = "Working...";
    try {
        const response = await fetch(endpoint, {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
            },
            body: body ? JSON.stringify(body) : undefined
        });

        const responseText = await response.text();

        if (!response.ok) {
            statusElement.innerHTML = "Failed: " + (responseText || response.status);
            return;
        }

        statusElement.innerHTML = successMessage + " - waiting for the confirmation webhook, this page will reload automatically...";
        pollUntilChanged(successMessage);
    } catch (error) {
        console.error(error);
        statusElement.innerHTML = "Error occurred. Look at console for details.";
    }
}

document.getElementById("modify-amount-button").addEventListener("click", () => {
    callEndpoint("/api/modify-amount", "Amount modification requested", { additionalAmount: 1000 });
});

document.getElementById("capture-button").addEventListener("click", () => {
    callEndpoint("/api/capture", "Capture requested");
});

document.getElementById("cancel-button").addEventListener("click", () => {
    callEndpoint("/api/cancel", "Cancel requested");
});

document.getElementById("refund-button").addEventListener("click", () => {
    callEndpoint("/api/refund", "Refund requested");
});
