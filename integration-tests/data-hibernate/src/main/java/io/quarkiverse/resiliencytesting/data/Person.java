package io.quarkiverse.resiliencytesting.data;

import jakarta.persistence.Entity;

import io.quarkus.data.hibernate.ManagedEntity;
import io.quarkus.data.hibernate.ManagedRepository;

@Entity
public class Person extends ManagedEntity {

    public String name;

    /**
     * The Quarkus Data repository: an interface nested in the entity.
     */
    public interface Repository extends ManagedRepository<Person> {
    }

    /**
     * The reactive Quarkus Data repository, on the reactive unit of the same name.
     */
    public interface ReactiveRepository extends ManagedRepository.Reactive<Person> {
    }
}
