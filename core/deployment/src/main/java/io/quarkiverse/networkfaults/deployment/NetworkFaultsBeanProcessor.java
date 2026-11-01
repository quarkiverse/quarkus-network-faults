package io.quarkiverse.networkfaults.deployment;

import java.util.List;

import jakarta.inject.Singleton;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.ClassType;
import org.jboss.jandex.ParameterizedType;

import io.quarkiverse.networkfaults.NetworkFaults;
import io.quarkiverse.networkfaults.runtime.internal.NetworkFaultsRecorder;
import io.quarkiverse.networkfaults.spi.BeanTargetResolver;
import io.quarkiverse.networkfaults.spi.ConsequenceCleaner;
import io.quarkiverse.networkfaults.spi.EventLoopWatcher;
import io.quarkiverse.networkfaults.spi.PoolResolver;
import io.quarkus.arc.All;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;

/**
 * The {@link NetworkFaults} bean, apart from the other build steps because it exists whether or not fault injection is
 * on: a test that injects it without the profile gets a message saying how to turn it on, rather than an
 * unsatisfied dependency.
 */
@BuildSteps(onlyIfNot = IsNormal.class)
class NetworkFaultsBeanProcessor {

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    SyntheticBeanBuildItem networkFaultsBean(NetworkFaultsRecorder recorder, ShutdownContextBuildItem shutdown) {
        return SyntheticBeanBuildItem.configure(NetworkFaults.class)
                .scope(Singleton.class)
                .setRuntimeInit()
                // every resolver, cleaner, pool and event-loop watcher in the application, whichever module brought it
                .addInjectionPoint(ParameterizedType.create(List.class, ClassType.create(BeanTargetResolver.class)),
                        AnnotationInstance.builder(All.class).build())
                .addInjectionPoint(ParameterizedType.create(List.class, ClassType.create(ConsequenceCleaner.class)),
                        AnnotationInstance.builder(All.class).build())
                .addInjectionPoint(ParameterizedType.create(List.class, ClassType.create(PoolResolver.class)),
                        AnnotationInstance.builder(All.class).build())
                .addInjectionPoint(ParameterizedType.create(List.class, ClassType.create(EventLoopWatcher.class)),
                        AnnotationInstance.builder(All.class).build())
                .createWith(recorder.networkFaults(shutdown))
                // the after-each callback looks it up programmatically, which bean removal does not see
                .unremovable()
                .done();
    }
}
