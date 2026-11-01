package io.quarkiverse.resiliencytesting.lc4j;

import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * On the unnamed default chat model.
 */
@RegisterAiService
public interface DefaultSummarizer {

    @UserMessage("Summarize in one sentence: {text}")
    String summarize(String text);
}
