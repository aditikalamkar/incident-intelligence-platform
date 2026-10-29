import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject } from 'rxjs';
import { Incident, Investigation, TimelineEvent } from '../core/api.models';
import { Directory } from '../core/directory';
import { AssistantPanel } from './assistant-panel';
import { IncidentStream, StreamEvent } from './incident-stream';

const INCIDENT: Incident = {
  id: 'inc-1',
  serviceId: 'svc',
  title: 'HighLatency on checkout-api',
  severity: 'HIGH',
  status: 'ACKNOWLEDGED',
  assigneeId: 'alice',
  alertCount: 1,
  version: 2,
  openedAt: '2026-10-29T14:05:00Z',
  acknowledgedAt: '2026-10-29T14:08:00Z',
  resolvedAt: null,
};

function investigation(changes: Partial<Investigation> = {}): Investigation {
  return {
    id: 'inv-1',
    incidentId: INCIDENT.id,
    status: 'RUNNING',
    requestedBy: 'alice',
    model: null,
    createdAt: '2026-10-29T14:20:00Z',
    completedAt: null,
    result: null,
    failure: null,
    ...changes,
  };
}

const COMPLETED = investigation({
  status: 'COMPLETED',
  model: 'claude-opus-5-5',
  completedAt: '2026-10-29T14:20:40Z',
  result: {
    summary: 'Checkout latency doubled after the 14:05 deploy.',
    findings: [
      {
        text: 'p99 rose when the deploy finished.',
        sources: [
          { kind: 'EVENT', id: 'ev-2', label: 'Comment by Alice', at: '2026-10-29T14:10:00Z' },
          { kind: 'ALERT', id: 'al-1', label: 'HighLatency', at: '2026-10-29T14:05:00Z' },
        ],
      },
    ],
    nextSteps: [
      {
        text: 'Roll back, as last time.',
        sources: [{ kind: 'INCIDENT', id: 'inc-0', label: 'HighLatency on checkout-api', at: '2026-10-20T09:00:00Z' }],
      },
    ],
    asOf: '2026-10-29T14:20:00Z',
  },
});

function event(id: string, type: TimelineEvent['type'], createdAt: string): TimelineEvent {
  return { id, type, actorId: 'alice', details: { investigationId: 'inv-1' }, createdAt };
}

describe('AssistantPanel', () => {
  let fixture: ComponentFixture<AssistantPanel>;
  let backend: HttpTestingController;
  let events: Subject<StreamEvent>;

  beforeEach(async () => {
    events = new Subject<StreamEvent>();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: IncidentStream, useValue: { events, status: signal('live') } },
        { provide: Directory, useValue: { userName: () => 'Alice' } },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AssistantPanel);
    fixture.componentRef.setInput('incidentId', INCIDENT.id);
    fixture.componentRef.setInput('timeline', [
      event('ev-2', 'COMMENT', '2026-10-29T14:10:00Z'),
      event('ev-3', 'COMMENT', '2026-10-29T14:25:00Z'),
    ]);
    await fixture.whenStable();
  });

  afterEach(() => backend.verify());

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');
  }

  function element(selector: string): HTMLElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector(selector);
  }

  async function turnOn(latest: Investigation | null): Promise<void> {
    backend.expectOne('/api/v1/assistant').flush({ enabled: true, model: 'claude-opus-5-5' });
    backend.expectOne(`/api/v1/incidents/${INCIDENT.id}/investigations/latest`).flush(latest);
    await fixture.whenStable();
  }

  it('stays hidden when the server has no assistant', async () => {
    backend.expectOne('/api/v1/assistant').flush({ enabled: false, model: null });
    await fixture.whenStable();

    expect(element('section')).toBeNull();
  });

  it('starts an investigation, then shows the result once the stream says it is ready', async () => {
    await turnOn(null);
    expect(text()).toContain('Ask the assistant to read');

    element('header button')!.click();
    const start = backend.expectOne(`/api/v1/incidents/${INCIDENT.id}/investigations`);
    expect(start.request.method).toBe('POST');
    start.flush(investigation());
    await fixture.whenStable();
    expect(text()).toContain('Reading the alerts, timeline and earlier incidents');
    expect(element('header button')).toBeNull();

    // Another incident's investigation is not ours
    events.next({
      type: 'update',
      update: {
        incident: { ...INCIDENT, id: 'inc-9' },
        event: event('ev-9', 'INVESTIGATION_COMPLETED', '2026-10-29T14:20:40Z'),
      },
    });
    backend.expectNone(`/api/v1/incidents/${INCIDENT.id}/investigations/latest`);

    events.next({
      type: 'update',
      update: { incident: INCIDENT, event: event('ev-4', 'INVESTIGATION_COMPLETED', '2026-10-29T14:20:40Z') },
    });
    backend.expectOne(`/api/v1/incidents/${INCIDENT.id}/investigations/latest`).flush(COMPLETED);
    await fixture.whenStable();

    expect(text()).toContain('Checkout latency doubled after the 14:05 deploy.');
    expect(text()).toContain('What the evidence shows p99 rose when the deploy finished.');
    expect(text()).toContain('Suggested next steps Roll back, as last time.');
    expect(text()).toContain('Written by claude-opus-5-5 for Alice');
    expect(text()).toContain('1 timeline event since then.');
    expect(element('button.source')!.textContent).toContain('Comment by Alice');
    expect(element('span.source')!.textContent).toContain('Alert HighLatency');
    expect(element('a.source')!.getAttribute('href')).toBe('/incidents/inc-0');
    expect(element('header button')!.textContent).toContain('Investigate again');
  });

  it('keeps a finished result when the start response arrives late', async () => {
    await turnOn(null);

    element('header button')!.click();
    const start = backend.expectOne(`/api/v1/incidents/${INCIDENT.id}/investigations`);
    events.next({
      type: 'update',
      update: { incident: INCIDENT, event: event('ev-4', 'INVESTIGATION_COMPLETED', '2026-10-29T14:20:40Z') },
    });
    backend.expectOne(`/api/v1/incidents/${INCIDENT.id}/investigations/latest`).flush(COMPLETED);
    start.flush(investigation());
    await fixture.whenStable();

    expect(text()).toContain('Checkout latency doubled');
    expect(text()).not.toContain('Reading the alerts');
  });

  it('shows why an investigation failed, and the limit message when starting is refused', async () => {
    await turnOn(
      investigation({ status: 'FAILED', failure: 'Claude is temporarily unavailable. Try again in a few minutes.' }),
    );
    expect(text()).toContain('Claude is temporarily unavailable.');

    element('header button')!.click();
    backend
      .expectOne(`/api/v1/incidents/${INCIDENT.id}/investigations`)
      .flush(
        { status: 429, detail: 'Your organisation has used its 100 assistant investigations for the last 24 hours.' },
        { status: 429, statusText: 'Too Many Requests' },
      );
    await fixture.whenStable();

    expect(element('[role="alert"]')!.textContent).toContain('has used its 100 assistant investigations');
  });

  it('shows a cited event in the timeline', async () => {
    await turnOn(COMPLETED);
    const target = document.createElement('li');
    target.id = 'event-ev-2';
    target.tabIndex = -1;
    target.scrollIntoView = vi.fn();
    document.body.appendChild(target);
    window.matchMedia = vi.fn().mockReturnValue({ matches: false });

    element('button.source')!.click();

    expect(target.scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'center' });
    expect(document.activeElement).toBe(target);
    target.remove();
  });
});
