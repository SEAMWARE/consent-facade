# Implementation plan — VC/OID4VP authentication for outbound TM Forum calls

## Goal

The consent-manager (at a central authority) and the provider's `tm-forum-api` run in **different
organizations**, so the facade's requests toward the provider's TM Forum API must be **authenticated
with verifiable credentials** (OID4VP). The provider already exposes an authenticated endpoint
(`mp-tmf-api.127.0.0.1.nip.io`). The facade obtains an access token by presenting a VC via the
[`oid4vp-client-lib`](https://github.com/wistefan/oid4vp-client-lib) and attaches it to its outbound
TM Forum requests.

**If no credentials are configured, requests happen unauthenticated** (today's behaviour is preserved
— the feature is fully opt-in via `oid4vp.enabled`).

## Reference implementation

[`FIWARE/contract-management`](https://github.com/FIWARE/contract-management) is a Micronaut app using
the same library; we follow its pattern (`org.fiware.iam.bean.Oid4VpBeanFactory`,
`org.fiware.iam.configuration.Oid4VpConfiguration`, `org.fiware.iam.http.Oid4VpAuthHandler`).

## Context: the two outbound paths

The facade reaches the TM Forum API through the `TMForumApis` seam (`REQUIREMENTS.md` §11.5), with two
implementations — auth must cover both, reusing **one** `Oid4VpAuthHandler`:

| Path | Used by | Transport | Auth injection point |
|---|---|---|---|
| `GeneratedTMForumApis` | the **default** provider | five generated `@Client(id=…)` beans (`agreement`, `party`, `product-catalog`, `product-inventory`, `product-order`) | a **Micronaut `HttpClientFilter`** scoped to those service ids, delegating to the handler |
| `HttpTMForumApis` | non-default providers | a low-level `HttpClient.create(URL)` from `TMForumClientFactory` | wrap the exchange with the handler (as contract-management does) |

## The library (from contract-management)

Dependencies (`pom.xml`):
- `io.github.wistefan:oid4vp-client-lib:0.0.5`
- `com.nimbusds:nimbus-jose-jwt:10.5`
- `org.bouncycastle:bcprov-jdk18on:1.81`, `org.bouncycastle:bcpkix-jdk18on:1.81`

Key facts:
- The `OID4VPClient` takes a **`java.net.http.HttpClient`** (not Micronaut's) + a `HolderConfiguration`
  + a snake-case `ObjectMapper` + `List<ClientResolver>` (`X509SanDnsClientResolver`) + a
  `DCQLEvaluator` + a `CredentialsRepository` (`FileSystemCredentialsRepository`) + a `SigningService`
  (`HolderSigningService`). Register `BouncyCastleProvider` once at startup.
- `getAccessToken(RequestParameters)` returns a `CompletableFuture<TokenResponse>`; the token is
  `TokenResponse.getAccessToken()`. **No token caching** in the library.
- `RequestParameters(serviceURI, path, clientId, scopes)` — **`serviceURI` is the target host**, and
  the library performs OIDC discovery against it. The URI is therefore **never configured** — it is
  taken from the outgoing request (`scheme://host:port`).

## Changes in the consent-facade

### 1. Dependencies (`pom.xml`)
Add the four dependencies above.

### 2. Config surface — opt-in (model on `Oid4VpConfiguration`)
`@ConfigurationProperties("oid4vp")`:
- `enabled` (default `false`) + an `Oid4VpCondition` (`Condition`) — the on/off switch.
- `holder`: `holderId` (`URI`, **optional** — if absent, generate a `did:key` from the private key
  via a `DidKeyGenerator`), `keyType`, `keyPath`, `signatureAlgorithm`.
- `credentialsFolder` (for `FileSystemCredentialsRepository`).
- `enableRevocation` (default `false`), `trustAnchors` (list; empty ⇒ system trust anchors), and an
  optional `proxyConfig`.

**Per-provider** `client-id` + `scopes`: a **configurable default** on the facade now; overridable
**per provider through the admin API in a later step** (alongside the TM Forum endpoint, so it flows
through the static and persistent registries — extend `ProviderConfig`). The **service URI is not
configured** (OIDC discovery only, per above).

### 3. `Oid4VpBeanFactory` (`@Factory`, `@Requires(bean = Oid4VpConfiguration.class)`)
Adapt contract-management's factory:
- `java.net.http.HttpClient` bean (redirects + optional proxy).
- `CredentialsRepository` = `new FileSystemCredentialsRepository(credentialsFolder, objectMapper)`.
- `OID4VPClient` bean: `Security.addProvider(new BouncyCastleProvider())`; a snake-case `ObjectMapper`
  copy with the `CredentialFormat` / `TrustedAuthorityType` deserializers; load the private key
  (`CertReader`), resolve the holder id (config or generated `did:key`), build `HolderConfiguration`
  + `HolderSigningService`, `X509SanDnsClientResolver` (config or system trust anchors), and a
  `DCQLEvaluator` over the JWT / dc+sd-jwt / vc+sd-jwt evaluators.
- Small helpers to adapt from contract-management: `CertReader` (load private key / certificates),
  `DidKeyGenerator` (did:key from a key). Check contract-management's licence before copying.

### 4. `Oid4VpAuthHandler` (`@Requires(condition = Oid4VpCondition)`) — reactive-on-401
Adapt contract-management's `AuthHandler` / `Oid4VpAuthHandler` verbatim:
`Mono<HttpResponse> executeWithAuth(MutableHttpRequest<?> request, Function<MutableHttpRequest<?>, Mono<HttpResponse>> executor)`:
run the request; on **`401`**, build `RequestParameters(URI(scheme://host:port), path, clientId, scope)`
(the URI = the request host ⇒ OIDC discovery), `getAccessToken` → `bearerAuth(token)` → **retry**.
`clientId`/`scope` come from request attributes (`CLIENT_ID_ATTRIBUTE` / `SCOPE_ATTRIBUTE`), which the
facade sets from the target provider's config. **When `oid4vp.enabled=false` the bean is absent**, so
callers inject `Optional<AuthHandler>` and skip it ⇒ unauthenticated.

### 5. Inject on path (a): generated clients (default provider)
A `HttpClientFilter` scoped to the five TM Forum service ids
(`@Filter`/`@ClientFilter(serviceId = {...})`): sets the `clientId`/`scope` attributes from the
**default** provider's auth config and delegates to
`authHandler.executeWithAuth(request, req -> Mono.from(chain.proceed(req)))`. No handler bean ⇒
plain pass-through.

### 6. Inject on path (b): low-level `HttpTMForumApis`
Thread `Optional<AuthHandler>` + the provider key (+ its `clientId`/`scope`) into `HttpTMForumApis`
(via `TMForumClientFactory`). Switch its `get`/`getList` from `retrieve` to **`exchange`** (the handler
needs the `HttpResponse`/status), set the `clientId`/`scope` attributes, and wrap the exchange with
`authHandler.executeWithAuth`. No handler ⇒ direct exchange (unauthenticated).

> Note: reactive-on-401 with no caching means an unauthenticated attempt precedes each authenticated
> call (contract-management's behaviour). A per-provider token cache (attach the cached token
> proactively, refresh on `401`) is a straightforward later optimisation if the double round-trip
> matters; it does not change the wiring above.

### 7. Endpoint wiring
Point the provider's TM Forum base URL at the authenticated endpoint (`mp-tmf-api.127.0.0.1.nip.io`
for the demo). No facade-code change beyond config.

### 8. Tests
- `Oid4VpBeanFactory` wiring (model on contract-management's `Oid4VpBeanFactoryTest`;
  `resolveHolderId` with/without a configured id).
- `Oid4VpAuthHandler`: `401` ⇒ `getAccessToken` + retry with bearer; non-`401` pass-through;
  downstream error mapping.
- Path (a) filter and path (b) `HttpTMForumApis`: handler invoked when present.
- **Fallback**: `oid4vp.enabled=false` ⇒ no handler ⇒ requests go out unauthenticated (preserves
  today's demo).

## Deployment counterpart (DSC, not the facade)
Provision the facade its **own VC + holder key** (a participant credential, like other DSC
participants), mounted as a read-only secret/volume; set `oid4vp.enabled=true`, `oid4vp.holder.*`,
`oid4vp.credentialsFolder`, per-provider `client-id`/`scopes`, and point the TM Forum base URL at
`mp-tmf-api`. Chart/values change; compatible with the strict pod security context (read-only mounts,
in-memory BouncyCastle).

## Sequencing (incremental)
1. Config (`Oid4VpConfiguration`) + `Oid4VpBeanFactory` + `Oid4VpAuthHandler` + `CertReader` /
   `DidKeyGenerator`, with the **unauthenticated fallback** (no behaviour change when disabled).
2. Path (a): the generated-clients `HttpClientFilter`.
3. Path (b): `HttpTMForumApis` exchange + handler.
4. Per-provider `client-id`/`scopes` via config now, admin API later.
5. DSC credential provisioning + live test against `mp-tmf-api`.
