package org.acme.orders;

import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService(modelName = "assistant")
public interface Summarizer {

    @UserMessage("Summarize in one sentence: {text}")
    String summarize(String text);
}
