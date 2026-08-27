package org.fiware.consent.provider;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import lombok.RequiredArgsConstructor;
import org.fiware.consent.internal.api.ProvidersApi;
import org.fiware.consent.internal.model.ProviderVO;

import java.util.List;

/**
 * Admin API to manage the provider registry at runtime (plan §11.8): create, read, update and
 * delete the {@code providerKey → TM Forum backend} mappings the facade routes on.
 *
 * <p>Only active when the {@link ProviderRegistry#PERSISTENT_PROPERTY persistent} registry is
 * selected (it writes through the {@link PersistentProviderRegistry}); in the static, config-only
 * mode this controller does not exist. Runs on the blocking pool because the registry is JDBC-backed.
 *
 * <p>Part of the <strong>internal</strong> API ({@code api/consent-facade-internal.yaml}), not of
 * the consent-manager-facing contract in {@code api/consent-facade.yaml}: it must never be published
 * - it rewrites the backends the facade routes to. The paths come from the generated
 * {@link ProvidersApi}.
 */
@Controller("/")
@Requires(property = ProviderRegistry.PERSISTENT_PROPERTY, value = "true")
@ExecuteOn(TaskExecutors.BLOCKING)
@RequiredArgsConstructor
public class ProviderAdminController implements ProvidersApi {

    private final PersistentProviderRegistry registry;

    /** {@inheritDoc} */
    @Override
    public HttpResponse<List<ProviderVO>> listProviders() {
        return HttpResponse.ok(registry.all().stream().map(ProviderVoMapper::toVo).toList());
    }

    /** {@inheritDoc} */
    @Override
    public HttpResponse<ProviderVO> getProvider(String key) {
        return registry.byKey(key)
                .map(ProviderVoMapper::toVo)
                .map(HttpResponse::ok)
                .orElseGet(HttpResponse::notFound);
    }

    /** {@inheritDoc} */
    @Override
    public HttpResponse<ProviderVO> createProvider(ProviderVO providerVO) {
        String rejection = validate(providerVO.getKey(), providerVO);
        if (rejection != null) {
            return badRequest(rejection);
        }
        if (registry.exists(providerVO.getKey())) {
            return HttpResponse.status(HttpStatus.CONFLICT);
        }
        ProviderConfig saved = registry.save(ProviderVoMapper.toConfig(providerVO.getKey(), providerVO));
        return HttpResponse.created(ProviderVoMapper.toVo(saved));
    }

    /** {@inheritDoc} */
    @Override
    public HttpResponse<ProviderVO> upsertProvider(String key, ProviderVO providerVO) {
        String rejection = validate(key, providerVO);
        if (rejection != null) {
            return badRequest(rejection);
        }
        ProviderConfig saved = registry.save(ProviderVoMapper.toConfig(key, providerVO));
        return HttpResponse.ok(ProviderVoMapper.toVo(saved));
    }

    /** {@inheritDoc} */
    @Override
    public HttpResponse<Object> deleteProvider(String key) {
        if (ProviderRegistry.DEFAULT_PROVIDER_KEY.equals(key)) {
            return badRequest(
                    "the '" + ProviderRegistry.DEFAULT_PROVIDER_KEY + "' provider must always exist");
        }
        return registry.delete(key) ? HttpResponse.noContent() : HttpResponse.notFound();
    }

    /**
     * Validates a provider: the effective key must be present and free of the
     * {@link ProviderScopedId#SEPARATOR} (which would make its ids un-decodable), the TM Forum base url
     * must be present, and no scope may carry whitespace (scopes are persisted in a single
     * space-delimited column, and OAuth2 forbids whitespace in a scope token anyway).
     *
     * <p>The returned reason is what the caller sees in the {@code 400} body: an operator driving this
     * API needs to know which of the rules they broke.
     *
     * @param key      the effective provider key (the path key on update, the body key on create)
     * @param provider the provider to validate
     * @return {@code null} if valid, otherwise a short reason
     */
    private static String validate(String key, ProviderVO provider) {
        if (key == null || key.isBlank()) {
            return "the provider key is required";
        }
        if (key.contains(ProviderScopedId.SEPARATOR)) {
            return "the provider key must not contain '" + ProviderScopedId.SEPARATOR + "'";
        }
        if (provider.getTmforumBaseUrl() == null || provider.getTmforumBaseUrl().isBlank()) {
            return "the TM Forum base url is required";
        }
        List<String> scopes = provider.getScopes();
        if (scopes != null && scopes.stream().anyMatch(ProviderAdminController::containsWhitespace)) {
            return "a scope must not contain whitespace";
        }
        return null;
    }

    /**
     * A {@code 400} carrying the rejection reason as its body. The generated method signatures are
     * typed on the <em>success</em> body, while the framework serializes whatever body it is given, so
     * the response type is widened here rather than at every call site.
     */
    @SuppressWarnings("unchecked")
    private static <T> HttpResponse<T> badRequest(String reason) {
        return (HttpResponse<T>) HttpResponse.badRequest(reason);
    }

    private static boolean containsWhitespace(String scope) {
        return scope == null || scope.chars().anyMatch(Character::isWhitespace);
    }
}
