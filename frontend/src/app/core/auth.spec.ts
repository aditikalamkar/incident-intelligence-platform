import { HttpClient, HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { Auth } from './auth';
import { authInterceptor } from './auth.interceptor';

const UNAUTHORIZED = { status: 401, statusText: 'Unauthorized' };

/** Lets awaited promises inside Auth move on, also under fake timers. */
async function settle(): Promise<void> {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve();
  }
}

describe('Auth', () => {
  let auth: Auth;
  let http: HttpClient;
  let backend: HttpTestingController;
  let navigate: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    auth = TestBed.inject(Auth);
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  });

  afterEach(() => {
    backend.verify();
    vi.useRealTimers();
  });

  /** Answers a token request, then the profile request that follows it. */
  async function grant(request: TestRequest, token: string): Promise<void> {
    request.flush({ accessToken: token, tokenType: 'Bearer', expiresIn: 900 });
    await settle();
    const me = backend.expectOne('/api/v1/me');
    expect(me.request.headers.get('Authorization')).toBe(`Bearer ${token}`);
    me.flush({ id: 'alice', email: 'alice@example.com', name: 'Alice', role: 'RESPONDER' });
    await settle();
  }

  /** The refresh request, checked: the session cookie travels on its own; no token, a CSRF header. */
  function expectRefresh(): TestRequest {
    const request = backend.expectOne('/api/v1/auth/refresh');
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('X-Requested-With')).toBe('OpsPilot');
    expect(request.request.headers.has('Authorization')).toBe(false);
    return request;
  }

  async function signIn(): Promise<void> {
    const done = auth.login('alice@example.com', 'secret');
    const login = backend.expectOne('/api/v1/auth/login');
    expect(login.request.body).toEqual({ email: 'alice@example.com', password: 'secret' });
    await grant(login, 'token-1');
    await done;
  }

  it('signs in and sends the token with API calls', async () => {
    await signIn();

    expect(auth.user()?.name).toBe('Alice');
    http.get('/api/v1/incidents').subscribe();
    expect(backend.expectOne('/api/v1/incidents').request.headers.get('Authorization')).toBe(
      'Bearer token-1',
    );
  });

  it('sends no token before signing in', () => {
    http.get('/api/v1/incidents').subscribe();

    expect(backend.expectOne('/api/v1/incidents').request.headers.has('Authorization')).toBe(false);
  });

  it('resumes the session from its cookie after a reload', async () => {
    const restored = auth.restore();

    await grant(expectRefresh(), 'token-1');
    await restored;

    expect(auth.signedIn()).toBe(true);
    expect(auth.token()).toBe('token-1');
    expect(navigate).not.toHaveBeenCalled();
  });

  it('stays signed out, quietly, when there is no session to resume', async () => {
    const restored = auth.restore();

    expectRefresh().flush(null, UNAUTHORIZED);
    await restored;

    expect(auth.signedIn()).toBe(false);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('renews the token a minute before it expires', async () => {
    vi.useFakeTimers();
    await signIn();

    vi.advanceTimersByTime(839_000);
    backend.expectNone('/api/v1/auth/refresh');
    vi.advanceTimersByTime(1_000);
    await grant(expectRefresh(), 'token-2');

    expect(auth.token()).toBe('token-2');
  });

  it('renews a rejected token once for all requests, then retries them', async () => {
    await signIn();
    const results: unknown[] = [];
    http.get('/api/v1/incidents').subscribe((body) => results.push(body));
    http.get('/api/v1/services').subscribe((body) => results.push(body));

    backend.expectOne('/api/v1/incidents').flush(null, UNAUTHORIZED);
    backend.expectOne('/api/v1/services').flush(null, UNAUTHORIZED);
    await grant(expectRefresh(), 'token-2'); // one refresh, shared

    for (const url of ['/api/v1/incidents', '/api/v1/services']) {
      const retried = backend.expectOne(url);
      expect(retried.request.headers.get('Authorization')).toBe('Bearer token-2');
      retried.flush({ url });
    }
    expect(results).toEqual([{ url: '/api/v1/incidents' }, { url: '/api/v1/services' }]);
  });

  it('signs out when the session has ended, and says so on the login page', async () => {
    await signIn();
    let failure: unknown;
    http.get('/api/v1/incidents').subscribe({ error: (error: unknown) => (failure = error) });

    backend.expectOne('/api/v1/incidents').flush(null, UNAUTHORIZED);
    expectRefresh().flush(null, UNAUTHORIZED);
    await settle();

    expect(auth.signedIn()).toBe(false);
    expect(auth.token()).toBeNull();
    expect(navigate).toHaveBeenCalledWith(['/login'], { queryParams: { expired: 1 } });
    expect((failure as HttpErrorResponse).status).toBe(401);
  });

  it('renews first when the token ran out while the computer slept', async () => {
    vi.useFakeTimers();
    await signIn();
    vi.setSystemTime(Date.now() + 901_000); // the renewal timer never got to run

    http.get('/api/v1/incidents').subscribe();
    await grant(expectRefresh(), 'token-2');

    expect(backend.expectOne('/api/v1/incidents').request.headers.get('Authorization')).toBe(
      'Bearer token-2',
    );
  });

  it('keeps the token through a network error while renewing, and tries again', async () => {
    vi.useFakeTimers();
    await signIn();

    vi.advanceTimersByTime(840_000);
    expectRefresh().error(new ProgressEvent('error'));
    await settle();
    expect(auth.token()).toBe('token-1');

    vi.advanceTimersByTime(15_000);
    await grant(expectRefresh(), 'token-2');
    expect(auth.token()).toBe('token-2');
  });

  it('signs out on the server, even when that request fails', async () => {
    await signIn();

    const done = auth.logout();
    const logout = backend.expectOne('/api/v1/auth/logout');
    expect(logout.request.headers.get('X-Requested-With')).toBe('OpsPilot');
    expect(logout.request.headers.has('Authorization')).toBe(false);
    logout.error(new ProgressEvent('error'));
    await done;

    expect(auth.signedIn()).toBe(false);
    expect(navigate).toHaveBeenCalledWith(['/login'], {});
  });

  it.skipIf(typeof BroadcastChannel === 'undefined')('signs out when another tab does', async () => {
    await signIn();
    const otherTab = new BroadcastChannel('opspilot-session');

    otherTab.postMessage('signed-out');

    await vi.waitFor(() => expect(auth.signedIn()).toBe(false));
    otherTab.close();
  });
});
