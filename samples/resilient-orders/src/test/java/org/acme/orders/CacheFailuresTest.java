package org.acme.orders;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.networkfaults.NetworkFaults;
import io.quarkiverse.networkfaults.junit.NetworkFaultsProfile;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
class CacheFailuresTest {

    @Inject
    NetworkFaults faults;

    @Test
    void cacheHangs() {
        try (var ignored = faults.target(RedisDataSource.class).hangs()) {
            when()
                    .get("/prices/apple")
                    .then()
                    .statusCode(200)
                    .body(is("42"))
                    .time(lessThan(1000L));
        }
    }
}
