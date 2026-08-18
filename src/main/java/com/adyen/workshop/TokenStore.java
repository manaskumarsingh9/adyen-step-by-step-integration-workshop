package com.adyen.workshop;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store mapping shopperReference to the recurringDetailReference (token)
 * received via the RECURRING_CONTRACT webhook. A real application would persist
 * this in a database instead.
 */
@Component
public class TokenStore {
    private final ConcurrentHashMap<String, String> tokensByShopperReference = new ConcurrentHashMap<>();

    public void store(String shopperReference, String recurringDetailReference) {
        tokensByShopperReference.put(shopperReference, recurringDetailReference);
    }

    public String get(String shopperReference) {
        return tokensByShopperReference.get(shopperReference);
    }

    public void remove(String shopperReference) {
        tokensByShopperReference.remove(shopperReference);
    }
}
