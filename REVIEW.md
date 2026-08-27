# Code Review — `consent-facade`

Reviewer: senior engineer review, full pass over source, tests, build, CI and docs.
Reviewed commit: `c980253` ("extend facade"), branch `main`, working tree clean.
Date: 2026-08-27.

---

## 0. Follow-up verification — `dd03449`, 2026-08-27

Re-checked every finding against `dd03449` ("Address the code-review findings in REVIEW.md").
**All findings resolved.** Sections 1–5 below are kept as the original review record.

| Check | Before | After |
|---|---|---|
| `mvn clean verify` | PASS | **PASS** |
| Tests | 131 | **193** (0 failures, 0 errors, 0 skipped) |
| Line coverage | 83.5% | **90.6%** (gated at 88%) |
| Branch coverage | 60.3% | **74.4%** (gated at 72%) |

Four fixes were confirmed by running code rather than by reading it:

* **M4** — `/metrics` and `/prometheus` now return `200` with real series
  (`http_server_requests_seconds_count{…,uri="/health"}`); previously `404`.
* **M5** — port isolation enforced in both directions on a dual-listener run: `GET /providers` on the
  public port and `GET /participants/org-1` on the internal port are both refused and logged, while
  the same participant path is served on the public port.
* **H3** — traversal and injection ids are now rejected at the edge (`400`), with percent-encoding at
  the sink as a second layer.
* **H4** — the round-trip base64 check rejects the slugs (`provider`, `acme`) that previously decoded
  to mojibake.

Notes on how the fixes went beyond the findings:

* **H1/M2** were solved together by extracting a shared `Oid4VpTokenCache`, so there is now one
  definition of staleness and one answer for `expires_in`. The reported lifetime derives from a
  separately stored `expiresAt`, and the loader runs outside any lock — which closes **M1**'s
  monitor-pinning path as well.
* `Oid4VpAuthHandler` additionally fixes a defect the review did not spot: `bearerAuth` *appends*, so
  retrying a request that already carried a refused token would have sent two `Authorization`
  headers and left the target reading the stale one.
* **H5/H6** are addressed structurally rather than locally: the repository cache is keyed on the whole
  `ProviderConfig` record (value equality makes a changed backend miss the cache), an `evict` hook
  closes superseded clients, and an immutable snapshot plus a scheduled reload makes the registry
  correct across replicas.
* **H2**'s page walk logs a `WARN` when the 100-page backstop truncates a listing — the specific
  ask, since a client-side filter cannot otherwise distinguish truncation from no match.
* Deterministic `all()` ordering, removal of the dead API surface and dead config, and the empty
  `exception/` package are all resolved as part of the above.

---

## 1. Verdict

This is a **well-engineered, unusually well-documented service** for its size (~5.8k LOC incl. tests).
The architecture is sound: a clean `TMForumApis` seam that genuinely enables multi-provider routing,
hand-written mappers where declarative mapping would not have paid off, an opt-in auth layer that
leaves the unauthenticated path untouched, and an internal API deliberately split from the public
contract. Javadoc quality is above average — comments explain *why*, and several of them encode
non-obvious downstream constraints (the consent-manager's string-containment check on rule targets,
the VCVerifier per-service discovery path) that would otherwise be lost.

The problems are not architectural. They are concentrated in three places:

1. **Silent truncation and silent coercion** on the read path — the facade answers "no contract"
   where the correct answer is "I did not look far enough" or "I mangled your identifier".
2. **Cache lifecycle** — three separate caches (token, provider registry, per-provider client) each
   have a correctness gap: one over-reports validity, one is never invalidated, one is per-replica.
3. **Operational readiness** — no error-handling layer, no timeouts on the OID4VP path, inert
   metrics configuration, and a release pipeline that ships images without running tests.

### What I verified

| Check | Result |
|---|---|
| `mvn clean verify` (offline) | **PASS** |
| Test suite | **131 tests, 0 failures, 0 errors, 0 skipped** |
| Coverage (JaCoCo, generated code excluded) | 83.5% line, **60.3% branch**, 82.9% instruction |
| Repo hygiene | `target/` and `.idea/` untracked; 77 files tracked; no secrets in tracked files |
| `TODO`/`FIXME` in `src/` or `api/` | none |
| Management endpoints (`endpoints.all.port: 9090`) | `/health` → 200; `info`/`beans`/`routes`/`threaddump`/`refresh` → **401** (correctly blocked) |

Three findings below (H3, H4, M4) were confirmed by running code, not by reading it. Those are
marked **[verified]**.

---

## 2. Strengths

Worth stating explicitly, because they are the things a reviewer should not ask to be changed:

* **The `TMForumApis` seam** (`tmforum/TMForumApis.java`) is the right abstraction and is drawn in
  the right place. The business logic in `TMForumBackedRepository` is written once; only transport
  varies per provider. The Javadoc explains why the generated declarative clients could not be
  retargeted per request — exactly the rationale a future maintainer needs.
* **`ProviderScopedId`** (`provider/ProviderScopedId.java`) is a small, well-reasoned design: the
  choice of `~` is justified against RFC 3986 and against the `urn:ngsi-ld:` ids the FIWARE TM Forum
  API actually generates, split-on-first-separator tolerates separators in local ids, and the
  un-prefixed form keeps previously minted ids resolvable. Good backward-compatibility instinct.
* **Auth is genuinely opt-in.** Gating every bean on `Oid4VpCondition` and injecting
  `Optional<AuthHandler>` means the disabled path is not merely untested — it cannot construct a
  key or read a credential. `Oid4VpFallbackTest` locks that in.
* **`CatalogUrls`** centralises URL construction to hold the consistency invariant (the URL a
  contract points at must equal the endpoint the facade serves). Recognising that as an invariant and
  giving it one owner is the correct call.
* **The internal API is a separate spec** with its own generated package and an `info.description`
  that states plainly that the endpoints are unauthenticated and rely on network placement. That
  honesty is more useful than a false sense of security.
* **Test suite is real**, not coverage theatre: `ConsentFacadeControllerTest` drives the actual HTTP
  layer with mocked TM Forum clients; `Oid4VpTokenServiceTest` uses an injected mutable `Clock` to
  test expiry without sleeping, and covers concurrent-miss coalescing with a latch. Assertion
  messages carry the intent.
* **The Lombok/Micronaut annotation-processor ordering fix** (`pom.xml:528-560`) is documented with
  the reason. That comment will save someone a day.

---

## 3. Findings

Severity is about consequence in a running deployment, not about how much code needs to change.

### HIGH

---

#### H1 — Cached token over-reports its remaining lifetime for short-lived tokens

`src/main/java/org/fiware/consent/auth/Oid4VpTokenService.java:194-215`

`store()` picks the cache TTL by one of two rules, but `valid()` reverses only one of them:

```java
Duration ttl = lifetime.compareTo(REFRESH_SKEW) > 0
        ? lifetime.minus(REFRESH_SKEW)            // branch A
        : lifetime.dividedBy(SHORT_LIVED_TTL_DIVISOR);  // branch B
...
long remaining = Duration.between(now, staleAt).toSeconds() + REFRESH_SKEW.toSeconds();
```

The `+ REFRESH_SKEW` correction is only valid in branch A, where `staleAt = expiry - skew`. In
branch B `staleAt = now + lifetime/2`, so the correction is pure invention.

**Failure scenario.** Verifier returns `expires_in: 40`. TTL becomes 20s, `staleAt = T+20`. A caller
hitting the cache at `T+10` gets `expires_in: 70` — but the token dies at `T+40`. The Go
consent-plugin, whose whole reason for calling this endpoint is that it cannot do OID4VP itself,
caches on that number and then presents a dead token for 30 seconds, failing closed the entire time.
The `expires_in` contract is documented in `api/consent-facade-internal.yaml` and the README, so
callers are entitled to trust it.

**Fix.** Store the real expiry alongside `staleAt` and report `min(actualExpiry - now, …)`, or
derive `remaining` from the stored expiry instead of reconstructing it from `staleAt`.

**Test gap.** `cachesAShortLivedTokenForPartOfItsLifetime` (`Oid4VpTokenServiceTest`) exercises
exactly this branch but asserts only on the token *value*, never on `expiresInSeconds()`. The
long-lived case *is* asserted (`reportsTheRemainingLifetimeOfACachedToken`). One assertion away from
having caught this.

---

#### H2 — `/bilaterals/for` and `/verify` silently truncate at 100 agreements per provider

`src/main/java/org/fiware/consent/tmforum/TMForumBackedRepository.java:64,88-90` →
`src/main/java/org/fiware/consent/facade/ConsentFacadeController.java:160-164,67-75,97-107`

`projectAllContracts()` calls `findAgreements()`, which is hard-wired to
`DEFAULT_PAGE_LIMIT = 100, FIRST_PAGE_OFFSET = 0`. Both participant-scoped endpoints then filter
that page **client-side**. There is no pagination loop and no signal that a page boundary was hit.

**Failure scenario.** A provider's TM Forum backend holds 150 agreements. A participant party to
agreement #120 calls `GET /verify/{provider}/{consumer}`. The facade fetches agreements 1–100,
finds no match, and answers `{"verified": false}` — indistinguishable from "no contract exists".
`GET /bilaterals/for/{participantId}` likewise reports a contract-free participant.

This fails *closed* (a false negative, not a false positive), so it is a correctness and
availability bug rather than an authorization bypass. But it is a **silent, load-dependent** one: it
will not appear in any demo and will appear in production, and the failure mode reads to an operator
as "the consent-manager thinks we have no contract" with nothing in the logs.

Note also the `filter → collectList` shape: the facade always pays for a full page fetch across
*every* registered provider on every one of these calls, then discards almost all of it.

**Fix.** Either page until exhausted (and log when the loop runs long), or push the filter
server-side. The Javadoc on `findAgreementsForParty` already concedes "the TM Forum list endpoint
does not support server-side filtering by engaged party" — if that holds, paging is the only correct
option, and the 100-row cap must at minimum be logged when reached rather than passed off as a
complete answer.

---

#### H3 — Attacker-controlled id is interpolated into the downstream URL without encoding **[verified]**

`src/main/java/org/fiware/consent/tmforum/TMForumEndpoints.java:119-145` (reached from
`HttpTMForumApis.get`, `:97-102`)

Every path helper concatenates the id verbatim:

```java
static String productSpecification(String id) {
    return PRODUCT_CATALOG_BASE + "/productSpecification/" + id;
}
```

`id` here is `ProviderScopedId.decode(...).localId()` — the tail of a public path variable
(`GET /catalog/dataresources/{id}`), fully attacker-controlled.

I confirmed the consequences against the project's own Micronaut version:

| supplied `localId` | resulting request path | resulting query |
|---|---|---|
| `spec-1` | `…/productSpecification/spec-1` | — |
| `spec?x=1` | `…/productSpecification/spec` | **`x=1`** |
| `spec#frag` | `…/productSpecification/spec` | — (rest dropped as fragment) |
| `../../../../actuator/env` | `…/productSpecification/../../../../actuator/env` | — |

So a caller can inject arbitrary query parameters into the facade's outbound TM Forum call, and can
emit `../` segments that many servers and reverse proxies normalise — reaching paths on the
provider's TM Forum host that the facade was never meant to request. The blast radius is bounded to
that one host, which keeps this out of "critical", but it is a genuine request-injection primitive
on a component whose entire job is to be the trusted boundary in front of a provider's backend.

The default-provider path goes through the generated declarative clients, which encode path
parameters, so **the exposure is specific to the non-default (`HttpTMForumApis`) path** — i.e. it
arms itself the moment a second provider is registered.

**Fix.** Build these paths with `UriBuilder.of(BASE).path(segment).build()` (per-segment encoding),
and reject ids containing `/`, `?`, `#` or `..` at the edge in `ProviderScopedId.decode`.

---

#### H4 — Lenient base64 decode silently mangles plain participant ids **[verified]**

`src/main/java/org/fiware/consent/facade/ConsentFacadeController.java:195-205`

```java
try {
    return new String(Base64.getDecoder().decode(encodedParticipantId), UTF_8);
} catch (IllegalArgumentException e) {
    log.debug("… not valid base64, using it verbatim.");
    return encodedParticipantId;
}
```

The catch block is the intended tolerance for un-encoded ids, but it only fires when the input
contains a character outside the base64 alphabet. Any alphanumeric string whose length is a multiple
of 4 decodes "successfully" into garbage. Verified:

```
'provider'                    -> decoded to 6 bytes of binary garbage
'acme'                        -> decoded to 3 bytes of binary garbage
'org-1'                       -> not base64 (verbatim)      ✓
'did:provider'                -> not base64 (verbatim)       ✓
'urn:ngsi-ld:organization:1'  -> not base64 (verbatim)       ✓
```

**Failure scenario.** A caller passes an un-encoded participant id `acme` (or any 4/8/12-character
slug). The facade compares mojibake against the agreement's self-description ids, matches nothing,
and returns an empty contract list or `verified: false`. The `log.debug` that would explain it never
fires, because from `decode()`'s point of view nothing went wrong.

Ids with `:` or `-` happen to be safe, which is why the existing tests and any demo using
`did:`-style ids pass. That makes this a latent trap, not a visible bug.

**Fix.** Decide the contract instead of guessing at it. Either require base64 (the OpenAPI spec and
README both say the consent-manager always encodes) and reject what does not decode, or make the
sniff decisive: attempt the decode, and accept the result only if it round-trips
(`Base64.getEncoder().encodeToString(decoded).equals(input)`) *and* the bytes are valid UTF-8.
Consider `getUrlDecoder()` too — a base64'd URL will contain `+` and `/`.

---

#### H5 — `TMForumClientFactory` caches repositories forever; admin-API updates never take effect

`src/main/java/org/fiware/consent/provider/TMForumClientFactory.java:71-81,111-116`

```java
return repositoriesByProviderKey.computeIfAbsent(provider.key(),
        key -> new TMForumBackedRepository(new HttpTMForumApis(
                clientFor(provider.tmforumBaseUrl()), …)));
```

The cache key is the provider **key**; the base URL, client id and scopes are captured on first use.
Nothing invalidates either map except `@PreDestroy`. `PersistentProviderRegistry` has no reference to
this factory.

**Failure scenario.** Operator runs `PUT /providers/acme` to move `acme` onto a new TM Forum backend
(the stated purpose of the persistent registry, plan §11.8). `registry.save()` updates the DB and the
registry cache and returns `200`. Every subsequent request for `acme` still goes to the **old** URL,
for the lifetime of the process. `DELETE /providers/acme` likewise leaves the repository and its
`HttpClient` cached and open — a slow leak on top of the stale routing.

This makes the runtime-mutable registry mostly non-functional in its main use case, which is the
thing that distinguishes it from the static one. It is a HIGH because the API reports success.

**Fix.** Key the repository cache on the resolved `ProviderConfig` (it is already a record — value
equality is free), or expose an invalidation hook the registry calls on `save`/`delete`, closing the
displaced `HttpClient`.

---

#### H6 — The persistent registry's cache is per-replica, so admin writes do not propagate

`src/main/java/org/fiware/consent/provider/PersistentProviderRegistry.java:36,99-132`

`reload()` is called only from `initialize()`, `save()` and `delete()` — all in-process. Reads are
served exclusively from the local `ConcurrentHashMap`.

**Failure scenario.** Two replicas behind a Service. `POST /providers` lands on replica A. Replica A
serves the new provider; replica B returns `404` for every id scoped to it until it restarts.
Requests alternate between working and not working depending on which pod the ingress picks.

The Javadoc claims "reads are served from an in-memory cache that is refreshed on every write",
which is true only of the writing replica. Since the persistent registry exists precisely so the
provider set can change without a redeploy, single-replica-only is a constraint that has to be
either enforced or removed.

**Fix.** Simplest correct option: drop the cache and read through to the repository (`findAll()` on a
handful of rows is cheap, and the endpoints are already `@ExecuteOn(BLOCKING)`). Otherwise add a
short TTL / periodic refresh, and document the eventual-consistency window.

**Related (LOW).** `reload()` mutates the cache *inside* the `@Transactional` method
(`:107`, `:123`), so a rollback after the update leaves the cache holding uncommitted state. And
building into a `LinkedHashMap` only to `putAll` into a `ConcurrentHashMap` (`:128-131`) discards the
insertion order it went to the trouble of preserving — `all()` iteration order is arbitrary, which
matters because `projectAllContracts()` fans out over it.

---

### MEDIUM

---

#### M1 — Unbounded blocking join under a monitor, with no HTTP timeouts

`src/main/java/org/fiware/consent/auth/Oid4VpTokenService.java:107-116,127` and
`src/main/java/org/fiware/consent/auth/Oid4VpBeanFactory.java:160-167`

Three things compound:

* `oid4vpHttpClient()` builds a `java.net.http.HttpClient` with **no `connectTimeout`**, and the
  OID4VP exchange sets no request timeout.
* `Oid4VpTokenService.request()` calls `oid4VPClient.getAccessToken(parameters).join()` — no
  `orTimeout`, no `get(timeout, unit)`.
* That `.join()` happens **inside `synchronized (entry)`** (`:108`).

**Failure scenario.** The verifier accepts the TCP connection and then stalls (a common failure mode
for an overloaded or half-broken service — much more common than a clean refusal). The first caller
blocks forever on a `TaskExecutors.BLOCKING` thread while holding the audience's monitor. Every
subsequent request for that audience queues on the monitor and consumes another blocking thread. The
coalescing design that makes a burst cheap on the happy path turns the pool into a queue on this
one. `/internal/tokens` stops responding, and because `InternalTokenController` never times out,
callers get no `502` — they get nothing.

**Fix.** Set `connectTimeout` on the builder, and bound the wait
(`getAccessToken(p).orTimeout(n, SECONDS).join()`), mapping `TimeoutException` to
`Reason.VERIFIER_UNREACHABLE` — the retryable classification this code already models correctly for
`BadGatewayException`. Make the timeout configurable on `Oid4VpConfiguration`.

Same gap on the inbound-to-TM-Forum side: `application.yaml` sets `read-timeout: 30` on the five
services but **no `connect-timeout`**, and `TMForumClientFactory.createClient` (`:102-108`) uses a
bare `HttpClient.create(url)` with default configuration for non-default providers.

---

#### M2 — Two independent token implementations; the outbound path has no cache at all

`src/main/java/org/fiware/consent/auth/Oid4VpAuthHandler.java:50-79` vs. `Oid4VpTokenService`

`Oid4VpAuthHandler` implements reactive-on-401: fire the request unauthenticated, and on `401`
perform a **full OID4VP presentation** and retry. There is no token cache on this path.

So every single outbound TM Forum call — and `projectAllContracts()` makes one per provider per
`/bilaterals/for` request — costs an unauthorized round trip *plus* a complete verifiable
presentation (OIDC discovery, DCQL evaluation, JWT signing, token exchange). Against an authenticated
`tm-forum-api`, that is the steady state, not an edge case.

`implementation-plan.md` §6 flags this explicitly ("a per-provider token cache … is a
straightforward later optimisation"), and it was a reasonable call when the plan was written.
It is less reasonable now, because `Oid4VpTokenService` — with per-audience caching, refresh-ahead,
and concurrent-miss coalescing — was subsequently built, and the auth handler does not use it. The
project now has two token paths with different maturity: the one serving an external consumer is
cached and tested; the one on its own hot path is neither.

**Fix.** Generalise `Oid4VpTokenService` to key on the target service URI as well as the named
audience, and have `Oid4VpAuthHandler` attach a cached token proactively, falling back to
present-and-retry on `401`. One cache, one set of failure semantics, one place to reason about
expiry (see H1).

**Minor, same file.** `:57` throws `BadGatewayException` from inside an `onErrorResume` lambda rather
than returning `Mono.error(...)`. It works, but it bypasses the reactive error channel and will
surprise the next reader.

---

#### M3 — No error-handling layer; the `exception/` package is empty

`src/main/java/org/fiware/consent/exception/` (empty directory), README:95

The README documents `exception/  # exception handlers`. There are none — `grep -rn
"ExceptionHandler\|@Error" src/main/java` returns nothing.

Consequence: a downstream failure that is not `404` propagates as a raw
`HttpClientResponseException` out of the controller (`HttpTMForumApis.body`, `:140-142`, constructs
one deliberately for any status ≥ 400). Micronaut's default handling for that exception type
reflects the downstream status and message toward the caller. That means a provider backend's `401`,
`403` or `500` — and whatever its error body says — can surface on the consent-manager-facing API,
which is both a confusing contract (`api/consent-facade.yaml` declares no such responses) and a
minor information-disclosure path from the provider's internal backend to an external consumer.

**Fix.** Add the `@Error`/`ExceptionHandler` beans the README already promises: map TM Forum
transport failures to `502`, unmapped internal failures to a body-less `500`, and log the downstream
detail server-side rather than forwarding it.

---

#### M4 — The metrics configuration is inert — no Micrometer dependency **[verified]**

`src/main/resources/application.yaml:8-13`, `pom.xml`

```yaml
micronaut:
  metrics:
    enabled: true
    export:
      prometheus:
        step: PT2s
```

There is no `micronaut-micrometer-registry-prometheus` (or any `micrometer`) dependency in `pom.xml`,
and none on the resolved classpath. Confirmed against the built jar:

```
/health     -> 200
/metrics    -> 404
/prometheus -> 404
```

So the service has **no metrics at all**, while its configuration, and the
`http.server.log.exclude-paths` entries for `/metrics` and `/prometheus` (`:44-47`), state
otherwise. For a component sitting on the consent path — where H2's silent truncation and M2's
per-request VP presentation are exactly the things you would want a counter and a timer on — this is
the observability gap that matters most.

**Fix.** Add `io.micronaut.micrometer:micronaut-micrometer-registry-prometheus`, or delete the dead
configuration so it stops implying a capability that is not there.

---

#### M5 — The entire API is unauthenticated; security rests wholly on network placement

`api/consent-facade.yaml` (no `securitySchemes`, no `security:`),
`api/consent-facade-internal.yaml:12-21`

Neither spec declares any security scheme, and no `micronaut-security` dependency exists. Anything
that can reach the port can:

* enumerate **any** participant's contracts (`GET /bilaterals/for/{participantId}` — the
  participant id is an unauthenticated path parameter, not a claim);
* obtain a token that **speaks for this participant** (`POST /internal/tokens`);
* **rewrite the TM Forum backends the facade routes to** (`PUT /providers/{key}`) — including
  pointing them at an attacker-controlled host, since `ProviderAdminController.validate` (`:93-104`)
  checks only that the URL is non-blank.

To be fair, this is *documented*, not overlooked: the internal spec's description spells out the
exposure, and ADR-0003 argues the design. The concern is that the whole control is external
(ingress allow-list plus NetworkPolicy), lives in another repository, and has nothing in this one
that fails if it is misconfigured. A single ingress path added by someone who has not read
`api/consent-facade-internal.yaml:12-21` turns a documented assumption into a participant-identity
compromise.

**Recommendation.** Defence in depth, cheapest first: bind the internal controllers to a **separate
port** (the mechanism already exists for `endpoints.all.port`) so they cannot be reached through the
public listener at all, regardless of ingress rules.

---

#### M6 — `validate()` computes a reason and the callers throw it away

`src/main/java/org/fiware/consent/provider/ProviderAdminController.java:53-72,93-104`

```java
if (validate(providerVO.getKey(), providerVO) != null) {
    return HttpResponse.badRequest();
}
```

`validate` returns a specific, human-readable reason ("the provider key must not contain '~'"), and
both call sites discard it, answering a bare `400` with no body. An operator using this admin API
gets no indication of which of three rules they broke. The method's own Javadoc documents the return
value as "a short reason" — the contract is there, just unused.

**Fix.** `HttpResponse.badRequest(reason)`, or a small problem-detail body. If the reason is genuinely
not meant to reach the caller, log it and change the signature to `boolean`.

---

#### M7 — Non-reproducible builds: TM Forum specs are fetched from a moving branch

`pom.xml:58-72`

All five client specs resolve to
`https://raw.githubusercontent.com/FIWARE/tmforum-api/refs/heads/main/api/…/api.json`.

Two consequences. Builds are **not reproducible** — the same commit generates different client code
on different days. And an upstream change to a model or an operation signature breaks or, worse,
*silently alters* generated code with nothing in this repository's history to explain it. It also
makes the build require network access to GitHub, which `test.yml` and both release workflows depend
on.

**Fix.** Pin to a tag or commit SHA instead of `refs/heads/main`, or vendor the specs under `api/`
and bump them deliberately. Pinning is a one-line change per spec and eliminates a whole class of
"it built yesterday" incident.

---

#### M8 — CI does not test pull requests, and releases skip tests entirely

`.github/workflows/test.yml:3-9`, `release.yml:59-62`, `pre-release.yml:57-60`

```yaml
# test.yml
on:
  push
jobs:
  test:
    if: github.event_name == 'push'
```

The trigger is `push` only, and the job then re-checks `event_name == 'push'` — so the test suite
**never runs on a `pull_request` event**. Nothing verifies the merge result before it lands, and a
PR from a fork is never tested at all. `check.yml`, which *does* run on `pull_request`, only
validates that a semver label is present.

Both release workflows then run `mvn clean deploy -DskipTests -Poci`. Combined: a push to `main`
publishes an image to `quay.io` with **no test having run against that merge commit**.

**Fix.** Add `pull_request` to `test.yml`'s triggers and drop the redundant `if:`. Drop `-DskipTests`
from the release builds, or gate the release job on a successful test job (`needs:`).

Smaller items in the same workflows:

* `marvinpinto/action-automatic-releases@latest` (`release.yml:71`, `pre-release.yml:69`) — an
  unpinned third-party action holding `GITHUB_TOKEN`. Pin to a SHA.
* `pre-release.yml:55` passes the registry password via `docker login -p "${{ secrets.QUAY_PASSWORD }}"`,
  putting it on the command line. `release.yml` already uses `docker/login-action` correctly — make
  `pre-release.yml` match.
* No JaCoCo `check` goal, so the 60.3% branch coverage is measured but not defended. A ratchet on
  the current number would stop regressions without demanding new work.
* No Dependabot config and no SBOM. `micronaut-parent` is pinned at **4.7.1** (Nov 2024) — worth a
  deliberate currency pass.

---

#### M9 — Locale-sensitive `toLowerCase()` in status mapping

`src/main/java/org/fiware/consent/mapping/AgreementContractMapper.java:152-154`

```java
.map(status -> AGREEMENT_STATUS_TO_CONTRACT_STATUS.get(status.toLowerCase()))
```

`toLowerCase()` uses the default locale. Under a Turkish locale, `"INPROGRESS".toLowerCase()`
produces a dotless `ı`, the map lookup misses, and the status silently defaults to `pending` —
turning a signed-equivalent agreement into one that fails the `hasSigned=true` filter. A container
inheriting an unexpected locale is not exotic.

**Fix.** `status.toLowerCase(Locale.ROOT)`.

---

#### M10 — Apache-2.0 code is vendored into an MIT repository with no NOTICE

`LICENSE` (MIT), six files in `src/main/java/org/fiware/consent/auth/`

`CertReader`, `DidKeyGenerator`, `AuthHandler`, `Oid4VpAuthHandler`, `Oid4VpConfiguration` and
`Oid4VpBeanFactory` all carry "Adapted from FIWARE/contract-management (Apache-2.0)". There is no
`NOTICE`, no `THIRD-PARTY` file, and no Apache-2.0 license text anywhere in the repository — only the
Javadoc mention.

The per-file attribution shows the right instinct and is the hard part; what is missing is the
paperwork. Apache-2.0 §4 requires retaining the license text and copyright notices for redistributed
portions and stating that files were changed. `implementation-plan.md` §3 even has the reminder
("Check contract-management's licence before copying"), so this looks like an open loop rather than
an oversight of principle.

**Fix.** Add `LICENSE-Apache-2.0` (or a `NOTICE`) naming the upstream project and the derived files,
and note in each header that it was modified. Low effort, and it matters for a FIWARE-adjacent
project others will vendor from.

---

### LOW

| # | Finding | Location |
|---|---|---|
| L1 | `PEMParser` is never closed. The underlying stream is closed by try-with-resources, so this is tidiness rather than a leak — but it is a `Closeable` sitting unclosed in a security-relevant path. | `auth/CertReader.java:42` |
| L2 | **Unused production API surface** (verified — zero non-test call sites): `TMForumBackedRepository.findAgreementsForParty` (used by nothing at all), `resolveSpecifications` (tests only), `findOrganizations()` (tests only), `ProviderRegistry.defaultProvider()` (tests only). Each is fully documented, which makes the dead surface look load-bearing. Delete or wire up. | `tmforum/TMForumBackedRepository.java:112,199,134`, `provider/ProviderRegistry.java:196` |
| L3 | **Dead configuration.** `facade.consumer.self-description` is defined and defaulted but read nowhere. The `product-order` client is generated (`pom.xml:328-360`) and given a `micronaut.http.services.product-order` entry, but no code references `productorder` — despite the README describing `ProductOrder` as the central concept. | `application.yaml:60-61,18-21` |
| L4 | `facade.providers.default.tmforum-base-url: http://localhost:8080` points at **the facade's own port**. Harmless today (the default provider uses the generated clients and ignores this value) but it is a loaded gun for the first person who copies the block for a second provider. | `application.yaml:76-78` |
| L5 | OID4VP scopes are persisted space-delimited in one column, so a scope containing whitespace round-trips incorrectly. Fine for OAuth2 scopes by spec; worth a constraint or a comment rather than an assumption. | `PersistentProviderRegistry.java:150-156` |
| L6 | `Application.main` has no Javadoc, against the repo's own "every public method must be documented" rule. `Oid4VpBeanFactory`'s constructor (`:155`) likewise. Everything else in the codebase complies, which is why these stand out. | `Application.java:9` |
| L7 | `.idea/` is not in `.gitignore` (it happens to be untracked). One `git add -A` from committing IDE state. | `.gitignore` |
| L8 | `getEcosystemContractsForParticipant` / `getEcosystemContract` ignore all parameters and return empty/`404`. Correct as a documented scaffold, but they will not fail loudly if a caller starts depending on them. Consider `501 Not Implemented` over a silent empty list. | `ConsentFacadeController.java:86-94` |
| L9 | Branch coverage is 60.3% against 83.5% line coverage — the gap is in the fallback branches (`CertReader` 42.5%, `HttpTMForumApis` 80%, `GeneratedTMForumApis` 70.9%). Those are precisely the error paths, and H1 is a branch that is executed but not asserted on. | JaCoCo report |

---

## 4. Documentation drift

The docs are a real asset here, which is why the stale parts are worth fixing rather than shrugging at.

* **README "Status" is wrong** (`README.md:145-148`): *"Initial scaffold… The controllers/mappers that
  translate TM Forum payloads into the contract and catalog self-descriptions are the next step."*
  They are implemented, tested, and the multi-provider routing on top of them is too. A reader
  taking this at face value would conclude the service does nothing.
* **README documents an `exception/` package** with exception handlers (`README.md:95`) — the
  directory exists and is empty (see M3).
* **README claims Prometheus metrics** by implication via the tech-stack and config sections; there
  are none (see M4).
* **`implementation-plan.md`** reads as a live plan but steps 1–4 are done. Step 4's "per-provider
  `client-id`/`scopes` via config now, admin API later" is *also* done (`ProviderConfig`,
  `V2__add_provider_oid4vp_columns.sql`). Worth converting to a completed record, or folding the
  remaining items (step 5, the token cache note in §6) into issues.
* **`REQUIREMENTS.md` §11 is the de-facto architecture doc** and is referenced from ~20 Javadoc
  comments. That cross-referencing is genuinely useful — but it makes `REQUIREMENTS.md` load-bearing,
  so it should be stated as the architecture document rather than "requirements", and kept in step
  with the code.
* Several Javadocs describe as future work things that now exist — e.g. `TMForumClientFactory:35`
  ("Until Phase 4 routes requests by provider, only the default branch is exercised at runtime"),
  contradicted by `ConsentFacadeController.routeById`; and `Oid4VpConfiguration:46` ("A per-provider
  override is planned"), which shipped.

---

## 5. Suggested order of work

Ordered by consequence-per-unit-effort, not by severity label.

**Now — small, self-contained correctness fixes**

1. **H1** token `expires_in` — a few lines plus the missing assertion in the test that already covers
   the branch. External consumers are relying on this number today.
2. **H4** base64 sniffing — decide the contract and enforce it.
3. **H3** path encoding in `TMForumEndpoints` — `UriBuilder` per segment, plus id validation in
   `ProviderScopedId.decode`.
4. **M9** `Locale.ROOT`; **M6** return the validation reason; **L1** close the `PEMParser`.
5. **M8** add `pull_request` to `test.yml` and drop `-DskipTests` from the release builds. This is
   the cheapest change on the list and it is what protects everything else.

**Next — the caches, as one piece of work**

6. **H5** + **H6** together: make the provider registry the single source of truth (read-through, or
   TTL + invalidation hook) and key the client factory on the resolved `ProviderConfig`. Fixing
   either alone leaves the runtime-mutable registry not actually mutable at runtime.
7. **M1** timeouts on the OID4VP client and a bounded wait, released before the monitor.

**Then — the load-bearing gaps**

8. **H2** pagination on `findAgreements` (and at minimum a `WARN` when the cap is hit). This is the
   finding most likely to be reported first from production.
9. **M2** consolidate onto one cached token path — this subsumes H1's fix and removes a full VP
   presentation from every outbound call.
10. **M3** the exception handlers the README already promises; **M4** Micrometer or delete the config.

**Also worth scheduling**

11. **M7** pin the TM Forum spec URLs — reproducible builds.
12. **M5** move the internal controllers onto their own port.
13. **M10** the Apache-2.0 NOTICE.
14. Documentation drift (§4) — fastest to fix while the code is fresh, and the README's "Status"
      section actively misrepresents the project.
