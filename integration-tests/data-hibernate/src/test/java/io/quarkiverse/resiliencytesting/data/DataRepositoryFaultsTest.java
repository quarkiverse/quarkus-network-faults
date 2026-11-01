package io.quarkiverse.resiliencytesting.data;

import static io.restassured.RestAssured.when;
import static org.junit.jupiter.api.Assertions.assertEquals;

import javax.sql.DataSource;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.Test;

import io.quarkiverse.resiliencytesting.ActiveFault;
import io.quarkiverse.resiliencytesting.Faults;
import io.quarkiverse.resiliencytesting.junit.NetworkFaultsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.mutiny.sqlclient.Pool;

@QuarkusTest
@TestProfile(NetworkFaultsProfile.class)
public class DataRepositoryFaultsTest {

    @Inject
    Faults faults;

    @Test
    public void repositoriesResolveToTheirDatasource() {
        String datasource = faults.target(DataSource.class).name();
        assertEquals(datasource, faults.target(Person.Repository.class).name());
        assertEquals(datasource, faults.target(People.class).name());
        assertEquals(datasource, faults.target(EntityManager.class).name());
    }

    @Test
    public void reactiveRepositoriesResolveToTheReactiveConnectionOfTheirDatasource() {
        // the default unit exists twice, blocking and reactive: each repository is on the one it uses
        String reactive = faults.target(Pool.class).name();
        assertEquals(reactive, faults.target(Person.ReactiveRepository.class).name());
        assertEquals(reactive, faults.target(ReactivePeople.class).name());
        assertEquals(reactive, faults.target(Mutiny.SessionFactory.class).name());
    }

    @Test
    public void databaseUnavailableThroughTheEntityManager() {
        when()
                .get("/people/count/entity-manager")
                .then()
                .statusCode(200);

        try (ActiveFault f = faults.target(EntityManager.class).unavailable()) {
            when()
                    .get("/people/count/entity-manager")
                    .then()
                    .statusCode(500);
        }

        when()
                .get("/people/count/entity-manager")
                .then()
                .statusCode(200);
    }

    @Test
    public void databaseUnavailableThroughTheQuarkusDataRepository() {
        when()
                .post("/people?name=a")
                .then()
                .statusCode(200);

        try (ActiveFault f = faults.target(Person.Repository.class).unavailable()) {
            when()
                    .get("/people/count")
                    .then()
                    .statusCode(500);
        }

        when()
                .get("/people/count")
                .then()
                .statusCode(200);
    }

    @Test
    public void databaseDiesMidRequestThroughTheJakartaDataRepository() {
        when()
                .get("/people/count/jakarta-data")
                .then()
                .statusCode(200);

        try (ActiveFault f = faults.target(People.class).diesMidRequest()) {
            when()
                    .get("/people/count/jakarta-data")
                    .then()
                    .statusCode(500);
        }

        when()
                .get("/people/count/jakarta-data")
                .then()
                .statusCode(200);
    }
}
