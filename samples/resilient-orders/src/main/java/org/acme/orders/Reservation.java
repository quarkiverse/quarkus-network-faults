package org.acme.orders;

/**
 * What the inventory service answers to a reservation; a pending one is what the fallback answers when it does not.
 */
public record Reservation(String item, boolean pending) {

    static Reservation confirmed(String item) {
        return new Reservation(item, false);
    }

    static Reservation pending(String item) {
        return new Reservation(item, true);
    }
}
