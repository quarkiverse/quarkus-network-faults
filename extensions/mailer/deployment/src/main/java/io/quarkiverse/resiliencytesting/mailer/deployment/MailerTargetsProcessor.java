package io.quarkiverse.resiliencytesting.mailer.deployment;

import java.util.Set;
import java.util.TreeSet;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.DotName;

import io.quarkiverse.resiliencytesting.deployment.spi.NetworkFaultDefaultValueBuildItem;
import io.quarkiverse.resiliencytesting.deployment.spi.NetworkFaultTargetBuildItem;
import io.quarkiverse.resiliencytesting.deployment.spi.NetworkFaultsEnabled;
import io.quarkiverse.resiliencytesting.mailer.runtime.internal.MailerTargets;
import io.quarkiverse.resiliencytesting.spi.TargetDescriptor;
import io.quarkus.arc.deployment.BeanDiscoveryFinishedBuildItem;
import io.quarkus.arc.processor.InjectionPointInfo;
import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.mailer.MailerName;
import io.quarkus.mailer.runtime.Mailers;
import io.vertx.ext.mail.MailConfig;

/**
 * One target for the default mailer and one per mailer an injection point names with {@code @MailerName}, which is
 * how the Mailer extension finds them too; the build item it keeps them in is not public.
 */
@BuildSteps(onlyIf = NetworkFaultsEnabled.class, onlyIfNot = IsNormal.class)
class MailerTargetsProcessor {

    private static final DotName MAILER_NAME = DotName.createSimple(MailerName.class);

    @BuildStep
    void targets(BeanDiscoveryFinishedBuildItem beans, BuildProducer<NetworkFaultTargetBuildItem> targets,
            BuildProducer<NetworkFaultDefaultValueBuildItem> defaults) {
        Set<String> mailers = new TreeSet<>();
        mailers.add(Mailers.DEFAULT_MAILER_NAME);
        for (InjectionPointInfo injectionPoint : beans.getInjectionPoints()) {
            AnnotationInstance qualifier = injectionPoint.getRequiredQualifier(MAILER_NAME);
            if (qualifier != null) {
                mailers.add(qualifier.value().asString());
            }
        }
        for (String mailer : mailers) {
            for (TargetDescriptor descriptor : MailerTargets.descriptors(mailer)) {
                targets.produce(new NetworkFaultTargetBuildItem(descriptor));
            }
            // the mailer leaves an unset port to Vert.x, and an unset property is not proxied: supply what Vert.x uses
            defaults.produce(new NetworkFaultDefaultValueBuildItem(MailerTargets.property(mailer, "port"),
                    Integer.toString(MailConfig.DEFAULT_PORT)));
        }
    }
}
