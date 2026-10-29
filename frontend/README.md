# OpsPilot dashboard

Angular single-page app for working incidents: a live incident list with filters and search, and an incident page with its timeline and actions (acknowledge, assign, comment, resolve).

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
- **Sign-in:** the token is kept in memory only, never in `localStorage`, so a script injected into the page cannot steal it from storage. Reloading the page asks you to sign in again. The session ends when the token expires (1 hour).
- **Live updates:** one connection to `GET /api/v1/incidents/stream`, shared by all pages (`incidents/incident-stream.ts`). It is read with `fetch`, because the browser's `EventSource` cannot send the `Authorization` header, and parsed by `incidents/sse-parser.ts`. It reconnects with backoff; the header shows its state.
- **Keeping the list right:** pages load their data on `ready`/`resync`. Updates that arrive while loading are held and applied afterwards, and an update never replaces a newer copy (`incidents/incident-merge.ts`, which has the rules and their tests). An incident that stops matching the filters (e.g. resolved while viewing open ones) leaves the list; a new one that matches appears in its place.
- **Security headers:** nginx sends a strict Content-Security-Policy (scripts only from the app's own origin). Critical-CSS inlining is turned off in `angular.json` because it relies on an inline event handler that this policy blocks.
