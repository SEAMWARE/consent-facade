# Architecture Decision Records

Decisions that constrain this service's API. Both records below are **mirrors** - the canonical
copies live in [FIWARE/data-space-connector](https://github.com/FIWARE/data-space-connector/blob/main/doc/adr/README.md) `doc/adr/`, which also holds
the design document they belong to and the decisions that are purely deployment-side (0001, 0004).
Numbering is shared across both repositories, so ADR-0002 is the same decision in either place.

| # | Decision | Status |
|---|---|---|
| [0002](0002-reuse-facade-oid4vp-client.md) | Reuse this facade's OID4VP client instead of implementing or importing OID4VP in Go | Accepted |
| [0003](0003-token-endpoint-not-consent-proxy.md) | Expose an internal token endpoint, not a consent proxy | Accepted |

Together they mean: this service gains a `POST /internal/tokens` endpoint that mints OID4VP access
tokens for **named**, pre-configured audiences, reachable only in-namespace, and it does **not**
proxy consent-manager traffic.
