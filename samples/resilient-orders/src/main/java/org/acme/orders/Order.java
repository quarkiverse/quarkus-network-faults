package org.acme.orders;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import io.quarkus.data.hibernate.ManagedEntity;
import io.quarkus.data.hibernate.ManagedRepository;

/**
 * An order that was placed, {@code CONFIRMED} when its stock was reserved, {@code PENDING} when the reservation was
 * deferred because the inventory service could not answer in time.
 */
@Entity
@Table(name = "orders")
public class Order extends ManagedEntity {

    public String item;

    public Instant placedAt;

    public String status;

    public interface Repository extends ManagedRepository<Order> {
    }
}
