/*
 * Copyright 2026 Seamless Middleware Technologies S.L and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fiware.consent.provider;

import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;

/**
 * Binds a single {@code facade.providers.<key>} configuration entry.
 *
 * <p>Micronaut creates one bean per configured provider key (the map key becomes the
 * {@link #getKey() provider key}); {@link StaticProviderRegistry} aggregates them into immutable
 * {@link ProviderConfig} values. This is the static, configuration-driven source of the provider
 * registry (multi-provider plan, {@code REQUIREMENTS.md} §11.3); a database-backed source (§11.8)
 * replaces it later behind the same {@link ProviderRegistry} interface.
 *
 * <p>Example configuration:
 * <pre>
 * facade:
 *   providers:
 *     default:
 *       tmforum-base-url: http://tm-forum-api.provider.svc.cluster.local:8080
 * </pre>
 */
@EachProperty("facade.providers")
public class ProviderConfiguration {

    private final String key;
    private String tmforumBaseUrl;
    private String selfDescription;
    private String clientId;
    private java.util.List<String> scopes;

    /**
     * Creates the configuration for one provider.
     *
     * @param key the provider key (the {@code facade.providers.<key>} map key)
     */
    public ProviderConfiguration(@Parameter String key) {
        this.key = key;
    }

    /**
     * @return the provider key
     */
    public String getKey() {
        return key;
    }

    /**
     * @return base url of the provider's TM Forum backend
     */
    public String getTmforumBaseUrl() {
        return tmforumBaseUrl;
    }

    /**
     * @param tmforumBaseUrl base url of the provider's TM Forum backend
     */
    public void setTmforumBaseUrl(String tmforumBaseUrl) {
        this.tmforumBaseUrl = tmforumBaseUrl;
    }

    /**
     * @return this provider participant's own (provider-keyed) self-description URL, or {@code null}
     */
    public String getSelfDescription() {
        return selfDescription;
    }

    /**
     * @param selfDescription this provider participant's own (provider-keyed) self-description URL
     */
    public void setSelfDescription(String selfDescription) {
        this.selfDescription = selfDescription;
    }

    /**
     * @return this provider's OID4VP {@code client_id}, or {@code null} for the facade default
     */
    public String getClientId() {
        return clientId;
    }

    /**
     * @param clientId this provider's OID4VP {@code client_id}
     */
    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    /**
     * @return this provider's OID4VP scopes, or {@code null} for the facade default
     */
    public java.util.List<String> getScopes() {
        return scopes;
    }

    /**
     * @param scopes this provider's OID4VP scopes
     */
    public void setScopes(java.util.List<String> scopes) {
        this.scopes = scopes;
    }
}
