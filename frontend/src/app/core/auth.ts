import { HttpClient, HttpHeaders } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { TokenResponse, User } from './api.models';

interface Session {
  token: string;
  expiresAt: number;
  user: User;
}

/**
 * The signed-in user. The token is kept in memory only, never in localStorage, where any injected
 * script could read it; the price is that reloading the page asks for the password again.
 */
@Injectable({ providedIn: 'root' })
export class Auth {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly session = signal<Session | null>(null);
  private expiryTimer: ReturnType<typeof setTimeout> | undefined;

  readonly user = computed(() => this.session()?.user ?? null);
  readonly signedIn = computed(() => this.session() !== null);

  /** The token, or null when signed out or expired. */
  token(): string | null {
    const session = this.session();
    return session && session.expiresAt > Date.now() ? session.token : null;
  }

  async login(email: string, password: string): Promise<void> {
    const token = await firstValueFrom(
      this.http.post<TokenResponse>('/api/v1/auth/login', { email, password }),
    );
    const user = await firstValueFrom(
      this.http.get<User>('/api/v1/me', {
        headers: new HttpHeaders({ Authorization: `Bearer ${token.accessToken}` }),
      }),
    );
    const expiresIn = token.expiresIn * 1000;
    this.session.set({ token: token.accessToken, expiresAt: Date.now() + expiresIn, user });
    clearTimeout(this.expiryTimer);
    this.expiryTimer = setTimeout(() => this.logout(true), expiresIn);
  }

  /** @param expired true when the session ran out, so the login page can say why */
  logout(expired = false): void {
    clearTimeout(this.expiryTimer);
    if (this.session() === null) {
      return;
    }
    this.session.set(null);
    void this.router.navigate(['/login'], expired ? { queryParams: { expired: 1 } } : {});
  }
}
