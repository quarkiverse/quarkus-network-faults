package io.quarkiverse.resiliencytesting.langchain4j.runtime.internal;

import java.util.Map;
import java.util.Optional;

import dev.langchain4j.model.audio.AudioTranscriptionModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.image.ImageModel;
import dev.langchain4j.model.moderation.ModerationModel;
import dev.langchain4j.model.scoring.ScoringModel;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkiverse.langchain4j.runtime.NamedConfigUtil;
import io.quarkiverse.resiliencytesting.spi.BeanTargetResolver;
import io.quarkiverse.resiliencytesting.spi.TargetBean;
import io.quarkiverse.resiliencytesting.spi.TargetDescriptor;
import io.quarkiverse.resiliencytesting.spi.TargetName;
import io.quarkiverse.resiliencytesting.spi.UrlRewriter;

/**
 * Quarkus LangChain4j model endpoints. The build step turns every model the extension selected into a target,
 * {@code langchain4j:<provider>:<name>}, {@code <default>} for a provider's unnamed model so that it is not a
 * prefix of the named ones, and {@code langchain4j:<provider>} covers every model of the provider. It hands the
 * resolver which provider serves each model and which model each AI service uses.
 */
public final class AiModelTargets {

    /**
     * The name Quarkus LangChain4j gives the unnamed model, store or client of a provider.
     */
    public static final String DEFAULT_NAME = NamedConfigUtil.DEFAULT_NAME;

    public enum Kind {
        CHAT,
        EMBEDDING,
        IMAGE,
        MODERATION,
        SCORING,
        AUDIO_TRANSCRIPTION
    }

    private AiModelTargets() {
    }

    /**
     * The target for the {@code base-url} of one model.
     */
    public static TargetDescriptor descriptor(String provider, String modelName) {
        return new TargetDescriptor(property(provider, modelName, "base-url"), UrlRewriter.class,
                targetName(provider, modelName));
    }

    /**
     * A property of one named thing under a provider's prefix, {@code quarkus.langchain4j.<provider>[.<name>].<attribute>}:
     * a model's {@code base-url}, an embedding store's {@code url} or {@code datasource}.
     */
    static String property(String provider, String name, String attribute) {
        return "quarkus.langchain4j." + provider
                + (DEFAULT_NAME.equals(name) ? "" : "." + TargetDescriptor.propertySegment(name))
                + "." + attribute;
    }

    static TargetName targetName(String provider, String name) {
        return TargetName.of("langchain4j", provider, name);
    }

    /**
     * An AI service interface resolves to the chat model its {@code @RegisterAiService} uses; a model bean
     * ({@code ChatModel}, {@code EmbeddingModel}, ...) to the model its {@code @ModelName} qualifier names.
     */
    public static final class Resolver implements BeanTargetResolver {

        // kind of model, then model name, to the provider that serves it
        private final Map<String, Map<String, String>> modelProviders;
        // AI service class name to the name of the chat model it uses
        private final Map<String, String> aiServiceChatModels;

        public Resolver(Map<String, Map<String, String>> modelProviders, Map<String, String> aiServiceChatModels) {
            this.modelProviders = Map.copyOf(modelProviders);
            this.aiServiceChatModels = Map.copyOf(aiServiceChatModels);
        }

        @Override
        public Optional<TargetName> resolve(TargetBean bean) {
            Optional<Class<?>> service = bean.typeAnnotatedWith(RegisterAiService.class);
            if (service.isPresent()) {
                String modelName = aiServiceChatModels.get(service.get().getName());
                if (modelName == null) {
                    throw new IllegalArgumentException(service.get().getName() + " is not a registered AI service; "
                            + "registered: " + aiServiceChatModels.keySet());
                }
                return Optional.of(model(Kind.CHAT, modelName, service.get().getName()));
            }
            Kind kind = kindOf(bean);
            if (kind == null) {
                return Optional.empty();
            }
            Optional<ModelName> qualifier = bean.qualifier(ModelName.class);
            String modelName = qualifier.isPresent() && !qualifier.get().value().isBlank() ? qualifier.get().value()
                    : DEFAULT_NAME;
            return Optional.of(model(kind, modelName, bean.toString()));
        }

        private TargetName model(Kind kind, String modelName, String what) {
            String provider = modelProviders.getOrDefault(kind.name(), Map.of()).get(modelName);
            if (provider == null) {
                throw new IllegalArgumentException("No " + kind.name().toLowerCase().replace('_', ' ')
                        + " model named '" + modelName + "' is configured for " + what + "; configured: "
                        + modelProviders);
            }
            return targetName(provider, modelName);
        }

        private static Kind kindOf(TargetBean bean) {
            if (bean.hasType(ChatModel.class) || bean.hasType(StreamingChatModel.class)) {
                return Kind.CHAT;
            }
            if (bean.hasType(EmbeddingModel.class)) {
                return Kind.EMBEDDING;
            }
            if (bean.hasType(ImageModel.class)) {
                return Kind.IMAGE;
            }
            if (bean.hasType(ModerationModel.class)) {
                return Kind.MODERATION;
            }
            if (bean.hasType(ScoringModel.class)) {
                return Kind.SCORING;
            }
            if (bean.hasType(AudioTranscriptionModel.class)) {
                return Kind.AUDIO_TRANSCRIPTION;
            }
            return null;
        }
    }
}
