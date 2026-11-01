package io.quarkiverse.resiliencytesting.kafka;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Incoming;

import io.smallrye.reactive.messaging.MutinyEmitter;

@ApplicationScoped
public class OrderEvents {

    private final MutinyEmitter<String> emitter;

    public OrderEvents(@Channel("orders-out") MutinyEmitter<String> emitter) {
        this.emitter = emitter;
    }

    private final List<String> received = new CopyOnWriteArrayList<>();

    public void publish(String event) {
        emitter.sendAndAwait(event);
    }

    @Incoming("orders-in")
    void consume(String event) {
        received.add(event);
    }

    public List<String> received() {
        return received;
    }
}
