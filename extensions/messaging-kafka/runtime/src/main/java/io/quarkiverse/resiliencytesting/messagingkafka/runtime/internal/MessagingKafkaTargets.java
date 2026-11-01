package io.quarkiverse.resiliencytesting.messagingkafka.runtime.internal;

import java.util.List;
import java.util.Optional;

import jakarta.inject.Singleton;

import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

import io.quarkiverse.resiliencytesting.kafkaclient.runtime.internal.KafkaBootstrapRewriter;
import io.quarkiverse.resiliencytesting.kafkaclient.runtime.internal.KafkaClientTargets;
import io.quarkiverse.resiliencytesting.spi.BeanTargetResolver;
import io.quarkiverse.resiliencytesting.spi.TargetBean;
import io.quarkiverse.resiliencytesting.spi.TargetDescriptor;
import io.quarkiverse.resiliencytesting.spi.TargetName;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.kafka.KafkaClientService;

/**
 * The Kafka connector's bootstrap servers, per connector and per channel, as {@code kafka:<host>:<port>} targets.
 */
public final class MessagingKafkaTargets {

    private MessagingKafkaTargets() {
    }

    public static final List<TargetDescriptor> DESCRIPTORS = List.of(
            new TargetDescriptor("mp.messaging.connector.smallrye-kafka.bootstrap.servers",
                    KafkaBootstrapRewriter.class, KafkaClientTargets.NAME),
            new TargetDescriptor("mp.messaging.incoming.{channel}.bootstrap.servers", KafkaBootstrapRewriter.class,
                    KafkaClientTargets.NAME),
            new TargetDescriptor("mp.messaging.outgoing.{channel}.bootstrap.servers", KafkaBootstrapRewriter.class,
                    KafkaClientTargets.NAME));

    /**
     * An emitter (a {@code @Channel} is assumed to be a Kafka channel) or the {@code KafkaClientService} resolves
     * to every broker.
     */
    @Singleton
    public static final class Resolver implements BeanTargetResolver {
        @Override
        public Optional<TargetName> resolve(TargetBean bean) {
            boolean kafka = bean.hasType(Emitter.class) || bean.hasType(MutinyEmitter.class)
                    || bean.hasType(KafkaClientService.class) || bean.qualifier(Channel.class).isPresent();
            return kafka ? Optional.of(KafkaClientTargets.ALL_BROKERS) : Optional.empty();
        }
    }
}
