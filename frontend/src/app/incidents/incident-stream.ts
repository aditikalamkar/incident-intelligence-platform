import { effect, inject, Injectable, signal, untracked } from '@angular/core';
import { Observable, Subject } from 'rxjs';
import { IncidentUpdate } from '../core/api.models';
import { Auth } from '../core/auth';
import { SseParser } from './sse-parser';

export type StreamStatus = 'offline' | 'connecting' | 'live' | 'reconnecting';

/**
 * {@code reload}: load the current state now (just connected, or changes may have been missed).
 * {@code update}: one incident changed.
 */
export type StreamEvent = { type: 'reload' } | { type: 'update'; update: IncidentUpdate };

const MAX_RECONNECT_DELAY = 30_000;

/**
 * One live connection to GET /api/v1/incidents/stream while signed in, shared by every page.
 * Reconnects with exponential backoff when the connection drops.
 */
@Injectable({ providedIn: 'root' })
export class IncidentStream {
  private readonly auth = inject(Auth);
  private readonly subject = new Subject<StreamEvent>();
  private connection: AbortController | null = null;

  readonly status = signal<StreamStatus>('offline');
  readonly events: Observable<StreamEvent> = this.subject.asObservable();

  constructor() {
    effect(() => {
      const signedIn = this.auth.signedIn();
      untracked(() => (signedIn ? this.start() : this.stop()));
    });
  }

  private start(): void {
    this.stop();
    this.connection = new AbortController();
    void this.run(this.connection.signal);
  }

  private stop(): void {
    this.connection?.abort();
    this.connection = null;
    this.status.set('offline');
  }

  private async run(abort: AbortSignal): Promise<void> {
    let delay = 1000;
    let attempt = 0;
    while (!abort.aborted) {
      const token = this.auth.token();
      if (!token) {
        return;
      }
      this.status.set(attempt++ === 0 ? 'connecting' : 'reconnecting');
      try {
        const response = await fetch('/api/v1/incidents/stream', {
          headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
          cache: 'no-store',
          signal: abort,
        });
        if (response.status === 401) {
          this.auth.logout(true);
          return;
        }
        if (!response.ok || !response.body) {
          throw new Error(`Live updates unavailable (HTTP ${response.status})`);
        }
        const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
        const parser = new SseParser();
        for (;;) {
          const { value, done } = await reader.read();
          if (done) {
            break; // the server ended the stream (token expiry, restart): reconnect
          }
          for (const message of parser.push(value)) {
            if (message.event === 'ready') {
              this.status.set('live');
              delay = 1000;
            }
            this.emit(message.event, message.data);
          }
        }
      } catch {
        // network error or aborted; the loop decides
      }
      if (abort.aborted) {
        return;
      }
      this.status.set('reconnecting');
      await sleep(delay, abort);
      delay = Math.min(delay * 2, MAX_RECONNECT_DELAY);
    }
  }

  private emit(event: string, data: string): void {
    if (event === 'ready' || event === 'resync') {
      this.subject.next({ type: 'reload' });
    } else if (event === 'incident') {
      this.subject.next({ type: 'update', update: JSON.parse(data) as IncidentUpdate });
    }
  }
}

function sleep(ms: number, abort: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, ms);
    abort.addEventListener('abort', () => {
      clearTimeout(timer);
      resolve();
    });
  });
}
