# Consent Facade

Facade that exposes the [Prometheus-X / Visions consent-manager](https://github.com/VisionsOfficial/consent-manager)
*contract-service* API on top of the FIWARE [TM Forum APIs](https://github.com/FIWARE/tmforum-api).

The consent-manager derives its privacy notices and consents from a *contract service* (configured through its
`CONTRACT_SERVICE_BASE_URL`). In the FIWARE Data Space Connector there is no dedicated bilateral contract between a user
and a provider - the commercial relationship is a **TM Forum `ProductOrder`** (its `relatedParty` is the data consumer)
for a **`ProductOffering`** backed by a **`ProductSpecification`**. This service projects that world into the shape the
consent-manager expects:

* a **bilateral contract** per product order (`/bilaterals/...`),
* the **catalog graph** the contracts dereference - service offerings (`/catalog/serviceofferings/{id}`), data resources
  (`/catalog/dataresources/{id}`), software resources (`/catalog/softwareresources/{id}`),
* the **participant self-descriptions** (`/participants/{id}`) read while building consent receipts.

It replaces the proof-of-concept `contract-facade` (a small Node script) shipped with the DSC (`UF-10`).

## API

The API provided towards the consent-manager is defined in [`api/consent-facade.yaml`](api/consent-facade.yaml). The
endpoints mirror exactly what the consent-manager's contract-service client calls:

| Method & path | Purpose |
|---|---|
| `GET /bilaterals/for/{participantId}?hasSigned=true` | bilateral contracts a participant is party to |
| `GET /bilaterals/{contractId}` | a single bilateral contract |
| `GET /contracts/for/{participantId}?hasSigned=true` | ecosystem contracts a participant is party to |
| `GET /contracts/{contractId}` | a single ecosystem contract |
| `GET /verify/{providerId}/{consumerId}` | verify a signed contract exists between two participants |
| `GET /catalog/serviceofferings/{id}` | service-offering self-description (`dataResources` / `softwareResources`) |
| `GET /catalog/dataresources/{id}` | data-resource self-description |
| `GET /catalog/softwareresources/{id}` | software-resource self-description (its `name` becomes the purpose) |
| `GET /participants/{id}` | participant self-description (`legalName`, `legalPerson.legalAddress`) |

> `{participantId}`, `{providerId}` and `{consumerId}` are the participants' self-description identifiers, base64-encoded,
> exactly as the consent-manager passes them.

## Tech stack

* [Micronaut](https://micronaut.io/) 4 (Java 21)
* API-first: server interfaces and TM Forum clients are generated from OpenAPI with the
  [openapi-generator](https://openapi-generator.tech/) + [kokuwa micronaut codegen](https://github.com/kokuwaio/micronaut-openapi-codegen)
* [jib](https://github.com/GoogleContainerTools/jib) for the container image

Package layout (following [`fiware/contract-management`](https://github.com/fiware/contract-management)):

```
api/consent-facade.yaml            # the API provided towards the consent-manager
src/main/java/org/fiware/consent/
  Application.java
  configuration/                   # @ConfigurationProperties
  facade/                          # controllers implementing the generated server API
  tmforum/                         # adapters over the generated TM Forum clients (TMForumBackedRepository)
  mapping/                         # mappers TM Forum <-> contract model
  exception/                       # exception handlers
src/main/resources/application.yaml
```

## Build

```shell
mvn clean verify
```

Generated sources land in `target/generated-sources/openapi` (server API under `org.fiware.consent.api` / `.model`,
TM Forum clients under `org.fiware.consent.tmforum.*`).

## Run

```shell
mvn mn:run
# or
java -jar target/consent-facade-*.jar
```

Point it at the TM Forum APIs and set its own public url via `application.yaml` (or the corresponding env vars):

```yaml
micronaut:
  http:
    services:
      product-order: { url: http://tm-forum-api:8080 }
      product-catalog: { url: http://tm-forum-api:8080 }
      product-inventory: { url: http://tm-forum-api:8080 }
      party: { url: http://tm-forum-api:8080 }
      agreement: { url: http://tm-forum-api:8080 }
facade:
  self-url: http://consent-facade:8080
```

## Container image

```shell
mvn clean package -Poci                 # build locally
mvn clean deploy  -Poci -Dimage.tag=...  # build & push (CI)
```

Image: `quay.io/wi_stefan/consent-facade`.

## Status

Initial scaffold: project structure, build, CI and the OpenAPI contract are in place. The controllers/mappers that
translate TM Forum payloads into the contract and catalog self-descriptions are the next step - see the `facade`,
`tmforum` and `mapping` packages.

## Requirements & integration notes

**[`REQUIREMENTS.md`](REQUIREMENTS.md)** captures everything needed to continue the implementation: the exact
contract-service API the consent-manager consumes (with source references), the contract/catalog data models, the data
granularity model, the TM Forum projection, the verified gotchas, and the suggested next-step order. Start there.
