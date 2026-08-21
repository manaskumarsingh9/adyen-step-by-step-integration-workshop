package com.adyen.workshop.controllers;

import com.adyen.model.notification.NotificationRequest;
import com.adyen.model.notification.NotificationRequestItem;
import com.adyen.util.HMACValidator;
import com.adyen.workshop.PreauthEventStore;
import com.adyen.workshop.PreauthStore;
import com.adyen.workshop.TokenEventStore;
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

    private final TokenEventStore tokenEventStore;

    private final PreauthStore preauthStore;

    private final PreauthEventStore preauthEventStore;

    @Autowired
    public WebhookController(ApplicationConfiguration applicationConfiguration, HMACValidator hmacValidator, TokenStore tokenStore, TokenEventStore tokenEventStore, PreauthStore preauthStore, PreauthEventStore preauthEventStore) {
        this.applicationConfiguration = applicationConfiguration;
        this.hmacValidator = hmacValidator;
        this.tokenStore = tokenStore;
        this.tokenEventStore = tokenEventStore;
        this.preauthStore = preauthStore;
        this.preauthEventStore = preauthEventStore;
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
            log.error("Unexpected error handling webhook", e);
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

        var shopperReference = additionalData.get("recurring.shopperReference");
        if (shopperReference == null) {
            // Not a subscription-create/charge webhook (e.g. a plain Part 1 checkout payment,
            // which doesn't set shopperReference) - nothing to do here.
            return;
        }

        // Record every attempt (success or failure) tied to the subscription shopper, so the
        // frontend can poll for it and show a toast notification reflecting what Adyen actually
        // reported - independent of whether it also carried a usable token below.
        tokenEventStore.record(item.getEventCode(), item.isSuccess(), item.getReason());

        if (!item.isSuccess()) {
            log.warn("Ignoring token from unsuccessful webhook, eventCode {}", item.getEventCode());
            return;
        }

        var recurringDetailReference = additionalData.get("recurring.recurringDetailReference");
        if (recurringDetailReference == null) {
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
                // Record every attempt (success or failure) so the frontend can poll for it and
                // show a toast notification reflecting what Adyen actually reported.
                preauthEventStore.record(item.getEventCode(), item.isSuccess(), item.getReason());

                if (item.isSuccess()) {
                    log.info("Preauthorisation event {} succeeded - pspReference {}, originalReference {}", item.getEventCode(), item.getPspReference(), item.getOriginalReference());

                    var current = preauthStore.get();
                    boolean matchesCurrent = current != null && current.pspReference().equals(item.getOriginalReference());

                    // Keep the pspReference around (never clear it) even once cancelled/refunded -
                    // README_PREAUTHORISATION.md's checklist deliberately requires attempting further
                    // operations against an already-cancelled/refunded/captured preauth (e.g.
                    // cancel -> capture, refund -> capture) to observe Adyen's real failure response.
                    // Clearing the store would make those calls bounce off this app's own
                    // "no pre-authorised payment found" guard before ever reaching Adyen. Just track
                    // what actually happened via status, and let the UI/buttons stay usable so those
                    // flows can be attempted for real.
                    if (matchesCurrent && ("CANCELLATION".equals(item.getEventCode()) || "TECHNICAL_CANCEL".equals(item.getEventCode()))) {
                        preauthStore.updateStatus("CANCELLED");
                    } else if (matchesCurrent && "CAPTURE".equals(item.getEventCode())) {
                        preauthStore.updateStatus("CAPTURED");
                    } else if (matchesCurrent && "REFUND".equals(item.getEventCode())) {
                        preauthStore.updateStatus("REFUNDED");
                    } else if (matchesCurrent && "REFUNDED_REVERSED".equals(item.getEventCode())) {
                        // The shopper's bank rejected the refund transfer (e.g. closed account) and
                        // the funds have been returned to us. Per Adyen's docs
                        // (https://docs.adyen.com/online-payments/refund#refunded-reversed), the
                        // payment's status becomes RefundedReversed - a distinct state from a normal
                        // Captured payment, since a merchant would typically want to contact the
                        // shopper about their bank details before attempting the refund again.
                        preauthStore.updateStatus("REFUNDED_REVERSED");
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