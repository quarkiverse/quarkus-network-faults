package io.quarkiverse.resiliencytesting.graphqlclient.deployment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;

import io.quarkiverse.resiliencytesting.deployment.spi.NetworkFaultDefaultValueBuildItem;
import io.quarkiverse.resiliencytesting.deployment.spi.NetworkFaultTargetBuildItem;
import io.quarkiverse.resiliencytesting.deployment.spi.NetworkFaultsEnabled;
import io.quarkiverse.resiliencytesting.graphqlclient.runtime.internal.GraphQLClientTargets;
import io.quarkiverse.resiliencytesting.spi.TargetName;
import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.smallrye.graphql.client.GraphQLClient;
import io.smallrye.graphql.client.typesafe.api.GraphQLClientApi;

@BuildSteps(onlyIf = NetworkFaultsEnabled.class, onlyIfNot = IsNormal.class)
class GraphQLClientTargetsProcessor {

    private static final DotName GRAPHQL_CLIENT_API = DotName.createSimple(GraphQLClientApi.class);
    private static final DotName GRAPHQL_CLIENT = DotName.createSimple(GraphQLClient.class);

    @BuildStep
    void targets(CombinedIndexBuildItem index, BuildProducer<NetworkFaultTargetBuildItem> targets,
            BuildProducer<NetworkFaultDefaultValueBuildItem> defaults) {
        for (AnnotationInstance annotation : index.getIndex().getAnnotations(GRAPHQL_CLIENT_API)) {
            ClassInfo api = annotation.target().asClass();
            String configKey = value(annotation, "configKey");
            String simpleName = api.name().withoutPackagePrefix();
            TargetName name = GraphQLClientTargets.name(configKey != null ? configKey : simpleName);
            List<String> keys = new ArrayList<>();
            if (configKey != null) {
                keys.add(configKey);
            }
            keys.add(api.name().toString());
            keys.add(simpleName);
            for (String key : keys) {
                targets.produce(new NetworkFaultTargetBuildItem(GraphQLClientTargets.descriptor(key, name)));
            }
            String endpoint = value(annotation, "endpoint");
            if (endpoint != null) {
                defaults.produce(new NetworkFaultDefaultValueBuildItem(GraphQLClientTargets.property(keys.getFirst()),
                        endpoint));
            }
        }
        Set<String> dynamic = new LinkedHashSet<>();
        dynamic.add(GraphQLClientTargets.DEFAULT_DYNAMIC_CLIENT);
        for (AnnotationInstance annotation : index.getIndex().getAnnotations(GRAPHQL_CLIENT)) {
            dynamic.add(annotation.value().asString());
        }
        for (String key : dynamic) {
            targets.produce(new NetworkFaultTargetBuildItem(
                    GraphQLClientTargets.descriptor(key, GraphQLClientTargets.name(key))));
        }
    }

    /**
     * A non-blank string attribute of the annotation, or null.
     */
    private static String value(AnnotationInstance annotation, String attribute) {
        AnnotationValue value = annotation.value(attribute);
        if (value == null || value.asString().isBlank()) {
            return null;
        }
        return value.asString();
    }
}
