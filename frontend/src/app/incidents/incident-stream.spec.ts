import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom, take, toArray } from 'rxjs';
import { Auth } from '../core/auth';
import { IncidentStream, StreamEvent } from './incident-stream';

/** A response whose body delivers the given chunks, then stays open like a live stream. */
function streamingResponse(chunks: string[]): Response {
  const encoder = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)));
    },
  });
  return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
}

describe('IncidentStream', () => {
  const signedIn = signal(true);
  const logout = vi.fn();

  beforeEach(() => {
    signedIn.set(true);
    logout.mockReset();
    TestBed.configureTestingModule({
      providers: [{ provide: Auth, useValue: { signedIn, token: () => 'token-1', logout } }],
    });
  });

  afterEach(() => {
    signedIn.set(false);
    TestBed.tick();
    vi.unstubAllGlobals();
  });

  it('connects with the token and turns server events into reloads and updates', async () => {
    const fetch = vi.fn().mockResolvedValue(
      streamingResponse([
        'event:ready\ndata:{}\n\n:keepalive\n\nevent:inci',
        'dent\ndata:{"incident":{"id":"i1"},"event":{"id":"e1"}}\n\n',
      ]),
    );
    vi.stubGlobal('fetch', fetch);
    const stream = TestBed.inject(IncidentStream);
    const events = firstValueFrom(stream.events.pipe(take(2), toArray()));

    TestBed.tick();

    expect(await events).toEqual<StreamEvent[]>([
      { type: 'reload' },
      { type: 'update', update: { incident: { id: 'i1' }, event: { id: 'e1' } } as never },
    ]);
    expect(stream.status()).toBe('live');
    const [url, init] = fetch.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/v1/incidents/stream');
    expect((init.headers as Record<string, string>)['Authorization']).toBe('Bearer token-1');
  });

  it('signs out when the token is rejected', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 401 })));
    TestBed.inject(IncidentStream);

    TestBed.tick();

    await vi.waitFor(() => expect(logout).toHaveBeenCalledWith(true));
  });

  it('disconnects when signed out', async () => {
    const fetch = vi.fn().mockResolvedValue(streamingResponse(['event:ready\ndata:{}\n\n']));
    vi.stubGlobal('fetch', fetch);
    const stream = TestBed.inject(IncidentStream);
    TestBed.tick();
    await vi.waitFor(() => expect(stream.status()).toBe('live'));

    signedIn.set(false);
    TestBed.tick();

    expect(stream.status()).toBe('offline');
    const init = fetch.mock.calls[0][1] as RequestInit;
    expect(init.signal?.aborted).toBe(true);
  });
});
