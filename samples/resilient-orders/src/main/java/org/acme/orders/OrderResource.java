package org.acme.orders;

import java.util.UUID;

import jakarta.persistence.PersistenceException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.rest.client.inject.RestClient;

import io.smallrye.mutiny.Multi;

@Path("/orders")
public class OrderResource {

    private final InventoryService reservations;
    private final InventoryClient inventory;
    private final OrderService orders;
    private final PaymentService payments;
    private final Order.Repository orderRepository;
    private final ShippingQuotes quotes;

    public OrderResource(InventoryService reservations, @RestClient InventoryClient inventory, OrderService orders,
            PaymentService payments, Order.Repository orderRepository, ShippingQuotes quotes) {
        this.reservations = reservations;
        this.inventory = inventory;
        this.orders = orders;
        this.payments = payments;
        this.orderRepository = orderRepository;
        this.quotes = quotes;
    }

    /**
     * Places an order: {@code 201} when the stock was reserved, {@code 202} when the reservation was deferred, and
     * {@code 503} when the order could not be saved together with its event.
     */
    @POST
    public Response place(@QueryParam("item") String item) {
        boolean pending = reservations.reserve(item).pending();
        String status = pending ? "PENDING" : "CONFIRMED";
        try {
            Order order = orders.place(item, status);
            return Response.status(pending ? 202 : 201).entity(order).build();
        } catch (RuntimeException e) {
            return Response.status(503).entity("try again later").build();
        }
    }

    @POST
    @Path("/retrying")
    public Response placeWithRetry(@QueryParam("item") String item) {
        reservations.reserveWithRetry(item);
        return Response.status(201).entity(orders.place(item, "CONFIRMED")).build();
    }

    /**
     * A database that cannot answer is an outage the caller should see as such, not as a stack trace.
     */
    @GET
    public Response list() {
        try {
            return Response.ok(orderRepository.listAll()).build();
        } catch (PersistenceException e) {
            return Response.status(503).entity("orders unavailable").build();
        }
    }

    /**
     * Relays the inventory's stream; a stream the inventory cuts short ends with a last event saying so, rather than
     * cutting the caller's stream too.
     */
    @GET
    @Path("/stock-updates")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<String> stockUpdates() {
        return inventory.stockUpdates()
                .onFailure().recoverWithItem("stream ended early");
    }

    @GET
    @Path("/{id}/shipping-quote")
    public long shippingQuote(@PathParam("id") long id) {
        return quotes.quote(id);
    }

    @POST
    @Path("/{id}/payment")
    public String pay(@PathParam("id") String id) {
        String idempotencyKey = UUID.randomUUID().toString(); // one key per payment, shared by every attempt
        return payments.charge(idempotencyKey, id);
    }
}
