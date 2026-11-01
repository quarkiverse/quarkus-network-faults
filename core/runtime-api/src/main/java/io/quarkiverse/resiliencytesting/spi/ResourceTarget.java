package io.quarkiverse.resiliencytesting.spi;

/**
 * A bounded resource of the application that an extension knows how to use up.
 */
public interface ResourceTarget {

    /**
     * Takes every unit of the resource and holds it until {@link Exhaustion#release()}.
     *
     * @throws IllegalStateException if the resource could not be taken whole; nothing is held then
     */
    Exhaustion exhaust();
}
