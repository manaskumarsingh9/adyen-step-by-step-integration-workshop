// Preauthorisation Module - Drives /api/modify-amount, /api/capture, /api/cancel, /api/refund.
const statusElement = document.getElementById("status");
const summaryElement = document.querySelector(".order-summary");

// The modification APIs are asynchronous: the POST above only confirms Adyen *received* the
// request, not the outcome - that arrives later via a webhook to /webhooks, which updates
// PreauthStore server-side. Poll the lightweight status endpoint until that change shows up,
// then reload so the shopper doesn't have to refresh manually.
function pollUntilChanged(successMessage) {
    const baselinePsp = summaryElement.dataset.psp;
    const baselineStatus = summaryElement.dataset.status;
    const baselineAmount = summaryElement.dataset.amount;

    let elapsedMs = 0;
    const intervalMs = 3000;
    const timeoutMs = 90000;

    const intervalId = setInterval(async () => {
        elapsedMs += intervalMs;
        try {
            const response = await fetch("/api/preauthorisation/status");
            const text = await response.text();
            const current = (!text || text === "null") ? null : JSON.parse(text);

            const changed = current === null
                ? baselinePsp !== ""
                : (current.pspReference !== baselinePsp || current.status !== baselineStatus || String(current.amountValue) !== baselineAmount);

            if (changed) {
                clearInterval(intervalId);
                location.reload();
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
