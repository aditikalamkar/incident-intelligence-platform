import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom, take, toArray } from 'rxjs';
import { Auth } from '../core/auth';
import { IncidentStream, StreamEvent } from './incident-stream';

/**
 * A response whose body delivers the given chunks, then stays open like a live stream, or ends
 * like a stream the server closed.
 */
function streamingResponse(chunks: string[], end = false): Response {
  const encoder = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)));
      if (end) {
        controller.close();
      }
    },
  });
  return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
}

function authorization(fetch: ReturnType<typeof vi.fn>, call: number): string {
  const init = fetch.mock.calls[call][1] as RequestInit;
  return (init.headers as Record<string, string>)['Authorization'];
}

describe('IncidentStream', () => {
  const signedIn = signal(true);
  const token = vi.fn<() => string | null>();
  const refresh = vi.fn<() => Promise<string | null>>();

  beforeEach(() => {
    signedIn.set(true);
    token.mockReset().mockReturnValue('token-1');
    refresh.mockReset();
    TestBed.configureTestingModule({
      providers: [{ provide: Auth, useValue: { signedIn, token, refresh } }],
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

  it('renews a rejected token and reconnects with the new one', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValue(streamingResponse(['event:ready\ndata:{}\n\n']));
    vi.stubGlobal('fetch', fetch);
    refresh.mockImplementation(async () => {
      token.mockReturnValue('token-2');
      return 'token-2';
    });
    const stream = TestBed.inject(IncidentStream);

    TestBed.tick();

    await vi.waitFor(() => expect(stream.status()).toBe('live'), { timeout: 3000 });
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(authorization(fetch, 0)).toBe('Bearer token-1');
    expect(authorization(fetch, 1)).toBe('Bearer token-2');
  });

  it('stops when the token is rejected and the session has ended', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(null, { status: 401 }));
    vi.stubGlobal('fetch', fetch);
    refresh.mockImplementation(async () => {
      signedIn.set(false); // what Auth does when the session is over
      return null;
    });
    const stream = TestBed.inject(IncidentStream);

    TestBed.tick();

    await vi.waitFor(() => expect(refresh).toHaveBeenCalled());
    TestBed.tick();
    expect(stream.status()).toBe('offline');
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it('reconnects at once, still live, when the server ends a stream whose token was renewed', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(streamingResponse(['event:ready\ndata:{}\n\n'], true))
      .mockResolvedValue(streamingResponse(['event:ready\ndata:{}\n\n']));
    vi.stubGlobal('fetch', fetch);
    token.mockReturnValueOnce('token-1').mockReturnValue('token-2'); // renewed while the first stream ran
    const stream = TestBed.inject(IncidentStream);
    const statuses: string[] = [];
    stream.events.subscribe(() => statuses.push(stream.status()));

    TestBed.tick();

    // Well within the one-second delay of a reconnection after a failure
    await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(2), { timeout: 500 });
    expect(authorization(fetch, 1)).toBe('Bearer token-2');
    await vi.waitFor(() => expect(statuses).toEqual(['live', 'live']));
    expect(refresh).not.toHaveBeenCalled();
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
