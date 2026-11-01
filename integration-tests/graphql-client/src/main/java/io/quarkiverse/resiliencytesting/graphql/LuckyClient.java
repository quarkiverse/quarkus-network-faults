package io.quarkiverse.resiliencytesting.graphql;

import org.eclipse.microprofile.graphql.Query;

import io.smallrye.graphql.client.typesafe.api.GraphQLClientApi;

/**
 * The typesafe client, configured under {@code quarkus.smallrye-graphql-client.lucky.url}.
 */
@GraphQLClientApi(configKey = "lucky")
public interface LuckyClient {

    @Query
    int lucky();
}
