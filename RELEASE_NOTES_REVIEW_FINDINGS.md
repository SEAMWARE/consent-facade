# Release notes — code-review findings

Addresses the findings in `REVIEW.md` (review of commit `c980253`). Every HIGH, MEDIUM and LOW
finding is covered; the one that is implemented differently from the recommendation is called out
under [Deviations](#deviations).

Test suite: **131 → 193 tests**, 0 failures. Coverage: **83.5% → 90.8% line**, **60.3% → 74.6%
branch** (both now enforced by a JaCoCo ratchet).

---

## Correctness

### H1 — a cached token no longer over-reports its remaining lifetime

`AccessToken`, `Oid4VpTokenCache`

The cache reconstructed `expires_in` from its own staleness deadline plus the refresh skew, which is
only valid for a long-lived token. A verifier answering `expires_in: 40` produced a cached TTL of 20s
and then reported `70` to a caller hitting it at T+10 — for a token that died at T+40. The Go
consent-plugin caches on that number, so it would have presented a dead token for 30 seconds, failing
closed throughout.

The cache now stores the token's real expiry alongside the staleness deadline and reports
`expiry - now`. `Oid4VpTokenServiceTest.cachesAShortLivedTokenForPartOfItsLifetime` — which already
exercised this branch but asserted only on the token *value* — now asserts the lifetime, and
`neverReportsMoreLifetimeThanTheTokenActuallyHas` covers the long-lived case at the deadline.

### H2 — the participant-scoped lookups no longer truncate at 100 agreements

`TMForumBackedRepository`

`findAgreements()` fetched one hard-wired page of 100 and the facade filtered it client-side, so a
participant party to agreement 120 of 150 was reported as having no contracts — indistinguishable
from "no contract exists", load-dependent, and invisible in the logs.

It now walks pages until one comes back short, bounded by `MAX_PAGES` (100 pages), and **logs a
warning when that bound is reached** rather than passing a truncated list off as a complete answer.
`TMForumPaginationTest` covers the multi-page walk, the short first page, the empty backend and the
cap.

### H3 — attacker-controlled ids can no longer shape the outbound request

`TMForumEndpoints`, `ProviderScopedId`

Path helpers concatenated the id verbatim, so a caller could inject query parameters (`spec?x=1`),
truncate the path with a fragment, or emit `../` segments a proxy may normalise — on the non-default
provider path.

> **The fix the review suggested does not work.** `UriBuilder.of(base).path(segment)` was verified
> against this project's Micronaut version and leaves `?`, `#` and `..` untouched. Every dynamic
> segment is therefore percent-encoded explicitly (`TMForumEndpoints.pathSegment`): only alphanumerics
> and `-._~:@` pass through — the last two matter because the FIWARE TM Forum API generates
> `urn:ngsi-ld:…` ids. A segment consisting only of dots is escaped too, so it cannot be normalised
> into a traversal downstream.

In addition `ProviderScopedId.decode` rejects `/`, `?`, `#`, `\`, `..`, control characters and blanks
at the edge, answering `400`. `TMForumEndpointsTest` pins the encoding table.

### H4 — participant ids must now really be base64

`ConsentFacadeController`

The lenient decode only fell back to using a value verbatim when it contained a non-base64 character.
Any alphanumeric slug whose length is a multiple of four decoded "successfully" into garbage, so
`acme` silently matched nothing and the `log.debug` that would have explained it never fired.

This cannot be sniffed — `acme` is valid base64 and decodes to valid UTF-8 — so the contract is
enforced instead of guessed, which is what both `api/consent-facade.yaml` and the README already
stated. Both the standard and URL-safe alphabets are accepted (standard base64 of a `did:`/`urn:` id
contains `+` and `/`), the decode must round-trip and yield valid UTF-8, and anything else is `400`.

### H5 + H6 — the provider registry is actually mutable at runtime

`TMForumClientFactory`, `PersistentProviderRegistry`

Fixed together, because either alone leaves the runtime-mutable registry not mutable at runtime:

* The repository cache was keyed on the provider **key**, capturing the base url, client id and scopes
  on first use. `PUT /providers/acme` answered `200` while every subsequent request kept going to the
  old backend for the lifetime of the process. It is now keyed on the whole resolved
  `ProviderConfig` (a record — value equality is free), and `evict(key)` drops superseded entries and
  closes the clients they no longer reference.
* The registry's snapshot was refreshed only by writes made in-process, so with two replicas a
  provider created on replica A returned `404` on replica B until it restarted. A scheduled refresh
  (`facade.provider-registry.refresh-interval`, default 30s) now bounds that window, and the
  eventual-consistency window is documented rather than implied away.

The snapshot is also swapped **outside** the transaction (a rollback no longer leaves uncommitted
state cached) and is an ordered immutable map, so `all()` — which the participant-scoped lookups fan
out over — iterates reproducibly. `StaticProviderRegistry` got the same ordering fix.

`PersistentProviderRegistryTest.save_stopsRequestsGoingToAProvidersOldTmForumBackend` covers the
combination end to end.

### M9 — locale-independent status mapping

`AgreementContractMapper` now lower-cases with `Locale.ROOT`. Under a Turkish locale `"INPROGRESS"`
produced a dotless `ı`, the lookup missed, and a signed-equivalent agreement silently became
`pending`, failing the `hasSigned=true` filter.

---

## Robustness and operations

### M1 — nothing waits forever any more

* `oid4vp.connect-timeout` (5s) and `oid4vp.request-timeout` (15s) are new, applied to the OID4VP
  `HttpClient` and to every exchange (`orTimeout` on the blocking path, `.timeout` on the reactive
  one). A timeout maps to `VERIFIER_UNREACHABLE`, the retryable classification the code already
  modelled.
* The blocking exchange no longer runs **inside** the cache's monitor. Concurrent misses are coalesced
  through an in-flight future instead, so a stalled verifier cannot pin blocking threads on a lock;
  waiters are bounded and always released.
* `connect-timeout: 5` added to the five TM Forum service clients, and non-default providers' clients
  are built with explicit connect/read timeouts instead of bare `HttpClient.create`.

### M2 — one cached token path instead of two

`Oid4VpAuthHandler` previously did reactive-on-401 with no cache, so every outbound TM Forum call —
one per registered provider on each `/bilaterals/for` request — cost an unauthorized round trip plus a
full verifiable presentation. That was the steady state, not an edge case.

Both paths now share `Oid4VpTokenCache`: the auth handler attaches a cached token up front (keyed on
target service + client id + scopes) and presents the credential only when there is none or the target
answers `401`. One cache, one set of failure semantics, one place that decides expiry.

> While testing this, a real bug surfaced: `MutableHttpRequest.bearerAuth` **appends**, so a retry of a
> request that already carried a refused token would have sent two `Authorization` headers and the
> target would have kept seeing the stale one. The header is now replaced.

Also fixed: the handler threw `BadGatewayException` from inside `onErrorResume` instead of returning
`Mono.error(...)`.

### M3 — the `exception/` package the README promised now exists

| Failure | Response |
|---|---|
| Any failure calling a provider's TM Forum backend (`HttpClientException`, including error statuses, connect failures and read timeouts) | body-less `502`, detail logged |
| An identifier the facade will not route on | `400` with the rule that was broken |
| Anything unmodelled | body-less `500`, detail logged |

Previously a backend's `401`/`403`/`500` **and its error body** surfaced on the consent-manager-facing
API. `api/consent-facade.yaml` now declares these responses, which it did not before.

### M4 — the metrics configuration is no longer inert

`micronaut-micrometer-core` and `-registry-prometheus` added. Verified against the built jar on the
management port: `/health` → 200, `/metrics` → 200, `/prometheus` → 200 with real HTTP-server timers
and JVM metrics, while `/info` and `/beans` stay `401`. `/prometheus` needed `sensitive: false` —
without it the endpoint answers `401` and the metrics are unscrapeable.

### M6 — the admin API says why it rejected a request

`ProviderAdminController.validate` computed a specific reason and both call sites threw it away,
answering a bare `400`. The reason is now the response body. Scope values carrying whitespace are also
rejected (see L5).

### M7 — reproducible builds

The five TM Forum specs resolved to `refs/heads/main`, so the same commit generated different client
code on different days. Pinned to `refs/tags/1.18.0` via a single `tmforum.api.ref` property —
verified byte-identical to `main` at the time of the change, so no generated code moved.

### M8 — CI actually protects the code

* `test.yml` runs on `pull_request` as well as `push`; the redundant `if: event_name == 'push'` that
  made the suite skip on PRs is gone.
* Both release workflows gained a `test` job that the image build `needs:`, and `-DskipTests` is
  removed — a push to `main` can no longer publish an image no test ran against.
* `marvinpinto/action-automatic-releases@latest` → pinned to the v1.2.1 SHA (it holds the
  `GITHUB_TOKEN`).
* `pre-release.yml` uses `docker/login-action` instead of putting the registry password on the command
  line.
* JaCoCo `check` ratchet, a Dependabot config (Maven + Actions), and a CycloneDX SBOM attached to the
  build.

### M10 — Apache-2.0 paperwork

`NOTICE` and `LICENSE-Apache-2.0` added, naming FIWARE/contract-management and the six derived files
in `auth/`; each of those files now states that it was modified, as Apache-2.0 §4(b) requires.

---

## Smaller items

| Finding | Change |
|---|---|
| L1 | `PEMParser` closed via try-with-resources |
| L2 | Dead surface removed: `findAgreementsForParty`, `findOrganizations`, `resolveSpecifications`, `ProviderRegistry.defaultProvider()`, and the orphaned `TMForumApis.listOrganizations` behind them |
| L3 | Dead config removed: `facade.consumer.self-description`, and the generated-but-unused `product-order` client and its service entry |
| L4 | The default provider's `tmforum-base-url` no longer points at the facade's own port, with a comment saying not to copy the block |
| L5 | Scope storage documented against RFC 6749 §3.3, and a scope carrying whitespace is now rejected rather than silently split on read-back |
| L6 | `Application.main` and `Oid4VpBeanFactory`'s constructor/factory methods documented |
| L7 | `.idea/`, `*.iml`, `.vscode/` in `.gitignore` |
| L8 | The ecosystem-contract scaffolds answer `501`, not an empty list that reads as "party to none" |
| L9 | Branch coverage 60.3% → 74.6%; `internal/api`/`internal/model` added to the JaCoCo excludes — ~90 lines of generated accessors were being counted as untested code |

## Documentation

* README **Status** rewritten — it claimed the controllers and mappers were "the next step" when they,
  and the multi-provider routing on top of them, were implemented and tested. A reader would have
  concluded the service does nothing.
* README: new Observability and Licensing sections, the corrected package layout, the base64/id/`502`
  contracts, and the ecosystem endpoints marked `501`.
* `implementation-plan.md` → an implementation **record**, with the sequencing marked done and the
  "later optimisation" note in §6 updated to say it shipped (M2).
* `REQUIREMENTS.md` retitled as the architecture document, with a note that ~20 Javadoc comments
  cross-reference its section numbers.
* Javadoc that described shipped work as future work corrected (`TMForumClientFactory`,
  `Oid4VpConfiguration`, `StaticProviderRegistry`, `ProviderRegistry`, `ProviderConfig`).
* `api/consent-facade-internal.yaml` documents what `expires_in` actually means (H1).

---

## Deviations

**M5 — the internal API on its own port is implemented, but opt-in.**

The recommendation was to bind the internal controllers to a separate port. That mechanism now exists
and is tested: configure two Netty listeners, set `facade.internal-port`, and `InternalApiPortFilter`
refuses any internal path arriving on the public listener — and any public path on the internal one —
with `404`, so it does not advertise the internal API's existence to the public port.
`InternalApiPortFilterTest` verifies all three directions against two real listeners.

It ships **commented out in `application.yaml`** rather than on by default, because enabling it moves
`POST /internal/tokens` and `/providers` from port 8080 to 8090 — a breaking change for anything
currently calling them, and a deployment decision rather than ours to make. To make the gap loud
instead of silent, `InternalApiExposureWarning` logs a warning at startup whenever an internal
endpoint is active without the isolation configured.

**Turning it on is two uncommented blocks in `application.yaml` plus keeping the internal port off the
ingress.** Say the word and it becomes the default.
