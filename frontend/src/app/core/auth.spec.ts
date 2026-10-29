import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { Auth } from './auth';
import { authInterceptor } from './auth.interceptor';

describe('Auth', () => {
  let auth: Auth;
  let http: HttpClient;
  let backend: HttpTestingController;

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
  });

  afterEach(() => backend.verify());

  async function signIn(): Promise<void> {
    const login = auth.login('alice@example.com', 'secret');
    const tokenRequest = backend.expectOne('/api/v1/auth/login');
    expect(tokenRequest.request.body).toEqual({ email: 'alice@example.com', password: 'secret' });
    tokenRequest.flush({ accessToken: 'token-1', tokenType: 'Bearer', expiresIn: 3600 });
    await Promise.resolve();
    const me = backend.expectOne('/api/v1/me');
    expect(me.request.headers.get('Authorization')).toBe('Bearer token-1');
    me.flush({ id: 'alice', email: 'alice@example.com', name: 'Alice', role: 'RESPONDER' });
    await login;
  }

  it('signs in and sends the token with API calls', async () => {
    await signIn();

    expect(auth.user()?.name).toBe('Alice');
    http.get('/api/v1/incidents').subscribe();
    expect(backend.expectOne('/api/v1/incidents').request.headers.get('Authorization')).toBe(
      'Bearer token-1',
    );
  });

  it('signs out when the API rejects the token', async () => {
    await signIn();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    http.get('/api/v1/incidents').subscribe({ error: () => undefined });
    backend.expectOne('/api/v1/incidents').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(auth.signedIn()).toBe(false);
    expect(auth.token()).toBeNull();
    expect(navigate).toHaveBeenCalledWith(['/login'], { queryParams: { expired: 1 } });
  });

  it('sends no token before signing in', () => {
    http.get('/api/v1/incidents').subscribe();

    expect(backend.expectOne('/api/v1/incidents').request.headers.has('Authorization')).toBe(false);
  });
});
