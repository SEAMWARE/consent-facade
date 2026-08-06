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

* **`ProductOrder` → `BilateralContract`** (`/bilaterals/for/{providerId}`, `/bilaterals/{id}`)
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
* **`facade/ConsentFacadeController.java`** — implements `ContractsApi`, `CatalogApi`, `ParticipantsApi`. **Scaffold only:**
  list endpoints return empty results, single lookups return `404`. **This is where the projection is wired in.**
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
