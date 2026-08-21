package com.adyen.workshop;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store mapping shopperReference to the recurringDetailReference (token) received via
 * the AUTHORISATION/RECURRING_CONTRACT webhook. A real application would persist this in a
 * database instead.
 */
@Component
public class TokenStore {
    public record TokenRecord(String token, boolean cancelled) {
    }

    private final ConcurrentHashMap<String, TokenRecord> tokensByShopperReference = new ConcurrentHashMap<>();

    public void store(String shopperReference, String recurringDetailReference) {
        tokensByShopperReference.put(shopperReference, new TokenRecord(recurringDetailReference, false));
    }

    public TokenRecord get(String shopperReference) {
        return tokensByShopperReference.get(shopperReference);
    }

    // Deliberately does not remove the record - README_TOKENIZATION.md's checklist requires
    // attempting /api/subscription-payment again *after* cancelling, to observe Adyen reject a
    // charge against a deleted token. Removing the record here would make that attempt bounce off
    // this app's own "no stored token" guard before ever reaching Adyen.
    public void markCancelled(String shopperReference) {
        tokensByShopperReference.computeIfPresent(shopperReference, (key, existing) -> new TokenRecord(existing.token(), true));
    }
}
