package io.quarkiverse.resiliencytesting.graphql;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.ActiveFault;
import io.quarkiverse.resiliencytesting.Faults;
import io.quarkiverse.resiliencytesting.Target;
import io.quarkiverse.resiliencytesting.junit.NetworkFaultsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.graphql.client.GraphQLClient;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;

/**
 * Both GraphQL clients call this application's own endpoint through the proxy. The typesafe client is addressed by
 * its interface; the dynamic one by its type plus the {@code @GraphQLClient} annotation, which is not a CDI
 * qualifier and so has no literal of its own.
 */
@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
public class GraphQLClientFaultsTest {

    @Inject
    Faults faults;

    /**
     * The dynamic client's name, as SmallRye ships no literal for the annotation.
     */
    private static GraphQLClient named(String name) {
        final class Literal extends AnnotationLiteral<GraphQLClient> implements GraphQLClient {
            @Override
            public String value() {
                return name;
            }
        }
        return new Literal();
    }

    @Test
    public void bothClientsShareTheConfiguredTarget() {
        Target typesafe = faults.target(LuckyClient.class);
        Target dynamic = faults.target(DynamicGraphQLClient.class, named("lucky"));
        assertEquals(typesafe.name(), dynamic.name());
    }

    @Test
    public void typesafeClientUnavailable() {
        when()
                .get("/lucky/typesafe")
                .then()
                .statusCode(200)
                .body(is("12"));

        try (ActiveFault f = faults.target(LuckyClient.class).unavailable()) {
            when()
                    .get("/lucky/typesafe")
                    .then()
                    .statusCode(503);
        }

        when()
                .get("/lucky/typesafe")
                .then()
                .statusCode(200)
                .body(is("12"));
    }

    @Test
    public void dynamicClientDiesMidRequest() {
        when()
                .get("/lucky/dynamic")
                .then()
                .statusCode(200)
                .body(is("12"));

        try (ActiveFault f = faults.target(DynamicGraphQLClient.class, named("lucky")).diesMidRequest()) {
            when()
                    .get("/lucky/dynamic")
                    .then()
                    .statusCode(503);
        }

        when()
                .get("/lucky/dynamic")
                .then()
                .statusCode(200)
                .body(is("12"));
    }

    @Test
    public void requestsAreCounted() {
        Target api = faults.target(LuckyClient.class);
        long before = api.traffic().httpRequestsSeen();

        when()
                .get("/lucky/typesafe")
                .then()
                .statusCode(200);

        assertEquals(1, api.traffic().httpRequestsSeen() - before, api.traffic().httpRequests().toString());
    }
}
