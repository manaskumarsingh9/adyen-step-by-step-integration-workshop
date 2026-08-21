// Tokenization Module - Drives the /api/subscription-payment and /api/subscriptions-cancel endpoints.
const statusElement = document.getElementById("status");

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
    // /api/subscription-payment already returns the final resultCode synchronously, but Adyen also
    // sends a separate AUTHORISATION webhook to /webhooks shortly after - notifications.js (loaded
    // on every page) shows a toast for it once it lands, regardless of whether this page is still open.
    await callEndpoint("/api/subscription-payment", "Charged the subscription");
});

document.getElementById("manual-charge-button").addEventListener("click", async () => {
    const tokenInput = document.getElementById("manual-token-input");
    const token = tokenInput.value.trim();
    if (!token) {
        statusElement.innerHTML = "Paste a recurringDetailReference token first.";
        return;
    }

    await callEndpoint("/makepaymentwithtoken/" + encodeURIComponent(token), "Charged using the manually-entered token");
});

document.getElementById("cancel-button").addEventListener("click", async () => {
    const ok = await callEndpoint("/api/subscriptions-cancel", "Cancelled the subscription");
    if (ok) {
        // Deleting a stored token is a synchronous API call with no webhook involved, so show the
        // confirmation immediately (via the shared toast from notifications.js) and reload once
        // it's had a moment to be read.
        window.showAppToast("Subscription cancelled", false);
        setTimeout(() => location.reload(), window.TOAST_VISIBLE_MS);
    }
});
