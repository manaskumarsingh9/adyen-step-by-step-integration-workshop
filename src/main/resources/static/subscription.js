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
            return;
        }

        // /api/subscriptions-cancel returns an empty body, /api/subscription-payment returns the PaymentResponse.
        const body = await response.text();
        const resultCode = body ? JSON.parse(body).resultCode : null;
        statusElement.innerHTML = resultCode ? successMessage + " (" + resultCode + ")" : successMessage;
    } catch (error) {
        console.error(error);
        statusElement.innerHTML = "Error occurred. Look at console for details.";
    }
}

document.getElementById("charge-button").addEventListener("click", () => {
    callEndpoint("/api/subscription-payment", "Charged the subscription");
});

document.getElementById("cancel-button").addEventListener("click", () => {
    callEndpoint("/api/subscriptions-cancel", "Cancelled the subscription");
});
