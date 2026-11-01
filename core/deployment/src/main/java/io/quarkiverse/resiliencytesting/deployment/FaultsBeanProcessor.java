package io.quarkiverse.resiliencytesting.deployment;

import java.util.List;

import jakarta.inject.Singleton;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.ClassType;
import org.jboss.jandex.ParameterizedType;

import io.quarkiverse.resiliencytesting.Faults;
import io.quarkiverse.resiliencytesting.runtime.internal.FaultsRecorder;
import io.quarkiverse.resiliencytesting.spi.BeanTargetResolver;
import io.quarkiverse.resiliencytesting.spi.ConsequenceCleaner;
import io.quarkiverse.resiliencytesting.spi.EventLoopWatcher;
import io.quarkiverse.resiliencytesting.spi.PoolResolver;
import io.quarkus.arc.All;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;

/**
 * The {@link Faults} bean, apart from the other build steps because it exists whether or not fault injection is
 * on: a test that injects it without the profile gets a message saying how to turn it on, rather than an
 * unsatisfied dependency.
 */
@BuildSteps(onlyIfNot = IsNormal.class)
class FaultsBeanProcessor {

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    SyntheticBeanBuildItem faultsBean(FaultsRecorder recorder, ShutdownContextBuildItem shutdown) {
        return SyntheticBeanBuildItem.configure(Faults.class)
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
                .createWith(recorder.faults(shutdown))
                // the after-each callback looks it up programmatically, which bean removal does not see
                .unremovable()
                .done();
    }
}
