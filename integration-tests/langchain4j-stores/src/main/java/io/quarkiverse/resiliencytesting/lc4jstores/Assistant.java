package io.quarkiverse.resiliencytesting.lc4jstores;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * A conversation per session, remembered in the chat memory store.
 */
@RegisterAiService
public interface Assistant {

    String chat(@MemoryId String session, @UserMessage String message);
}
