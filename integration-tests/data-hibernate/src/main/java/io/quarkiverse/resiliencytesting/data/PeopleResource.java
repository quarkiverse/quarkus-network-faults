package io.quarkiverse.resiliencytesting.data;

import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

/**
 * Reads and writes people through both repositories and the entity manager; a datasource failure is a 500.
 */
@Path("/people")
public class PeopleResource {

    private final Person.Repository quarkusData;
    private final People jakartaData;
    private final EntityManager entityManager;

    public PeopleResource(Person.Repository quarkusData, People jakartaData, EntityManager entityManager) {
        this.quarkusData = quarkusData;
        this.jakartaData = jakartaData;
        this.entityManager = entityManager;
    }

    @POST
    @Transactional
    public long create(@QueryParam("name") String name) {
        Person person = new Person();
        person.name = name;
        quarkusData.persist(person);
        return quarkusData.count();
    }

    @GET
    @Path("/count")
    public long count() {
        return quarkusData.count();
    }

    @GET
    @Path("/count/entity-manager")
    public long countThroughTheEntityManager() {
        return entityManager.createQuery("select count(p) from Person p", Long.class).getSingleResult();
    }

    @GET
    @Path("/count/jakarta-data")
    public long countThroughJakartaData() {
        return jakartaData.findAll().count();
    }
}
