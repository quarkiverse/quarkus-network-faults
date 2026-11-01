package org.acme.orders;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.faulttolerance.Fallback;

@ApplicationScoped
public class Summaries {

    @Inject
    Summarizer summarizer;

    /**
     * A summary, or null when the model could not provide one, after the retry its client makes on its own.
     */
    @Fallback(fallbackMethod = "unavailable")
    public String summarize(String text) {
        return summarizer.summarize(text);
    }

    String unavailable(String text) {
        return null; // the endpoint answers 503 with a Retry-After header
    }
}
