package io.quarkiverse.resiliencytesting.it;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class FailureLog {

    private volatile String last = "";

    public void record(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (!sb.isEmpty()) {
                sb.append(" <- ");
            }
            sb.append(c.getClass().getName()).append(": ").append(c.getMessage());
            if (c.getCause() == c) {
                break;
            }
        }
        last = sb.toString();
    }

    public String last() {
        return last;
    }

    public void clear() {
        last = "";
    }
}
