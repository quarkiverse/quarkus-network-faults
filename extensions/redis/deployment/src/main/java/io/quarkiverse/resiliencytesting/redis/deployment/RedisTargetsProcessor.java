package io.quarkiverse.resiliencytesting.redis.deployment;

import java.util.List;

import io.quarkiverse.resiliencytesting.deployment.spi.NetworkFaultTargetBuildItem;
import io.quarkiverse.resiliencytesting.deployment.spi.NetworkFaultsEnabled;
import io.quarkiverse.resiliencytesting.redis.runtime.internal.RedisTargets;
import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.redis.deployment.client.RequestedRedisClientBuildItem;

/**
 * One target per Redis client the application requested, from the Redis extension's own build items.
 */
@BuildSteps(onlyIf = NetworkFaultsEnabled.class, onlyIfNot = IsNormal.class)
class RedisTargetsProcessor {

    @BuildStep
    void targets(List<RequestedRedisClientBuildItem> clients, BuildProducer<NetworkFaultTargetBuildItem> targets) {
        for (RequestedRedisClientBuildItem client : clients) {
            targets.produce(new NetworkFaultTargetBuildItem(RedisTargets.descriptor(client.name)));
        }
    }
}
