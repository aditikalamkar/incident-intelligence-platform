# ADR 0005: An investigation assistant built on Claude

Status: accepted

## Context
The first minutes of an incident go into catching up: what fired, what has been tried, whether this happened before. The answers are already in OpsPilot (alerts, the timeline, comments, earlier incidents on the same service), spread over several screens. A language model can read them and give the responder a short summary, findings and next steps.

An AI feature in an incident tool must not make things worse:
- A wrong answer must be easy to spot.
- Nothing may act on the system unchecked.
- Customer data must not leave without the operator choosing that.
- Cost must stay bounded.

## Decision
- **Claude, one request per investigation.** OpsPilot gathers the incident's data and sends it in one request (Messages API, official Java SDK); there is no agent loop. Everything needed is known up front, so a single request is faster, cheaper and more predictable. Tool use would only add cost.
- **Structured output.** The answer must match a JSON schema derived from the `Analysis` record: a summary, findings and next steps. It is parsed and stored as data, never shown as free-form text.
- **Every claim cites its evidence.** Each alert, timeline event and earlier incident in the briefing has a reference (`A1`, `E3`, `P2`), and every finding and next step lists the references it relies on. OpsPilot turns them into links and **drops any reference that was not in the briefing**, so a link always leads to something real. The dashboard shows the links beside each item, says which model wrote it, and how many timeline events have happened since.
- **The assistant only reads.** It has no tools and cannot change an incident. Comments and alert payloads are written by people and monitoring systems, and could contain text meant to steer the model (prompt injection). They are quoted as JSON strings, and the instructions tell Claude to treat them as evidence, never as instructions. At worst, injected text could produce a misleading summary, which is labelled as AI-written and comes with its sources.
- **Asynchronous, stored, shared.** `POST /incidents/{id}/investigations` creates a `RUNNING` row and returns 202. The work runs on a virtual thread once the transaction commits. The result is stored in `investigation`. Finishing writes an `INVESTIGATION_COMPLETED` or `INVESTIGATION_FAILED` event to the incident's timeline, so:
  - the result reaches every open browser through the existing live stream (ADR 0004);
  - the timeline records who asked the assistant, and when;
  - teammates see the result without paying for it again.
- **One investigation at a time per incident.** A partial unique index allows one `RUNNING` row per incident. A second request, including a simultaneous one, gets the running investigation back instead of starting another.
- **Bounded cost.**
  - The briefing is capped: the latest 150 events, 20 alerts, and 5 earlier incidents from the last 30 days; long comments and payloads are shortened. The briefing says when something was left out.
  - At most 4 investigations run at once per instance; the rest wait their turn.
  - Each organisation has a daily limit (100 by default); after that the API answers 429.
  - Token use is a metric, tagged by model.
- **Model settings.** The model is Claude Opus 5.5 (`claude-opus-5-5`) at `medium` effort, and both are configurable. The model thinks adaptively; effort controls how much.
- **Refusals.** Requests ask for the API's server-side fallback (`fallbacks: "default"`). If a safety classifier declines, the API re-runs the request on the model Anthropic recommends for that kind of refusal. Security incidents (brute-force alerts, exploit attempts in logs) are where this matters most. If every model declines, the investigation fails with a message saying so.
- **Off by default.** The assistant runs only when `ANTHROPIC_API_KEY` is set. `GET /api/v1/assistant` tells clients whether it is on, and the dashboard hides it when it is not.

## Consequences
- **Incident data goes to Anthropic** when the assistant is on: titles, alert payloads, comments and user names. Operators must accept that, and a commercial deployment must list Anthropic as a subprocessor. Per-organisation opt-in is a likely next step.
- **Restarts and crashes.**
  - A graceful shutdown records running investigations as interrupted.
  - After a crash, an investigation stays `RUNNING` until it is 15 minutes old, the most it could take with API timeouts and retries. After that it is shown as interrupted and can be started again.
- **Daily-limit races.** The limit is counted before the insert without a lock, so two requests at the same moment can exceed it by one. It bounds cost; it is not a billing record.
- **Prompt caching does not apply.** The instructions are shorter than the minimum cacheable prefix, and each briefing is different.
- **Testing.**
  - Unit tests run the real SDK against a local server that plays the API, and check what is sent (model, fallbacks, effort, schema, authentication) and how every kind of answer is handled.
  - Integration tests replace the model with `TestInvestigator`.
  - Nothing in CI calls the real API.
