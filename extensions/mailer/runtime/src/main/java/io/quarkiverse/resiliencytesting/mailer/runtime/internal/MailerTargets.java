package io.quarkiverse.resiliencytesting.mailer.runtime.internal;

import java.util.List;
import java.util.Optional;

import jakarta.inject.Singleton;

import io.quarkiverse.resiliencytesting.spi.BeanTargetResolver;
import io.quarkiverse.resiliencytesting.spi.TargetBean;
import io.quarkiverse.resiliencytesting.spi.TargetDescriptor;
import io.quarkiverse.resiliencytesting.spi.TargetName;
import io.quarkus.mailer.Mailer;
import io.quarkus.mailer.MailerName;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.quarkus.mailer.runtime.Mailers;

/**
 * The SMTP server of every mailer, named {@code mailer:<mailer name>}. A mailer's address is split over
 * {@code host} and {@code port}; the two properties share one target and one rewriter that reads the other.
 */
public final class MailerTargets {

    private static final String FAMILY = "mailer";

    private MailerTargets() {
    }

    /**
     * The descriptors of one mailer: its host and port.
     */
    public static List<TargetDescriptor> descriptors(String mailer) {
        TargetName name = name(mailer);
        return List.of(
                new TargetDescriptor(property(mailer, MailerAddressRewriter.HOST), MailerAddressRewriter.class, name),
                new TargetDescriptor(property(mailer, MailerAddressRewriter.PORT), MailerAddressRewriter.class, name));
    }

    /**
     * The property that sets one attribute of a mailer, {@code host} or {@code port}.
     */
    public static String property(String mailer, String attribute) {
        return Mailers.DEFAULT_MAILER_NAME.equals(mailer) ? "quarkus.mailer." + attribute
                : "quarkus.mailer." + TargetDescriptor.propertySegment(mailer) + "." + attribute;
    }

    static TargetName name(String mailer) {
        return TargetName.of(FAMILY, mailer);
    }

    /**
     * A mailer, reactive mailer or Vert.x mail client bean is the mailer its {@code @MailerName} qualifier names,
     * the default one without it.
     */
    @Singleton
    public static final class Resolver implements BeanTargetResolver {
        @Override
        public Optional<TargetName> resolve(TargetBean bean) {
            boolean mailer = bean.hasType(Mailer.class) || bean.hasType(ReactiveMailer.class)
                    || bean.hasType(io.vertx.ext.mail.MailClient.class)
                    || bean.hasType(io.vertx.mutiny.ext.mail.MailClient.class);
            if (!mailer) {
                return Optional.empty();
            }
            Optional<MailerName> qualifier = bean.qualifier(MailerName.class);
            String name = qualifier.isPresent() && !qualifier.get().value().isBlank() ? qualifier.get().value()
                    : Mailers.DEFAULT_MAILER_NAME;
            return Optional.of(name(name));
        }
    }
}
