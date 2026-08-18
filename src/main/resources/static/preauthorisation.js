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

        statusElement.innerHTML = successMessage + " - refresh the page to see the updated status.";
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
