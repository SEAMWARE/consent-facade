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
 * selected. The registry runs on {@link Dialect#POSTGRES PostgreSQL}; the {@code provider} table is
 * created by the Flyway migration ({@code db/migration/V1__create_provider_table.sql}), not by
 * {@code schema-generate} (which is not idempotent across restarts). Tests exercise the same
 * migration against H2 in PostgreSQL-compatibility mode. Only the standard {@link CrudRepository}
 * operations are used.
 */
@Requires(property = ProviderRegistry.PERSISTENT_PROPERTY, value = "true")
@JdbcRepository(dialect = Dialect.POSTGRES)
public interface ProviderRepository extends CrudRepository<ProviderEntity, String> {
}
