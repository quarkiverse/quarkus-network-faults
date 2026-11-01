package io.quarkiverse.resiliencytesting.lc4j;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Timeout;

/**
 * The fault tolerance layer around each model call: bounded by a 1s timeout, protected by a breaker, with a
 * fallback that records why the model was skipped.
 */
@ApplicationScoped
public class Summaries {

    private final OpenAiSummarizer openai;
    private final OllamaSummarizer ollama;
    private final AnthropicSummarizer anthropic;
    private final DefaultSummarizer defaultModel;

    private volatile String lastFailure = "";

    public Summaries(OpenAiSummarizer openai, OllamaSummarizer ollama, AnthropicSummarizer anthropic,
            DefaultSummarizer defaultModel) {
        this.openai = openai;
        this.ollama = ollama;
        this.anthropic = anthropic;
        this.defaultModel = defaultModel;
    }

    public String lastFailure() {
        return lastFailure;
    }

    public void clearLastFailure() {
        lastFailure = "";
    }

    @Timeout(1000)
    @CircuitBreaker(requestVolumeThreshold = 2, failureRatio = 1.0, delay = 60_000)
    @Fallback(fallbackMethod = "unavailable")
    public String summarize(String provider, String text) {
        return switch (provider) {
            case "openai" -> openai.summarize(text);
            case "ollama" -> ollama.summarize(text);
            case "anthropic" -> anthropic.summarize(text);
            case "default" -> defaultModel.summarize(text);
            default -> throw new IllegalArgumentException("unknown provider " + provider);
        };
    }

    String unavailable(String provider, String text, Throwable cause) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = cause; c != null && c.getCause() != c; c = c.getCause()) {
            sb.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage()).append(" <- ");
        }
        lastFailure = sb.toString();
        return null;
    }
}
