package io.quarkiverse.resiliencytesting.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.runtime.internal.FaultsImpl;
import io.quarkiverse.resiliencytesting.spi.BeanTargetResolver;
import io.quarkiverse.resiliencytesting.spi.EventLoopWatcher;
import io.quarkiverse.resiliencytesting.spi.Exhaustion;
import io.quarkiverse.resiliencytesting.spi.PoolResolver;
import io.quarkiverse.resiliencytesting.spi.ResourceTarget;
import io.quarkiverse.resiliencytesting.spi.TargetName;

class FaultsAfterEachCallbackTest {

    private static final TargetName POOL = TargetName.parse("datasource:<default>:jdbc");

    /**
     *
     * A pool nothing ever waits on, so exhausting it is a guard violation.
     *
     */
    private static final ResourceTarget UNUSED_POOL = new ResourceTarget() {
        @Override
        public Exhaustion exhaust() {
            return new Exhaustion() {
                @Override
                public void release() {
                }

                @Override
                public boolean watched() {
                    return true;
                }

                @Override
                public boolean contended() {
                    return false;
                }
            };
        }
    };

    /**
     *
     * The same pool, failing to give its connections back.
     *
     */
    private static final ResourceTarget FAILING_POOL = new ResourceTarget() {
        @Override
        public Exhaustion exhaust() {
            return new Exhaustion() {
                @Override
                public void release() {
                    throw new IllegalStateException("release failed");
                }

                @Override
                public boolean watched() {
                    return true;
                }

                @Override
                public boolean contended() {
                    return false;
                }
            };
        }
    };

    private static FaultsImpl faults(List<String> blocked) {
        return faults(blocked, UNUSED_POOL);
    }

    private static FaultsImpl faults(List<String> blocked, ResourceTarget resource) {
        BeanTargetResolver resolver = bean -> bean.hasType(DataSource.class) ? Optional.of(POOL) : Optional.empty();
        PoolResolver pool = target -> Optional.of(resource);
        EventLoopWatcher watcher = () -> () -> blocked;
        return new FaultsImpl(false, List.of(resolver), List.of(), List.of(pool), List.of(watcher));
    }

    @Test
    void aBlockedWatchAndAGuardViolationAreBothReportedAndNothingCarriesOver() {
        FaultsImpl faults = faults(List.of("loop-1 blocked"));
        faults.eventLoops().watched();
        faults.pool(DataSource.class).exhausted();

        AssertionError failure = assertThrows(AssertionError.class, () -> FaultsAfterEachCallback.verify(faults));

        assertTrue(failure.getMessage().contains("loop-1 blocked"), failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(failure.getSuppressed()[0].getMessage().startsWith("Fault had no effect:"),
                failure.getSuppressed()[0].getMessage());
        assertTrue(faults.takeGuardViolations().isEmpty());
    }

    @Test
    void aGuardViolationAloneIsReported() {
        FaultsImpl faults = faults(List.of());
        faults.pool(DataSource.class).exhausted();

        AssertionError failure = assertThrows(AssertionError.class, () -> FaultsAfterEachCallback.verify(faults));

        assertTrue(failure.getMessage().startsWith("Fault had no effect:"), failure.getMessage());
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test
    void anyFailureOfTheResetStillReportsTheGuardAndNothingCarriesOver() {
        FaultsImpl faults = faults(List.of(), FAILING_POOL);
        faults.pool(DataSource.class).exhausted();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> FaultsAfterEachCallback.verify(faults));

        assertEquals("release failed", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(failure.getSuppressed()[0].getMessage().startsWith("Fault had no effect:"),
                failure.getSuppressed()[0].getMessage());
        assertTrue(faults.takeGuardViolations().isEmpty());
    }
}
