package io.quarkiverse.resiliencytesting.grpc.runtime.internal;

import java.util.List;
import java.util.Optional;

import jakarta.inject.Singleton;

import io.quarkiverse.resiliencytesting.spi.BeanTargetResolver;
import io.quarkiverse.resiliencytesting.spi.TargetBean;
import io.quarkiverse.resiliencytesting.spi.TargetDescriptor;
import io.quarkiverse.resiliencytesting.spi.TargetName;
import io.quarkus.grpc.GrpcClient;

/**
 * gRPC client targets, {@code grpc-client:<name>}. A client's address is split over {@code host}, {@code port}
 * and, in tests, {@code test-port}; the three properties share one target and one rewriter that reads the others.
 */
public final class GrpcClientTargets {

    private static final String FAMILY = "grpc-client";

    private GrpcClientTargets() {
    }

    public static TargetName name(String client) {
        return TargetName.of(FAMILY, client);
    }

    /**
     * The descriptors of one client the gRPC extension reported at build time: its host, port and test port.
     */
    public static List<TargetDescriptor> descriptors(String client) {
        String prefix = "quarkus.grpc.clients." + TargetDescriptor.propertySegment(client) + ".";
        TargetName name = name(client);
        return List.of(
                new TargetDescriptor(prefix + GrpcClientAddressRewriter.HOST, GrpcClientAddressRewriter.class, name),
                new TargetDescriptor(prefix + GrpcClientAddressRewriter.PORT, GrpcClientAddressRewriter.class, name),
                new TargetDescriptor(prefix + GrpcClientAddressRewriter.TEST_PORT, GrpcClientAddressRewriter.class,
                        name));
    }

    /**
     * A stub, Mutiny client or channel bean is the client its {@code @GrpcClient} qualifier names. The name has to
     * be spelled out: when the annotation takes it from the field name, the bean itself carries it but a class plus
     * qualifier does not, so pass {@code GrpcClient.Literal.of("name")}.
     */
    @Singleton
    public static final class Resolver implements BeanTargetResolver {
        @Override
        public Optional<TargetName> resolve(TargetBean bean) {
            Optional<GrpcClient> qualifier = bean.qualifier(GrpcClient.class);
            if (qualifier.isEmpty()) {
                return Optional.empty();
            }
            String client = qualifier.get().value();
            if (GrpcClient.ELEMENT_NAME.equals(client) || client.isBlank()) {
                throw new IllegalArgumentException("The gRPC client has no name of its own: pass "
                        + "GrpcClient.Literal.of(\"<name>\") with the type, or the injected Instance");
            }
            return Optional.of(name(client));
        }
    }
}
