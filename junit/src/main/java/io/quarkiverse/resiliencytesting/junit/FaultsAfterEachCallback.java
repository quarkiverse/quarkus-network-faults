package io.quarkiverse.resiliencytesting.junit;

import java.util.List;

import io.quarkiverse.resiliencytesting.Faults;
import io.quarkiverse.resiliencytesting.runtime.internal.FaultsImpl;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.test.junit.callback.QuarkusTestAfterEachCallback;
import io.quarkus.test.junit.callback.QuarkusTestMethodContext;

/**
 * After every test: end every fault still open, clean up the consequences of all of them, then fail the test if
 * the no-traffic guard recorded a violation.
 * <p>
 * The guard protects against a test that is green for the wrong reason. If a fault was active on a target and no
 * connection went through that target's proxy while it was, or, for a fault on some requests, none of those requests,
 * the fault cannot have affected the request under test, so the assertions that passed did not test the fault. That happens
 * when the application resolves the
 * dependency's address through a key the extension did not rewrite, when the wrong bean is named in
 * {@code target(...)}, or when a pooled connection was reused and never hit the proxy. The failure message names
 * the target and the configuration property it came from, which is where to look.
 */
public class FaultsAfterEachCallback implements QuarkusTestAfterEachCallback {

    @Override
    public void afterEach(QuarkusTestMethodContext context) {
        ArcContainer container = Arc.container();
        if (container == null) {
            return;
        }
        InstanceHandle<Faults> handle = container.instance(Faults.class);
        if (!handle.isAvailable()) {
            return;
        }
        // the extension's own synthetic bean, the only implementation there is
        FaultsImpl faults = (FaultsImpl) handle.get();
        verify(faults);
    }

    /**
     * Resets the faults, then takes the guard's verdicts whether or not that failed, so none carries into the next
     * test. A failed reset is thrown with the guard's failure attached as suppressed.
     */
    static void verify(FaultsImpl faults) {
        Throwable failure = null;
        try {
            faults.resetAll();
        } catch (Throwable e) {
            failure = e;
            throw e;
        } finally {
            List<String> violations = faults.takeGuardViolations();
            if (!violations.isEmpty()) {
                AssertionError guardFailure = new AssertionError(
                        "Fault had no effect:\n  " + String.join("\n  ", violations));
                if (failure == null) {
                    throw guardFailure;
                }
                failure.addSuppressed(guardFailure);
            }
        }
    }
}
