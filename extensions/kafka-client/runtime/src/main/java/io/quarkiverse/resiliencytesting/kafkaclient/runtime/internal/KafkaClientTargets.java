package io.quarkiverse.resiliencytesting.kafkaclient.runtime.internal;

import java.util.Optional;

import jakarta.inject.Singleton;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.Producer;

import io.quarkiverse.resiliencytesting.spi.BeanTargetResolver;
import io.quarkiverse.resiliencytesting.spi.TargetBean;
import io.quarkiverse.resiliencytesting.spi.TargetDescriptor;
import io.quarkiverse.resiliencytesting.spi.TargetName;

/**
 * Kafka targets: one per bootstrap server of {@code kafka.bootstrap.servers}, {@code kafka:<host>:<port>}.
 */
public final class KafkaClientTargets {

    private static final String FAMILY = "kafka";
    /**
     * Every broker.
     */
    public static final TargetName ALL_BROKERS = TargetName.of(FAMILY);
    /**
     * One target per broker, named by its address.
     */
    public static final TargetName NAME = TargetName.of(FAMILY, "{host}", "{port}");

    public static final TargetDescriptor DESCRIPTOR = new TargetDescriptor("kafka.bootstrap.servers",
            KafkaBootstrapRewriter.class, NAME);

    private KafkaClientTargets() {
    }

    /**
     * A producer, consumer or admin client resolves to every broker: it connects to whichever the cluster names.
     */
    @Singleton
    public static final class Resolver implements BeanTargetResolver {
        @Override
        public Optional<TargetName> resolve(TargetBean bean) {
            boolean kafka = bean.hasType(Producer.class) || bean.hasType(Consumer.class) || bean.hasType(Admin.class);
            return kafka ? Optional.of(ALL_BROKERS) : Optional.empty();
        }
    }
}
