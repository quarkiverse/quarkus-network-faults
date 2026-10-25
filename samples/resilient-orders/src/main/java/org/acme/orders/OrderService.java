package org.acme.orders;

import java.time.Instant;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import org.eclipse.microprofile.reactive.messaging.Channel;

import io.smallrye.reactive.messaging.MutinyEmitter;

@ApplicationScoped
public class OrderService {

    private final Order.Repository orders;
    private final MutinyEmitter<Long> events;

    public OrderService(Order.Repository orders, @Channel("order-events") MutinyEmitter<Long> events) {
        this.orders = orders;
        this.events = events;
    }

    /**
     * Saves an order and publishes an event for it, in one transaction: when Kafka does not acknowledge the event,
     * the transaction rolls back, so there is no order without its event.
     */
    @Transactional
    public Order place(String item, String status) {
        Order order = new Order();
        order.item = item;
        order.placedAt = Instant.now();
        order.status = status;
        orders.persistAndFlush(order);
        // no acknowledgement from Kafka, no order
        events.sendAndAwait(order.id);
        return order;
    }
}
