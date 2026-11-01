package io.quarkiverse.resiliencytesting.langchain4j.runtime.internal;

import static io.quarkiverse.resiliencytesting.langchain4j.runtime.internal.AiModelTargets.DEFAULT_NAME;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.quarkiverse.langchain4j.EmbeddingStoreName;
import io.quarkiverse.resiliencytesting.datasource.DatasourceTargets;
import io.quarkiverse.resiliencytesting.spi.BeanTargetResolver;
import io.quarkiverse.resiliencytesting.spi.TargetBean;
import io.quarkiverse.resiliencytesting.spi.TargetDescriptor;
import io.quarkiverse.resiliencytesting.spi.TargetName;
import io.quarkiverse.resiliencytesting.spi.UrlRewriter;

/**
 * The stores of Quarkus LangChain4j: the chat memory store and the embedding stores. Most are a thin layer over a
 * client another module already proxies, a Redis client, a MongoDB client, a datasource, so their beans resolve to
 * that client's target. Chroma has an address of its own, {@code quarkus.langchain4j.chroma[.<store>].url}, which
 * becomes {@code langchain4j:chroma:<store>} like a model's {@code base-url} does. The build step reports which
 * store extensions are present, by the features they register; which client a store uses is read from its
 * configuration when a test asks.
 */
public final class StoreTargets {

    /**
     * The chat memory store extensions, by the feature each registers.
     */
    public enum MemoryStore {
        REDIS("langchain4j-memory-store-redis", "redis", REDIS_FAMILY),
        MONGODB("langchain4j-memory-store-mongodb", "mongodb", MONGODB_FAMILY);

        final String feature;
        // the segment of its configuration, quarkus.langchain4j.memorystore.<segment>.client-name
        final String configSegment;
        // the family of the client's targets
        final String clientFamily;

        MemoryStore(String feature, String configSegment, String clientFamily) {
            this.feature = feature;
            this.configSegment = configSegment;
            this.clientFamily = clientFamily;
        }

        public static MemoryStore withFeature(String feature) {
            for (MemoryStore store : values()) {
                if (store.feature.equals(feature)) {
                    return store;
                }
            }
            return null;
        }
    }

    /**
     * The embedding store extensions this module knows, by the feature each registers.
     */
    public enum EmbeddingStoreKind {
        PGVECTOR("langchain4j-pgvector", "pgvector"),
        ORACLE("langchain4j-oracle", "oracle"),
        REDIS("langchain4j-redis", "redis"),
        CHROMA("langchain4j-chroma", "chroma");

        final String feature;
        // the provider segment of its configuration, quarkus.langchain4j.<provider>[.<store>].*
        final String provider;

        EmbeddingStoreKind(String feature, String provider) {
            this.feature = feature;
            this.provider = provider;
        }

        public static EmbeddingStoreKind withFeature(String feature) {
            for (EmbeddingStoreKind kind : values()) {
                if (kind.feature.equals(feature)) {
                    return kind;
                }
            }
            return null;
        }
    }

    // The families the Redis and MongoDB modules name their clients with. This module cannot depend on those modules
    // without bringing their clients into every LangChain4j application, so the names are repeated here; the
    // integration tests check that a store and the client behind it resolve to the same target.
    private static final String REDIS_FAMILY = "redis";
    private static final String MONGODB_FAMILY = "mongodb";

    private StoreTargets() {
    }

    /**
     * The targets of the Chroma stores: the default store's {@code url} and, as a template, the {@code url} of
     * every named store.
     */
    public static List<TargetDescriptor> chromaDescriptors() {
        String provider = EmbeddingStoreKind.CHROMA.provider;
        return List.of(
                new TargetDescriptor(AiModelTargets.property(provider, DEFAULT_NAME, "url"), UrlRewriter.class,
                        AiModelTargets.targetName(provider, DEFAULT_NAME)),
                new TargetDescriptor(AiModelTargets.property(provider, "{store}", "url"), UrlRewriter.class,
                        AiModelTargets.targetName(provider, "{store}")));
    }

    /**
     * A {@code ChatMemoryStore} bean is the Redis or MongoDB client the memory store extension present uses; an
     * {@code EmbeddingStore} bean, the default one or the one its {@code @EmbeddingStoreName} names, is the
     * datasource or Redis client its store extension uses, or for Chroma the store's own URL.
     */
    public static final class Resolver implements BeanTargetResolver {

        // the memory store extension present, if any; two cannot be, both would provide the default bean
        private final MemoryStore memoryStore;
        private final List<EmbeddingStoreKind> embeddingStores;

        public Resolver(MemoryStore memoryStore, List<EmbeddingStoreKind> embeddingStores) {
            this.memoryStore = memoryStore;
            this.embeddingStores = List.copyOf(embeddingStores);
        }

        @Override
        public Optional<TargetName> resolve(TargetBean bean) {
            if (bean.hasType(ChatMemoryStore.class)) {
                return Optional.of(memoryStore());
            }
            if (bean.hasType(EmbeddingStore.class)) {
                return Optional.of(embeddingStore(bean));
            }
            return Optional.empty();
        }

        private TargetName memoryStore() {
            if (memoryStore == null) {
                throw new IllegalArgumentException("The ChatMemoryStore keeps its messages in memory: neither the "
                        + "Redis nor the MongoDB memory store extension of Quarkus LangChain4j is present");
            }
            // the store extension picks the default client when no name is configured
            String client = config("quarkus.langchain4j.memorystore." + memoryStore.configSegment + ".client-name")
                    .orElse(DEFAULT_NAME);
            return TargetName.of(memoryStore.clientFamily, client);
        }

        private TargetName embeddingStore(TargetBean bean) {
            Optional<EmbeddingStoreName> qualifier = bean.qualifier(EmbeddingStoreName.class);
            String store = qualifier.isPresent() && !qualifier.get().value().isBlank() ? qualifier.get().value()
                    : DEFAULT_NAME;
            EmbeddingStoreKind kind = kindOf(store);
            String provider = kind.provider;
            return switch (kind) {
                // the store extension picks the default datasource or client when none is configured
                case PGVECTOR, ORACLE -> DatasourceTargets.jdbc(
                        config(AiModelTargets.property(provider, store, "datasource")).orElse(DEFAULT_NAME));
                case REDIS -> TargetName.of(REDIS_FAMILY,
                        config(AiModelTargets.property(provider, store, "client-name")).orElse(DEFAULT_NAME));
                // a named Chroma store without a url of its own talks to the default store's URL
                case CHROMA -> AiModelTargets.targetName(provider,
                        config(AiModelTargets.property(provider, store, "url")).isPresent() ? store : DEFAULT_NAME);
            };
        }

        /**
         * The one store extension present that provides the store of this name.
         */
        private EmbeddingStoreKind kindOf(String store) {
            String what = DEFAULT_NAME.equals(store) ? "the default embedding store"
                    : "the embedding store named '" + store + "'";
            List<EmbeddingStoreKind> providers = new ArrayList<>();
            for (EmbeddingStoreKind kind : embeddingStores) {
                if (provides(kind, store)) {
                    providers.add(kind);
                }
            }
            if (providers.size() == 1) {
                return providers.getFirst();
            }
            if (providers.isEmpty()) {
                throw new IllegalArgumentException("No embedding store extension present provides " + what
                        + "; present: " + embeddingStores + ", known: " + List.of(EmbeddingStoreKind.values()));
            }
            throw new IllegalArgumentException("Several embedding store extensions provide " + what + ": " + providers
                    + "; address the datasource or client behind the store instead");
        }

        /**
         * A store extension provides the default store unless {@code default-store-enabled} is false, and a named
         * store when anything is configured under its name.
         */
        private static boolean provides(EmbeddingStoreKind kind, String store) {
            Config config = ConfigProvider.getConfig();
            if (DEFAULT_NAME.equals(store)) {
                return config.getOptionalValue(AiModelTargets.property(kind.provider, DEFAULT_NAME, "default-store-enabled"),
                        Boolean.class).orElse(true);
            }
            String prefix = AiModelTargets.property(kind.provider, store, "");
            for (String name : config.getPropertyNames()) {
                if (name.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }

        private static Optional<String> config(String property) {
            return ConfigProvider.getConfig().getOptionalValue(property, String.class);
        }
    }
}
