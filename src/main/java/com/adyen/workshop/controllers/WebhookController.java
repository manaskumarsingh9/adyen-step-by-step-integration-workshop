package com.adyen.workshop.controllers;

import com.adyen.model.notification.NotificationRequest;
import com.adyen.model.notification.NotificationRequestItem;
import com.adyen.util.HMACValidator;
import com.adyen.workshop.PreauthStore;
import com.adyen.workshop.TokenStore;
import com.adyen.workshop.configurations.ApplicationConfiguration;
import org.apache.coyote.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.security.SignatureException;

/**
 * REST controller for receiving Adyen webhook notifications
 */
@RestController
public class WebhookController {
    private final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final ApplicationConfiguration applicationConfiguration;

    private final HMACValidator hmacValidator;

    private final TokenStore tokenStore;

    private final PreauthStore preauthStore;

    @Autowired
    public WebhookController(ApplicationConfiguration applicationConfiguration, HMACValidator hmacValidator, TokenStore tokenStore, PreauthStore preauthStore) {
        this.applicationConfiguration = applicationConfiguration;
        this.hmacValidator = hmacValidator;
        this.tokenStore = tokenStore;
        this.preauthStore = preauthStore;
    }

    // Step 16 - Validate the HMAC signature using the ADYEN_HMAC_KEY
    @PostMapping("/webhooks")
    public ResponseEntity<String> webhooks(@RequestBody String json) throws Exception {
        log.info("Received: {}", json);
        var notificationRequest = NotificationRequest.fromJson(json);
        var notificationRequestItem = notificationRequest.getNotificationItems().stream().findFirst();

        try {
            NotificationRequestItem item = notificationRequestItem.get();

            // Step 16 - Validate the HMAC signature using the ADYEN_HMAC_KEY
            if (!hmacValidator.validateHMAC(item, this.applicationConfiguration.getAdyenHmacKey())) {
                log.warn("Could not validate HMAC signature for incoming webhook message: {}", item);
                return ResponseEntity.unprocessableEntity().build();
            }

            // Success, log it for now
            log.info("Received webhook with event {}", item.toString());

            // Tokenization Module - Capture the token (recurringDetailReference) so we can charge
            // the shopper later via /api/subscription-payment.
            handleTokenizationWebhook(item);

            // Preauthorisation Module - Log the outcome of each modification event.
            handlePreauthorisationWebhook(item);

            return ResponseEntity.accepted().build();
        } catch (SignatureException e) {
            // Handle invalid signature
            return ResponseEntity.unprocessableEntity().build();
        } catch (Exception e) {
            // Handle all other errors
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Tokenization Module - Extract and store the token created by /api/subscription-create.
     *
     * Adyen sends the token in the `recurring.recurringDetailReference` field of the webhook's
     * additionalData. Depending on your Customer Area "Additional data" settings this arrives on
     * the AUTHORISATION webhook, and/or as a separate RECURRING_CONTRACT webhook.
     */
    private void handleTokenizationWebhook(NotificationRequestItem item) {
        var additionalData = item.getAdditionalData();
        if (additionalData == null) {
            return;
        }

        var recurringDetailReference = additionalData.get("recurring.recurringDetailReference");
        var shopperReference = additionalData.get("recurring.shopperReference");

        if (recurringDetailReference == null || shopperReference == null) {
            return;
        }

        if (!item.isSuccess()) {
            log.warn("Ignoring token from unsuccessful webhook, eventCode {}", item.getEventCode());
            return;
        }

        tokenStore.store(shopperReference, recurringDetailReference);
        log.info("Stored token {} for shopperReference {} (eventCode {})", recurringDetailReference, shopperReference, item.getEventCode());
    }

    /**
     * Preauthorisation Module - Log the outcome of each pre-authorisation/capture/cancel/refund
     * modification. README_PREAUTHORISATION.md asks us to "handle" each of these event codes:
     * AUTHORISATION, AUTHORISATION_ADJUSTMENT, CAPTURE, CAPTURE_FAILED, TECHNICAL_CANCEL,
     * CANCELLATION, REFUND, REFUND_FAILED, REFUNDED_REVERSED.
     *
     * On success:false, Adyen includes a `reason` explaining why the operation failed
     * (see https://docs.adyen.com/online-payments/cancel/ and .../refund/).
     */
    private void handlePreauthorisationWebhook(NotificationRequestItem item) {
        switch (item.getEventCode()) {
            case "AUTHORISATION_ADJUSTMENT":
            case "CAPTURE":
            case "CAPTURE_FAILED":
            case "TECHNICAL_CANCEL":
            case "CANCELLATION":
            case "REFUND":
            case "REFUND_FAILED":
            case "REFUNDED_REVERSED":
                if (item.isSuccess()) {
                    log.info("Preauthorisation event {} succeeded - pspReference {}, originalReference {}", item.getEventCode(), item.getPspReference(), item.getOriginalReference());

                    var current = preauthStore.get();
                    boolean matchesCurrent = current != null && current.pspReference().equals(item.getOriginalReference());

                    // Once a preauthorisation is cancelled or refunded, there is nothing left to
                    // capture/cancel/refund - clear the store so the UI reflects that (buttons
                    // disable, amount clears) instead of continuing to show a hold that Adyen has
                    // already released.
                    if (matchesCurrent && ("CANCELLATION".equals(item.getEventCode()) || "TECHNICAL_CANCEL".equals(item.getEventCode()) || "REFUND".equals(item.getEventCode()))) {
                        preauthStore.clear();
                    } else if (matchesCurrent && "CAPTURE".equals(item.getEventCode())) {
                        // Mark the hold as captured so the UI can stop offering Modify/Capture/Cancel
                        // (Adyen will reject those on an already-captured payment) and enable Refund.
                        preauthStore.updateStatus("CAPTURED");
                    } else if (matchesCurrent && "AUTHORISATION_ADJUSTMENT".equals(item.getEventCode()) && item.getAmount() != null) {
                        // The webhook's amount is the confirmed new total authorised amount - use it
                        // as ground truth instead of the value /api/modify-amount guessed when it
                        // fired the (async) request, so a failed adjustment doesn't leave the UI
                        // showing an amount Adyen never actually applied.
                        preauthStore.updateAmount(item.getAmount().getValue());
                    }
                } else {
                    log.warn("Preauthorisation event {} failed - pspReference {}, reason {}", item.getEventCode(), item.getPspReference(), item.getReason());
                }
                break;
            default:
                // Not a preauthorisation-related event (e.g. AUTHORISATION, RECURRING_CONTRACT) - nothing to do here.
                break;
        }
    }
}