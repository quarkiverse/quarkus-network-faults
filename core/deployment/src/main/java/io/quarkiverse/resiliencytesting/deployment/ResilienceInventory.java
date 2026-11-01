package io.quarkiverse.resiliencytesting.deployment;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;

/**
 * Where an application believes it is protected: every method guarded by a MicroProfile Fault Tolerance annotation,
 * with the effective parameters as declared in the code, and the types its class injects that a test can pass to
 * {@code target(...)}.
 */
final class ResilienceInventory {

    // the MicroProfile Fault Tolerance specification defaults, and SmallRye Fault Tolerance's for @RateLimit
    private static final Map<DotName, Map<String, Object>> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put(DotName.createSimple("org.eclipse.microprofile.faulttolerance.Timeout"),
                parameters("value", 1000L, "unit", "MILLIS"));
        DEFAULTS.put(DotName.createSimple("org.eclipse.microprofile.faulttolerance.Retry"),
                parameters("maxRetries", 3, "delay", 0L, "delayUnit", "MILLIS", "maxDuration", 180000L,
                        "durationUnit", "MILLIS", "jitter", 200L, "jitterDelayUnit", "MILLIS",
                        "retryOn", List.of("java.lang.Exception"), "abortOn", List.of()));
        DEFAULTS.put(DotName.createSimple("org.eclipse.microprofile.faulttolerance.CircuitBreaker"),
                parameters("requestVolumeThreshold", 20, "failureRatio", 0.5, "delay", 5000L, "delayUnit", "MILLIS",
                        "successThreshold", 1, "failOn", List.of("java.lang.Throwable"), "skipOn", List.of()));
        DEFAULTS.put(DotName.createSimple("org.eclipse.microprofile.faulttolerance.Fallback"),
                parameters("fallbackMethod", "", "value", "", "applyOn", List.of("java.lang.Throwable"),
                        "skipOn", List.of()));
        DEFAULTS.put(DotName.createSimple("org.eclipse.microprofile.faulttolerance.Bulkhead"),
                parameters("value", 10, "waitingTaskQueue", 10));
        DEFAULTS.put(DotName.createSimple("io.smallrye.faulttolerance.api.RateLimit"),
                parameters("value", 100, "window", 1L, "windowUnit", "SECONDS", "minSpacing", 0L,
                        "minSpacingUnit", "SECONDS"));
    }

    private static final List<DotName> CLIENT_ANNOTATIONS = List.of(
            DotName.createSimple("org.eclipse.microprofile.rest.client.inject.RegisterRestClient"),
            DotName.createSimple("io.smallrye.graphql.client.typesafe.api.GraphQLClientApi"),
            DotName.createSimple("io.quarkiverse.langchain4j.RegisterAiService"));

    // the bean types the Targets reference lists
    private static final Set<String> TARGET_BEAN_TYPES = Set.of("DataSource", "Pool", "RedisDataSource",
            "ReactiveRedisDataSource", "MongoClient", "ReactiveMongoClient", "EntityManager", "SessionFactory",
            "ChatModel", "StreamingChatModel", "EmbeddingModel", "ChatMemoryStore", "EmbeddingStore", "McpClient",
            "KafkaClientService", "Emitter", "MutinyEmitter", "Mailer", "ReactiveMailer");

    private ResilienceInventory() {
    }

    /**
     * The inventory of an application.
     *
     * @param applicationIndex the index of the root archive, scanned for guarded methods
     * @param combinedIndex the index of every application archive, where an injected client interface may live when it
     *        sits in another module
     */
    static Map<String, Object> of(IndexView applicationIndex, IndexView combinedIndex) {
        // by name, so that the inventory reads the same from one build to the next
        Map<String, ClassInfo> guardedClasses = new TreeMap<>();
        for (DotName annotation : DEFAULTS.keySet()) {
            for (AnnotationInstance instance : applicationIndex.getAnnotations(annotation)) {
                ClassInfo declaring = switch (instance.target().kind()) {
                    case CLASS -> instance.target().asClass();
                    case METHOD -> instance.target().asMethod().declaringClass();
                    default -> null;
                };
                if (declaring != null) {
                    guardedClasses.put(declaring.name().toString(), declaring);
                }
            }
        }

        List<Map<String, Object>> guardedMethods = new ArrayList<>();
        for (ClassInfo guarded : guardedClasses.values()) {
            List<String> injects = injects(guarded, combinedIndex);
            for (MethodInfo method : guarded.methods()) {
                if (!isBusinessMethod(method)) {
                    continue;
                }
                Map<String, Object> annotations = new LinkedHashMap<>();
                for (Map.Entry<DotName, Map<String, Object>> entry : DEFAULTS.entrySet()) {
                    // one on the method wins over one on the class
                    AnnotationInstance instance = method.declaredAnnotation(entry.getKey());
                    if (instance == null) {
                        instance = guarded.declaredAnnotation(entry.getKey());
                    }
                    if (instance != null) {
                        annotations.put(entry.getKey().withoutPackagePrefix(), effective(entry.getValue(), instance));
                    }
                }
                if (!annotations.isEmpty()) {
                    Map<String, Object> guardedMethod = new LinkedHashMap<>();
                    guardedMethod.put("class", guarded.name().toString());
                    guardedMethod.put("method", method.name());
                    guardedMethod.put("annotations", annotations);
                    guardedMethod.put("injects", injects);
                    guardedMethods.add(guardedMethod);
                }
            }
        }

        List<String> notes = new ArrayList<>();
        if (guardedMethods.isEmpty()) {
            notes.add("No method of this application is guarded by a MicroProfile Fault Tolerance annotation.");
        } else {
            notes.add("Values are as declared in the code, over the specification defaults; MicroProfile Config can"
                    + " override them (for example <class>/<method>/Timeout/value), and such overrides are not"
                    + " reflected here.");
            notes.add("Only annotations declared on the class itself and on its methods are listed, not those"
                    + " inherited by a class from its superclass or interfaces.");
        }

        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("guardedMethods", guardedMethods);
        inventory.put("notes", notes);
        return inventory;
    }

    private static boolean isBusinessMethod(MethodInfo method) {
        return !Modifier.isStatic(method.flags()) && !Modifier.isPrivate(method.flags()) && !method.isSynthetic()
                && !method.isConstructor();
    }

    private static Map<String, Object> effective(Map<String, Object> defaults, AnnotationInstance instance) {
        Map<String, Object> parameters = new LinkedHashMap<>(defaults);
        for (AnnotationValue value : instance.values()) {
            parameters.put(value.name(), toData(value));
        }
        return parameters;
    }

    private static Object toData(AnnotationValue value) {
        return switch (value.kind()) {
            case ENUM -> value.asEnum();
            case CLASS -> value.asClass().name().toString();
            case ARRAY -> {
                List<Object> elements = new ArrayList<>();
                for (AnnotationValue element : value.asArrayList()) {
                    elements.add(toData(element));
                }
                yield elements;
            }
            default -> value.value();
        };
    }

    private static List<String> injects(ClassInfo guarded, IndexView combinedIndex) {
        List<Type> types = new ArrayList<>();
        for (FieldInfo field : guarded.fields()) {
            types.add(field.type());
        }
        for (MethodInfo constructor : guarded.constructors()) {
            types.addAll(constructor.parameterTypes());
        }
        Set<String> injects = new TreeSet<>();
        for (Type type : types) {
            if (isTargetable(type.name(), combinedIndex)) {
                injects.add(type.name().toString());
            }
        }
        return new ArrayList<>(injects);
    }

    private static boolean isTargetable(DotName type, IndexView combinedIndex) {
        if (TARGET_BEAN_TYPES.contains(type.withoutPackagePrefix())) {
            return true;
        }
        ClassInfo info = combinedIndex.getClassByName(type);
        if (info == null) {
            return false;
        }
        for (DotName annotation : CLIENT_ANNOTATIONS) {
            if (info.declaredAnnotation(annotation) != null) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> parameters(Object... namesAndValues) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            parameters.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        return parameters;
    }
}
