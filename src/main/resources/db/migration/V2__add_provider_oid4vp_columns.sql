-- Per-provider OID4VP parameters for authenticating outbound TM Forum calls
-- (implementation-plan.md step 4). Both nullable: a null column falls back to the
-- facade-level oid4vp.client-id / oid4vp.scopes. Scopes are stored space-delimited.
ALTER TABLE provider ADD COLUMN client_id VARCHAR(255);
ALTER TABLE provider ADD COLUMN scopes    VARCHAR(2048);
