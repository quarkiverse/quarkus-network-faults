package io.quarkiverse.resiliencytesting.lc4j;

import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.Multi;

@RegisterAiService(modelName = "m-openai")
public interface OpenAiStreamingSummarizer {

    @UserMessage("Summarize in one sentence: {text}")
    Multi<String> summarize(String text);
}
