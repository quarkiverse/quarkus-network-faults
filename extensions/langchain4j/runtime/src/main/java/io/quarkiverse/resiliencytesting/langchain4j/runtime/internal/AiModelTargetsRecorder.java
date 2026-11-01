package io.quarkiverse.resiliencytesting.langchain4j.runtime.internal;

import java.util.List;
import java.util.Map;

import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import io.quarkus.runtime.annotations.StaticInit;

@Recorder
public class AiModelTargetsRecorder {

    /**
     * The resolver bean, wrapped since a final class cannot be proxied, made with what the build found: the provider of each
     * model, the model of each AI service.
     */
    @StaticInit
    public RuntimeValue<AiModelTargets.Resolver> resolver(Map<String, Map<String, String>> modelProviders,
            Map<String, String> aiServiceChatModels) {
        return new RuntimeValue<>(new AiModelTargets.Resolver(modelProviders, aiServiceChatModels));
    }

    /**
     * The resolver of the store beans, made with the store extensions the build found present.
     */
    @StaticInit
    public RuntimeValue<StoreTargets.Resolver> storeResolver(StoreTargets.MemoryStore memoryStore,
            List<StoreTargets.EmbeddingStoreKind> embeddingStores) {
        return new RuntimeValue<>(new StoreTargets.Resolver(memoryStore, embeddingStores));
    }
}
