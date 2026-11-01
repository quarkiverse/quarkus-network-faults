package io.quarkiverse.resiliencytesting.datahibernate.deployment;

import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.data.repository.Repository;
import jakarta.inject.Singleton;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;

import io.quarkiverse.resiliencytesting.datahibernate.runtime.internal.DataRepositoryTargets;
import io.quarkiverse.resiliencytesting.datahibernate.runtime.internal.DataRepositoryTargetsRecorder;
import io.quarkiverse.resiliencytesting.datasource.DatasourceTargets;
import io.quarkiverse.resiliencytesting.deployment.spi.NetworkFaultsEnabled;
import io.quarkiverse.resiliencytesting.spi.BeanTargetResolver;
import io.quarkiverse.resiliencytesting.spi.TargetName;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.data.hibernate.RepositorySwitcher;
import io.quarkus.data.hibernate.managed.reactive.ReactiveManagedRepositoryBase;
import io.quarkus.data.hibernate.stateless.reactive.ReactiveRecordRepositoryBase;
import io.quarkus.datasource.common.runtime.DataSourceUtil;
import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.util.JandexUtil;
import io.quarkus.hibernate.orm.deployment.PersistenceUnitDescriptorBuildItem;
import io.quarkus.hibernate.orm.runtime.PersistenceUnitUtil;

@BuildSteps(onlyIf = NetworkFaultsEnabled.class, onlyIfNot = IsNormal.class)
class DataRepositoryTargetsProcessor {

    private static final DotName REPOSITORY_SWITCHER = DotName.createSimple(RepositorySwitcher.class);
    // what a reactive Quarkus Data repository implements
    private static final List<DotName> REACTIVE_REPOSITORIES = List.of(
            DotName.createSimple(ReactiveManagedRepositoryBase.class),
            DotName.createSimple(ReactiveRecordRepositoryBase.class));
    private static final DotName JAKARTA_DATA_REPOSITORY = DotName.createSimple(Repository.class);
    // Hibernate's processor implements a Jakarta Data repository interface in a class of the interface's name plus this
    private static final String GENERATED_SUFFIX = "_";
    // what the session getter of a reactive Jakarta Data repository returns, possibly wrapped in a Uni; named, since
    // Hibernate Reactive is not there in a blocking application
    private static final Set<DotName> MUTINY_SESSIONS = Set.of(
            DotName.createSimple("org.hibernate.reactive.mutiny.Mutiny$Session"),
            DotName.createSimple("org.hibernate.reactive.mutiny.Mutiny$StatelessSession"));
    private static final DotName UNI = DotName.createSimple("io.smallrye.mutiny.Uni");
    private static final String QUARKUS_DATA_PACKAGE = "io.quarkus.data.";
    private static final String JAKARTA_DATA_PACKAGE = "jakarta.data.";

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    SyntheticBeanBuildItem resolver(CombinedIndexBuildItem index, List<PersistenceUnitDescriptorBuildItem> persistenceUnits,
            DataRepositoryTargetsRecorder recorder) {
        // A unit may exist twice under one name, blocking and reactive, with the same datasource and entities, so a
        // repository's unit is looked up by name and whether it is reactive is up to the repository.
        Map<String, String> datasourceByUnit = new HashMap<>();
        Set<String> blockingUnits = new HashSet<>();
        Set<String> reactiveUnits = new HashSet<>();
        Map<String, String> unitByEntity = new HashMap<>();
        for (PersistenceUnitDescriptorBuildItem unit : persistenceUnits) {
            String name = unit.getPersistenceUnitName();
            datasourceByUnit.put(name, unit.getConfig().getDataSource().orElse(DataSourceUtil.DEFAULT_DATASOURCE_NAME));
            (unit.isReactive() ? reactiveUnits : blockingUnits).add(name);
            for (String entity : unit.getManagedClassNames()) {
                unitByEntity.put(entity, name);
            }
        }
        IndexView view = index.getIndex();
        Set<DotName> reactiveRepositories = new HashSet<>();
        for (DotName reactive : REACTIVE_REPOSITORIES) {
            for (ClassInfo impl : view.getAllKnownImplementations(reactive)) {
                reactiveRepositories.add(impl.name());
            }
        }
        Map<String, TargetName> repositories = new HashMap<>();

        // Quarkus Data repositories: the generated implementation, its entity, the entity's persistence unit
        for (ClassInfo impl : view.getAllKnownImplementations(REPOSITORY_SWITCHER)) {
            if (impl.isInterface() || impl.isAbstract()) {
                continue;
            }
            List<Type> parameters = JandexUtil.resolveTypeParameters(impl.name(), REPOSITORY_SWITCHER, view);
            if (parameters.isEmpty() || parameters.getFirst().kind() != Type.Kind.CLASS) {
                continue;
            }
            String unit = unitByEntity.get(parameters.getFirst().name().toString());
            boolean reactive = reactiveRepositories.contains(impl.name());
            if (unit == null || !(reactive ? reactiveUnits : blockingUnits).contains(unit)) {
                continue;
            }
            TargetName target = DatasourceTargets.of(datasourceByUnit.get(unit), reactive);
            repositories.put(impl.name().toString(), target);
            for (DotName iface : impl.interfaceNames()) {
                if (isApplicationType(iface)) {
                    repositories.put(iface.toString(), target);
                }
            }
        }

        // Jakarta Data repositories: the interface, its dataStore names the persistence unit
        for (AnnotationInstance annotation : view.getAnnotations(JAKARTA_DATA_REPOSITORY)) {
            if (annotation.target().kind() != AnnotationTarget.Kind.CLASS) {
                continue;
            }
            ClassInfo api = annotation.target().asClass();
            AnnotationValue dataStore = annotation.value("dataStore");
            String unit = dataStore == null || dataStore.asString().isBlank()
                    ? PersistenceUnitUtil.DEFAULT_PERSISTENCE_UNIT_NAME
                    : dataStore.asString();
            boolean reactive = hasMutinySessionGetter(api, view);
            if (!(reactive ? reactiveUnits : blockingUnits).contains(unit)) {
                continue;
            }
            TargetName target = DatasourceTargets.of(datasourceByUnit.get(unit), reactive);
            repositories.put(api.name().toString(), target);
            repositories.put(api.name() + GENERATED_SUFFIX, target);
        }
        return SyntheticBeanBuildItem.configure(DataRepositoryTargets.Resolver.class)
                .addType(BeanTargetResolver.class)
                .scope(Singleton.class)
                .runtimeValue(recorder.resolver(repositories))
                .done();
    }

    /**
     * Whether a Jakarta Data repository is reactive: Hibernate's processor gives it the session its getter returns,
     * a blocking one without a getter, so it is reactive when it, or an interface it extends, has a getter for a
     * Mutiny session.
     */
    private static boolean hasMutinySessionGetter(ClassInfo repository, IndexView view) {
        for (MethodInfo method : repository.methods()) {
            if (method.parametersCount() == 0 && Modifier.isAbstract(method.flags()) && isMutinySession(method.returnType())) {
                return true;
            }
        }
        for (DotName parent : repository.interfaceNames()) {
            ClassInfo info = view.getClassByName(parent);
            if (info != null && hasMutinySessionGetter(info, view)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMutinySession(Type type) {
        if (type.kind() == Type.Kind.PARAMETERIZED_TYPE && type.name().equals(UNI)) {
            return isMutinySession(type.asParameterizedType().arguments().getFirst());
        }
        return MUTINY_SESSIONS.contains(type.name());
    }

    private static boolean isApplicationType(DotName name) {
        String s = name.toString();
        return !s.startsWith(QUARKUS_DATA_PACKAGE) && !s.startsWith(JAKARTA_DATA_PACKAGE);
    }
}
