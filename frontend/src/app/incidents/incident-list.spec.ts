import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject } from 'rxjs';
import { Incident } from '../core/api.models';
import { Auth } from '../core/auth';
import { Directory } from '../core/directory';
import { IncidentList } from './incident-list';
import { IncidentStream, StreamEvent } from './incident-stream';

function incident(id: string, title: string, openedAt: string, changes: Partial<Incident> = {}): Incident {
  return {
    id,
    serviceId: 'svc',
    title,
    severity: 'HIGH',
    status: 'TRIGGERED',
    assigneeId: null,
    alertCount: 1,
    version: 0,
    openedAt,
    acknowledgedAt: null,
    resolvedAt: null,
    ...changes,
  };
}

describe('IncidentList', () => {
  let fixture: ComponentFixture<IncidentList>;
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
        { provide: Auth, useValue: { user: signal({ id: 'me', name: 'Me' }) } },
        {
          provide: Directory,
          useValue: {
            serviceName: () => 'payment-api',
            userName: (id: string | null) => (id ? 'Alice' : 'Nobody'),
            noteServices: () => undefined,
          },
        },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(IncidentList);
    await fixture.whenStable();
  });

  afterEach(() => backend.verify());

  function titles(): string[] {
    const element = fixture.nativeElement as HTMLElement;
    return [...element.querySelectorAll('.title')].map((title) => title.textContent!.trim());
  }

  it('loads open incidents, then applies live updates without reloading', async () => {
    const page = backend.expectOne((request) => request.url === '/api/v1/incidents');
    expect(page.request.params.get('status')).toBe('TRIGGERED,ACKNOWLEDGED');
    page.flush({
      items: [incident('b', 'DiskFull on db', '2026-10-01T10:20:00Z')],
      nextCursor: null,
    });
    await fixture.whenStable();
    expect(titles()).toEqual(['DiskFull on db']);

    events.next({
      type: 'update',
      update: {
        incident: incident('a', 'HighLatency on payment-api', '2026-10-01T10:30:00Z'),
        event: { id: 'e1', type: 'INCIDENT_CREATED', actorId: null, details: {}, createdAt: '' },
      },
    });
    await fixture.whenStable();
    expect(titles()).toEqual(['HighLatency on payment-api', 'DiskFull on db']);

    events.next({
      type: 'update',
      update: {
        incident: incident('b', 'DiskFull on db', '2026-10-01T10:20:00Z', {
          status: 'RESOLVED',
          version: 1,
        }),
        event: { id: 'e2', type: 'RESOLVED', actorId: 'me', details: {}, createdAt: '' },
      },
    });
    await fixture.whenStable();
    expect(titles()).toEqual(['HighLatency on payment-api']);
  });

  it('reloads when the stream asks for it', async () => {
    backend.expectOne((request) => request.url === '/api/v1/incidents').flush({ items: [], nextCursor: null });

    events.next({ type: 'reload' });

    backend.expectOne((request) => request.url === '/api/v1/incidents').flush({ items: [], nextCursor: null });
    await fixture.whenStable();
    expect(titles()).toEqual([]);
  });
});
