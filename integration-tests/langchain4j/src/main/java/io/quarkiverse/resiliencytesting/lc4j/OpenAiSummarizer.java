package io.quarkiverse.resiliencytesting.lc4j;

import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService(modelName = "m-openai")
public interface OpenAiSummarizer {

    @UserMessage("Summarize in one sentence: {text}")
    String summarize(String text);
}
