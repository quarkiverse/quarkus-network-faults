package io.quarkiverse.networkfaults.runtime.internal;

import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkiverse.networkfaults.spi.Exhaustion;
import io.quarkiverse.networkfaults.spi.ResourceTarget;
import io.quarkus.runtime.ExecutorRecorder;
import io.quarkus.runtime.ThreadPoolConfig;
import io.smallrye.config.SmallRyeConfig;

/**
 * The worker pool Quarkus provides, the one blocking requests are dispatched to.
 */
final class WorkerPool implements ResourceTarget {

    static final String NAME = "worker-pool";

    @Override
    public Exhaustion exhaust() {
        ThreadPoolConfig config = ConfigProvider.getConfig().unwrap(SmallRyeConfig.class)
                .getConfigMapping(ThreadPoolConfig.class);
        return WorkerPoolExhaustion.take(NAME, ExecutorRecorder.getCurrent(), ExecutorRecorder.getMaxSize(config));
    }
}
