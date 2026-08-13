package org.fiware.consent.provider;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * Admin API to manage the provider registry at runtime (plan §11.8): create, read, update and
 * delete the {@code providerKey → TM Forum backend} mappings the facade routes on.
 *
 * <p>Only active when the {@link ProviderRegistry#PERSISTENT_PROPERTY persistent} registry is
 * selected (it writes through the {@link PersistentProviderRegistry}); in the static, config-only
 * mode this controller does not exist. It is separate from the consent-manager-facing API in
 * {@code api/consent-facade.yaml}. Runs on the blocking pool because the registry is JDBC-backed.
 */
@Controller("/providers")
@Requires(property = ProviderRegistry.PERSISTENT_PROPERTY, value = "true")
@ExecuteOn(TaskExecutors.BLOCKING)
@RequiredArgsConstructor
public class ProviderAdminController {

    private final PersistentProviderRegistry registry;

    /**
     * Lists all registered providers.
     *
     * @return the providers
     */
    @Get
    public List<ProviderRepresentation> list() {
        return registry.all().stream().map(ProviderRepresentation::from).toList();
    }

    /**
     * Retrieves a single provider.
     *
     * @param key the provider key
     * @return the provider, or {@code 404} if none is registered under {@code key}
     */
    @Get("/{key}")
    public HttpResponse<ProviderRepresentation> get(String key) {
        return registry.byKey(key)
                .map(ProviderRepresentation::from)
                .map(HttpResponse::ok)
                .orElseGet(HttpResponse::notFound);
    }

    /**
     * Registers a new provider.
     *
     * @param provider the provider to create (its {@code key} is used)
     * @return {@code 201} with the created provider, {@code 400} for an invalid provider, or
     *         {@code 409} if the key is already registered
     */
    @Post
    public HttpResponse<ProviderRepresentation> create(@Body ProviderRepresentation provider) {
        String validationError = validate(provider.key(), provider);
        if (validationError != null) {
            return HttpResponse.badRequest();
        }
        if (registry.exists(provider.key())) {
            return HttpResponse.status(io.micronaut.http.HttpStatus.CONFLICT);
        }
        ProviderConfig saved = registry.save(provider.toConfig(provider.key()));
        return HttpResponse.created(ProviderRepresentation.from(saved));
    }

    /**
     * Creates or updates the provider under the path key (the body's {@code key} is ignored).
     *
     * @param key      the provider key
     * @param provider the provider fields
     * @return {@code 200} with the persisted provider, or {@code 400} for an invalid provider
     */
    @Put("/{key}")
    public HttpResponse<ProviderRepresentation> upsert(String key, @Body ProviderRepresentation provider) {
        if (validate(key, provider) != null) {
            return HttpResponse.badRequest();
        }
        ProviderConfig saved = registry.save(provider.toConfig(key));
        return HttpResponse.ok(ProviderRepresentation.from(saved));
    }

    /**
     * Removes a provider.
     *
     * @param key the provider key
     * @return {@code 204} if removed, {@code 400} if it is the default provider, or {@code 404} if
     *         none is registered under {@code key}
     */
    @Delete("/{key}")
    public HttpResponse<Void> delete(String key) {
        if (ProviderRegistry.DEFAULT_PROVIDER_KEY.equals(key)) {
            // the default provider is the routing fallback and must always exist
            return HttpResponse.badRequest();
        }
        return registry.delete(key) ? HttpResponse.noContent() : HttpResponse.notFound();
    }

    /**
     * Validates a provider: the effective key must be present and free of the
     * {@link ProviderScopedId#SEPARATOR} (which would make its ids un-decodable), and the TM Forum
     * base url must be present.
     *
     * @return {@code null} if valid, otherwise a short reason
     */
    private static String validate(String key, ProviderRepresentation provider) {
        if (key == null || key.isBlank()) {
            return "the provider key is required";
        }
        if (key.contains(ProviderScopedId.SEPARATOR)) {
            return "the provider key must not contain '" + ProviderScopedId.SEPARATOR + "'";
        }
        if (provider.tmforumBaseUrl() == null || provider.tmforumBaseUrl().isBlank()) {
            return "the TM Forum base url is required";
        }
        return null;
    }
}
