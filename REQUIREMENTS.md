# Consent Facade — Requirements & Integration Notes

This document captures everything needed to implement the **consent-facade**: the exact contract-service API the
[Prometheus-X / Visions consent-manager](https://github.com/VisionsOfficial/consent-manager) consumes, the data models
involved, the TM Forum projection, and the hard-won gotchas. It is derived from reading the **running consent-manager**
(image `consent-manager:local`, sources under `/usr/src/app/dist/src/...`), the Prometheus-X
[`catalog-api`](https://github.com/Prometheus-X-association/catalog-api), and the proof-of-concept Node facade shipped
with the DSC.

> All "verified" claims below were checked against source. File paths in `code font under /usr/src/app` refer to the
> consent-manager image; re-`grep` them there to re-verify.

---

## 0. Design decisions & current status (authoritative — read first)

These supersede any conflicting detail below (§6 in particular predates the design pivot).

1. **Source model = TM Forum `Agreement`, not `ProductOrder`.** A bilateral contract is projected from the
   EDC-written TM Forum **Agreement** (characteristics `policy`, `asset-id`, `provider-id`, `consumer-id`,
   `signing-date`; engaged-party roles `Provider`/`Consumer`), resolving `agreementItem → productOffering →
   productSpecification` (and `→ product → productSpecification`) for the catalog graph. The generated `ProductOrder`
   client is **unused** and should be removed. §6's `ProductOrder → BilateralContract` mapping is **superseded** by this.

2. **Purpose is modelled on the `ProductSpecification` as a characteristic.** The provider authors it where it defines
   the data product. A well-known `productSpecCharacteristic` (name configurable, default `purpose`) carries **one**
   structured value (matching decision 3):

   ```jsonc
   { "name": "purpose", "valueType": "object", "productSpecCharacteristicValue": [ { "value": {
       "id":          "profile-service-provision",              // stable → softwareResource / purpose-offering URL
       "name":        "Personal profile for service provision", // REQUIRED → softwareResource.name → consent purpose
       "description": "Use the subject's profile to deliver the requested service.",
       "purpose":     "https://w3id.org/dpv#ServiceProvision",  // optional DPV purpose (forward-compat receipts)
       "legalBasis":  "https://w3id.org/dpv#Consent"            // optional DPV legal basis
   } } ] }
   ```
   Only `name` is read by today's consent-manager (→ `softwareResource.name` → `privacyNotice.purposes[].purpose`);
   `id` drives the stable URLs; the rest is DPV/ISO-27560 richness for later receipts. If the DSC `tm-forum-api`
   rejects object-typed characteristic values, store `value` as a JSON string the facade parses, or flatten into
   sibling string characteristics (`purpose`, `purpose.dpv`, `purpose.legalBasis`). The facade reads this once per
   spec and emits the data-offering URL, the purpose-offering URL and the software-resource URL deterministically from
   the spec id (consistency invariant, §6).

3. **Granularity = 1 `ProductSpecification` = 1 `DataResource`** → one all-or-nothing `Consent`. Finer per-item/field
   decomposition (§5) is deferred.

4. **What the DSC actually exercises today.** The DSC's current consent flow **seeds the `Consent` directly in Mongo**
   (`consent_grant.sh`) and enforces it via the two-call check (`identifier/search` + `GET
   /consents/participants/{id}?receipt=true`). That path only dereferences participant self-descriptions, so **only
   `GET /participants/{id}` is called today**. The contract endpoints (`/bilaterals/*`, `/contracts/*`, `/verify`)
   and **all** `/catalog/*` endpoints are exercised **only** by the consent-manager's give-consent / privacy-notice
   APIs — i.e. by the *real* give/withdraw flow that replaces the direct-Mongo seed. Completing them (this document's
   remaining work) is the enabler for that flow; it does not change the current seeded demo. The acceptance test is
   therefore "the consent-manager builds a privacy notice with non-empty `data` **and** `purposes`, then
   `giveConsent` succeeds" — not the current demo.

---

## 1. Purpose & context

The consent-manager derives its **privacy notices** and **consents** from a *contract service*, configured through its
`CONTRACT_SERVICE_BASE_URL` env var. In the FIWARE Data Space Connector there is **no dedicated user↔provider bilateral
contract** — the commercial relationship is a TM Forum **`ProductOrder`** (its `relatedParty` is the data consumer) for a
**`ProductOffering`** backed by a **`ProductSpecification`**.

**The facade's job:** implement the contract-service API the consent-manager expects, on top of the TM Forum APIs. It
replaces the proof-of-concept Node `contract-facade` (`UF-10` in the DSC plan).

* Consumer of this facade: the consent-manager (`CONTRACT_SERVICE_BASE_URL` → this service).
* Backing systems: TM Forum product-ordering, product-catalog and party APIs (the DSC `tm-forum-api`).
* Related DSC docs: `data-space-connector/doc/CONSENT_MANAGEMENT.md` and `.../CONSENT_MANAGEMENT_PLAN.md` (§10 facade
  design, §13 DSC integration, `UF-1..UF-14` backlog).
* The POC this replaces: `data-space-connector/charts/data-space-connector/files/contract-facade/{facade.js,mapping.js}`.

---

## 2. The API the facade MUST provide (verified against the consent-manager)

All calls originate from the consent-manager. **Identifiers `{participantId}`/`{providerId}`/`{consumerId}` are the
participants' self-description identifiers, base64-encoded** (the consent-manager `Buffer.from(id,'base64')`-decodes them
internally — `contracts.js` `getPrivacyNoticesFromContractsBetweenParties`).

### 2.1 Contract lookup (`/usr/src/app/dist/src/utils/contracts.js`, `exchanges.js`, `privacyNotices.js`)

| Call | Source | Response |
|---|---|---|
| `GET /bilaterals/for/{participantId}?hasSigned=true` | `contracts.js:115`, `:332` | `{ "contracts": BilateralContract[] }` |
| `GET /contracts/for/{participantId}?hasSigned=true` | `contracts.js:116`, `:333` | `{ "contracts": EcosystemContract[] }` |
| `GET /bilaterals/{contractId}` | `exchanges.js:43`, `privacyNotices.js:7` | `BilateralContract` |
| `GET /contracts/{contractId}` | `exchanges.js:67`, `privacyNotices.js:48` | `EcosystemContract` |
| `GET /verify/{providerId}/{consumerId}` | `contracts.js:255` | verification result (see §2.4) |

* The `hasSigned=true` query means "only contracts signed by all parties".
* **Resolution model:** given a provider id + consumer id, the consent-manager asks for the **provider's** contracts
  (`/bilaterals/for/{providerId}`, `/contracts/for/{providerId}`) and narrows to those involving the consumer. There is
  **no "contract between X and Y" endpoint** other than `/verify`. So the facade indexes contracts by participant.
* ⚠️ The POC facade returned **all** synthesized contracts for **any** `/for/{id}` (it ignored the id). The real facade
  **should filter by the participant id**.

### 2.2 Catalog graph (dereferenced from contract fields)

Contracts reference **URLs** that the consent-manager then GETs. These URLs point back at the facade, so the facade must
serve them. The URL the facade puts into a contract's `serviceOffering` / `purpose[].purpose` **must be a URL this facade
answers**.

| Dereferenced URL | Read by | Expected body |
|---|---|---|
| a **data** `serviceOffering` URL | `getDataFromPoliciesInBilateralContract` / `...EcosystemContract` (`contracts.js`) | `{ "dataResources": [ "<url>", ... ] }` |
| a **purpose** offering URL (`purpose[].purpose`) | `getPurposeFromBilateralContract` (`contracts.js`) | `{ "softwareResources": [ "<url>", ... ] }` |
| a **softwareResource** URL | same | `{ "name": "<purpose name>" }` |
| a **dataResource** URL | *currently not fetched* — `populatePrivacyNotice.js:43-46` is **commented out** | a `DataResource` (see §4) |

> Note: the consent-manager stores each entry of `dataResources[]` **verbatim as a string** in `Consent.data[].resource`
> (`Consent.model.js` → `data: [{ resource: String, serviceOffering: String }]`). It does **not** parse a DataResource
> today. Serve `/catalog/dataresources/{id}` anyway for forward-compatibility.

### 2.3 Participant self-descriptions (⚠️ load-bearing — `/usr/src/app/dist/src/utils/consentReceipt.js`)

`GET /consents/participants/{userId}` **always builds a receipt** for every consent, which does
`axios.get(participant.selfDescriptionURL)` for **both** parties and reads:

```
consumerSelfDescription.data.legalPerson.legalAddress
consumerSelfDescription.data.legalName
consumerSelfDescription.data.legalPerson.subOrganization
(and the same for the provider)
```

⇒ Each participant's `selfDescriptionURL` **must resolve to** `{ "legalName": ..., "legalPerson": { "legalAddress": { "countryCode": ... }, "subOrganization": [...] } }`.
If it 404s or lacks `legalPerson.legalAddress`, this call returns **500**, and the consent PIP treats that as "no consent".
The POC served a stub for exactly this reason (`/sd/provider`, `/sd/consumer`).

### 2.4 `/verify/{providerId}/{consumerId}`

Used by the consent-manager's validate path (`contracts.js:255`). Return whether a signed contract exists between the two
participants; the facade's OpenAPI returns `{ "verified": boolean, "contracts": BilateralContract[] }`. (Exact fields the
consent-manager reads back are not critical — it primarily checks existence.)

---

## 3. Contract models (what the consent-manager reads)

### 3.1 Bilateral contract — an ODRL policy document

Fields the consent-manager actually uses (`contracts.js`, `privacyNotices.js`):

* `dataProvider`, `dataConsumer` — participant self-description identifiers.
* `serviceOffering` — **URL** dereferenced to `{ dataResources }` (§2.2). Matching filter: a policy counts if
  `contract.serviceOffering.includes(policy.permission[].target)` (a **string containment** check).
* `policy[]` — `{ permission: OdrlRule[], prohibition: OdrlRule[] }`; `OdrlRule = { target, action, assigner, assignee, constraint[] }`.
* `purpose[]` — each `{ purpose: <url or string>, ... }`; a purpose URL → `{ softwareResources }` → each → `{ name }`.
* `status` — `signed` / `pending` / `revoked` / `terminated` / `draft` (`hasSigned=true` filters to signed).
* `_id` / `uid`.

Fuller canonical example (from the consent-manager's own simulated service, `dist/src/simulated/contract/router.js`):
top-level `@context`, `@type: Policy`, `@id`, ODRL `permission[]` with `target/assigner/assignee/action/constraint[]`
(spatial, dateTime), a `data[]`, and Kantara-style `purpose[]` (`purposeCategory`, `consentType`, `piiCategory`,
`primaryPurpose`, `thirdPartyDisclosure`, …). Most of that is optional richness; the facade only needs the fields above.

### 3.2 Ecosystem contract — multi-party, orchestrated

Keyed on `serviceOfferings[]` instead of a single `serviceOffering`
(`getDataFromPoliciesInEcosystemContract`):

* `ecosystem`, `orchestrator`, `status`
* `members[]` — `{ participant, role, signature }`
* `serviceOfferings[]` — `{ participant, serviceOffering, policies: OdrlPolicy[] }` (filtered by `participant === dataConsumer`
  for purposes and by provider for data)
* `purpose[]`

> The DSC has no ecosystem-contract source in TM Forum yet — the facade can return an **empty list** for
> `/contracts/for/{id}` initially (the scaffold already does).

---

## 4. Catalog objects

### 4.1 ServiceOffering (self-description the facade serves)

`{ "@context", "@type": "ServiceOffering", "@id", name, description, aggregationOf[], dataResources[], softwareResources[], policy[], providedBy }`

* A **data** offering exposes `dataResources` (data-resource URLs).
* A **purpose/software** offering exposes `softwareResources` (software-resource URLs).
* The POC used **two separate endpoints** for these two roles (`/catalog/offering/{id}/so-data` → `{dataResources}`,
  `/catalog/offering/{id}/so-purpose` → `{softwareResources}`, `/catalog/offering/{id}/sw` → `{name}`). The new OpenAPI
  uses a single `/catalog/serviceofferings/{id}` that can carry both — **decide and keep consistent** with whatever URLs
  the contract's `serviceOffering` and `purpose[].purpose` point at (see §6, "consistency invariant").

### 4.2 DataResource (Prometheus-X `catalog-api`, `src/models/DataResource/DataResource.model.ts`)

Required: `name`, `description`, `producedBy`, `containsPII` (boolean). Optional: `aggregationOf[]`, `copyrightOwnedBy[]`,
`license[]`, `policy[]` (ODRL), `exposedThrough[]`, `category`, `representation` (ref), `isPayloadForAPI` (bool, default
false), `apiResponseRepresentation` (ref), `obsoleteDateTime`, `expirationDateTime`, `schema_version` (default "1"),
timestamps.

### 4.3 Representation (`catalog-api`, `src/models/Representation/Representation.model.ts`)

**Only** `{ resourceID, url, credential }` (+ timestamps). It is an **access endpoint descriptor** — it does **not**
describe the data's fields/attributes. There is **no field/attribute schema anywhere in the catalog model.**

---

## 5. Data granularity (important design conclusion)

* The **unit of consent is a `DataResource`** listed in `serviceOffering.dataResources`, scoped by purpose. A `DataResource`
  is opaque (no field schema; its `representation` is just an endpoint URL).
* **One bilateral contract → one privacy notice → one `Consent`**, and a `Consent` has a **single `status`** covering *all*
  its `data[]`. So everything in one offering is consented **all-or-nothing**.
* ⇒ **Granularity = how the data is partitioned into DataResources/ServiceOfferings.** Finer consent = more resources + more
  provider endpoints. Examples for a user profile:
  * whole "Profile" → 1 DataResource / 1 offering.
  * per top-level item (name, address, …) → model each as its **own DataResource with its own endpoint**; for *independent*
    grant/revoke, each in its **own ServiceOffering** (⇒ own consent). Bundling them in one offering = one all-or-nothing
    consent.
  * sub-entry (`address.street`) → decompose `address` further (recursion; needs a retrievable endpoint per unit).
* **ODRL `AssetCollection` + `refinement` in the `target` does NOT help the consent-manager**: it treats
  `serviceOffering`/`target` as **URL strings** (`serviceOffering.includes(target)` / `axios.get(target)`), never walks the
  object, never evaluates refinements. AssetCollection+refinement *is* the right shape on the **odrl-pap/OPA enforcement**
  side (compiled to rego → path/attribute access control), but that governs *access*, not the consent **record's** scope.
* **The facade is the bridge:** it may *author* granularity as a refined collection and **flatten it into `dataResources`**
  for the consent-manager. Field-level *access* stays on odrl-pap; field-level *consent record* = resource decomposition;
  they only meet if the facade (or a consent-manager extension) keeps them in sync.
* The descriptive DPV/ISO-27560 layer (`purpose[].piiCategory`, `Consent.purposes[].piiInformation[].piiAttributeId`) can
  *name* attributes in the receipt, but is **not** an independent grant switch.

---

## 6. TM Forum projection (how to implement the facade)

Map the TM Forum world into the contract/catalog shapes above.

> ⚠️ **Superseded by §0.1:** the implementation projects the **`Agreement`** (not `ProductOrder`) into a
> `BilateralContract`. The `ProductOrder → BilateralContract` bullet below is kept for historical context only; read
> `dataProvider`/`dataConsumer`/`status`/`_id` from the Agreement's characteristics + engaged parties instead. The
> `ProductSpecification`/catalog rules and the **consistency invariant** below still apply as written.

* **`ProductOrder` → `BilateralContract`** *(superseded — use `Agreement`)* (`/bilaterals/for/{providerId}`, `/bilaterals/{id}`)
  * `dataProvider` = the provider participant self-description id.
  * `dataConsumer` = the ordering org's self-description id (order `relatedParty` → Organization → its `did`).
  * `serviceOffering` = a facade catalog URL for the ordered offering (see invariant below).
  * `status` from the order state (`completed`/`acknowledged` → `signed`; honour `hasSigned=true`).
  * `_id`/`uid` = order id.
  * Filter `for/{participantId}` to orders where that participant is provider or the ordering party.
* **`ProductOffering` → `ProductSpecification` → `dataResources`** (`/catalog/serviceofferings/{id}`)
  * The POC did `dataResources = spec.productSpecCharacteristic[].productSpecCharacteristicValue[].value` (as strings).
    **Decide the real mapping** — this is where granularity (§5) is chosen.
  * `purpose` offering (`softwareResources` → `name`) = the offering/spec name (POC: `offeringToPurposeName`).
* **`Organization` (party) → `SelfDescription`** (`/participants/{id}`)
  * `legalName`, `legalPerson.legalAddress.countryCode`, `subOrganization` (⚠️ required by the receipt builder, §2.3).
  * `did` from the party characteristic named `did` (POC: `organizationDid`).

### Consistency invariant (do not miss this)

The URL the facade writes into `contract.serviceOffering` **must be a URL this facade serves** and that returns
`{ dataResources: [...] }`; likewise `purpose[].purpose` must resolve to `{ softwareResources: [...] }`, and each of those
to `{ name }`. The consent-manager blindly dereferences these URLs. Keep the contract's URLs and the facade's catalog
endpoints in lock-step. (The POC did this with `/catalog/offering/{id}/{so-data|so-purpose|sw}`; the new OpenAPI uses
`/catalog/serviceofferings/{id}`, `/catalog/softwareresources/{id}` — pick one scheme and use it in both the contract and
the served endpoints.)

---

## 7. Gotchas / must-satisfy (all verified)

1. **Participant SD must resolve** to `legalName` + `legalPerson.legalAddress` — else `GET /consents/participants/...`
   returns 500 and the PIP reads it as "no consent" (§2.3).
2. **`serviceOffering`/`target`/`purpose` are URL strings**, GET-ed for `{dataResources}`/`{softwareResources}`/`{name}`.
   No object structures (an `AssetCollection` object breaks `.includes`/`axios.get`) (§5).
3. **Ids are base64-encoded** self-description identifiers (§2).
4. `hasSigned=true` filtering on the `/for/{id}` endpoints.
5. Filter `/for/{id}` by the participant (the POC did not — a real requirement).
6. Contract `status` values, and the bilateral-vs-ecosystem split (`/bilaterals/*` vs `/contracts/*`).
7. `dataResources[]` entries land verbatim in `Consent.data[].resource` — keep them stable, meaningful ids/urls.

---

## 8. Current scaffold state (what exists in this repo)

Built and green (`mvn clean verify`, JDK 21, Micronaut 4). Structure mirrors `fiware/contract-management`.

* **`api/consent-facade.yaml`** — the API from §2, server interfaces generated into `org.fiware.consent.api` / `.model`.
* **TM Forum clients** generated into `org.fiware.consent.tmforum.{productorder,productcatalog,party,agreement}.*`,
  surfaced to the facade through `tmforum/TMForumBackedRepository` (organizations + agreements; an agreement carries
  the ODRL contract policy as its `policy` characteristic, written by the EDC extension's `TMFEdcMapper#toAgreement`).
* **`facade/ConsentFacadeController.java`** — implements `ContractsApi`, `CatalogApi`, `ParticipantsApi`. **No longer
  scaffold** (this §8 note is historical): `/participants/{id}`, `/bilaterals/for/{id}` + `/bilaterals/{id}`, `/verify`,
  `/catalog/serviceofferings`, `/catalog/dataresources` and `/catalog/softwareresources` are implemented
  (Agreement-backed). Ecosystem `/contracts/*` stays empty (§3.2).
* **Purpose chain — done (DSC roadmap item 3, W1–W4):** `AgreementContractMapper` sets `purpose[]` (pointing at the same
  offering URL) and `profile`; the offering carries `dataResources`, `softwareResources` and `userInteraction`;
  `/catalog/softwareresources/{specId}` returns `{name}` read from the spec's `purpose` characteristic (§0.2, name
  configurable via `facade.spec.purpose-characteristic`); `DataResource` carries the required `producedBy`
  (`facade.provider.self-description`) and `containsPII`. Covered by the mapper/controller unit tests.
* **Policy ↔ offering consistency — done.** `AgreementContractMapper` retargets every permission/prohibition rule's
  `target` to the contract's service-offering URL, so the consent-manager's
  `getDataFromPoliciesInBilateralContract` containment check (`serviceOffering.includes(target)`, §3.1) matches and the
  derived privacy notice carries non-empty `data`. (The EDC writes asset URNs; the consent-manager expects a
  service-offering URL, so this is the correct projection, not a workaround.) Covered by the mapper unit test.
* **Still open (the "actual catalog endpoints" pass):**
  1. Richer `DataResource`/`ServiceOffering` (representation/exposedThrough/category/policy; offering name/description/
     providedBy) and the granularity mapping (§5, kept 1 spec = 1 resource for now).
  2. Full agreement pagination + server-side party filtering (today the first 100 agreements only).
  3. An integration test against a real consent-manager (§8 next-step 6) — the end-to-end proof that a seeded agreement
     yields a privacy notice with non-empty `data` **and** `purposes` and a successful `giveConsent`.
* **`configuration/FacadeProperties.java`** — `facade.self-url`, `facade.provider/consumer.self-description`,
  `facade.party.did-characteristic`.
* Empty package slots to fill: **`tmforum/`** (adapters over the generated clients), **`mapping/`** (hand-written
  mappers TM Forum ↔ contract model), **`exception/`** (handlers).
* `application.yaml` — TM Forum client service urls (`product-order`, `product-catalog`, `party`) + `facade.*`.

### Next implementation steps (suggested order)

1. `/participants/{id}` → Organization self-description (unblocks the receipt path first).
2. `/catalog/serviceofferings/{id}` (+ `/catalog/softwareresources/{id}`) → from ProductOffering/Specification; decide the
   `dataResources` granularity mapping (§5/§6).
3. `/bilaterals/for/{participantId}` + `/bilaterals/{id}` → project ProductOrders, wiring the `serviceOffering`/`purpose`
   URLs to the endpoints from step 2 (consistency invariant, §6).
4. `/verify/{providerId}/{consumerId}`.
5. `/contracts/*` (ecosystem) — leave empty until an ecosystem source exists.
6. Integration test against the real consent-manager (e.g. reuse the DSC's `consent_grant.sh` flow / a docker-compose with
   the consent-manager pointed at this facade via `CONTRACT_SERVICE_BASE_URL`).

---

## 9. Reference: consent-manager internals worth knowing (context)

Not implemented by the facade, but useful to understand the end-to-end flow:

* **Consent model** (`Consent.model.js`): `contract` (url), `dataProvider`, `dataConsumer`, `providerUserIdentifier`,
  `consumerUserIdentifier`, `consented` (bool), `status` (pending/draft/granted/revoked/expired/terminated/refused),
  `data: [{ resource, serviceOffering }]`, `purposes: [{ purpose, resource, serviceOffering, piiInformation[...] }]`,
  `privacyNotice`.
* **Identity**: a `UserIdentifier` binds an **email** (the DSC stores the holder **DID** here) to a **participant**
  (`attachedParticipant`). Registered via `POST /v1/users/register` (participant-JWT auth). Resolved via
  `POST /v1/users/identifier/search` (consent-key auth).
* **Enforcement (DSC)**: an odrl-pap `consent:hasValidConsent` constraint runs a two-call chain against the consent-manager
  (`identifier/search` → `GET /v1/consents/participants/<id>?receipt=true`, allow iff some consent `status === granted`).
  The `receipt=true` call is the one that requires participant SDs to resolve (§2.3). See DSC `CONSENT_MANAGEMENT.md`.
* **Contract-service API also exposed by the consent-manager itself**: OpenAPI at `/docs` (spec `docs/swagger.json`,
  "Prometheus-X Consent Manager", 41 paths).

---

## 10. Source index (for re-verification)

Consent-manager (image `consent-manager:local`, `kubectl -n provider exec deploy/consent-manager -- cat <path>`):
* `dist/src/utils/contracts.js` — contract lookup, `serviceOffering → dataResources`, `purpose → softwareResources → name`,
  bilateral vs ecosystem, `/verify`.
* `dist/src/utils/consentReceipt.js` — the receipt builder that fetches participant SDs (§2.3).
* `dist/src/utils/exchanges.js`, `dist/src/utils/privacyNotices.js`, `dist/src/utils/populatePrivacyNotice.js`.
* `dist/src/models/{Consent,UserIdentifier,Participant,User}/*.model.js`.
* `dist/src/simulated/contract/router.js` — a full worked contract example.

Prometheus-X catalog: `github.com/Prometheus-X-association/catalog-api` →
`src/models/DataResource/DataResource.model.ts`, `src/models/Representation/Representation.model.ts`.

DSC POC (reference implementation this replaces):
`data-space-connector/charts/data-space-connector/files/contract-facade/{facade.js,mapping.js}`.

---

## 11. Multi-provider support (plan)

A data space has **many providers**, each with its **own TM Forum backend** (`tm-forum-api`). One consent-facade (at `CONTRACT_SERVICE_BASE_URL`) must therefore serve contracts/catalog/participants across all of them and route every request to the right provider's TM Forum endpoint. **The consent-manager needs no changes** — it only ever calls the facade with identifiers the facade itself minted (§2), so the facade can carry the provider in them.

### 11.1 How the provider is known per request

| Facade endpoint | Provider on the wire today? | Source of the provider key |
|---|---|---|
| `GET /bilaterals/for/{participantId}` | **yes** | `participantId` = base64(provider SD URL) → decode → provider key |
| `GET /contracts/for/{participantId}` | yes | same |
| `GET /verify/{providerId}/{consumerId}` | yes | `providerId` = base64(provider SD URL) |
| `GET /participants/{id}` | via URL the facade minted | provider-key segment in the SD URL (§11.4) |
| `GET /catalog/serviceofferings\|dataresources\|softwareresources/{id}` | via URL the facade minted | provider-key segment in the catalog URL (§11.4) |
| `GET /bilaterals/{contractId}` \| `/contracts/{contractId}` | via id the facade minted | composite id `{providerKey}:{agreementId}` (§11.4) |

So the `/for` + `/verify` calls already name the provider; the resource endpoints don't yet, but the facade authors those ids/URLs and can encode the provider into them.

### 11.2 Design principles

1. **`providerKey` is a first-class part of every facade-minted identifier** — participant SD URLs, catalog URLs, and composite contract ids. URL-safe, stable (e.g. a slug or the provider's org id / DID).
2. **A `ProviderRegistry` abstraction** resolves `providerKey → ProviderConfig{ tmforumBaseUrl, … }`. Swappable implementation: **static config first**, **DB + admin API later**.
3. **A `TMForumClientFactory`** produces per-provider TM Forum access at runtime (keyed by endpoint, cached).
4. **Backward compatible** — a single `default` provider entry keeps today's single-endpoint behaviour working.

### 11.3 Phase 1 — Provider registry abstraction + static config (foundation) — **implemented**

- `ProviderConfig` (record): `key`, `tmforumBaseUrl` (+ later `didCharacteristic`, `purposeCharacteristic`, auth).
- `ProviderRegistry` (interface): `Optional<ProviderConfig> byKey(String)`, `ProviderConfig defaultProvider()`, `Collection<ProviderConfig> all()`.
- `StaticProviderRegistry` (`@Singleton`) backed by `ProviderConfiguration` (`@EachProperty("facade.providers")`); a `default` provider is required and its absence fails fast at startup.
- Config `facade.providers.<key>.tmforum-base-url` (default `default` entry in `application.yaml`); **not yet consumed** — the generated TM Forum clients still use `micronaut.http.services.*` until Phase 3.
- Package `org.fiware.consent.provider`; unit-tested in `StaticProviderRegistryTest`. No behaviour change (nothing routes through the registry yet — that is Phase 4).
- Deferred to Phase 2/4: `byParticipantSelfDescription(sdUrl)` (needs the provider-keyed SD-URL scheme first).

### 11.4 Phase 2 — Provider-keyed identifier scheme — **implemented**

**Refinement over the original sketch:** the provider key is encoded as a **composite single path segment** `providerKey~localId` (a `ProviderScopedId`), *not* as an extra path segment `…/{providerKey}/{id}`. This keeps every API path a single `{id}` variable, so **`api/consent-facade.yaml` is unchanged and nothing is regenerated** — the composite is purely the facade's own encode/decode. Separator is `~` (URL-safe, RFC 3986 unreserved) rather than `:`, because the FIWARE TM Forum API mints `urn:ngsi-ld:…` ids that are full of colons; `~` appears in neither those ids nor provider-key slugs, and decode splits on the *first* `~` so colon-laden local ids survive.

- `org.fiware.consent.provider.ProviderScopedId` (record + `encode()`/`decode()`): the wire form; a bare id (no `~`) decodes to the `default` provider, so ids minted before this scheme keep resolving.
- `CatalogUrls`: `serviceOffering`/`dataResource`/`softwareResource` now take `(providerKey, localId)` and mint `…/catalog/<kind>/{providerKey}~{localId}`.
- Contract `_id`/`uid` → `{providerKey}~{agreementId}`; `getBilateralContract`/`getServiceOffering`/`getDataResource`/`getSoftwareResource` decode the incoming id, look the backend up by the **local** id, and re-mint with the **same** key (so a non-default key round-trips; a bare legacy id resolves under `default`).
- Because a contract's `dataProvider`/`dataConsumer` **are** the SD URLs and its `serviceOffering`/`purpose[]` **are** the catalog URLs, the provider key propagates through the whole graph for free.
- Still Phase 5: **participant** SD URLs stay bare (they are minted at registration, §11.7); `getParticipantSelfDescription` already decodes tolerantly so Phase 5 is a pure minting change.
- Minting still stamps the `default` key everywhere (single backend until Phase 4). Covered by `ProviderScopedIdTest` + updated mapper/controller tests.

### 11.5 Phase 3 — Per-provider TM Forum access — **implemented**

- **Problem:** the generated TM Forum clients are declarative `@Client(id=…)` beans bound to a **compile-time** service URL, so they cannot target a per-request base URL.
- **Solution — a `TMForumApis` seam.** `TMForumBackedRepository` no longer injects the five generated clients; it holds one `TMForumApis` (the raw reads it needs, returning already-unwrapped `Mono`/`Flux`, empty on 404). Two implementations:
  - `GeneratedTMForumApis` (`@Singleton`) — the **default** provider, wrapping the generated `@Client` beans (`micronaut.http.services.*`). It is the only `TMForumApis` **bean**, so it is what the context injects; behaviour and all `@MicronautTest`/mock wiring are unchanged (mocks still bite at the generated-client layer beneath it).
  - `HttpTMForumApis` — any **other** provider, over a low-level Micronaut `HttpClient` bound to that provider's base url, hitting the standard TM Forum v4 paths (`TMForumEndpoints`) and deserializing into the same generated model classes.
- `TMForumClientFactory` (`@Singleton`, in `provider`): `forProvider(config)` → the injected default repository for the `default` key, else a `TMForumBackedRepository` over an `HttpTMForumApis` on a client created for `config.tmforumBaseUrl()`. Clients are cached per base url, repositories per provider key, and clients are closed on shutdown (`@PreDestroy`).
- **Not yet wired into the controller** — the factory + low-level path exist and are unit-tested (`TMForumClientFactoryTest`, `HttpTMForumApisTest`), but the controller still uses the injected default repository directly; routing through the factory is Phase 4. So this phase is behaviour-preserving (73 tests green).
- **Deployment note for Phase 4:** the default provider still reads via the generated clients (`tmfApiUrl` → `micronaut.http.services.*`), so `facade.providers.default.tmforum-base-url` stays **unused**; a *non-default* provider must supply its own `facade.providers.<key>.tmforum-base-url` (host root, like `tmfApiUrl`).

### 11.6 Phase 4 — Route each endpoint by provider — **implemented**

- The controller no longer injects a single repository; it injects the `ProviderRegistry` + `TMForumClientFactory` and routes every request:
  - **Id-carrying endpoints** (`/bilaterals/{id}`, all `/catalog/*`, `/participants/{id}`) go through `routeById`: decode the `ProviderScopedId` → `ProviderRegistry.byKey` → `TMForumClientFactory.forProvider` → that provider's repository, projecting with the decoded key. An **unknown provider key short-circuits to `404`** (it can't be routed).
  - **Participant-scoped lookups** (`/bilaterals/for`, `/verify`) **fan out** across `ProviderRegistry.all()` via `projectAllContracts()` — a participant may hold contracts at more than one provider — projecting each provider's agreements with its own key, then filtering by participant.
- Behaviour-preserving for a single-provider deployment: fan-out over `[default]` and `byKey("default")` reproduce the previous single-backend behaviour exactly; the only new outcome is `404` for an unregistered key. Covered by `getBilateralContract_returns404ForAnUnknownProvider` / `getServiceOffering_returns404ForAnUnknownProvider` (74 tests green).

### 11.7 Phase 5 — Provider-aware registration — **facade side implemented**

Participant self-description URLs are **shared identity**: the value in a contract's `dataProvider`/`dataConsumer` (from the agreement's `provider-id`/`consumer-id`) and the one stored at registration (`selfDescriptionURL`) must be **byte-identical**, because the consent-manager matches them. So the facade cannot unilaterally retarget participant URLs the way it retargets its private catalog URLs (§11.4). The facade's role therefore splits:

- **Serve + route** provider-keyed participant URLs — done in Phase 4 (`getParticipantSelfDescription` decodes the `ProviderScopedId` and routes `byKey`; a bare/legacy id resolves to `default`).
- **Emit** its own participant references consistently. The only participant URL the facade *emits* is a data resource's `producedBy` (the SD itself carries no dereferenceable self-id, and sub-organizations are plain ids). Phase 5 makes `producedBy` the **routed provider's own** self-description: `ProviderConfig.selfDescription` (`facade.providers.<key>.self-description`), resolved by provider key in `CatalogMapper`, falling back to the legacy global `facade.provider.self-description` when a provider configures none. Behaviour-preserving for single-provider (the `default` provider configures none → same static as before).

**Deployment counterpart (DSC, not the facade — the enabler for a real second provider):** whoever registers a participant (the deploy-time register Job / `consent_grant.sh`) and whatever writes the agreement (`provider-id`/`consumer-id`) must mint the **provider-keyed** `{selfUrl}/participants/{providerKey}~{orgId}` form. Until they do, participant URLs stay bare and route to `default` (backward-compatible), which is why the single-provider live flow is unaffected.

**Consumer-org modelling (resolved):** the `providerKey` in a participant SD URL names *which backend holds that org record*, not "this participant is that provider". A consumer's org is registered in the provider's `tm-forum-api` (as an ordering party), so the consumer's SD URL is keyed with the **provider's** key — both parties' SD URLs route to the same backend, which is exactly what the receipt build (which dereferences both) needs.

### 11.8 Phase 6 — Dynamic registry (API + DB) — future

- `PersistentProviderRegistry` backed by a database (Micronaut Data; small schema `provider(key, tmforum_base_url, …)`), replacing/overlaying the static config; cache with refresh.
- Admin API: `POST/GET/PUT/DELETE /providers` (create/list/update/remove a provider→endpoint mapping), authenticated. Seed the DB from `facade.providers` on first start.
- The registry interface (§11.3) is unchanged, so Phases 1–5 are written against it and this phase is a drop-in implementation swap plus the CRUD controller.

### 11.9 Out of scope / notes

- Ecosystem (`/contracts/*`) multi-provider federation stays deferred (no TM Forum source yet).
- `providerKey` format decision (slug vs org id vs DID) is a one-way door for URL stability — pick early.
