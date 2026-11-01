package io.quarkiverse.resiliencytesting.lc4j;

import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService(modelName = "m-anthropic")
public interface AnthropicSummarizer {

    @UserMessage("Summarize in one sentence: {text}")
    String summarize(String text);
}
