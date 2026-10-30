# ADR 0002: API keys for machines, JWTs for people

Status: accepted; token lifetime and sessions amended by ADR 0006

## Context
Two kinds of callers: monitoring systems that push alerts, and engineers who work incidents. They need different credentials, lifetimes and permissions. A leaked webhook key must not expose incident data.

## Decision
- **Webhooks** (`/api/v1/webhooks/**`) accept only an `X-API-Key`. Keys are random 256-bit values; only their SHA-256 hash is stored. Admins create and revoke them.
- **Everything else** accepts only a Bearer JWT, issued by `/api/v1/auth/login` or `/signup`. Tokens are HS256-signed, carry `sub` (user), `tenant_id` and `role`, and expire after 15 minutes; clients renew them from their session (ADR 0006).
- The two schemes are separate Spring Security filter chains, so neither credential works on the other's endpoints.
- Roles: `ADMIN` (users, teams, API keys) and `RESPONDER` (incident work), enforced with `@PreAuthorize`.
- Email is globally unique, so login needs only email and password.

## Consequences
- Stateless: no session store, any instance can verify a token.
- A role change or user removal takes effect only when the current token expires. ADR 0006 shortened access tokens to 15 minutes and added sessions with refresh tokens and revocation.
- A symmetric key is simple to operate for a single service. If other services ever need to verify tokens, switch to RS256 with a published JWK set.
- One email cannot belong to two tenants. Agencies serving several clients will need a membership model later.
