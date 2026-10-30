import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { TokenResponse, User } from './api.models';

interface Session {
  token: string;
  expiresAt: number;
  user: User;
}

/**
 * Sent with requests that use the session cookie. Another site cannot add a custom header without the
 * server's consent (CORS), so the server knows the request came from these pages (CSRF protection).
 */
export const SESSION_HEADERS = { 'X-Requested-With': 'OpsPilot' };

/** Endpoints authenticated by the session cookie rather than the access token. */
export const AUTH_API = '/api/v1/auth/';

/** Renew the access token this long before it expires. */
const REFRESH_AHEAD = 60_000;
/** After a network error, try renewing again this soon. */
const RETRY_DELAY = 15_000;
/** Tabs share one session cookie, and every refresh replaces it: they take turns. */
const REFRESH_LOCK = 'opspilot-session-refresh';
const CHANNEL = 'opspilot-session';

/**
 * The signed-in user. The access token lives in memory only, never in localStorage, where an injected
 * script could read it. A reload gets a new one from the session cookie, which scripts cannot read
 * either (HttpOnly).
 */
@Injectable({ providedIn: 'root' })
export class Auth {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly session = signal<Session | null>(null);
  private readonly channel = typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel(CHANNEL);
  private refreshTimer: ReturnType<typeof setTimeout> | undefined;
  private refreshing: Promise<string | null> | null = null;

  readonly user = computed(() => this.session()?.user ?? null);
  readonly signedIn = computed(() => this.session() !== null);

  constructor() {
    inject(DestroyRef).onDestroy(() => this.channel?.close());
    // Keep tabs in step: signing out in one signs out all, and signing in lets the others resume
    this.channel?.addEventListener('message', (event: MessageEvent<string>) => {
      if (event.data === 'signed-out') {
        this.clear(false);
      } else if (event.data === 'signed-in' && !this.signedIn()) {
        void this.restore();
      }
    });
  }

  /** The access token, or null when signed out or expired. */
  token(): string | null {
    const session = this.session();
    return session && session.expiresAt > Date.now() ? session.token : null;
  }

  /** At startup: resumes the session from its cookie, if the browser has one. Never fails. */
  async restore(): Promise<void> {
    await this.refresh();
  }

  async login(email: string, password: string): Promise<void> {
    const token = await firstValueFrom(
      this.http.post<TokenResponse>('/api/v1/auth/login', { email, password }),
    );
    await this.start(token);
    this.channel?.postMessage('signed-in');
  }

  /**
   * A new access token from the session cookie; null when the session has ended. Concurrent callers
   * share one request, and tabs take turns, since each refresh rotates the cookie they all share.
   */
  refresh(): Promise<string | null> {
    this.refreshing ??= this.inTurn(() => this.exchange()).finally(() => (this.refreshing = null));
    return this.refreshing;
  }

  /** Ends the session on the server too, so its cookie cannot bring it back, then in every tab. */
  async logout(): Promise<void> {
    try {
      await firstValueFrom(this.http.post<void>(`${AUTH_API}logout`, null, { headers: SESSION_HEADERS }));
    } catch {
      // Signed out here anyway; the server session expires on its own
    }
    this.channel?.postMessage('signed-out');
    this.clear(false);
  }

  private async exchange(): Promise<string | null> {
    try {
      const token = await firstValueFrom(
        this.http.post<TokenResponse>(`${AUTH_API}refresh`, null, { headers: SESSION_HEADERS }),
      );
      await this.start(token);
      return token.accessToken;
    } catch (error) {
      if (error instanceof HttpErrorResponse && (error.status === 401 || error.status === 403)) {
        this.clear(this.signedIn()); // the session ended: expired, revoked, or signed out elsewhere
        return null;
      }
      // Network or server trouble: keep the current token while it lasts, and try again soon
      if (this.signedIn()) {
        this.schedule(RETRY_DELAY);
      }
      return this.token();
    }
  }

  private async start(token: TokenResponse): Promise<void> {
    const user = await firstValueFrom(
      this.http.get<User>('/api/v1/me', {
        headers: new HttpHeaders({ Authorization: `Bearer ${token.accessToken}` }),
      }),
    );
    const expiresIn = token.expiresIn * 1000;
    this.session.set({ token: token.accessToken, expiresAt: Date.now() + expiresIn, user });
    this.schedule(Math.max(expiresIn - REFRESH_AHEAD, expiresIn / 2));
  }

  private schedule(delay: number): void {
    clearTimeout(this.refreshTimer);
    this.refreshTimer = setTimeout(() => void this.refresh(), delay);
  }

  /** @param expired true when the session ran out, so the login page can say why */
  private clear(expired: boolean): void {
    clearTimeout(this.refreshTimer);
    if (this.session() === null) {
      return;
    }
    this.session.set(null);
    void this.router.navigate(['/login'], expired ? { queryParams: { expired: 1 } } : {});
  }

  /** Runs the task holding a lock shared by all tabs, where the browser supports it. */
  private inTurn<T>(task: () => Promise<T>): Promise<T> {
    const locks = typeof navigator === 'undefined' ? undefined : navigator.locks;
    return locks ? locks.request(REFRESH_LOCK, task) : task();
  }
}
