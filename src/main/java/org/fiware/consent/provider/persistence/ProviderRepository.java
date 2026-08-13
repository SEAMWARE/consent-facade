package org.fiware.consent.provider.persistence;

import io.micronaut.context.annotation.Requires;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;
import org.fiware.consent.provider.ProviderRegistry;

/**
 * Micronaut Data JDBC repository over the {@code provider} table backing the persistent provider
 * registry (plan §11.8).
 *
 * <p>Only present when the {@link ProviderRegistry#PERSISTENT_PROPERTY persistent} registry is
 * selected. The compile-time {@link Dialect#H2 H2} dialect matches the bundled default database;
 * a deployment on another database (e.g. PostgreSQL) rebuilds with its dialect. Only the standard
 * {@link CrudRepository} operations are used, whose SQL is dialect-portable.
 */
@Requires(property = ProviderRegistry.PERSISTENT_PROPERTY, value = "true")
@JdbcRepository(dialect = Dialect.H2)
public interface ProviderRepository extends CrudRepository<ProviderEntity, String> {
}
