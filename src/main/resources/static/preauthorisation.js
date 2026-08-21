// Preauthorisation Module - Drives /api/modify-amount, /api/capture, /api/cancel, /api/refund.
const statusElement = document.getElementById("status");

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
    } catch (error) {
        console.error(error);
        statusElement.innerHTML = "Error occurred. Look at console for details.";
    }
}

// The modification APIs are asynchronous: the POST above only confirms Adyen *received* the
// request, not the outcome - that arrives later via a webhook to /webhooks. notifications.js
// (loaded on every page) shows the toast regardless of which page is open when it lands; this page
// additionally reloads itself on a successful preauth event so the rendered pspReference/status/
// amount reflect what actually happened, without the shopper refreshing manually. Listening for the
// push - rather than polling a snapshot from this page only - means the reload still fires even if
// this page was opened/returned to after the webhook already arrived while it was closed.
let reloadScheduled = false;
window.addEventListener("webhook-notification", (e) => {
    if (e.detail.source === "preauth" && e.detail.success && !reloadScheduled) {
        reloadScheduled = true;
        setTimeout(() => location.reload(), window.TOAST_VISIBLE_MS);
    }
});

document.getElementById("modify-amount-button").addEventListener("click", () => {
    const amountInput = document.getElementById("modify-amount-input");
    const amount = parseFloat(amountInput.value);
    if (isNaN(amount) || amount < 0) {
        statusElement.innerHTML = "Enter a new authorised amount first, e.g. 66.00.";
        return;
    }

    // Adyen amounts are in minor units (cents), so 66.00 becomes 6600.
    callEndpoint("/api/modify-amount", "Amount modification requested", { amount: Math.round(amount * 100) });
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
