/**
 * ai module: the investigation assistant. On request it gives Claude an incident's alerts, timeline
 * and recent history on the same service, and stores the summary, findings and next steps it returns,
 * each linked to the items it relies on. It only reads; it never changes an incident. See
 * docs/adr/0005-investigation-assistant.md.
 */
package com.opspilot.ai;
