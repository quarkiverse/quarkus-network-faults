package io.quarkiverse.resiliencytesting.data;

import jakarta.data.repository.Find;
import jakarta.data.repository.Repository;

import org.hibernate.reactive.mutiny.Mutiny;

import io.smallrye.mutiny.Uni;

/**
 * A reactive Jakarta Data repository: its Mutiny session getter puts it on the reactive unit of the default name.
 */
@Repository
public interface ReactivePeople {

    Mutiny.StatelessSession session();

    @Find
    Uni<Person> byId(Long id);
}
