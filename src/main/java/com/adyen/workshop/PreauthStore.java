package com.adyen.workshop;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory store tracking the single most recent pre-authorised payment: its pspReference
 * (needed for /api/modify-amount, /api/capture, /api/cancel, /api/refund) and the currently
 * authorised amount. A real application would persist this in a database instead.
 *
 * README_PREAUTHORISATION.md: "For this particular exercise, you can just manually remember
 * pspReference and enter it in the subsequent API call."
 */
@Component
public class PreauthStore {
    public record Preauthorisation(String pspReference, String reference, long amountValue, String currency, String status) {
    }

    private final AtomicReference<Preauthorisation> current = new AtomicReference<>();

    public void store(String pspReference, String reference, long amountValue, String currency) {
        current.set(new Preauthorisation(pspReference, reference, amountValue, currency, "AUTHORISED"));
    }

    public Preauthorisation get() {
        return current.get();
    }

    public void updateAmount(long newAmountValue) {
        current.updateAndGet(p -> p == null ? null : new Preauthorisation(p.pspReference(), p.reference(), newAmountValue, p.currency(), p.status()));
    }

    public void updateStatus(String newStatus) {
        current.updateAndGet(p -> p == null ? null : new Preauthorisation(p.pspReference(), p.reference(), p.amountValue(), p.currency(), newStatus));
    }

    public void clear() {
        current.set(null);
    }
}
