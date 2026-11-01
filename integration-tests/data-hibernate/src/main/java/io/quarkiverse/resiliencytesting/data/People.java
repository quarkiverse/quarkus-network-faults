package io.quarkiverse.resiliencytesting.data;

import jakarta.data.repository.BasicRepository;
import jakarta.data.repository.Repository;

/**
 * A plain Jakarta Data repository on the same entity, in the default persistence unit.
 */
@Repository
public interface People extends BasicRepository<Person, Long> {
}
