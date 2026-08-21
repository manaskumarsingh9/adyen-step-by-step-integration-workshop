package com.adyen.workshop.controllers;

import com.adyen.model.RequestOptions;
import com.adyen.model.checkout.*;
import com.adyen.workshop.PreauthStore;
import com.adyen.workshop.TokenStore;
import com.adyen.workshop.configurations.ApplicationConfiguration;
import com.adyen.service.checkout.ModificationsApi;
import com.adyen.service.checkout.PaymentsApi;
import com.adyen.service.checkout.RecurringApi;
import com.adyen.service.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for using the Adyen payments API.
 */
@RestController
public class ApiController {
    private final Logger log = LoggerFactory.getLogger(ApiController.class);

    // Shopper reference used to look up/store the subscription token. In a real
    // application this would come from the authenticated shopper's session.
    private static final String SUBSCRIPTION_SHOPPER_REFERENCE = "shopperReference";

    private final ApplicationConfiguration applicationConfiguration;
    private final PaymentsApi paymentsApi;
    private final RecurringApi recurringApi;
    private final ModificationsApi modificationsApi;
    private final TokenStore tokenStore;
    private final PreauthStore preauthStore;

    public ApiController(ApplicationConfiguration applicationConfiguration, PaymentsApi paymentsApi, RecurringApi recurringApi, ModificationsApi modificationsApi, TokenStore tokenStore, PreauthStore preauthStore) {
        this.applicationConfiguration = applicationConfiguration;
        this.paymentsApi = paymentsApi;
        this.recurringApi = recurringApi;
        this.modificationsApi = modificationsApi;
        this.tokenStore = tokenStore;
        this.preauthStore = preauthStore;
    }

    // Step 0
    @GetMapping("/hello-world")
    public ResponseEntity<String> helloWorld() throws Exception {
        return ResponseEntity.ok().body("This is the 'Hello World' from the workshop - You've successfully finished step 0!");
    }

    // Step 7
    @PostMapping("/api/paymentMethods")
    public ResponseEntity<PaymentMethodsResponse> paymentMethods() throws IOException, ApiException {
        var paymentMethodsRequest = new PaymentMethodsRequest();
        paymentMethodsRequest.setMerchantAccount(applicationConfiguration.getAdyenMerchantAccount());

        log.info("Retrieving available Payment Methods from Adyen {}", paymentMethodsRequest);
        var response = paymentsApi.paymentMethods(paymentMethodsRequest);
        log.info("Payment Methods response from Adyen {}", response);
        return ResponseEntity.ok().body(response);
    }

    // Step 9 - Implement the /payments call to Adyen.
    @PostMapping("/api/payments")
    public ResponseEntity<PaymentResponse> payments(@RequestBody PaymentRequest body) throws IOException, ApiException {
        var paymentRequest = new PaymentRequest();

        var amount = new Amount()
                .currency("EUR")
                .value(9998L);
        paymentRequest.setAmount(amount);
        paymentRequest.setMerchantAccount(applicationConfiguration.getAdyenMerchantAccount());
        paymentRequest.setChannel(PaymentRequest.ChannelEnum.WEB);

        paymentRequest.setPaymentMethod(body.getPaymentMethod());

        var orderRef = UUID.randomUUID().toString();
        paymentRequest.setReference(orderRef);
        // The returnUrl field basically means: Once done with the payment, where should the application redirect you?
        paymentRequest.setReturnUrl("http://localhost:8080/handleShopperRedirect");


        // Step 12 3DS2 Redirect - Add the following additional parameters to your existing payment request for 3DS2 Redirect:
        // Note: Visa requires additional properties to be sent in the request, see documentation for Redirect 3DS2: https://docs.adyen.com/online-payments/3d-secure/redirect-3ds2/web-drop-in/#make-a-payment
        var authenticationData = new AuthenticationData();
        authenticationData.setAttemptAuthentication(AuthenticationData.AttemptAuthenticationEnum.ALWAYS);
        paymentRequest.setAuthenticationData(authenticationData);

        // Change the following lines, if you want to enable the Native 3DS2 flow:
        // Note: Visa requires additional properties to be sent in the request, see documentation for Native 3DS2: https://docs.adyen.com/online-payments/3d-secure/native-3ds2/web-drop-in/#make-a-payment
        authenticationData.setThreeDSRequestData(new ThreeDSRequestData().nativeThreeDS(ThreeDSRequestData.NativeThreeDSEnum.PREFERRED));
        paymentRequest.setAuthenticationData(authenticationData);

        paymentRequest.setOrigin("https://localhost:8080");
        paymentRequest.setBrowserInfo(body.getBrowserInfo());
        paymentRequest.setShopperIP("192.168.0.1");
        paymentRequest.setShopperInteraction(PaymentRequest.ShopperInteractionEnum.ECOMMERCE);

        var billingAddress = new BillingAddress();
        billingAddress.setCity("Amsterdam");
        billingAddress.setCountry("NL");
        billingAddress.setPostalCode("1012KK");
        billingAddress.setStreet("Rokin");
        billingAddress.setHouseNumberOrName("49");
        paymentRequest.setBillingAddress(billingAddress);

        // Step 19 - Klarna requires shopperEmail, shopperReference, and LineItems on the payment request
        var lineItem1 = new LineItem()
                .quantity(1L)
                .taxPercentage(2100L)
                .amountIncludingTax(4999L)
                .description("The best sunglasses")
                .id("uniqueId-1");

        var lineItem2 = new LineItem()
                .quantity(1L)
                .taxPercentage(2100L)
                .amountIncludingTax(4999L)
                .description("The best headphones")
                .id("uniqueId-2");

        paymentRequest.setCountryCode("NL");
        paymentRequest.setShopperReference("shopperReference");
        paymentRequest.setShopperEmail("example@email.com");
        paymentRequest.setLineItems(Arrays.asList(lineItem1, lineItem2));

        // Step 11 - Optionally, add the idempotency key
        var requestOptions = new RequestOptions();
        requestOptions.setIdempotencyKey(UUID.randomUUID().toString());

        log.info("PaymentsRequest {}", paymentRequest);
        var response = paymentsApi.payments(paymentRequest, requestOptions);
        log.info("PaymentsResponse {}", response);
        return ResponseEntity.ok().body(response);
    }

    // Tokenization Module - Zero-value payment to tokenize the shopper's card for future (subscription) use.
    // See: https://docs.adyen.com/online-payments/tokenization/create-tokens
    @PostMapping("/api/subscription-create")
    public ResponseEntity<PaymentResponse> subscriptionCreate(@RequestBody PaymentRequest body) throws IOException, ApiException {
        var paymentRequest = new PaymentRequest();

        // Zero-auth: authorize for 0 to validate/tokenize the card without charging the shopper.
        var amount = new Amount()
                .currency("EUR")
                .value(0L);
        paymentRequest.setAmount(amount);
        paymentRequest.setMerchantAccount(applicationConfiguration.getAdyenMerchantAccount());
        paymentRequest.setChannel(PaymentRequest.ChannelEnum.WEB);
        paymentRequest.setPaymentMethod(body.getPaymentMethod());

        var orderRef = UUID.randomUUID().toString();
        paymentRequest.setReference(orderRef);
        paymentRequest.setReturnUrl("http://localhost:8080/handleShopperRedirect");

        paymentRequest.setOrigin("https://localhost:8080");
        paymentRequest.setBrowserInfo(body.getBrowserInfo());
        paymentRequest.setShopperIP("192.168.0.1");
        paymentRequest.setShopperInteraction(PaymentRequest.ShopperInteractionEnum.ECOMMERCE);

        // Required to create a token: store the payment method against a shopperReference,
        // and flag the intended future usage as a recurring Subscription.
        paymentRequest.setShopperReference(SUBSCRIPTION_SHOPPER_REFERENCE);
        paymentRequest.setStorePaymentMethod(true);
        paymentRequest.setRecurringProcessingModel(PaymentRequest.RecurringProcessingModelEnum.SUBSCRIPTION);

        var requestOptions = new RequestOptions();
        requestOptions.setIdempotencyKey(UUID.randomUUID().toString());

        log.info("SubscriptionCreate PaymentsRequest {}", paymentRequest);
        var response = paymentsApi.payments(paymentRequest, requestOptions);
        log.info("SubscriptionCreate PaymentsResponse {}", response);
        return ResponseEntity.ok().body(response);
    }

    // Tokenization Module - Charge the shopper using the token stored from /api/subscription-create.
    // The recurringDetailReference (token) is populated by the RECURRING_CONTRACT webhook, see WebhookController.
    // See: https://docs.adyen.com/online-payments/tokenization/make-token-payments
    @PostMapping("/api/subscription-payment")
    public ResponseEntity<?> subscriptionPayment() throws IOException, ApiException {
        var token = tokenStore.get(SUBSCRIPTION_SHOPPER_REFERENCE);
        if (token == null) {
            log.warn("No stored token found for shopperReference {}, cannot charge subscription", SUBSCRIPTION_SHOPPER_REFERENCE);
            return ResponseEntity.unprocessableEntity().body("No stored token found for this shopper. Create a subscription first.");
        }

        var paymentRequest = new PaymentRequest();

        var amount = new Amount()
                .currency("EUR")
                .value(500L); // 5 euros/month, per the briefing
        paymentRequest.setAmount(amount);
        paymentRequest.setMerchantAccount(applicationConfiguration.getAdyenMerchantAccount());
        paymentRequest.setChannel(PaymentRequest.ChannelEnum.WEB);

        var paymentMethod = new StoredPaymentMethodDetails().storedPaymentMethodId(token);
        paymentRequest.setPaymentMethod(new CheckoutPaymentMethod(paymentMethod));

        paymentRequest.setReference(UUID.randomUUID().toString());
        paymentRequest.setShopperReference(SUBSCRIPTION_SHOPPER_REFERENCE);
        // ContAuth: the shopper is not present, this is a merchant-initiated recurring charge.
        paymentRequest.setShopperInteraction(PaymentRequest.ShopperInteractionEnum.CONTAUTH);
        paymentRequest.setRecurringProcessingModel(PaymentRequest.RecurringProcessingModelEnum.SUBSCRIPTION);

        var requestOptions = new RequestOptions();
        requestOptions.setIdempotencyKey(UUID.randomUUID().toString());

        log.info("SubscriptionPayment PaymentsRequest {}", paymentRequest);
        var response = paymentsApi.payments(paymentRequest, requestOptions);
        log.info("SubscriptionPayment PaymentsResponse {}", response);
        return ResponseEntity.ok().body(response);
    }

    // Tokenization Module - Cancel the subscription by deleting the stored token.
    // See: https://docs.adyen.com/online-payments/tokenization/managing-tokens/#delete-stored-details
    @PostMapping("/api/subscriptions-cancel")
    public ResponseEntity<?> subscriptionsCancel() throws IOException, ApiException {
        var token = tokenStore.get(SUBSCRIPTION_SHOPPER_REFERENCE);
        if (token == null) {
            log.warn("No stored token found for shopperReference {}, nothing to cancel", SUBSCRIPTION_SHOPPER_REFERENCE);
            return ResponseEntity.unprocessableEntity().body("No stored token found for this shopper.");
        }

        log.info("Deleting token {} for shopperReference {}", token, SUBSCRIPTION_SHOPPER_REFERENCE);
        recurringApi.deleteTokenForStoredPaymentDetails(token, SUBSCRIPTION_SHOPPER_REFERENCE, applicationConfiguration.getAdyenMerchantAccount());
        tokenStore.remove(SUBSCRIPTION_SHOPPER_REFERENCE);

        return ResponseEntity.ok().build();
    }

    // Preauthorisation Module - Preauthorize a payment: authorize now, capture later.
    // additionalData.authorisationType=PreAuth lets us adjust the amount afterwards; additionalData.manualCapture=true
    // means Adyen won't auto-capture on authorisation, so we can capture explicitly via /api/capture.
    // See: https://docs.adyen.com/online-payments/adjust-authorisation/adjust-with-preauth/#pre-authorize
    // and: https://docs.adyen.com/online-payments/capture/?tab=individual_payment_1_2
    @PostMapping("/api/preauthorisation")
    public ResponseEntity<PaymentResponse> preauthorisation(@RequestBody PaymentRequest body) throws IOException, ApiException {
        var paymentRequest = new PaymentRequest();

        var amount = new Amount()
                .currency("EUR")
                .value(9998L);
        paymentRequest.setAmount(amount);
        paymentRequest.setMerchantAccount(applicationConfiguration.getAdyenMerchantAccount());
        paymentRequest.setChannel(PaymentRequest.ChannelEnum.WEB);
        paymentRequest.setPaymentMethod(body.getPaymentMethod());

        var orderRef = UUID.randomUUID().toString();
        paymentRequest.setReference(orderRef);
        paymentRequest.setReturnUrl("http://localhost:8080/handleShopperRedirect");

        var authenticationData = new AuthenticationData();
        authenticationData.setAttemptAuthentication(AuthenticationData.AttemptAuthenticationEnum.ALWAYS);
        paymentRequest.setAuthenticationData(authenticationData);

        paymentRequest.setOrigin("https://localhost:8080");
        paymentRequest.setBrowserInfo(body.getBrowserInfo());
        paymentRequest.setShopperIP("192.168.0.1");
        paymentRequest.setShopperInteraction(PaymentRequest.ShopperInteractionEnum.ECOMMERCE);

        var billingAddress = new BillingAddress();
        billingAddress.setCity("Amsterdam");
        billingAddress.setCountry("NL");
        billingAddress.setPostalCode("1012KK");
        billingAddress.setStreet("Rokin");
        billingAddress.setHouseNumberOrName("49");
        paymentRequest.setBillingAddress(billingAddress);

        paymentRequest.setAdditionalData(Map.of(
                "authorisationType", "PreAuth",
                "manualCapture", "true"
        ));

        var requestOptions = new RequestOptions();
        requestOptions.setIdempotencyKey(UUID.randomUUID().toString());

        log.info("PreauthorisationRequest {}", paymentRequest);
        var response = paymentsApi.payments(paymentRequest, requestOptions);
        log.info("PreauthorisationResponse {}", response);

        if (response.getResultCode() == PaymentResponse.ResultCodeEnum.AUTHORISED && response.getPspReference() != null) {
            preauthStore.store(response.getPspReference(), orderRef, amount.getValue(), amount.getCurrency());
        }

        return ResponseEntity.ok().body(response);
    }

    // Preauthorisation Module - Adjust (increase) the pre-authorized amount (asynchronous flow).
    // See: https://docs.adyen.com/online-payments/adjust-authorisation/adjust-with-preauth/#adjust-auth
    @PostMapping("/api/modify-amount")
    public ResponseEntity<?> modifyAmount(@RequestBody(required = false) Map<String, Long> body) throws IOException, ApiException {
        var preauth = preauthStore.get();
        if (preauth == null) {
            log.warn("No pre-authorised payment found, cannot modify amount");
            return ResponseEntity.unprocessableEntity().body("No pre-authorised payment found. Preauthorize a payment first.");
        }

        // Default demo increment if the caller doesn't specify one: add 10.00 EUR.
        long additionalAmount = (body != null && body.get("additionalAmount") != null) ? body.get("additionalAmount") : 1000L;
        long newAmountValue = preauth.amountValue() + additionalAmount;

        var paymentAmountUpdateRequest = new PaymentAmountUpdateRequest();
        paymentAmountUpdateRequest.setMerchantAccount(applicationConfiguration.getAdyenMerchantAccount());
        paymentAmountUpdateRequest.setReference(UUID.randomUUID().toString());
        paymentAmountUpdateRequest.setIndustryUsage(PaymentAmountUpdateRequest.IndustryUsageEnum.DELAYEDCHARGE);
        paymentAmountUpdateRequest.setAmount(new Amount().currency(preauth.currency()).value(newAmountValue));

        log.info("PaymentAmountUpdateRequest {}", paymentAmountUpdateRequest);
        var response = modificationsApi.updateAuthorisedAmount(preauth.pspReference(), paymentAmountUpdateRequest);
        log.info("PaymentAmountUpdateResponse {}", response);

        // The /amountUpdates response only means the request was received, not that Adyen applied
        // it - the final outcome arrives via the AUTHORISATION_ADJUSTMENT webhook, which is what
        // updates preauthStore. Updating it here too, before confirmation, would leave the UI
        // showing a bumped amount even if the adjustment later fails.
        return ResponseEntity.ok().body(response);
    }

    // Preauthorisation Module - Capture the (possibly adjusted) pre-authorized amount.
    // See: https://docs.adyen.com/online-payments/capture/
    @PostMapping("/api/capture")
    public ResponseEntity<?> capture(@RequestBody(required = false) Map<String, Long> body) throws IOException, ApiException {
        var preauth = preauthStore.get();
        if (preauth == null) {
            log.warn("No pre-authorised payment found, cannot capture");
            return ResponseEntity.unprocessableEntity().body("No pre-authorised payment found. Preauthorize a payment first.");
        }

        long amountToCapture = (body != null && body.get("amount") != null) ? body.get("amount") : preauth.amountValue();

        var paymentCaptureRequest = new PaymentCaptureRequest();
        paymentCaptureRequest.setMerchantAccount(applicationConfiguration.getAdyenMerchantAccount());
        paymentCaptureRequest.setReference(UUID.randomUUID().toString());
        paymentCaptureRequest.setAmount(new Amount().currency(preauth.currency()).value(amountToCapture));

        log.info("PaymentCaptureRequest {}", paymentCaptureRequest);
        var response = modificationsApi.captureAuthorisedPayment(preauth.pspReference(), paymentCaptureRequest);
        log.info("PaymentCaptureResponse {}", response);

        return ResponseEntity.ok().body(response);
    }

    // Preauthorisation Module - Cancel a pre-authorized (not yet captured) payment, by PSP reference.
    // See: https://docs.adyen.com/online-payments/cancel/
    @PostMapping("/api/cancel")
    public ResponseEntity<?> cancel() throws IOException, ApiException {
        var preauth = preauthStore.get();
        if (preauth == null) {
            log.warn("No pre-authorised payment found, cannot cancel");
            return ResponseEntity.unprocessableEntity().body("No pre-authorised payment found. Preauthorize a payment first.");
        }

        var paymentCancelRequest = new PaymentCancelRequest();
        paymentCancelRequest.setMerchantAccount(applicationConfiguration.getAdyenMerchantAccount());
        paymentCancelRequest.setReference(UUID.randomUUID().toString());

        log.info("PaymentCancelRequest {}", paymentCancelRequest);
        var response = modificationsApi.cancelAuthorisedPaymentByPspReference(preauth.pspReference(), paymentCancelRequest);
        log.info("PaymentCancelResponse {}", response);

        return ResponseEntity.ok().body(response);
    }

    // Preauthorisation Module - Refund a captured payment.
    // See: https://docs.adyen.com/online-payments/refund/
    @PostMapping("/api/refund")
    public ResponseEntity<?> refund(@RequestBody(required = false) Map<String, Long> body) throws IOException, ApiException {
        var preauth = preauthStore.get();
        if (preauth == null) {
            log.warn("No pre-authorised payment found, cannot refund");
            return ResponseEntity.unprocessableEntity().body("No pre-authorised payment found. Preauthorize a payment first.");
        }

        long amountToRefund = (body != null && body.get("amount") != null) ? body.get("amount") : preauth.amountValue();

        var paymentRefundRequest = new PaymentRefundRequest();
        paymentRefundRequest.setMerchantAccount(applicationConfiguration.getAdyenMerchantAccount());
        paymentRefundRequest.setReference(UUID.randomUUID().toString());
        paymentRefundRequest.setAmount(new Amount().currency(preauth.currency()).value(amountToRefund));

        log.info("PaymentRefundRequest {}", paymentRefundRequest);
        var response = modificationsApi.refundCapturedPayment(preauth.pspReference(), paymentRefundRequest);
        log.info("PaymentRefundResponse {}", response);

        return ResponseEntity.ok().body(response);
    }

    // Preauthorisation Module - Lightweight JSON status check, polled by preauthorisation.js so the
    // page can reload itself once a modification webhook lands, instead of the shopper needing to
    // refresh manually while waiting for Adyen's async confirmation.
    @GetMapping("/api/preauthorisation/status")
    public ResponseEntity<PreauthStore.Preauthorisation> preauthorisationStatus() {
        return ResponseEntity.ok(preauthStore.get());
    }

    // Step 13 - Handle details call (triggered after Native 3DS2 flow)
    @PostMapping("/api/payments/details")
    public ResponseEntity<PaymentDetailsResponse> paymentsDetails(@RequestBody PaymentDetailsRequest detailsRequest) throws IOException, ApiException
    {
        log.info("PaymentDetailsRequest {}", detailsRequest);
        var response = paymentsApi.paymentsDetails(detailsRequest);
        log.info("PaymentDetailsResponse {}", response);
        return ResponseEntity.ok().body(response);
    }

    // Step 14 - Handle Redirect 3DS2 during payment.
    @GetMapping("/handleShopperRedirect")
    public RedirectView redirect(@RequestParam(required = false) String payload, @RequestParam(required = false) String redirectResult) throws IOException, ApiException {
        var paymentDetailsRequest = new PaymentDetailsRequest();

        PaymentCompletionDetails paymentCompletionDetails = new PaymentCompletionDetails();

        // Handle redirect result or payload
        if (redirectResult != null && !redirectResult.isEmpty()) {
            // For redirect, you are redirected to an Adyen domain to complete the 3DS2 challenge
            // After completing the 3DS2 challenge, you get the redirect result from Adyen in the returnUrl
            // We then pass on the redirectResult
            paymentCompletionDetails.redirectResult(redirectResult);
        } else if (payload != null && !payload.isEmpty()) {
            paymentCompletionDetails.payload(payload);
        }

        paymentDetailsRequest.setDetails(paymentCompletionDetails);

        var paymentsDetailsResponse = paymentsApi.paymentsDetails(paymentDetailsRequest);
        log.info("PaymentsDetailsResponse {}", paymentsDetailsResponse);

        // Handle response and redirect user accordingly
        var redirectURL = "http://localhost:8080/result/"; // Update your url here by replacing `http://localhost:8080` with where your application is hosted (if needed)
        switch (paymentsDetailsResponse.getResultCode()) {
            case AUTHORISED:
                redirectURL += "success";
                break;
            case PENDING:
            case RECEIVED:
                redirectURL += "pending";
                break;
            case REFUSED:
                redirectURL += "failed";
                break;
            default:
                redirectURL += "error";
                break;
        }
        return new RedirectView(redirectURL + "?reason=" + paymentsDetailsResponse.getResultCode());
    }
}
