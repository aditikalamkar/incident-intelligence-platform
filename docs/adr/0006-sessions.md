# ADR 0006: Sessions with rotating refresh tokens in an HttpOnly cookie

Status: accepted (amends ADR 0002)

## Context
Access tokens were 1-hour JWTs held only in the dashboard's memory. That caused two problems:
- Reloading the page signed the user out.
- A stolen token worked for up to an hour, and nothing could revoke it.

Keeping tokens in `localStorage` would survive a reload, but any injected script could read them.

## Decision
- **Two credentials.**
  - The **access token** is a JWT that lasts 15 minutes. It is kept in memory only and sent as `Authorization: Bearer`.
  - The **refresh token** is 256 random bits in an HttpOnly cookie. The server stores only its SHA-256 hash.
- **Sessions on the server.** Each sign-in creates a `user_session`.
  - It ends after 7 days without a refresh: each refresh token expires 7 days after it is issued.
  - It ends 30 days after sign-in, however active it was.
- **Rotation with reuse detection.** `POST /api/v1/auth/refresh` marks the presented token used and issues the next one.
  - A used token presented again means two parties hold it, and we cannot tell which is the thief.
  - So the whole session is revoked, and both must sign in again. Each case counts toward the `REUSED` outcome of the refresh metric.
- **Concurrent refreshes are expected, not mistaken for theft.**
  - Within a tab, the dashboard shares one refresh request between all callers.
  - Across tabs, it holds a Web Lock while refreshing, so tabs take turns, and each sends the cookie the previous one received.
  - On the server, a refresh locks the token's row (`FOR UPDATE`). A token presented again within 10 seconds of its rotation is a race from the same browser. That request gets an access token but no new cookie, because the first response's cookie is the one that stands.
- **The cookie.**
  - `HttpOnly`: no script can read it.
  - `Secure`, except in the dev profile, which runs over plain HTTP.
  - `SameSite=Strict`: browsers never attach it to a request another site starts.
  - `Path=/api/v1/auth`: it travels only to the auth endpoints, not with every API call.
  - `Max-Age` equals the token's lifetime, and never exceeds the session's.
- **CSRF.** The browser attaches the cookie by itself, so the endpoints that act on it (`refresh`, `logout`) check where the request came from:
  - They require an `X-Requested-With` header (any value). A foreign page cannot add a custom header without a CORS preflight, and OpsPilot never grants one.
  - They refuse any request whose `Sec-Fetch-Site` is not `same-origin`.
  - `SameSite=Strict` on the cookie is a third, independent barrier.
  - Login and signup accept only `application/json`, which a cross-site form cannot send without a preflight.
  - All of this relies on the dashboard being served from the same origin as the API, through its nginx proxy.
- **Revocation.**
  - Signing out revokes the session (any of its tokens identifies it) and deletes the cookie.
  - Signing in again in the same browser ends that browser's previous session.
  - Reuse revokes the session, as above.
  - Each refresh reads the user's current role, so a role change takes effect within 15 minutes.
- **Restoring after a reload.** The dashboard calls `refresh` before its first navigation. If the session is alive, the user stays on the page they were on.
- **Clean-up.** An hourly job deletes tokens and sessions that ended more than a day ago.

## Consequences
- **Access tokens are not revoked.** One copied before sign-out keeps working until it expires, within 15 minutes, because JWTs are not checked against the session on every request. A denylist would close this, at the cost of a database lookup per request.
- **The grace period has a cost.** A copy replayed within 10 seconds of the legitimate rotation gets a 15-minute access token without revoking the session. The window is configurable (`opspilot.security.session.reuse-grace-period`; `PT0S` turns it off).
- **A lost response ends the session.** If a refresh response is lost on the network, the browser keeps the used token, and its next refresh, after the grace period, ends the session. This fails safe: the user signs in again.
- **Live streams reconnect every 15 minutes.** A stream still ends when its access token expires, now every 15 minutes. The dashboard reconnects at once with the token it renewed a minute earlier, without showing a reconnection.
- **Same site only.** Serving the dashboard from a different site than the API would need CORS with credentials and `SameSite=None`. That is deliberately not supported.
- **Possible next steps.** The data model already supports "sign out everywhere" and a list of active sessions; neither is built yet.
