# OpsPilot dashboard

Angular single-page app for working incidents: a live incident list with filters and search, and an incident page with its timeline, actions (acknowledge, assign, comment, resolve) and the AI investigation assistant.

## Run
```
npm install
npm start      # http://localhost:4200; /api is forwarded to the backend on localhost:8080
npm test       # unit tests (Vitest)
npm run build  # production build in dist/
```
The scripts call the Angular CLI through `node`, so they also work in folders whose path contains characters such as `&`.

In Docker (`docker compose --profile demo up`), nginx serves the build and forwards `/api` to OpsPilot (`nginx/default.conf.template`; the upstream is set with `OPSPILOT_API_URL`).

## How it works
- **Sign-in and sessions** (`core/auth.ts`, `core/auth.interceptor.ts`):
  - **Where the tokens live:** the access token is kept in memory only, never in `localStorage`, so an injected script cannot steal it from storage. The session itself lives in an HttpOnly cookie, which scripts cannot read either.
  - **After a reload:** before the first navigation, the app exchanges the cookie for a new access token, so a reload keeps you where you were.
  - **Renewal:** the token is renewed a minute before it expires. A request the API rejects with 401 is retried once with a renewed token, and concurrent callers share one renewal.
  - **Several tabs:** tabs take turns renewing (Web Locks), since each renewal replaces the shared cookie. Signing out in one tab signs out all of them (BroadcastChannel).
  - **CSRF:** requests that use the cookie send `X-Requested-With`.
- **Live updates:** one connection to `GET /api/v1/incidents/stream`, shared by all pages (`incidents/incident-stream.ts`). It is read with `fetch`, because the browser's `EventSource` cannot send the `Authorization` header, and parsed by `incidents/sse-parser.ts`. It reconnects with backoff; the header shows its state.
- **Keeping the list right:** pages load their data on `ready`/`resync`. Updates that arrive while loading are held and applied afterwards, and an update never replaces a newer copy (`incidents/incident-merge.ts`, which has the rules and their tests). An incident that stops matching the filters (e.g. resolved while viewing open ones) leaves the list; a new one that matches appears in its place.
- **Assistant:** `incidents/assistant-panel.ts` is shown only when `GET /api/v1/assistant` says it is on. Investigate starts an investigation; the result arrives as an `INVESTIGATION_COMPLETED` timeline event on the live stream, and the panel then loads it. Each finding links to its sources: a timeline event (scrolls to it and highlights it), an earlier incident (opens it) or an alert. All text the model wrote is shown as plain text, never as HTML.
- **Security headers:** nginx sends a strict Content-Security-Policy (scripts only from the app's own origin). Critical-CSS inlining is turned off in `angular.json` because it relies on an inline event handler that this policy blocks.
