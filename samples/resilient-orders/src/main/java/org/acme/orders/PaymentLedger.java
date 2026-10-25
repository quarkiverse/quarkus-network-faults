package org.acme.orders;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * The charges of the stand-in payment provider. Like real payment APIs, it honours idempotency keys: a charge with a
 * key it has already seen returns the original charge instead of charging again.
 */
@ApplicationScoped
public class PaymentLedger {

    private final Map<String, String> chargesByKey = new HashMap<>();
    private final List<String> charges = new ArrayList<>();

    public synchronized String charge(String idempotencyKey, String orderId) {
        return chargesByKey.computeIfAbsent(idempotencyKey, key -> {
            charges.add(orderId);
            return "charge " + charges.size();
        });
    }

    public synchronized long chargesFor(String orderId) {
        return charges.stream().filter(orderId::equals).count();
    }
}
